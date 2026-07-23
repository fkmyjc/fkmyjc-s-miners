package com.fkmyjc.fkmyjcs_miners.integration.jei;

import com.fkmyjc.fkmyjcs_miners.integration.IntegrationSupport;
import com.fkmyjc.fkmyjcs_miners.integration.MultiblockPreviewRecipe;
import com.fkmyjc.fkmyjcs_miners.integration.MultiblockSchematicRenderer;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * JEI 里的「多方块预览」分类。左侧画 2D 分层示意图，OUTPUT 槽放控制器方块，
 * INPUT 槽放结构所需材料。
 */
public class MultiblockPreviewCategory implements IRecipeCategory<MultiblockPreviewRecipe> {
    public static final RecipeType<MultiblockPreviewRecipe> TYPE =
            RecipeType.create("fkmyjcs_miners", "multiblock_preview", MultiblockPreviewRecipe.class);

    private static final int WIDTH = 120;
    private static final int HEIGHT = 200;
    /** 示意图右侧为控制器槽(右上角)让出空间，避免候选清单被控制器槽遮挡。 */
    private static final int SCHEMATIC_MAX_W = WIDTH - 30;

    private final IDrawable background;
    private final IGuiHelper guiHelper;

    public MultiblockPreviewCategory(IGuiHelper guiHelper) {
        this.guiHelper = guiHelper;
        this.background = guiHelper.createBlankDrawable(WIDTH, HEIGHT);
    }

    @Override
    public RecipeType<MultiblockPreviewRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable(IntegrationSupport.CATEGORY_KEY);
    }

    @Override
    public IDrawable getBackground() {
        return background;
    }

    @Override
    public IDrawable getIcon() {
        return guiHelper.createDrawableItemStack(IntegrationSupport.firstControllerItem());
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, MultiblockPreviewRecipe recipe, IFocusGroup focuses) {
        ItemStack ctrl = IntegrationSupport.controllerItem(recipe.pattern);
        // 核心（控制器）放到右上角，避免与左侧预览图重叠
        if (!ctrl.isEmpty()) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, WIDTH - 20, 2).addItemStack(ctrl);
        }
        // 材料列表用原生 INPUT 槽：每个候选组放进「同一个」槽位（含全部候选项），
        // JEI 会在原生槽位内按 ~1 秒周期轮播候选项，实现「每 1 秒刷新一次」。
        int mx = 2, my = HEIGHT - 20;
        for (MultiBlockPattern.MaterialGroup g : IntegrationSupport.materials(recipe.pattern)) {
            builder.addSlot(RecipeIngredientRole.INPUT, mx, my).addItemStacks(g.alternatives());
            mx += 20;
            if (mx > WIDTH - 20) {
                mx = 2;
                my += 20;
            }
        }
    }

    @Override
    public void draw(MultiblockPreviewRecipe recipe, IRecipeSlotsView slotsView,
                     GuiGraphics guiGraphics, double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;
        int wrapWidth = WIDTH - 12;   // 左右各留 6px 边距

        // 字段显示（结构 name/id）置于顶部，自动换行避免超出屏幕
        int y = 6;
        String display = recipe.pattern.getDisplayName();
        for (var line : font.split(Component.literal(display), wrapWidth)) {
            guiGraphics.drawString(font, line, 6, y, 0xFFFFFF);
            y += font.lineHeight + 2;
        }
        String id = recipe.pattern.getId();
        if (!id.isEmpty() && !id.equals(display)) {
            for (var line : font.split(Component.literal(id), wrapWidth)) {
                guiGraphics.drawString(font, line, 6, y, 0x55FFFF); // 青色 id，易与白色 name 区分
                y += font.lineHeight + 2;
            }
        }
        int top = y + 6;   // 字段下方的起始 y：示意图与页码下移至此，避免与字段重叠

        // 实际显示（2D 分层示意图）下移
        MultiblockSchematicRenderer.drawLayer(guiGraphics, 6, top, SCHEMATIC_MAX_W,
                recipe.pattern, recipe.layer, mouseX, mouseY);
        // 页码（Lx/y）下移，置于示意图下方、材料槽上方
        guiGraphics.drawString(font,
                "L" + (recipe.layer + 1) + "/" + recipe.totalLayers,
                6, HEIGHT - 34, 0xAAAAAA);
    }
}
