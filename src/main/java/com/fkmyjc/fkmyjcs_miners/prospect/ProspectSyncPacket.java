package com.fkmyjc.fkmyjcs_miners.prospect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 探矿数据同步包：携带若干「区块键 -> 矿脉摘要」条目，客户端收到后合并进 {@link ClientProspectMirror}。
 */
public class ProspectSyncPacket {

    public final Map<Long, VeinSummary> entries;

    public ProspectSyncPacket(Map<Long, VeinSummary> entries) {
        this.entries = entries;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Map.Entry<Long, VeinSummary> e : entries.entrySet()) {
            buf.writeLong(e.getKey());
            e.getValue().encode(buf);
        }
    }

    public static ProspectSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        Map<Long, VeinSummary> map = new HashMap<>();
        for (int i = 0; i < n; i++) {
            long k = buf.readLong();
            map.put(k, VeinSummary.decode(buf));
        }
        return new ProspectSyncPacket(map);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientProspectMirror.merge(this));
        ctx.get().setPacketHandled(true);
    }
}
