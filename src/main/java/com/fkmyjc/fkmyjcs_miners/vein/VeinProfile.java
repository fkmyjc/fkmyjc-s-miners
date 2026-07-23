package com.fkmyjc.fkmyjcs_miners.vein;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个「矿脉维度实例（Profile）」——把某一类维度的矿脉规则打包成可复用、可被 KubeJS 编辑的模块。
 *
 * <p>这是「区块矿脉 × 维度」关系的模块化封装。每个实例是独立的一份规则，包含：
 * <ol>
 *   <li><b>id</b>：实例名（默认三个：{@code overworld} / {@code nether} / {@code end}）；</li>
 *   <li><b>矿石比重</b> {@link #oreWeights}：本实例内各矿石的生成比重覆盖（沿用原比重逻辑，
 *       概率 = 比重 / 池内比重之和；未覆盖的矿石回退到全局默认 {@link VeinOreRegistry#getOreWeight}）；</li>
 *   <li><b>矿石 / 岩石池</b> {@link #normalOres}/{@link #rocks}：沿用原
 *       {@code vein_ores/*.json} 的 normal / rock 内容；所有矿石（含原 rare）统一进 normal 池，
 *       稀有度由比重（{@link #oreWeights}/{@link VeinOreRegistry#getOreWeight}）决定；</li>
 *   <li><b>维度绑定</b> {@link #dimensions}：本实例作用于哪些维度 id（可绑定多个 mod 维度）。</li>
 * </ol>
 *
 * <p><b>全部可用 KubeJS 编辑</b>（startup 脚本），链式调用示例：
 * <pre>
 *   // kubejs/startup_scripts/vein_profiles.js
 *   // 1) 新建一个实例（或取已有的）
 *   let p = VeinProfiles.getOrCreate("my_dim");
 *   // 2) 生成 / 编辑矿石比重（沿用之前的比重逻辑）
     *   p.addNormalOre("minecraft:iron_ore")
     *    .addNormalOre("minecraft:diamond_ore")
     *    .addRock("minecraft:stone")
 *    .setOreWeight("minecraft:iron_ore", 1.0)
 *    .setOreWeight("minecraft:diamond_ore", 0.09);
 *   // 3) 把该逻辑绑定到维度 id / dim
 *   p.category("overworld").bind("mymod:my_dimension");
 * </pre>
 */
public class VeinProfile {

    /** 实例名（唯一 id）。 */
    public final String id;

    /** 本实例绑定的维度 id 集合（作用于哪些维度）。 */
    public final Set<ResourceLocation> dimensions = new LinkedHashSet<>();

    /** 普通矿池（沿用 vein_ores json 的 normal；含旧配置里并入的 rare 矿，稀有度由比重决定）。 */
    public final List<Item> normalOres = new ArrayList<>();
    /** 岩石池（沿用 vein_ores json 的 rock，如石头/深板岩/地狱岩/末地岩）。 */
    public final List<Item> rocks = new ArrayList<>();

    /** 本实例的矿石生成比重覆盖（未覆盖回退全局默认）。 */
    public final Map<ResourceLocation, Double> oreWeights = new LinkedHashMap<>();

    /**
     * 该实例的矿石「类别」，仅用于把生成式矿石列表（{@code #forge:ores}，含全部模组矿石）
     * 按类别归入本实例（避免下界矿石出现在主世界等）。默认 {@link VeinOreRegistry.PoolCategory#OVERWORLD}。
     */
    public VeinOreRegistry.PoolCategory category = VeinOreRegistry.PoolCategory.OVERWORLD;

    /** 是否按「末地」规则处理：为 true 且 {@code Config.veinEndMinerals} 为 false 时，只生成纯岩石矿脉。 */
    public boolean endLike = false;

    public VeinProfile(String id) {
        this.id = id;
    }

    // ==================== 维度绑定 ====================

    public VeinProfile bind(ResourceLocation dim) {
        if (dim != null) dimensions.add(dim);
        return this;
    }

    /** 绑定维度 id / dim（KubeJS 便捷字符串重载）。 */
    public VeinProfile bind(String dim) {
        return bind(ResourceLocation.tryParse(dim));
    }

    public VeinProfile unbind(String dim) {
        ResourceLocation d = ResourceLocation.tryParse(dim);
        if (d != null) dimensions.remove(d);
        return this;
    }

    // ==================== 类别 / 末地开关 ====================

    /** 设置矿石类别（"overworld" / "nether" / "end"），决定生成式矿石按类别归入哪个实例。 */
    public VeinProfile category(String cat) {
        if (cat != null) {
            try {
                this.category = VeinOreRegistry.PoolCategory.valueOf(cat.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 非法类别名忽略，保持原值
            }
        }
        return this;
    }

    public VeinProfile endLike(boolean v) {
        this.endLike = v;
        return this;
    }

    // ==================== 矿石 / 岩石池编辑 ====================

    /** 添加普通矿（自动把掉落物/粗矿 id 归一化为矿石方块 id，沿用原逻辑）。 */
    public VeinProfile addNormalOre(String oreId) {
        Item it = VeinOreRegistry.normalizeOreItem(oreId);
        if (it != null) normalOres.add(it);
        return this;
    }

    /** 添加岩石（不做矿石归一化，因石头/地狱岩/末地岩本身不是矿石）。 */
    public VeinProfile addRock(String rockId) {
        Item it = plainItem(rockId);
        if (it != null) rocks.add(it);
        return this;
    }

    public VeinProfile clearNormal() { normalOres.clear(); return this; }
    public VeinProfile clearRocks()  { rocks.clear();      return this; }

    // ==================== 矿石比重（沿用原逻辑）====================

    /** 设置本实例内某矿石的生成比重（覆盖全局默认）。 */
    public VeinProfile setOreWeight(String oreId, double weight) {
        ResourceLocation d = ResourceLocation.tryParse(oreId);
        if (d != null) oreWeights.put(d, weight);
        return this;
    }

    /** 移除本实例内某矿石的比重覆盖（之后回退全局默认）。 */
    public VeinProfile removeOreWeight(String oreId) {
        ResourceLocation d = ResourceLocation.tryParse(oreId);
        if (d != null) oreWeights.remove(d);
        return this;
    }

    /** 取某矿石在本实例中的比重：本实例覆盖优先，否则回退全局默认逻辑。 */
    public double getOreWeight(Item ore) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(ore);
        if (id != null) {
            Double w = oreWeights.get(id);
            if (w != null) return w;
        }
        return VeinOreRegistry.getOreWeight(ore);
    }

    /**
     * 按本实例比重加权随机取一个矿石：概率 = 该矿石比重 / 池内全部矿石比重之和。
     * 与 {@link VeinOreRegistry#getWeightedRandomOre} 同算法，但比重取自本实例（含覆盖）。
     */
    public Item weightedRandomOre(List<Item> pool, RandomSource r) {
        if (pool == null || pool.isEmpty()) return null;
        double total = 0;
        for (Item it : pool) total += getOreWeight(it);
        if (total <= 0) return pool.get(r.nextInt(pool.size()));
        double x = r.nextDouble() * total;
        for (Item it : pool) {
            x -= getOreWeight(it);
            if (x <= 0) return it;
        }
        return pool.get(pool.size() - 1);
    }

    private static Item plainItem(String id) {
        ResourceLocation d = ResourceLocation.tryParse(id);
        if (d == null) return null;
        Item it = ForgeRegistries.ITEMS.getValue(d);
        return (it == null || it == Items.AIR) ? null : it;
    }
}
