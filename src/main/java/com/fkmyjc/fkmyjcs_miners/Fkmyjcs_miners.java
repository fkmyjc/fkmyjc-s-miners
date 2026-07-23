package com.fkmyjc.fkmyjcs_miners;

import com.fkmyjc.fkmyjcs_miners.command.VeinCommand;
import com.fkmyjc.fkmyjcs_miners.ore.*;
import com.fkmyjc.fkmyjcs_miners.ModRegistry;
import com.fkmyjc.fkmyjcs_miners.network.ModNetwork;
import com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockTooltipHandler;
import com.fkmyjc.fkmyjcs_miners.prospect.ClientProspectOverlay;
import com.fkmyjc.fkmyjcs_miners.prospect.ProspectStore;
import com.fkmyjc.fkmyjcs_miners.screen.VeinMapScreen;
import com.fkmyjc.fkmyjcs_miners.screen.VeinScreen;
import com.fkmyjc.fkmyjcs_miners.vein.VeinOreRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.slf4j.Logger;

@Mod(Fkmyjcs_miners.MODID)
public class Fkmyjcs_miners {

    public static final String MODID = "fkmyjcs_miners";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Fkmyjcs_miners() {
        var ctx = FMLJavaModLoadingContext.get();
        var modBus = ctx.getModEventBus();
        ModContainer modContainer = ctx.getContainer();

        // 注册事件监听器
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onLoadComplete);  // ← 新增：加载完成后初始化矿石列表
        // 注册菜单类型（矿脉信息 GUI）
        ModRegistry.register(modBus);
        // 注册多方块结构 / 矿脉矿石池的数据包重载监听器（支持 KubeJS 修改）。
        // 注意：AddReloadListenerEvent 是「游戏事件总线」事件（非 IModBusEvent），
        // 必须挂在 MinecraftForge.EVENT_BUS，挂在 modBus 会启动崩溃。
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        // 注册矿脉信息 GUI 的屏幕与客户端快捷键（仅客户端触发）
        modBus.addListener(this::onClientSetup);
        modBus.addListener(EventPriority.NORMAL, false, RegisterKeyMappingsEvent.class, event -> {
            event.register(ClientProspectOverlay.KEY_TOGGLE);
            event.register(ClientProspectOverlay.KEY_MOVE_UP);
            event.register(ClientProspectOverlay.KEY_MOVE_DOWN);
        });
        MinecraftForge.EVENT_BUS.register(this);
        // 注册多方块自动建造的任务推进（服务端 tick）
        MinecraftForge.EVENT_BUS.register(new com.fkmyjc.fkmyjcs_miners.multiblock.AutoBuildManager());
        // 注册网络通道（探矿数据同步）
        ModNetwork.register();
        modContainer.addConfig(new ModConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC, modContainer));
        modContainer.addConfig(new ModConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC, modContainer));

        LOGGER.info("{} constructed", MODID);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // CommonSetup 只做轻量工作，不依赖 tag
        LOGGER.info("{} common setup", MODID);
    }

    private void onLoadComplete(FMLLoadCompleteEvent event) {
        // 所有 mod 加载完毕，forge:ores 等 tag 100% 就绪
        OreListManager.init();
        // 时运（挖掘掉落物）矿物列表：依赖 OreListManager 的成果，故在实例启动完成阶段初始化
        FortuneMiningListManager.init();
        LOGGER.info("Ore list managers initialized (load complete)");
    }

    private void onAddReloadListeners(net.minecraftforge.event.AddReloadListenerEvent event) {
        // 数据包重载时重新加载（KubeJS 覆盖后 /reload 即生效）：
        event.addListener(com.fkmyjc.fkmyjcs_miners.multiblock.MultiBlockRegistry.INSTANCE); // 多方块结构
        event.addListener(VeinOreRegistry.INSTANCE);                                         // 矿脉矿石池（按维度）
        event.addListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                com.fkmyjc.fkmyjcs_miners.ore.FortuneMiningListManager::onResourceManagerReload); // 时运矿石列表（标签就绪后兜底重生成）
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // 客户端：把 VEIN_MENU 菜单类型映射到绘制屏幕
        event.enqueueWork(() ->
                net.minecraft.client.gui.screens.MenuScreens.register(
                        ModRegistry.VEIN_MENU.get(), VeinScreen::new));
        // 矿脉地图 GUI（7×7）
        event.enqueueWork(() ->
                net.minecraft.client.gui.screens.MenuScreens.register(
                        ModRegistry.VEIN_MAP_MENU.get(), VeinMapScreen::new));
        // 客户端：注册右侧矿脉叠加层（仅客户端触发，专用服务器不会加载该类）
        MinecraftForge.EVENT_BUS.register(new ClientProspectOverlay());
        // 客户端：多方块核心方块悬停尺寸提示（范式——覆盖所有核心方块，无需逐方块硬编码）
        MinecraftForge.EVENT_BUS.register(new MultiBlockTooltipHandler());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // 玩家登录时，把已保存的探矿数据一次性同步到其客户端
        if (event.getEntity() instanceof ServerPlayer sp) {
            ProspectStore.sendAll(sp);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // 注册 /vein 指令，打开当前区块矿脉信息 GUI
        VeinCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Server starting with {} ores", OreListManager.getOres().size());
    }
}