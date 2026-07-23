package com.fkmyjc.fkmyjcs_miners.integration;

import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 多方块结构的 2D 分层示意图渲染器。
 *
 * <p>用 vanilla 的 {@link GuiGraphics#renderItem(ItemStack, int, int)} 把每一层的方块以
 * 「物品图标网格」形式画出来——本质是 JEI 里随处可见的槽位图标画法，不涉及任何透视投影
 * 矩阵 / 深度测试 / 裁剪，因此不会出现之前 3D 渲染那种空白、前后失向、污染外部渲染的问题。</p>
 *
 * <p>每层画一行：顶部标 "L{n}"，下方是该层的 rows×cols 图标网格；控制器所在格用金色高亮。</p>
 *
 * <p>候选组（某格可接受多个方块中的任一个）的呈现：</p>
 * <ul>
 *   <li><b>轮播</b>：每 1 秒自动切换显示候选组里的下一个候选图标，让玩家看到「这是一组可选项」。</li>
 *   <li><b>角标</b>：候选组的格子右下角画一个金色小方块，提示「此格可多选」。</li>
 *   <li><b>悬停清单</b>：鼠标悬停在候选组格子上时，在示意图旁自绘一个半透明清单，列出全部可放置方块；渲染时提升到 z=400，确保位于 JEI/REI/EMI 原生槽位之上，避免被遮挡或穿透。</li>
 * </ul>
 *
 * <p>轮播与悬停清单均在本渲染器内部完成（不依赖 JEI/REI/EMI 各自的 tooltip API），坐标系天然一致。</p>
 *
 * <p>底部「总材料列表」改回由 JEI/REI/EMI 的<b>原生槽位</b>渲染：每个候选组放进同一个原生槽位
 * （含全部候选项），由查看器在原生槽位内按 ~1 秒周期轮播候选项，实现「每 1 秒刷新一次」。</p>
 */
public final class MultiblockSchematicRenderer {
    public static final int CELL = 18;
    public static final int LABEL_H = 10;

    private MultiblockSchematicRenderer() {
    }

    /** 一个候选组格子的屏幕矩形与候选列表，供悬停命中检测。 */
    private record CandCell(int x, int y, int w, int h, List<BlockState> cands) {
    }

    /**
     * 画单层示意图，自动缩放使宽度不超过 maxWidth。
     * @param mouseX / mouseY 鼠标在示意图坐标系（与 originX/originY 同坐标系）下的位置，用于悬停清单。
     * @return 绘制区域底部 y 坐标（供调用方做后续布局）。
     */
    public static int drawLayer(GuiGraphics gui, int originX, int originY, int maxWidth,
                                MultiBlockPattern pattern, int layer, double mouseX, double mouseY) {
        int rows = pattern.getRowCount(layer);
        int maxCols = 0;
        for (int z = 0; z < rows; z++) {
            maxCols = Math.max(maxCols, pattern.getColCount(layer, z));
        }
        if (maxCols == 0 || rows == 0) {
            gui.drawString(Minecraft.getInstance().font, "(empty)", originX, originY, 0xAAAAAA);
            return originY + LABEL_H;
        }
        int cell = Math.min(CELL, Math.max(2, maxWidth / Math.max(1, maxCols)));
        gui.drawString(Minecraft.getInstance().font, "L" + layer, originX, originY, 0xAAAAAA);
        int gridX = originX;
        int gridY = originY + LABEL_H;

        List<CandCell> candCells = new ArrayList<>();
        long cycle = System.currentTimeMillis() / 1000L;   // 每 1 秒切换一次轮播索引

        for (int z = 0; z < rows; z++) {
            int cols = pattern.getColCount(layer, z);
            for (int x = 0; x < cols; x++) {
                int px = gridX + x * cell;
                int py = gridY + z * cell;
                boolean ctrl = pattern.isController(layer, z, x);
                gui.fill(px, py, px + cell, py + cell, ctrl ? 0x66FFD700 : 0x22000000);

                List<BlockState> cands = pattern.candidateStatesAt(layer, z, x);
                if (cands.isEmpty()) continue;

                // 候选组：每 1 秒轮播到下一个候选；单候选直接显示
                int shownIdx = cands.size() > 1 ? (int) (cycle % cands.size()) : 0;
                BlockState shown = cands.get(shownIdx);
                renderItemScaled(gui, new ItemStack(shown.getBlock()), px, py, cell);

                if (cands.size() > 1) {
                    // 右下角金色角标，提示「此格可多选」
                    int bx = px + cell - 4, by = py + cell - 4;
                    gui.fill(bx, by, bx + 4, by + 4, 0xCCFFD700);
                    candCells.add(new CandCell(px, py, cell, cell, cands));
                }
            }
        }

        // 悬停清单：命中候选组格时自绘（画在方块之上，且已夹紧在示意图可视区内，不会被遮挡）
        for (CandCell cc : candCells) {
            if (mouseX >= cc.x && mouseX < cc.x + cc.w && mouseY >= cc.y && mouseY < cc.y + cc.h) {
                drawCandidateTooltip(gui, cc, originX, maxWidth);
                break;
            }
        }

        return gridY + rows * cell;
    }

    /** 在候选组格子旁自绘「可放置以下任一方块」清单（半透明框 + 文字）。
     *  位置夹紧在 [originX, originX+maxWidth] 内，且顶部行改画在格子下方，彻底避开右上角控制器槽。 */
    private static void drawCandidateTooltip(GuiGraphics gui, CandCell cc, int originX, int maxWidth) {
        Minecraft mc = Minecraft.getInstance();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("tooltip.fkmyjcs_miners.multiblock.any_of"));
        for (BlockState s : cc.cands) {
            lines.add(Component.literal("  ").append(s.getBlock().getName()));
        }
        int lineH = mc.font.lineHeight + 2;
        int boxW = 8, boxH = lines.size() * lineH + 6;
        for (Component l : lines) boxW = Math.max(boxW, mc.font.width(l) + 8);

        int maxRight = originX + maxWidth;            // 示意图可视区右边界（已为控制器槽让位）
        int bx = cc.x + cc.w + 2;                     // 默认画在格子右侧
        if (bx + boxW > maxRight) bx = cc.x - boxW - 2;   // 右侧溢出则画在左侧
        if (bx < originX) bx = originX;                   // 左侧越界夹紧
        if (bx + boxW > maxRight) bx = Math.max(originX, maxRight - boxW);

        int by = cc.y;
        // 顶部两行贴近右上角控制器槽：改画在格子正下方，避免被控制器槽遮挡
        if (by < 22) by = cc.y + cc.h + 2;

        gui.pose().pushPose();
        gui.pose().translate(0, 0, 400);              // 提升到高于 JEI/REI/EMI 原生槽位，避免被遮挡
        gui.fill(bx, by, bx + boxW, by + boxH, 0xF0101010);   // 半透明背景
        gui.fill(bx, by, bx + boxW, by + 1, 0x80FFD700);      // 顶边高亮

        int ty = by + 4;
        for (Component l : lines) {
            gui.drawString(mc.font, l, bx + 4, ty, 0xFFFFFF);
            ty += lineH;
        }
        gui.pose().popPose();
    }

    private static void renderItemScaled(GuiGraphics gui, ItemStack stack, int px, int py, int cell) {
        // 图标在格子内留出 1px 边框；当 cell >= 18 时按原始 16x16 渲染，
        // 当 cell 缩小时按比例缩放，使图标不会超出格子也不会被截断。
        float iconSize = Math.max(1.0f, cell - 2.0f);
        float scale = iconSize / 16.0f;
        gui.pose().pushPose();
        gui.pose().translate(px + 1, py + 1, 0);
        gui.pose().scale(scale, scale, 1.0f);
        gui.renderItem(stack, 0, 0);
        gui.pose().popPose();
    }

    /**
     * 画所有层的概览（自动缩放使总宽不超过 maxWidth，层间留 4px 间距）。
     * 用于需要一屏看全貌的场景；分页预览请改用 {@link #drawLayer}。
     * 概览不带交互（mouse 传 (-1,-1) 表示无悬停）。
     */
    public static void draw(GuiGraphics gui, int originX, int originY, int maxWidth,
                            MultiBlockPattern pattern) {
        draw(gui, originX, originY, maxWidth, pattern, -1, -1);
    }

    public static void draw(GuiGraphics gui, int originX, int originY, int maxWidth,
                            MultiBlockPattern pattern, double mouseX, double mouseY) {
        int layers = pattern.getLayerCount();
        int maxRows = 0, maxCols = 0;
        for (int y = 0; y < layers; y++) {
            maxRows = Math.max(maxRows, pattern.getRowCount(y));
            for (int z = 0; z < pattern.getRowCount(y); z++) {
                maxCols = Math.max(maxCols, pattern.getColCount(y, z));
            }
        }
        if (maxCols == 0) return;
        int cell = Math.min(CELL, Math.max(2, maxWidth / Math.max(1, maxCols)));
        int curY = originY;
        for (int y = 0; y < layers; y++) {
            curY = drawLayer(gui, originX, curY, maxWidth, pattern, y, mouseX, mouseY);
            curY += 4;
        }
    }
}
