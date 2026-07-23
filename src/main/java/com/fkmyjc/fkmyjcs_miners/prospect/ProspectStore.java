package com.fkmyjc.fkmyjcs_miners.prospect;

import com.fkmyjc.fkmyjcs_miners.network.ModNetwork;
import com.fkmyjc.fkmyjcs_miners.vein.Vein;
import com.fkmyjc.fkmyjcs_miners.vein.VeinManager;
import com.fkmyjc.fkmyjcs_miners.vein.VeinOreRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * 玩家探矿数据存储：把每个区块的 {@link VeinSummary} 存进玩家持久化数据
 * （{@link ServerPlayer#getPersistentData()}，跨重生保留），并通过网络同步到客户端镜像。
 */
public final class ProspectStore {

    /** 玩家持久化数据根下的子键。 */
    private static final String KEY = "fkmyjcs_miners:prospect";

    private ProspectStore() {}

    /** 玩家用探矿道具探测当前区块：计算摘要、写入持久化数据、并同步给该客户端。 */
    public static void prospect(ServerPlayer player, ChunkPos chunk) {
        prospect(player, chunk, true);
    }

    /**
     * 同 {@link #prospect(ServerPlayer, ChunkPos)}，但 {@code announce} 控制是否把探测结果发到玩家聊天栏。
     * 实际探矿（探矿杖/探矿仪）传 {@code true}；指令修正矿脉后的重同步传 {@code false}，避免刷屏。
     */
    public static void prospect(ServerPlayer player, ChunkPos chunk, boolean announce) {
        Vein vein = VeinManager.getVein(player.level(), chunk);
        VeinSummary summary = VeinSummary.from(vein);

        CompoundTag root = player.getPersistentData();
        CompoundTag data = root.contains(KEY) ? root.getCompound(KEY) : new CompoundTag();
        CompoundTag entry = new CompoundTag();
        summary.toNbt(entry);
        data.put(chunkKey(chunk), entry);
        root.put(KEY, data);

        // 仅同步这一条更新（客户端做合并）
        Map<Long, VeinSummary> delta = new HashMap<>();
        delta.put(chunk.toLong(), summary);
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ProspectSyncPacket(delta));

        // 把探测结果发到聊天栏（仅真实探矿时）
        if (announce) announceResult(player, summary);
    }

    /** 把矿脉摘要格式化为若干条聊天消息发给玩家。 */
    private static void announceResult(ServerPlayer player, VeinSummary s) {
        player.sendSystemMessage(Component.translatable("fkmyjcs_miners.prospect.chat_title")
                .withStyle(ChatFormatting.GOLD));
        if (s.mainId != null) {
            player.sendSystemMessage(resultLine("主矿：", s.mainName, s.mainId, s.mainW));
        }
        if (s.secId != null) {
            player.sendSystemMessage(resultLine("次矿：", s.secName, s.secId, s.secW));
        }
        player.sendSystemMessage(resultLine("岩石：", s.rockName, s.rockId, s.rockW));
        player.sendSystemMessage(Component.literal("储量：" + String.format("%,d", s.reserves))
                .withStyle(ChatFormatting.AQUA));
    }

    /** 构造一行「标签 + 矿名(翻译) + 权重%」的聊天文本。 */
    private static Component resultLine(String label, String nameKey, String id, int weight) {
        int rgb = (id != null)
                ? VeinOreRegistry.getOreColor(ResourceLocation.tryParse(id)) & 0xFFFFFF
                : 0xE0E0E0;
        return Component.literal(label).withStyle(ChatFormatting.GRAY)
                .append(Component.translatable(nameKey)
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))))
                .append(Component.literal(" " + weight + "%").withStyle(ChatFormatting.YELLOW));
    }

    /** 玩家登录时，把已保存的全部探矿数据一次性发给客户端。 */
    public static void sendAll(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(KEY)) return;
        CompoundTag data = root.getCompound(KEY);
        Map<Long, VeinSummary> all = new HashMap<>();
        for (String k : data.getAllKeys()) {
            try {
                long ck = Long.parseLong(k);
                all.put(ck, VeinSummary.fromNbt(data.getCompound(k)));
            } catch (NumberFormatException ignored) {
                // 跳过非数字键
            }
        }
        if (!all.isEmpty()) {
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ProspectSyncPacket(all));
        }
    }

    /** 玩家是否已探测过该区块（即其探矿数据已写入玩家持久化数据）。 */
    public static boolean hasChunk(ServerPlayer player, ChunkPos chunk) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(KEY)) return false;
        CompoundTag data = root.getCompound(KEY);
        return data.contains(chunkKey(chunk));
    }

    private static String chunkKey(ChunkPos chunk) {
        return Long.toString(chunk.toLong());
    }
}
