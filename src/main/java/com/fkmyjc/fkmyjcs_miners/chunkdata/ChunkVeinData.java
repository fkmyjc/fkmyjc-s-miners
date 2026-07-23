package com.fkmyjc.fkmyjcs_miners.chunkdata;

import com.fkmyjc.fkmyjcs_miners.vein.Vein;
import com.fkmyjc.fkmyjcs_miners.vein.VeinType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 区块矿脉的持久化数据容器，是「该区块矿脉」的权威存储。
 *
 * <p>它完整保存一个矿脉的全部可变字段（类型 / 主矿 / 次矿 / 岩石 / 三权重 / 储量），
 * 实际由 {@link VeinSavedData} 按维度统一持久化（不依赖 chunk capability）。
 *
 * <p>与 {@link com.fkmyjc.fkmyjcs_miners.vein.VeinManager} 的关系：
 * <ul>
 *   <li>{@code VeinManager.getVein} 是“读取入口”，会从 {@link VeinSavedData} 取已存储矿脉；
 *       首次访问（未初始化）时由确定性随机生成并写回，之后 SavedData 即为权威。</li>
 *   <li>{@code /vein} 指令修改的正是此处字段，并通过
 *       {@code VeinManager.invalidate} 使缓存失效，使后续读取反映修改后的值。</li>
 * </ul>
 *
 * <p><b>兼容性</b>：旧格式仅含 {@code reserves} 字段（无 {@code type}）时，反序列化后
 * {@code initialized=false}，交由 {@code VeinManager} 按当前版本重新生成，避免把旧数据
 * 误读成“纯石头矿脉”。</p>
 */
public class ChunkVeinData {

    /** 类型编码：0=贫穷(POVERTY) / 1=单(SINGLE) / 2=双(DOUBLE)。与 VeinType 顺序独立，便于指令用整数表达。 */
    private static final int CODE_POVERTY = 0;
    private static final int CODE_SINGLE = 1;
    private static final int CODE_DOUBLE = 2;

    private int typeCode = CODE_SINGLE;
    private String mainOreId = null;       // 主矿物品 id（纯岩石矿脉为 null）
    private String secondaryOreId = null;   // 次矿物品 id（单/贫穷/纯岩石为 null）
    private String rockId = null;           // 岩石物品 id
    private int mainW = 0;
    private int secW = 0;
    private int rockW = 100;
    private int reserves = -1;
    /** 生成盐：首次确定性生成用 0；{@code /vein reset} 会重新随机此值，使本区块获得一个“全新”矿脉（而非还原旧值）。 */
    private long genSalt = 0L;
    private boolean initialized = false;

    public boolean isInitialized() {
        return initialized;
    }

    /* ===================== 由生成结果初始化 ===================== */

    /** 用确定性生成的 {@link Vein} 覆盖本区块的全部字段，并标记为已初始化。 */
    public void initFromVein(Vein v) {
        this.typeCode = codeOf(v.type);
        this.mainOreId = idOrNull(v.mainOre);
        this.secondaryOreId = idOrNull(v.secondaryOre);
        this.rockId = idOrNull(v.rock);
        this.mainW = v.mainWeight();
        this.secW = v.secondaryWeight();
        this.rockW = v.rockWeight();
        this.reserves = v.reserves;
        this.initialized = true;
    }

    /* ===================== 重建为 Vein（供 VeinManager / 展示 / 探矿） ===================== */

    /** 由已存储字段重建一个 {@link Vein}。矿石 id 失效（如模组被移除）时对应项为 null。 */
    public Vein toVein() {
        Item main = itemOrNull(mainOreId);
        Item sec = itemOrNull(secondaryOreId);
        Item rock = itemOrNull(rockId);
        if (rock == null) rock = Blocks.STONE.asItem();   // 岩石缺失兜底为石头
        return new Vein(fromCode(typeCode), main, sec, rock, mainW, secW, rockW, reserves);
    }

    /* ===================== 指令用 mutator（就地修改，调用方负责落盘/失效缓存） ===================== */

    public void setType(int code) {
        if (code >= 0 && code <= 2) {
            this.typeCode = code;
            this.initialized = true;
        }
    }

    public void setMainWeight(int w) { this.mainW = clamp(w, 0, 100); }
    public void setSecondaryWeight(int w) { this.secW = clamp(w, 0, 100); }
    public void setRockWeight(int w) { this.rockW = clamp(w, 0, 100); }

    /** 设置主矿（传 null 表示清除）。 */
    public void setMainOre(ResourceLocation id) { this.mainOreId = rlOrNull(id); this.initialized = true; }
    /** 设置次矿（传 null 表示清除）。 */
    public void setSecondaryOre(ResourceLocation id) { this.secondaryOreId = rlOrNull(id); this.initialized = true; }
    /** 设置岩石（传 null 表示清除，回退石头）。 */
    public void setRock(ResourceLocation id) { this.rockId = rlOrNull(id); this.initialized = true; }

    public void setReserves(int r) { this.reserves = Math.max(0, r); }
    public void addReserves(int d) { this.reserves = Math.max(0, this.reserves + d); }

    /** 设置生成盐并重生成（reset 用）；同时标记已初始化，避免被当作旧格式。 */
    public void setGenSalt(long s) { this.genSalt = s; this.initialized = true; }
    public long getGenSalt() { return genSalt; }

    /* ===================== 读取（供指令反馈） ===================== */

    public int getTypeCode() { return typeCode; }
    public int getMainWeight() { return mainW; }
    public int getSecondaryWeight() { return secW; }
    public int getRockWeight() { return rockW; }
    public int getReserves() { return reserves; }
    public String getMainOreId() { return mainOreId; }
    public String getSecondaryOreId() { return secondaryOreId; }
    public String getRockId() { return rockId; }

    /* ===================== NBT 持久化 ===================== */

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("initialized", initialized);
        tag.putInt("type", typeCode);
        tag.putInt("mainW", mainW);
        tag.putInt("secW", secW);
        tag.putInt("rockW", rockW);
        tag.putInt("reserves", reserves);
        tag.putLong("salt", genSalt);
        putNullable(tag, "main", mainOreId);
        putNullable(tag, "sec", secondaryOreId);
        putNullable(tag, "rock", rockId);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        // 旧格式兼容：仅有 reserves（无 type）视为未初始化，重新生成而非误读
        if (!tag.contains("type")) {
            this.initialized = false;
            return;
        }
        this.initialized = tag.getBoolean("initialized");
        this.typeCode = tag.getInt("type");
        this.mainW = tag.getInt("mainW");
        this.secW = tag.getInt("secW");
        this.rockW = tag.getInt("rockW");
        this.reserves = tag.getInt("reserves");
        this.genSalt = tag.getLong("salt");   // 旧存档无此字段时默认 0（确定性生成）
        this.mainOreId = getNullable(tag, "main");
        this.secondaryOreId = getNullable(tag, "sec");
        this.rockId = getNullable(tag, "rock");
    }

    /* ===================== 内部工具 ===================== */

    private static void putNullable(CompoundTag tag, String key, String v) {
        if (v != null) tag.putString(key, v);
    }

    private static String getNullable(CompoundTag tag, String key) {
        return tag.contains(key) ? tag.getString(key) : null;
    }

    private static String idOrNull(Item item) {
        if (item == null) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return (id == null) ? null : id.toString();
    }

    private static String rlOrNull(ResourceLocation id) {
        return (id == null) ? null : id.toString();
    }

    private static Item itemOrNull(String id) {
        if (id == null) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Item it = ForgeRegistries.ITEMS.getValue(rl);
        return (it == null || it == Items.AIR) ? null : it;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int codeOf(VeinType t) {
        return switch (t) {
            case POVERTY -> CODE_POVERTY;
            case SINGLE -> CODE_SINGLE;
            case DOUBLE -> CODE_DOUBLE;
        };
    }

    private static VeinType fromCode(int c) {
        return switch (c) {
            case CODE_POVERTY -> VeinType.POVERTY;
            case CODE_DOUBLE -> VeinType.DOUBLE;
            default -> VeinType.SINGLE;
        };
    }
}
