package com.fkmyjc.fkmyjcs_miners.integration;

import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多方块预览标签页的共享数据来源。
 *
 * <p>三家查看器（JEI / REI / EMI）的插件各自实现自己的分类/配方模型，
 * 但都从这里拿到「要预览哪些多方块结构的哪一层」以及「控制器方块 / 所需材料」。</p>
 *
 * <p>分页策略：每个结构的每一层拆成一个独立的 {@link PreviewTarget}，
 * 这样在 JEI/REI/EMI 里天然就有「左/右箭头翻页」（每层一条 recipe/display），
 * 无需在分类内部自己实现点击事件。</p>
 */
public final class IntegrationSupport {
    /** JEI/REI/EMI 共用的分类标题翻译键（EMI 会自动用 emi.category.&lt;ns&gt;.&lt;path&gt;）。 */
    public static final String CATEGORY_KEY = "fkmyjcs_miners.jei.category.multiblock_preview";

    private IntegrationSupport() {
    }

    /** 一个预览目标：指定某个结构 + 某一层（用于分页，每层一个 recipe）。 */
    public static class PreviewTarget {
        public final MultiBlockPattern pattern;
        public final int layer;
        public final int totalLayers;

        public PreviewTarget(MultiBlockPattern pattern, int layer, int totalLayers) {
            this.pattern = pattern;
            this.layer = layer;
            this.totalLayers = totalLayers;
        }
    }

    /** 当前已加载的全部多方块结构的每一层，作为预览数据源（每层一个 target）。 */
    public static List<PreviewTarget> getPreviewTargets() {
        List<PreviewTarget> result = new ArrayList<>();
        for (MultiBlockPattern p : MultiBlockRegistry.getAllPatterns()) {
            int n = p.getLayerCount();
            for (int y = 0; y < n; y++) {
                result.add(new PreviewTarget(p, y, n));
            }
        }
        return result;
    }

    /** 预览分类/配方的控制器方块物品（结构无 controller 时返回空）。 */
    public static ItemStack controllerItem(MultiBlockPattern pattern) {
        Block b = pattern.getControllerBlock();
        return b != null ? new ItemStack(b) : ItemStack.EMPTY;
    }

    /** 结构所需材料组（供原生槽位展示）：每组含某字符(候选组)出现次数 + 全部候选方块物品(均带数量)。
     *  候选组的所有候选项放在<b>同一个</b>物品列表里，由原生槽位按 ~1s 周期轮播。 */
    public static List<MultiBlockPattern.MaterialGroup> materials(MultiBlockPattern pattern) {
        return pattern.getMaterialGroups();
    }

    /** 结构所需材料（扁平化，供搜索索引 / 原生槽位铺排）：每个候选组展开为多个带数量的 ItemStack。 */
    public static List<ItemStack> materialStacks(MultiBlockPattern pattern) {
        List<ItemStack> out = new ArrayList<>();
        for (MultiBlockPattern.MaterialGroup g : pattern.getMaterialGroups()) {
            out.addAll(g.alternatives());
        }
        return out;
    }

    /** 取首个有 controller 方块的结构的控制器物品，作分类图标；都没有则回退纸张。 */
    public static ItemStack firstControllerItem() {
        for (PreviewTarget t : getPreviewTargets()) {
            ItemStack s = controllerItem(t.pattern);
            if (!s.isEmpty()) return s;
        }
        return new ItemStack(net.minecraft.world.item.Items.PAPER);
    }
}
