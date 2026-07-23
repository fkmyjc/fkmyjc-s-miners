package com.fkmyjc.fkmyjcs_miners.integration.jade;

import net.minecraft.world.level.block.Block;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * Jade 兼容层入口。Jade 在启动时扫描 {@link WailaPlugin} 注解类并实例化本类。
 *
 * <p>注册「多方块核心成形状态」组件：当玩家看向任意多方块控制器方块时，
 * 在 Jade 信息框追加一行「已成形 / 未成形」。数据来自
 * {@link com.fkmyjcs_miners.multiblock.MultiBlockRegistry}。</p>
 *
 * <p>本插件只在装有 Jade 时被其注解扫描器发现并加载；不装 Jade 时本类永远不会被
 * 类加载，因此本 mod 在缺少 Jade 时也能正常加载运行（与 JEI/REI/EMI 同样的软依赖模式）。</p>
 */
@WailaPlugin("fkmyjcs_miners")
public class FkmyjcsJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // 注册到 Block 基类：组件内部会先判断该方块是否为某多方块结构的控制器，
        // 不是则直接跳过，所以不会影响任何非控制器方块。
        registration.registerBlockComponent(MultiblockFormationProvider.INSTANCE, Block.class);
    }
}
