package com.fkmyjc.fkmyjcs_miners.vein;

import com.fkmyjc.fkmyjcs_miners.Config;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「矿脉维度实例」注册表 / 模块入口。把原本分散的三套机制
 * （vein_ores json 矿石池 + 全局矿石比重 + config 维度分组）统一封装成
 * 可复用、可被 KubeJS 编辑的 {@link VeinProfile} 实例集合。
 *
 * <p><b>默认已内置三个实例</b>：{@link #OVERWORLD} / {@link #NETHER} / {@link #END}，
 * 分别绑定原版三维度（绑定来源见 {@code Config.vein.*Dimensions}，可在 config 或 KubeJS 中扩展）。
 *
 * <p><b>模块化流程（方便二创）</b>：
 * <ol>
 *   <li><b>建立实例</b>：{@link #getOrCreate(String)}（默认已有 overworld/nether/end）；</li>
 *   <li><b>生成 / 编辑矿石比重</b>：{@link VeinProfile#addNormalOre}/{@link VeinProfile#setOreWeight}
 *       等（沿用之前的比重逻辑）；</li>
 *   <li><b>绑定维度 id / dim</b>：{@link VeinProfile#bind(String)}。</li>
 * </ol>
 * 以上全部可在 KubeJS startup 脚本中完成，无需重编译。
 *
 * <p><b>刷新策略</b>：数据包 /reload 时只清空并重填各实例的「矿石/岩石池」（内容来自 json），
 * 而「维度绑定」与「矿石比重」会保留（因为它们可能由 KubeJS 设置）。
 */
public final class VeinProfiles {

    /** 内置实例：主世界。 */
    public static final String OVERWORLD = "overworld";
    /** 内置实例：下界。 */
    public static final String NETHER = "nether";
    /** 内置实例：末地。 */
    public static final String END = "end";

    private static final Map<String, VeinProfile> PROFILES = new LinkedHashMap<>();

    private VeinProfiles() {}

    /** 取一个实例；不存在则新建。KubeJS：{@code VeinProfiles.getOrCreate("my_dim")}。 */
    public static VeinProfile getOrCreate(String id) {
        return PROFILES.computeIfAbsent(id, VeinProfile::new);
    }

    /** 取一个实例（不存在返回 null）。 */
    public static VeinProfile get(String id) {
        return PROFILES.get(id);
    }

    /** 全部实例。 */
    public static Collection<VeinProfile> all() {
        return PROFILES.values();
    }

    /** 删除一个实例（KubeJS 可用来移除自建实例）。 */
    public static void remove(String id) {
        PROFILES.remove(id);
    }

    /**
     * 按维度解析实例：返回绑定了该维度的第一个实例；都没绑定则回退主世界实例。
     */
    public static VeinProfile resolve(ResourceLocation dim) {
        for (VeinProfile p : PROFILES.values()) {
            if (p.dimensions.contains(dim)) return p;
        }
        return PROFILES.get(OVERWORLD);
    }

    /**
     * 确保三个默认实例存在并具备默认类别 / 末地标记 / 维度绑定。
     * <p>幂等且「增量」：已存在的实例不会被清空；维度绑定为追加（保留 KubeJS 自定义绑定）。
     */
    public static void ensureDefaults() {
        getOrCreate(OVERWORLD).category = VeinOreRegistry.PoolCategory.OVERWORLD;

        getOrCreate(NETHER).category = VeinOreRegistry.PoolCategory.NETHER;

        VeinProfile end = getOrCreate(END);
        end.category = VeinOreRegistry.PoolCategory.END;
        end.endLike = true;

        applyConfigBindings();
    }

    /** 从 Config 的维度列表应用默认绑定（追加，不清空既有绑定）。 */
    private static void applyConfigBindings() {
        bindAll(OVERWORLD, Config.veinOverworldDims, "minecraft:overworld");
        bindAll(NETHER,    Config.veinNetherDims,    "minecraft:the_nether");
        bindAll(END,       Config.veinEndDims,       "minecraft:the_end");
    }

    private static void bindAll(String profileId, Set<ResourceLocation> dims, String vanillaFallback) {
        VeinProfile p = getOrCreate(profileId);
        if (dims == null || dims.isEmpty()) {
            p.bind(vanillaFallback);
            return;
        }
        for (ResourceLocation d : dims) p.bind(d);
    }
}
