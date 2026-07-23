package com.fkmyjc.fkmyjcs_miners.multiblock;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 全局物品提示处理器：让任何「多方块核心方块」的物品在悬停时自动显示结构尺寸。
 *
 * <p>范式实现——判定标准是「该方块是否注册为多方块核心」
 * （{@link MultiBlockRegistry#getPattern(Block)} 非 null），而非逐个方块硬编码。
 * 因此现在与未来的任意核心方块均零成本获得尺寸信息栏，无需修改方块类本身。</p>
 */
@OnlyIn(Dist.CLIENT)
public class MultiBlockTooltipHandler {

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;
        Block block = blockItem.getBlock();
        MultiBlockPattern pattern = MultiBlockRegistry.getPattern(block);
        if (pattern == null) return;
        int[] size = pattern.getSize();
        event.getToolTip().add(Component.translatable(
                "tooltip.fkmyjcs_miners.multiblock.size", size[0], size[1], size[2]));
    }
}
