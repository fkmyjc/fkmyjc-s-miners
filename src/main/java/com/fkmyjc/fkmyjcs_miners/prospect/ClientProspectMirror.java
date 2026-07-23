package com.fkmyjc.fkmyjcs_miners.prospect;

import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端持有的「区块 -> 矿脉摘要」镜像，由 {@link ProspectSyncPacket} 增量更新。
 * 右侧叠加层据此在当前区块已被探过时绘制矿脉信息。
 */
public final class ClientProspectMirror {

    private static final Map<Long, VeinSummary> MAP = new ConcurrentHashMap<>();

    private ClientProspectMirror() {}

    public static void merge(ProspectSyncPacket packet) {
        MAP.putAll(packet.entries);
    }

    /** 取当前区块的摘要；未探测过返回 null。 */
    public static VeinSummary get(ChunkPos chunk) {
        return MAP.get(chunk.toLong());
    }
}
