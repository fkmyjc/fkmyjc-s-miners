package com.fkmyjc.fkmyjcs_miners.integration;

import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;

/**
 * JEI 配方包装：把某个多方块结构的某一层包成一个 JEI recipe（供分页），
 * 供 {@code MultiblockPreviewCategory} 消费。
 */
public class MultiblockPreviewRecipe {
    public final MultiBlockPattern pattern;
    public final int layer;
    public final int totalLayers;

    public MultiblockPreviewRecipe(MultiBlockPattern pattern, int layer, int totalLayers) {
        this.pattern = pattern;
        this.layer = layer;
        this.totalLayers = totalLayers;
    }
}
