package com.fkmyjc.fkmyjcs_miners.integration.emi;

import com.fkmyjc.fkmyjcs_miners.integration.IntegrationSupport;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * EMI 兼容层入口。在 Forge/NeoForge 上，EMI 通过 {@link EmiEntrypoint} 注解
 * 发现并加载本类。
 *
 * <p>注册「多方块预览」分类，并给每个已加载的多方块结构生成一条预览配方，
 * 预览数据统一来自 {@link IntegrationSupport#getPreviewTargets()}。</p>
 */
@EmiEntrypoint
public class FkmyjcsEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        ItemStack icon = IntegrationSupport.firstControllerItem();
        EmiRecipeCategory category = new EmiRecipeCategory(
                new ResourceLocation("fkmyjcs_miners", "multiblock_preview"),
                EmiStack.of(icon.isEmpty() ? new ItemStack(Items.PAPER) : icon));
        registry.addCategory(category);

        int i = 0;
        for (IntegrationSupport.PreviewTarget target : IntegrationSupport.getPreviewTargets()) {
            ResourceLocation id = new ResourceLocation("fkmyjcs_miners", "mb_preview_" + i);
            registry.addRecipe(new MultiblockPreviewRecipe(
                    category, target.pattern, id, target.layer, target.totalLayers));
            i++;
        }
    }
}
