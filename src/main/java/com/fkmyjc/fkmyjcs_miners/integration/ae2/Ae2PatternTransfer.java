package com.fkmyjc.fkmyjcs_miners.integration.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.integration.modules.jeirei.EncodingHelper;
import appeng.menu.me.items.PatternEncodingTermMenu;
import com.fkmyjc.fkmyjcs_miners.integration.IntegrationSupport;
import com.fkmyjc.fkmyjcs_miners.integration.MultiblockPreviewRecipe;
import com.fkmyjc.fkmyjcs_miners.integration.jei.MultiblockPreviewCategory;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * AE2 集成：把多方块预览里的结构材料，通过 JEI 的「+」按钮写入 ME 样板编码终端的处理样板。
 *
 * <p>所有 AE2 API 引用都隔离在本类（及同包 {@link Ae2JeiTransfer}）里；只有在
 * {@code ModList.isLoaded("ae2")} 为真时才会被 {@code FkmyjcsJeiPlugin} 触碰，
 * 因此 AE2 缺失时本 mod 不会因缺类而崩溃。</p>
 *
 * <p>规则（对应用户需求「候选为列表时选择第一个」）：每个 {@link MultiBlockPattern.MaterialGroup}
 * 取 {@code alternatives().get(0)}——即该格候选组的<b>第一个</b>候选方块，其数量已是该材料在结构中
 * 出现的总次数（见 {@code MultiBlockPattern.getMaterialGroups()}）。控制器方块作为样板输出。</p>
 */
public class Ae2PatternTransfer implements IRecipeTransferHandler<PatternEncodingTermMenu, MultiblockPreviewRecipe> {

    /** AE2 处理样板的最大输入槽数（3x3 编码网格）。超过则无法一次性放入一张样板。 */
    private static final int MAX_INPUTS = 9;

    private final IRecipeTransferHandlerHelper helper;

    public Ae2PatternTransfer(IRecipeTransferHandlerHelper helper) {
        this.helper = helper;
    }

    @Override
    public Class<? extends PatternEncodingTermMenu> getContainerClass() {
        return PatternEncodingTermMenu.class;
    }

    @Override
    public Optional<MenuType<PatternEncodingTermMenu>> getMenuType() {
        return Optional.of(PatternEncodingTermMenu.TYPE);
    }

    @Override
    public RecipeType<MultiblockPreviewRecipe> getRecipeType() {
        return MultiblockPreviewCategory.TYPE;
    }

    @Nullable
    @Override
    public IRecipeTransferError transferRecipe(PatternEncodingTermMenu menu, MultiblockPreviewRecipe recipe,
                                               IRecipeSlotsView slotsView, Player player,
                                               boolean maxTransfer, boolean doTransfer) {
        // 1) 收集材料输入：每个候选组取第一个候选（数量已带在 ItemStack 上）。
        List<List<GenericStack>> inputs = new ArrayList<>();
        for (MultiBlockPattern.MaterialGroup g : IntegrationSupport.materials(recipe.pattern)) {
            if (g.alternatives().isEmpty()) continue;
            ItemStack first = g.alternatives().get(0); // 候选为列表时选择第一个
            if (first.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(first);
            if (key == null) continue;
            // 每个输入位置只放单元素列表 => AE2 不会在候选间挑选，强制用这一个方块。
            inputs.add(List.of(new GenericStack(key, first.getCount())));
        }

        if (inputs.isEmpty()) {
            return helper.createUserErrorWithTooltip(
                    Component.translatable("fkmyjcs_miners.ae2.transfer.no_materials"));
        }
        if (inputs.size() > MAX_INPUTS) {
            return helper.createUserErrorWithTooltip(
                    Component.translatable("fkmyjcs_miners.ae2.transfer.too_many"));
        }

        // 悬停（doTransfer=false）：无错误即代表「+」按钮可点。
        if (!doTransfer) {
            return null;
        }

        // 2) 输出：控制器方块（若有）。
        List<GenericStack> outputs = new ArrayList<>();
        ItemStack controller = IntegrationSupport.controllerItem(recipe.pattern);
        if (!controller.isEmpty()) {
            AEItemKey ck = AEItemKey.of(controller);
            if (ck != null) {
                outputs.add(new GenericStack(ck, 1));
            }
        }

        // 3) 交给 AE2 官方入口写入样板编码终端（处理样板）。
        EncodingHelper.encodeProcessingRecipe(menu, inputs, outputs);
        return null;
    }
}
