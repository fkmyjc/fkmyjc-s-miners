package com.fkmyjc.fkmyjcs_miners.prospect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 叠加层显示开关包：由服务端 {@code /vein display} 指令发给执行指令的玩家客户端，
 * 触发 {@link ClientProspectOverlay#toggle()} 翻转右侧矿脉叠加层的显示状态。
 * 无载荷（状态由客户端本地维护，服务端不追踪）。
 */
public class OverlayTogglePacket {

    public OverlayTogglePacket() {}

    public void encode(FriendlyByteBuf buf) {
        // 无载荷
    }

    public static OverlayTogglePacket decode(FriendlyByteBuf buf) {
        return new OverlayTogglePacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(ClientProspectOverlay::toggle);
        ctx.get().setPacketHandled(true);
    }
}
