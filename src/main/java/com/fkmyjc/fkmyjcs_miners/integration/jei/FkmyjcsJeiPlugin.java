package com.fkmyjc.fkmyjcs_miners.integration.jei;

import com.fkmyjc.fkmyjcs_miners.integration.IntegrationSupport;
import com.fkmyjc.fkmyjcs_miners.integration.MultiblockPreviewRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI 兼容层入口。JEI 在启动时扫描 {@link JeiPlugin} 注解类并实例化本类。
 *
 * <p>注册「多方块预览」分类，并给每个已加载的多方块结构生成一条预览配方，
 * 预览数据统一来自 {@link IntegrationSupport#getPreviewTargets()}。</p>
 */
@JeiPlugin
public class FkmyjcsJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation("fkmyjcs_miners", "jei_plugin");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new MultiblockPreviewCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        List<MultiblockPreviewRecipe> recipes = new ArrayList<>();
        for (IntegrationSupport.PreviewTarget target : IntegrationSupport.getPreviewTargets()) {
            recipes.add(new MultiblockPreviewRecipe(target.pattern, target.layer, target.totalLayers));
        }
        registration.addRecipes(MultiblockPreviewCategory.TYPE, recipes);
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        // 仅在装了 AE2 时挂上「+」→ME 样板编码终端 的转移处理器。
        // 用独立类 Ae2JeiTransfer 承载所有 AE2 引用，保证 AE2 缺失时相关类永不被加载。
        if (ModList.get().isLoaded("ae2")) {
            com.fkmyjc.fkmyjcs_miners.integration.ae2.Ae2JeiTransfer.register(registration);
        }
    }
}
