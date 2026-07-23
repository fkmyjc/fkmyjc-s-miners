package com.fkmyjc.fkmyjcs_miners.integration.jade;

import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * 在 Jade 信息框为「多方块控制器方块」追加一行：核心是否已成形。
 *
 * <p>只对控制器方块生效：{@link MultiblockFormationProvider#appendTooltip} 会先通过
 * {@link MultiBlockRegistry#getPattern} 判断当前方块是否为某结构控制器，不是则直接返回。</p>
 */
public enum MultiblockFormationProvider implements IBlockComponentProvider {
    INSTANCE;

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (MultiBlockRegistry.getPattern(accessor.getBlock()) == null) return; // 不是多方块控制器，跳过

        BlockState state = accessor.getBlockState();
        Direction facing = state.hasProperty(HorizontalDirectionalBlock.FACING)
                ? state.getValue(HorizontalDirectionalBlock.FACING)
                : Direction.NORTH;
        BlockPos pos = accessor.getPosition();
        boolean formed = MultiBlockRegistry.isFormed(accessor.getLevel(), pos, facing);

        tooltip.add(Component.translatable(formed
                ? "jade.fkmyjcs_miners.multiblock.formed"
                : "jade.fkmyjcs_miners.multiblock.not_formed"));
    }

    @Override
    public ResourceLocation getUid() {
        return new ResourceLocation("fkmyjcs_miners", "multiblock_formation");
    }
}
