package com.fkmyjc.fkmyjcs_miners.integration.rei;

import com.fkmyjc.fkmyjcs_miners.integration.IntegrationSupport;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.forge.REIPlugin;
import net.minecraftforge.api.distmarker.Dist;

import java.util.ArrayList;
import java.util.List;

/**
 * REI 兼容层入口。REI 在客户端扫描 {@link REIPlugin} 注解类并实例化本类
 * （{@link Dist#CLIENT} 保证它只在客户端被加载）。
 *
 * <p>注册「多方块预览」分类，并给每个已加载的多方块结构生成一条预览 display，
 * 预览数据统一来自 {@link IntegrationSupport#getPreviewTargets()}。</p>
 */
@REIPlugin(Dist.CLIENT)
public class FkmyjcsReiPlugin implements REIClientPlugin {
    @Override
    public void registerCategories(CategoryRegistry registry) {
        registry.add(new MultiblockPreviewCategory());
    }

    @Override
    public void registerDisplays(DisplayRegistry registry) {
        CategoryIdentifier<MultiblockPreviewDisplay> id = MultiblockPreviewCategory.ID;
        for (IntegrationSupport.PreviewTarget target : IntegrationSupport.getPreviewTargets()) {
            registry.add(new MultiblockPreviewDisplay(target.pattern, target.layer, target.totalLayers, id));
        }
    }
}
