package com.fkmyjc.fkmyjcs_miners.prospect;

import com.fkmyjc.fkmyjcs_miners.vein.Vein;
import com.fkmyjc.fkmyjcs_miners.vein.VeinOreRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 一个区块矿脉的「简明摘要」：三种矿（主/次/岩石）各自的翻译 key、物品 id 与权重（三者权重和为 100）。
 * 同时支持 NBT（玩家持久化数据）与网络字节流（同步到客户端）两种序列化。
 * 携带物品 id（应用展示映射例外后），以便客户端按矿上色。
 */
public class VeinSummary {

    public final String mainId;     // 主矿物品 id（应用 display override 后），可为 null（纯岩石矿脉）
    public final String secId;      // 次矿物品 id，可为 null（单/贫穷矿脉无次矿）
    public final String rockId;     // 岩石物品 id
    public final String mainName;   // 主矿 translation key（item.<mod>.<id>）
    public final String secName;    // 次矿 translation key，可为 null
    public final String rockName;   // 岩石 translation key
    public final int mainW;         // 主矿权重（%）
    public final int secW;          // 次矿权重（%）
    public final int rockW;         // 岩石权重（%）
    public final int reserves;      // 储量

    public VeinSummary(String mainId, String secId, String rockId,
                       String mainName, String secName, String rockName,
                       int mainW, int secW, int rockW, int reserves) {
        this.mainId = mainId;
        this.secId = secId;
        this.rockId = rockId;
        this.mainName = mainName;
        this.secName = secName;
        this.rockName = rockName;
        this.mainW = mainW;
        this.secW = secW;
        this.rockW = rockW;
        this.reserves = reserves;
    }

    public static VeinSummary from(Vein v) {
        // 应用矿脉矿石展示映射例外（如远古残骸→下界合金碎片）
        Item main = (v.mainOre != null) ? VeinOreRegistry.getVeinDisplayItem(v.mainOre) : null;
        Item sec = (v.secondaryOre != null) ? VeinOreRegistry.getVeinDisplayItem(v.secondaryOre) : null;
        Item rock = v.rock;
        String mainId = (main != null) ? ForgeRegistries.ITEMS.getKey(main).toString() : null;
        String secId = (sec != null) ? ForgeRegistries.ITEMS.getKey(sec).toString() : null;
        String rockId = ForgeRegistries.ITEMS.getKey(rock).toString();
        String mainName = (main != null) ? main.getDescriptionId() : null;
        String secName = (sec != null) ? sec.getDescriptionId() : null;
        String rockName = rock.getDescriptionId();
        return new VeinSummary(mainId, secId, rockId, mainName, secName, rockName,
                v.mainWeight(), v.secondaryWeight(), v.rockWeight(), v.reserves);
    }

    public void toNbt(CompoundTag tag) {
        if (mainId != null) tag.putString("mid", mainId);
        if (secId != null) tag.putString("sid", secId);
        tag.putString("rid", rockId);
        tag.putString("main", mainName);
        if (secName != null) tag.putString("sec", secName);
        tag.putString("rock", rockName);
        tag.putInt("mw", mainW);
        tag.putInt("sw", secW);
        tag.putInt("rw", rockW);
        tag.putInt("res", reserves);
    }

    public static VeinSummary fromNbt(CompoundTag tag) {
        String mid = tag.contains("mid") ? tag.getString("mid") : null;
        String sid = tag.contains("sid") ? tag.getString("sid") : null;
        String rid = tag.contains("rid") ? tag.getString("rid") : null;
        String sec = tag.contains("sec") ? tag.getString("sec") : null;
        return new VeinSummary(
                mid, sid, rid,
                tag.getString("main"),
                sec,
                tag.getString("rock"),
                tag.getInt("mw"),
                tag.getInt("sw"),
                tag.getInt("rw"),
                tag.getInt("res"));
    }

    public void encode(FriendlyByteBuf buf) {
        writeNullable(buf, mainId);
        writeNullable(buf, secId);
        writeNullable(buf, rockId);
        buf.writeUtf(mainName);
        buf.writeBoolean(secName != null);
        if (secName != null) buf.writeUtf(secName);
        buf.writeUtf(rockName);
        buf.writeVarInt(mainW);
        buf.writeVarInt(secW);
        buf.writeVarInt(rockW);
        buf.writeVarInt(reserves);
    }

    public static VeinSummary decode(FriendlyByteBuf buf) {
        String mid = readNullable(buf);
        String sid = readNullable(buf);
        String rid = readNullable(buf);
        String main = buf.readUtf();
        String sec = buf.readBoolean() ? buf.readUtf() : null;
        String rock = buf.readUtf();
        int mw = buf.readVarInt();
        int sw = buf.readVarInt();
        int rw = buf.readVarInt();
        int res = buf.readVarInt();
        return new VeinSummary(mid, sid, rid, main, sec, rock, mw, sw, rw, res);
    }

    private static void writeNullable(FriendlyByteBuf buf, String s) {
        buf.writeBoolean(s != null);
        if (s != null) buf.writeUtf(s);
    }

    private static String readNullable(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf() : null;
    }
}
