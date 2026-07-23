package com.fkmyjc.fkmyjcs_miners.integration.rei;

import com.fkmyjc.fkmyjcs_miners.integration.IntegrationSupport;
import com.fkmyjc.fkmyjcs_miners.integration.MultiblockSchematicRenderer;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * REI 的「多方块预览」分类。左侧用自定义 Widget 画 2D 分层示意图。
 */
public class MultiblockPreviewCategory implements DisplayCategory<MultiblockPreviewDisplay> {
    public static final CategoryIdentifier<MultiblockPreviewDisplay> ID =
            CategoryIdentifier.of("fkmyjcs_miners", "multiblock_preview");

    private static final int WIDTH = 120;
    private static final int HEIGHT = 200;
    /** 示意图右侧为控制器槽(右上角)让出空间，避免候选清单被控制器槽遮挡。 */
    private static final int SCHEMATIC_MAX_W = WIDTH - 30;

    @Override
    public CategoryIdentifier<? extends MultiblockPreviewDisplay> getCategoryIdentifier() {
        return ID;
    }

    @Override
    public Component getTitle() {
        return Component.translatable(IntegrationSupport.CATEGORY_KEY);
    }

    @Override
    public Renderer getIcon() {
        return EntryStacks.of(IntegrationSupport.firstControllerItem());
    }

    @Override
    public int getDisplayWidth(MultiblockPreviewDisplay display) {
        return WIDTH;
    }

    @Override
    public int getDisplayHeight() {
        return HEIGHT;
    }

    @Override
    public List<Widget> setupDisplay(MultiblockPreviewDisplay display, Rectangle bounds) {
        MultiBlockPattern pattern = display.pattern();
        int layer = display.layer();
        int total = display.totalLayers();
        List<Widget> widgets = new ArrayList<>();
        widgets.add(new Widget() {
            @Override
            public List<? extends GuiEventListener> children() {
                return List.of();
            }

            @Override
            public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
                // 4 参来自 Renderable，REI 实际走 5 参变体；这里留空。
            }

            @Override
            public void render(GuiGraphics gui, Rectangle b, int mouseX, int mouseY, float delta) {
                Font font = Minecraft.getInstance().font;
                int wrapWidth = WIDTH - 12;   // 左右各留 6px 边距

                // 字段显示（结构 name/id）置于顶部，自动换行避免超出屏幕
                int y = b.y + 6;
                String display = pattern.getDisplayName();
                for (var line : font.split(Component.literal(display), wrapWidth)) {
                    gui.drawString(font, line, b.x + 6, y, 0xFFFFFF);
                    y += font.lineHeight + 2;
                }
                String pid = pattern.getId();
                if (!pid.isEmpty() && !pid.equals(display)) {
                    for (var line : font.split(Component.literal(pid), wrapWidth)) {
                        gui.drawString(font, line, b.x + 6, y, 0x55FFFF); // 青色 id，易与白色 name 区分
                        y += font.lineHeight + 2;
                    }
                }
                int top = y + 6;   // 字段下方的起始 y：示意图与页码下移至此，避免与字段重叠

                // 实际显示（2D 分层示意图）下移
                MultiblockSchematicRenderer.drawLayer(gui, b.x + 6, top, SCHEMATIC_MAX_W, pattern, layer, mouseX, mouseY);
                // 页码（Lx/y）下移，置于示意图下方、材料槽上方
                gui.drawString(font,
                        "L" + (layer + 1) + "/" + total, b.x + 6, b.y + b.height - 34, 0xAAAAAA);
            }
        });

        // 材料原生槽：每个候选组放进「同一个」槽位（含全部候选项），REI 在原生槽位内按 ~1 秒周期轮播。
        int mx = bounds.x + 2, my = bounds.y + bounds.height - 20;
        for (MultiBlockPattern.MaterialGroup g : IntegrationSupport.materials(pattern)) {
            EntryIngredient ing = EntryIngredient.of(g.alternatives().stream()
                    .map(EntryStacks::of).toList());
            widgets.add(Widgets.createSlot(new Rectangle(mx, my, 18, 18)).entries(ing).markInput());
            mx += 20;
            if (mx > bounds.x + bounds.width - 20) {
                mx = bounds.x + 2;
                my += 20;
            }
        }
        // 控制器槽（右上角）
        ItemStack ctrl = IntegrationSupport.controllerItem(pattern);
        if (!ctrl.isEmpty()) {
            widgets.add(Widgets.createSlot(new Rectangle(bounds.x + bounds.width - 20, bounds.y + 2, 18, 18))
                    .entries(EntryIngredient.of(EntryStacks.of(ctrl))).markOutput());
        }
        return widgets;
    }
}
