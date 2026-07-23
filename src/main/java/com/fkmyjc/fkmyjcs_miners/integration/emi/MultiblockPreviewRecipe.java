package com.fkmyjc.fkmyjcs_miners.integration.emi;

import com.fkmyjc.fkmyjcs_miners.integration.IntegrationSupport;
import com.fkmyjc.fkmyjcs_miners.integration.MultiblockSchematicRenderer;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import dev.emi.emi.api.widget.WidgetHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * EMI 的「多方块预览」配方：左侧画 2D 分层示意图，并摆出控制器(OUTPUT)与材料(INPUT)槽位。
 */
public class MultiblockPreviewRecipe implements EmiRecipe {
    private final EmiRecipeCategory category;
    private final MultiBlockPattern pattern;
    private final ResourceLocation id;
    private final int layer;
    private final int totalLayers;

    public MultiblockPreviewRecipe(EmiRecipeCategory category, MultiBlockPattern pattern,
                                   ResourceLocation id, int layer, int totalLayers) {
        this.category = category;
        this.pattern = pattern;
        this.id = id;
        this.layer = layer;
        this.totalLayers = totalLayers;
    }

    @Override
    public EmiRecipeCategory getCategory() {
        return category;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public List<EmiIngredient> getInputs() {
        // 材料视觉槽位由 addWidgets 以原生槽位铺排（含轮播）；
        // 此处仍需返回扁平材料清单，供 EMI 搜索索引使用。
        List<EmiIngredient> list = new ArrayList<>();
        for (ItemStack s : IntegrationSupport.materialStacks(pattern)) {
            list.add(EmiStack.of(s));
        }
        return list;
    }

    @Override
    public List<EmiStack> getOutputs() {
        ItemStack ctrl = IntegrationSupport.controllerItem(pattern);
        return ctrl.isEmpty() ? List.of() : List.of(EmiStack.of(ctrl));
    }

    @Override
    public int getDisplayWidth() {
        return 120;
    }

    @Override
    public int getDisplayHeight() {
        return 200;
    }

    /** 示意图右侧为控制器槽(右上角)让出空间，避免候选清单被控制器槽遮挡。 */
    private static final int SCHEMATIC_MAX_W = 120 - 30;

    @Override
    public void addWidgets(WidgetHolder widgets) {
        widgets.add(new Widget() {
            @Override
            public Bounds getBounds() {
                return Bounds.EMPTY;
            }

            @Override
            public void render(GuiGraphics gui, int mouseX, int mouseY, float delta) {
                Font font = Minecraft.getInstance().font;
                int wrapWidth = getDisplayWidth() - 12;   // 左右各留 6px 边距

                // 字段显示（结构 name/id）置于顶部，自动换行避免超出屏幕
                int y = 6;
                String display = pattern.getDisplayName();
                for (var line : font.split(Component.literal(display), wrapWidth)) {
                    gui.drawString(font, line, 6, y, 0xFFFFFF);
                    y += font.lineHeight + 2;
                }
                String pid = pattern.getId();
                if (!pid.isEmpty() && !pid.equals(display)) {
                    for (var line : font.split(Component.literal(pid), wrapWidth)) {
                        gui.drawString(font, line, 6, y, 0x55FFFF); // 青色 id，易与白色 name 区分
                        y += font.lineHeight + 2;
                    }
                }
                int top = y + 6;   // 字段下方的起始 y：示意图与页码下移至此，避免与字段重叠

                // 实际显示（2D 分层示意图）下移
                MultiblockSchematicRenderer.drawLayer(gui, 6, top, SCHEMATIC_MAX_W, pattern, layer, mouseX, mouseY);
                // 页码（Lx/y）下移，置于示意图下方、材料槽上方
                gui.drawString(font,
                        "L" + (layer + 1) + "/" + totalLayers, 6, getDisplayHeight() - 34, 0xAAAAAA);
            }
        });

        // 材料原生槽：每个候选组放进「同一个」槽位（含全部候选项），EMI 在原生槽位内按 ~1 秒周期轮播。
        int mx = 2, my = getDisplayHeight() - 20;
        for (MultiBlockPattern.MaterialGroup g : IntegrationSupport.materials(pattern)) {
            EmiIngredient ing = EmiIngredient.of(g.alternatives().stream().map(EmiStack::of).toList());
            widgets.addSlot(ing, mx, my);
            mx += 20;
            if (mx > getDisplayWidth() - 20) {
                mx = 2;
                my += 20;
            }
        }
        ItemStack ctrl = IntegrationSupport.controllerItem(pattern);
        // 核心（控制器）放到右上角，避免与左侧预览图重叠
        if (!ctrl.isEmpty()) {
            widgets.addSlot(EmiStack.of(ctrl), getDisplayWidth() - 20, 2);
        }
    }
}
