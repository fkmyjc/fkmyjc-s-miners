package com.fkmyjc.fkmyjcs_miners.integration.rei;

import com.fkmyjc.fkmyjcs_miners.integration.IntegrationSupport;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * REI 的「多方块预览」display：INPUT = 结构材料，OUTPUT = 控制器方块。
 * 每个结构是拆成每层一个 display 的（见 FkmyjcsReiPlugin），layer 用于分页标题。
 */
public class MultiblockPreviewDisplay implements Display {
    private final MultiBlockPattern pattern;
    private final int layer;
    private final int totalLayers;
    private final CategoryIdentifier<MultiblockPreviewDisplay> category;

    public MultiblockPreviewDisplay(MultiBlockPattern pattern, int layer, int totalLayers,
                                    CategoryIdentifier<MultiblockPreviewDisplay> category) {
        this.pattern = pattern;
        this.layer = layer;
        this.totalLayers = totalLayers;
        this.category = category;
    }

    public MultiBlockPattern pattern() {
        return pattern;
    }

    public int layer() {
        return layer;
    }

    public int totalLayers() {
        return totalLayers;
    }

    @Override
    public List<EntryIngredient> getInputEntries() {
        // 材料视觉槽位由 MultiblockPreviewCategory.setupDisplay 以原生槽位铺排（含轮播）；
        // 此处仍需返回扁平材料清单，供 REI 搜索索引使用。
        return IntegrationSupport.materialStacks(pattern).stream()
                .map(s -> EntryIngredient.of(EntryStacks.of(s)))
                .toList();
    }

    @Override
    public List<EntryIngredient> getOutputEntries() {
        ItemStack ctrl = IntegrationSupport.controllerItem(pattern);
        return ctrl.isEmpty() ? List.of() : List.of(EntryIngredient.of(EntryStacks.of(ctrl)));
    }

    @Override
    public CategoryIdentifier<?> getCategoryIdentifier() {
        return category;
    }
}
