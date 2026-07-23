package com.fkmyjc.fkmyjcs_miners.item;

import com.fkmyjc.fkmyjcs_miners.prospect.ProspectStore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 探矿杖：64 耐久，右键当前区块即触发一次探矿（消耗 1 点耐久），
 * 并把该区块矿脉写入玩家数据。带 {@code #fkmyjcs_miners:vein_display} 标签。
 */
public class ProspectWandItem extends Item {

    public ProspectWandItem() {
        super(new Properties().durability(64).setNoRepair());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack); // 实际逻辑在服务端
        ProspectStore.prospect((ServerPlayer) player, player.chunkPosition());
        stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(hand));
        return InteractionResultHolder.consume(stack);
    }
}
