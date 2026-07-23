package com.fkmyjc.fkmyjcs_miners.chunkdata;

import com.fkmyjc.fkmyjcs_miners.Fkmyjcs_miners;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 按维度（{@link ServerLevel}）持久化所有已访问区块的矿脉数据。
 *
 * <p>相比挂在 {@code LevelChunk} 上的 capability，SavedData 不依赖
 * {@code AttachCapabilitiesEvent} 的注册时序，写入与读取更稳定可靠，
 * 是 {@code /vein} 指令修改与矿脉持久化的权威来源。
 *
 * <p>每个维度独立保存一份文件（{@code data/<dim>/fkmyjcs_miners_chunk_veins.dat}），
 * 只在区块首次被访问或指令修改时写入，开销可控。</p>
 */
public class VeinSavedData extends SavedData {

    private static final String NAME = Fkmyjcs_miners.MODID + "_chunk_veins";

    private final Map<ChunkPos, ChunkVeinData> chunks = new HashMap<>();

    public static VeinSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(VeinSavedData::load, VeinSavedData::new, NAME);
    }

    public static VeinSavedData load(CompoundTag tag) {
        VeinSavedData data = new VeinSavedData();
        CompoundTag chunksTag = tag.getCompound("chunks");
        for (String key : chunksTag.getAllKeys()) {
            String[] parts = key.split(",");
            if (parts.length != 2) {
                Fkmyjcs_miners.LOGGER.warn("VeinSavedData: invalid chunk key '{}'", key);
                continue;
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int z = Integer.parseInt(parts[1]);
                ChunkPos cp = new ChunkPos(x, z);
                ChunkVeinData d = new ChunkVeinData();
                d.deserializeNBT(chunksTag.getCompound(key));
                data.chunks.put(cp, d);
            } catch (NumberFormatException e) {
                Fkmyjcs_miners.LOGGER.warn("VeinSavedData: failed to parse chunk key '{}'", key);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag chunksTag = new CompoundTag();
        for (Map.Entry<ChunkPos, ChunkVeinData> entry : chunks.entrySet()) {
            String key = entry.getKey().x + "," + entry.getKey().z;
            chunksTag.put(key, entry.getValue().serializeNBT());
        }
        tag.put("chunks", chunksTag);
        return tag;
    }

    /** 获取或创建某区块的矿脉数据。 */
    public ChunkVeinData getOrCreate(ChunkPos cp) {
        return chunks.computeIfAbsent(cp, k -> new ChunkVeinData());
    }

    /** 删除某区块数据（调试用）。 */
    public void remove(ChunkPos cp) {
        chunks.remove(cp);
    }

    /** 遍历所有已记录的区块矿脉数据（供 {@code /vein reset all} 等批量操作）。 */
    public void forEach(BiConsumer<ChunkPos, ChunkVeinData> consumer) {
        chunks.forEach(consumer);
    }
}
