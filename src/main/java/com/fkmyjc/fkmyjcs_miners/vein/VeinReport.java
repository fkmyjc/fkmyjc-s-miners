package com.fkmyjc.fkmyjcs_miners.vein;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 某区块矿脉情况的只读快照（查询结果），由 {@link VeinManager#queryVein} 构造。
 *
 * <p>除完整 {@link Vein} 字段外，还附带维度、区块坐标、初始化状态与生成盐等元信息，
 * 并给出若干派生判定（是否纯岩石 / 是否双矿脉 / 是否含次矿）。</p>
 */
public final class VeinReport {

    public final ResourceLocation dimension;
    public final int chunkX;
    public final int chunkZ;
    public final boolean initialized;
    public final long genSalt;
    public final Vein vein;

    public VeinReport(ResourceLocation dimension, int chunkX, int chunkZ,
                      boolean initialized, long genSalt, Vein vein) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.initialized = initialized;
        this.genSalt = genSalt;
        this.vein = vein;
    }

    /* ===================== 矿脉字段视图 ===================== */

    public VeinType type() { return vein.type; }
    public Item mainOre() { return vein.mainOre; }
    public Item secondaryOre() { return vein.secondaryOre; }
    public Item rock() { return vein.rock; }

    public int mainWeight() { return vein.mainWeight(); }
    public int secondaryWeight() { return vein.secondaryWeight(); }
    public int rockWeight() { return vein.rockWeight(); }
    public int reserves() { return vein.reserves; }

    public String mainOreId() { return idOrNull(vein.mainOre); }
    public String secondaryOreId() { return idOrNull(vein.secondaryOre); }
    public String rockId() { return idOrNull(vein.rock); }

    /* ===================== 派生判定 ===================== */

    /** 是否为纯岩石矿脉（无主矿）。 */
    public boolean isPureRock() { return vein.mainOre == null; }
    /** 是否为双矿石矿脉。 */
    public boolean isDouble() { return vein.type == VeinType.DOUBLE; }
    /** 是否含有次矿（次矿物品非空）。 */
    public boolean hasSecondary() { return vein.secondaryOre != null; }

    private static String idOrNull(Item item) {
        if (item == null) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return (id == null) ? null : id.toString();
    }
}
