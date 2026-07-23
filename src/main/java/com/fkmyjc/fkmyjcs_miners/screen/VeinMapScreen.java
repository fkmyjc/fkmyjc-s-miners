package com.fkmyjc.fkmyjcs_miners.screen;

import com.fkmyjc.fkmyjcs_miners.Config;
import com.fkmyjc.fkmyjcs_miners.menu.VeinMapData;
import com.fkmyjc.fkmyjcs_miners.menu.VeinMapMenu;
import com.fkmyjc.fkmyjcs_miners.prospect.VeinSummary;
import com.fkmyjc.fkmyjcs_miners.vein.VeinOreRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 7×7 区块矿脉地图 GUI（客户端渲染）。
 * 以玩家当前区块为中心，向四周各 ±3 共 49 格；
 * 每格用「主/次/岩石三矿按权重竖向分段」填充，从而同时显示全部矿脉。
 * 右侧绘制「矿石图例」：颜色块 + 矿石名 + id（名称与 id 均用该矿对应颜色）。
 * 图例支持滚轮滚动；点击图例某矿可在 7×7 网格中只显示该矿、隐藏其他矿。
 * 网格使用白色格线。
 */
public class VeinMapScreen extends AbstractContainerScreen<VeinMapMenu> {

    private static final int PAD = 12;
    private static final int TITLE_H = 24;
    private static final int FOOTER_H = 18;
    private static final int CELL = 30;                  // 每格像素
    private static final int GRID = VeinMapData.SIDE;    // 7
    private static final int GRID_W = GRID * CELL;       // 210
    private static final int GAP = 12;                   // 网格与图例间距
    private static final int LEGEND_W = 280;             // 右侧图例宽度
    private static final int LEGEND_BLOCK = 10;          // 左侧色块大小（上限，实际随行高自适应）

    private final List<OreInfo> legendOres;
    private int legendScroll = 0;        // 图例滚动偏移（像素）
    private int maxLegendScroll = 0;     // 最大滚动偏移
    private int legendAreaTop;           // 图例内容区上边界（绝对坐标）
    private int legendAreaBottom;        // 图例内容区下边界
    private int legendAreaLeft;          // 图例内容区左边界
    private int legendAreaRight;         // 图例内容区右边界
    private String selectedOreId = null; // 当前选中的过滤矿 id（null=无过滤）

    public VeinMapScreen(VeinMapMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.legendOres = buildLegendOres(menu.data);
        int w = PAD + GRID_W + GAP + LEGEND_W + PAD;
        // 面板高度严格贴合 7×7 网格高度，不随矿石数量撑高；
        // 矿石过多时由右侧图例滚轮滚动，避免冗余空白
        int h = TITLE_H + PAD + GRID_W + PAD + FOOTER_H;
        this.imageWidth = w;
        this.imageHeight = h;
        this.inventoryLabelX = Integer.MAX_VALUE;
        this.titleLabelX = Integer.MAX_VALUE;
        this.titleLabelY = Integer.MAX_VALUE;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 背景完全在 render() 中绘制
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int x = (this.width - imageWidth) / 2;
        int y = (this.height - imageHeight) / 2;
        this.leftPos = x;
        this.topPos = y;

        // 半透明面板 + 绿边框
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xC0101010);
        graphics.fill(x, y, x + imageWidth, y + 2, 0xFF4C7A34);
        graphics.fill(x, y, x + 2, y + imageHeight, 0xFF4C7A34);
        graphics.fill(x + imageWidth - 2, y, x + imageWidth, y + imageHeight, 0xFF4C7A34);
        graphics.fill(x, y + imageHeight - 2, x + imageWidth, y + imageHeight, 0xFF4C7A34);

        // 标题（中心区块坐标）
        VeinMapData d = menu.data;
        graphics.drawString(this.font,
                Component.literal("矿脉地图 7×7  (" + d.centerX + ", " + d.centerZ + ")"),
                x + PAD, y + 6, 0xFFFFFF, false);

        // 网格
        int gx0 = x + PAD;
        int gy0 = y + TITLE_H + PAD;
        int centerIdx = VeinMapData.indexOf(0, 0);
        for (int idx = 0; idx < d.cells.length; idx++) {
            int col = idx % GRID;
            int row = idx / GRID;
            int cx = gx0 + col * CELL;
            int cy = gy0 + row * CELL;
            VeinSummary s = d.cells[idx];
            if (s == null) {
                drawUnknownCell(graphics, cx, cy);
            } else {
                drawCell(graphics, s, cx, cy);
            }

            // 中心格：金框高亮（即使为空也显示范围中心）
            if (idx == centerIdx) {
                graphics.fill(cx - 1, cy - 1, cx + CELL + 1, cy, 0xFFFFD700);
                graphics.fill(cx - 1, cy + CELL - 1, cx + CELL + 1, cy + CELL, 0xFFFFD700);
                graphics.fill(cx - 1, cy - 1, cx, cy + CELL + 1, 0xFFFFD700);
                graphics.fill(cx + CELL - 1, cy - 1, cx + CELL + 1, cy + CELL + 1, 0xFFFFD700);
            }
        }

        // 右侧图例（与 7×7 网格等高，内容超出时滚轮滚动）
        int lx = x + PAD + GRID_W + GAP;
        int ly = y + TITLE_H + PAD;
        int legendH = GRID_W;
        drawLegend(graphics, d, lx, ly, LEGEND_W, legendH);

        // 页脚说明
        int fy = y + imageHeight - FOOTER_H + 2;
        String footer = selectedOreId == null
                ? "中心格=当前区块 · 滚轮滚动图例 · 点击图例筛选矿石"
                : "已筛选: " + selectedOreId + "  （再次点击该矿或点击空白处取消）";
        graphics.drawString(this.font, Component.literal(footer), x + PAD, fy, 0xBFBFBF, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        // 悬停 tooltip（网格）
        int idx = cellIndexAt(mouseX, mouseY);
        if (idx >= 0 && idx < d.cells.length) {
            List<FormattedCharSequence> tip = buildTooltip(d.cells[idx], idx).stream()
                    .map(Component::getVisualOrderText)
                    .collect(Collectors.toList());
            graphics.renderTooltip(this.font, tip, mouseX, mouseY);
        }
    }

    /** 单个区块格：竖向分段显示主/次/岩石三种矿（按权重比例），每段用对应矿色。 */
    private void drawCell(GuiGraphics graphics, VeinSummary s, int cx, int cy) {
        // 白格线
        graphics.fill(cx, cy, cx + CELL, cy + 1, 0xFFFFFFFF);
        graphics.fill(cx, cy + CELL - 1, cx + CELL, cy + CELL, 0xFFFFFFFF);
        graphics.fill(cx, cy, cx + 1, cy + CELL, 0xFFFFFFFF);
        graphics.fill(cx + CELL - 1, cy, cx + CELL, cy + CELL, 0xFFFFFFFF);

        // 若处于筛选模式，只绘制被选中的矿；未选中则该格置暗
        if (selectedOreId != null) {
            int w = 0;
            int color = 0xFF333333;
            if (selectedOreId.equals(s.mainId)) { w = s.mainW; color = colorOf(s.mainId); }
            else if (selectedOreId.equals(s.secId)) { w = s.secW; color = colorOf(s.secId); }
            else if (selectedOreId.equals(s.rockId)) { w = s.rockW; color = colorOf(s.rockId); }

            if (w > 0) {
                graphics.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, color);
                String pct = w + "%";
                int tw = this.font.width(pct);
                graphics.drawString(this.font, Component.literal(pct),
                        cx + (CELL - tw) / 2, cy + CELL / 2 - 4, 0xFFFFFF, true);
            } else {
                graphics.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, 0xFF2A2A2A);
            }
            return;
        }

        // 收集非空且权重>0的矿段（主→次→岩石）
        List<int[]> bands = new ArrayList<>();   // {weight, colorArgb}
        if (s.mainId != null && s.mainW > 0) bands.add(new int[]{s.mainW, colorOf(s.mainId)});
        if (s.secId != null && s.secW > 0)  bands.add(new int[]{s.secW,  colorOf(s.secId)});
        if (s.rockId != null && s.rockW > 0) bands.add(new int[]{s.rockW, colorOf(s.rockId)});

        int inner = CELL - 2;          // 28
        int top = cy + 1;
        if (bands.isEmpty()) {
            graphics.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, 0xFFAAAAAA);
            return;
        }

        int yCur = top;
        int remaining = inner;
        int n = bands.size();
        for (int i = 0; i < n; i++) {
            int weight = bands.get(i)[0];
            int color = bands.get(i)[1];
            int h = (i == n - 1) ? remaining : (int) Math.round(inner * (weight / 100.0));
            if (h <= 0) { h = (i == n - 1) ? remaining : 1; }
            if (h > remaining) h = remaining;
            graphics.fill(cx + 1, yCur, cx + CELL - 1, yCur + h, color);
            // 段足够高时显示权重百分比（带阴影保证可读）
            if (h >= 11) {
                String w = weight + "%";
                int tw = this.font.width(w);
                graphics.drawString(this.font, Component.literal(w),
                        cx + (CELL - tw) / 2, yCur + h / 2 - 4, 0xFFFFFF, true);
            }
            yCur += h;
            remaining -= h;
            if (remaining <= 0) break;
        }
    }

    /** 未探明格：深灰填充 + "?" 标记，表示高级探矿仪能量不足以扫描该区块。 */
    private void drawUnknownCell(GuiGraphics graphics, int cx, int cy) {
        graphics.fill(cx, cy, cx + CELL, cy + 1, 0xFFFFFFFF);
        graphics.fill(cx, cy + CELL - 1, cx + CELL, cy + CELL, 0xFFFFFFFF);
        graphics.fill(cx, cy, cx + 1, cy + CELL, 0xFFFFFFFF);
        graphics.fill(cx + CELL - 1, cy, cx + CELL, cy + CELL, 0xFFFFFFFF);
        graphics.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, 0xFF2A2A2A);
        String q = "?";
        int tw = this.font.width(q);
        graphics.drawString(this.font, Component.literal(q),
                cx + (CELL - tw) / 2, cy + CELL / 2 - 4, 0xFF888888, true);
    }

    /** 右侧图例：只显示本图中实际出现过的矿（权重>0），支持滚轮滚动；点击可选中筛选。 */
    private void drawLegend(GuiGraphics graphics, VeinMapData d, int lx, int ly, int lw, int lh) {
        graphics.drawString(this.font,
                Component.literal("矿石图例 (名称 · id)"),
                lx, ly, 0xFFFFFF, false);

        int titleH = 14;
        int areaTop = ly + titleH;
        int areaBottom = ly + lh;
        int rowH = Config.mapLegendRowHeight;
        int block = Math.min(LEGEND_BLOCK, rowH - 2);

        int contentH = legendOres.size() * rowH;
        int visibleH = areaBottom - areaTop;
        maxLegendScroll = Math.max(0, contentH - visibleH);
        if (legendScroll > maxLegendScroll) legendScroll = maxLegendScroll;
        if (legendScroll < 0) legendScroll = 0;

        // 记录图例区域（用于鼠标事件）
        legendAreaLeft = lx;
        legendAreaRight = lx + lw;
        legendAreaTop = areaTop;
        legendAreaBottom = areaBottom;

        // 裁剪区域，只绘制可见部分
        graphics.enableScissor(lx, areaTop, lx + lw, areaBottom);
        int yyBase = areaTop - legendScroll;
        for (int i = 0; i < legendOres.size(); i++) {
            OreInfo o = legendOres.get(i);
            int yy = yyBase + i * rowH;
            if (yy + rowH < areaTop || yy > areaBottom) continue;

            boolean selected = o.id.equals(selectedOreId);
            int rowY = yy;

            // 选中行背景高亮
            if (selected) {
                graphics.fill(lx, rowY, lx + lw, rowY + rowH, 0x55FFFFFF);
            }

            int by = rowY + (rowH - block) / 2;
            // 色块
            graphics.fill(lx, by, lx + block, by + block, o.color);
            // 色块黑边框
            graphics.fill(lx, by, lx + block, by + 1, 0xFF000000);
            graphics.fill(lx, by + block - 1, lx + block, by + block, 0xFF000000);
            graphics.fill(lx, by, lx + 1, by + block, 0xFF000000);
            graphics.fill(lx + block - 1, by, lx + block, by + block, 0xFF000000);

            int tx = lx + block + 6;
            int ty = rowY + (rowH - this.font.lineHeight) / 2;
            // 矿石名（矿色）
            graphics.drawString(this.font, Component.literal(o.name),
                    tx, ty, o.color, false);
            int nameW = this.font.width(o.name);

            // id（矿色，略微压暗），紧跟名称右侧
            int idX = tx + nameW + 6;
            int maxIdW = lw - (idX - lx) - 4;
            String idText = o.id;
            if (maxIdW > 0 && this.font.width(idText) > maxIdW) {
                idText = this.font.plainSubstrByWidth(idText, maxIdW - this.font.width("...")) + "...";
            }
            graphics.drawString(this.font, Component.literal(idText),
                    idX, ty, dim(o.color), false);
        }
        graphics.disableScissor();

        // 若有滚动，绘制简单滚动条
        if (maxLegendScroll > 0) {
            int barX = lx + lw - 3;
            int trackH = visibleH;
            int thumbH = Math.max(20, visibleH * visibleH / (contentH + 1));
            int thumbY = areaTop + legendScroll * (trackH - thumbH) / maxLegendScroll;
            graphics.fill(barX, areaTop, barX + 2, areaBottom, 0xFF333333);
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFFAAAAAA);
        }
    }

    /** 滚轮滚动图例（1.20.1 ContainerEventHandler 为单 delta 签名）。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isInLegendArea((int) mouseX, (int) mouseY)) {
            int step = Config.mapLegendRowHeight * 3;
            legendScroll -= (int) (delta * step);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** 点击图例矿石进行筛选；点击已选中项或图例空白处取消筛选。 */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isInLegendArea((int) mouseX, (int) mouseY)) {
            int row = legendRowAt((int) mouseY);
            if (row >= 0 && row < legendOres.size()) {
                String id = legendOres.get(row).id;
                selectedOreId = id.equals(selectedOreId) ? null : id;
            } else {
                selectedOreId = null;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isInLegendArea(int mx, int my) {
        return mx >= legendAreaLeft && mx < legendAreaRight
                && my >= legendAreaTop && my < legendAreaBottom;
    }

    private int legendRowAt(int my) {
        int relY = my - legendAreaTop + legendScroll;
        return relY / Config.mapLegendRowHeight;
    }

    private void clampScroll() {
        if (legendScroll < 0) legendScroll = 0;
        if (legendScroll > maxLegendScroll) legendScroll = maxLegendScroll;
    }

    /** 构建图例列表：只保留在本图中实际出现过的矿（权重>0），按主→次→岩石顺序去重。 */
    private static List<OreInfo> buildLegendOres(VeinMapData d) {
        List<OreInfo> ores = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (VeinSummary s : d.cells) {
            if (s == null) continue;
            consider(ores, seen, s.mainId, s.mainName, s.mainW);
            consider(ores, seen, s.secId, s.secName, s.secW);
            consider(ores, seen, s.rockId, s.rockName, s.rockW);
        }
        return ores;
    }

    private static void consider(List<OreInfo> ores, Set<String> seen, String id, String nameKey, int weight) {
        if (id == null || weight <= 0 || !seen.add(id)) return;
        ores.add(new OreInfo(id, oreName(id, nameKey), colorOf(id)));
    }

    private static final class OreInfo {
        final String id;
        final String name;
        final int color;
        OreInfo(String id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    /** 由 id + translation key 取可读名称。 */
    private static String oreName(String id, String nameKey) {
        if (nameKey != null) {
            String t = Component.translatable(nameKey).getString();
            if (t != null && !t.isEmpty()) return t;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        String path = (rl != null) ? rl.getPath() : id;
        return path.replace('_', ' ');
    }

    /** 将颜色压暗（用于 id 次级文本）。 */
    private static int dim(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r = r * 3 / 4; g = g * 3 / 4; b = b * 3 / 4;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 计算鼠标所在的格子下标；不在网格内返回 -1。 */
    private int cellIndexAt(int mx, int my) {
        int gx = mx - (leftPos + PAD);
        int gy = my - (topPos + TITLE_H + PAD);
        if (gx < 0 || gy < 0) return -1;
        int col = gx / CELL;
        int row = gy / CELL;
        if (col < 0 || col >= GRID || row < 0 || row >= GRID) return -1;
        if (gx % CELL == 0 || gy % CELL == 0) return -1; // 落在格间分隔线
        return row * GRID + col;
    }

    private List<Component> buildTooltip(VeinSummary s, int idx) {
        List<Component> lines = new ArrayList<>();
        int dx = idx % GRID - VeinMapData.RADIUS;
        int dz = idx / GRID - VeinMapData.RADIUS;
        lines.add(Component.literal("区块偏移 (" + dx + ", " + dz + ")"));
        if (s == null) {
            lines.add(Component.literal("未探明：能量不足以扫描该区块").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        lines.add(nameLine("主矿", s.mainName, s.mainW));
        lines.add(nameLine("次矿", s.secName, s.secW));
        lines.add(nameLine("岩石", s.rockName, s.rockW));
        lines.add(Component.literal("储量: " + String.format("%,d", s.reserves)));
        return lines;
    }

    private Component nameLine(String label, String nameKey, int weight) {
        String name = (nameKey == null) ? "无" : Component.translatable(nameKey).getString();
        return Component.literal(label + ": " + name + " (" + weight + "%)");
    }

    /** 由矿石物品 id 字符串取颜色；空或解析失败回退灰。 */
    private static int colorOf(String id) {
        if (id == null) return 0xFFAAAAAA;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return (rl != null) ? VeinOreRegistry.getOreColor(rl) : 0xFFAAAAAA;
    }
}
