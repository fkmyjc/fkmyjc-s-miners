package com.fkmyjc.fkmyjcs_miners.network;

import com.fkmyjc.fkmyjcs_miners.Fkmyjcs_miners;
import com.fkmyjc.fkmyjcs_miners.prospect.OverlayTogglePacket;
import com.fkmyjc.fkmyjcs_miners.prospect.ProspectSyncPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 本模组的网络通道。当前仅承载「探矿数据同步」包（{@link ProspectSyncPacket}）。
 */
public final class ModNetwork {

    public static final String PROTOCOL = "1";
    public static final ResourceLocation CHANNEL_ID = new ResourceLocation(Fkmyjcs_miners.MODID, "main");
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CHANNEL_ID,
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private static boolean registered = false;

    /** 注册所有消息（幂等，只需调用一次）。 */
    public static void register() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(
                0,
                ProspectSyncPacket.class,
                (msg, buf) -> msg.encode(buf),
                ProspectSyncPacket::decode,
                (msg, ctx) -> msg.handle(ctx));
        // 1: 右侧叠加层显示开关（/vein display）
        CHANNEL.registerMessage(
                1,
                OverlayTogglePacket.class,
                (msg, buf) -> msg.encode(buf),
                OverlayTogglePacket::decode,
                (msg, ctx) -> msg.handle(ctx));
    }

    private ModNetwork() {}
}
