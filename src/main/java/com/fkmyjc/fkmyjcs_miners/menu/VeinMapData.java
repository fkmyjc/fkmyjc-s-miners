package com.fkmyjc.fkmyjcs_miners.menu;

import com.fkmyjc.fkmyjcs_miners.prospect.VeinSummary;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 7×7 区块矿脉地图的「可序列化快照」：在服务端由 {@code /vein gui} 构造，
 * 经 {@link FriendlyByteBuf} 发给客户端 {@code VeinMapScreen} 显示。
 *
 * <p>布局：以玩家当前区块为中心（{@link #centerX}/{@link #centerZ}），
 * {@link #cells} 长度恒为 49，下标 {@code idx == (dz+3)*7 + (dx+3)}，
 * 其中 {@code dx,dz ∈ [-3,3]} 是相对中心区块的偏移；中心区块（dx=dz=0）即 {@code idx==24}。
 * <p>每个单元是一个 {@link VeinSummary}（主/次/岩石 id、权重、储量），客户端据此按矿上色。
 */
public final class VeinMapData {

    public static final int RADIUS = 3;          // 中心向各方向 ±3
    public static final int SIDE = RADIUS * 2 + 1; // 7
    public static final int CELLS = SIDE * SIDE;    // 49

    public final int centerX;
    public final int centerZ;
    public final VeinSummary[] cells; // 长度 49

    public VeinMapData(int centerX, int centerZ, VeinSummary[] cells) {
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.cells = cells;
    }

    /** 由相对偏移 (dx,dz) 计算 cells 下标。dx,dz ∈ [-3,3]。 */
    public static int indexOf(int dx, int dz) {
        return (dz + RADIUS) * SIDE + (dx + RADIUS);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(centerX);
        buf.writeInt(centerZ);
        buf.writeVarInt(cells.length);
        for (VeinSummary s : cells) {
            // 高级探矿仪能量不足时部分区块会留空，用 null 标记传递“未探明”格子
            if (s == null) {
                buf.writeBoolean(false);
            } else {
                buf.writeBoolean(true);
                s.encode(buf);
            }
        }
    }

    public static VeinMapData decode(FriendlyByteBuf buf) {
        int cx = buf.readInt();
        int cz = buf.readInt();
        int n = buf.readVarInt();
        VeinSummary[] arr = new VeinSummary[n];
        for (int i = 0; i < n; i++) {
            arr[i] = buf.readBoolean() ? VeinSummary.decode(buf) : null;
        }
        return new VeinMapData(cx, cz, arr);
    }
}
