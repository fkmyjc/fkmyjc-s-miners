package com.fkmyjc.fkmyjcs_miners.block;

import com.fkmyjc.fkmyjcs_miners.Config;
import com.fkmyjc.fkmyjcs_miners.multiblock.AutoBuildManager;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockPattern;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 测试用的多方块机器控制器方块。
 *
 * <p>它自身不做任何生产逻辑，仅作为多方块结构的「控制器」：右键时以自身为锚点、
 * 按当前朝向旋转结构并调用 {@link MultiBlockRegistry#isFormed} 校验是否成形，
 * 再把结果以双语提示发给玩家。用于直观验证多方块系统。</p>
 *
 * <p>结构定义见 {@code data/fkmyjcs_miners/multiblock/test_machine.json}，
 * 可被 KubeJS 覆盖后 /reload 生效。</p>
 */
public class TestMachineBlock extends HorizontalDirectionalBlock {

    public TestMachineBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        // 用配置物品右键核心 → 触发自动建造
        ItemStack held = player.getItemInHand(hand);
        if (Config.autoBuildTriggerItem != null && held.getItem() == Config.autoBuildTriggerItem) {
            MultiBlockPattern pattern = MultiBlockRegistry.getPattern(this);
            if (pattern != null) {
                AutoBuildManager.startBuild(player, level, pos, state.getValue(FACING), pattern);
                return InteractionResult.CONSUME;
            }
        }

        boolean formed = MultiBlockRegistry.isFormed(level, pos, state.getValue(FACING));
        if (formed) {
            player.sendSystemMessage(Component.translatable("message.fkmyjcs_miners.test_machine.formed"));
        } else {
            player.sendSystemMessage(Component.translatable("message.fkmyjcs_miners.test_machine.not_formed"));
        }
        return InteractionResult.CONSUME;
    }

}
