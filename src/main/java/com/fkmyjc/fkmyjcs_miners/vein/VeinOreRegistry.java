package com.fkmyjc.fkmyjcs_miners.vein;

import com.fkmyjc.fkmyjcs_miners.ore.OreListManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.Tags;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 按维度加载「矿石池」：数据驱动（data/&lt;ns&gt;/vein_ores/&lt;任意名&gt;.json），
 * 走数据包重载监听器，因此 KubeJS 用户把修改后的 json 放进
 * <pre>  kubejs/data/&lt;ns&gt;/vein_ores/&lt;任意名&gt;.json</pre>
 * 后执行 <b>/reload</b> 即生效，无需重编译。
 *
 * <p>json 结构（normal 应填写矿石方块 id，如 {@code minecraft:iron_ore}，
 * 若误写为掉落物/粗矿如 {@code minecraft:raw_iron} 会在加载时自动归一化为矿石方块；
 * 旧配置里的 {@code rare} 键仍会被读取并并入 normal 池，rare 机制本身已取消）：
 * <pre>
 * {
 *   "dimension": "minecraft:overworld",
 *   "normal": ["minecraft:coal_ore", "minecraft:iron_ore", ...],
 *   "rock":   ["minecraft:stone", "minecraft:deepslate"]
 * }
 * </pre>
 *
 * <p><b>矿石来源</b>：矿脉的矿石池不再局限于原版。{@link #getOrePool} 会先并入
 * {@link OreListManager}（来自 {@code #forge:ores} 的矿石方块，涵盖全部模组矿石，以
 * {@code block.asItem()} 形式并入），再叠加维度 json 中声明的 normal
 * （用户自定义补充）。这样矿脉会“从生成的矿石列表中寻找”，而不是仅限原版硬编码列表。
 * 池内矿石的稀有度由各自比重（{@link #getOreWeight}）决定，不再有「rare 仅次矿可取」的特殊处理。
 *
 * <p>另见公开静态字段 {@link #VEIN_ORE_DISPLAY_OVERRIDE} 与
 * {@link #registerDisplayOverride(String, String)}：矿脉“展示用物品”的映射（如下界
 * 远古残骸显示成下界合金碎片）预留给 KubeJS 在 startup 脚本中增删，无需改本模组代码。
 *
 * <p>以及矿石「生成比重」表 {@link #ORE_WEIGHT} 与 {@link #registerOreWeight(String, double)}：
 * 矿脉抽矿按比重加权（概率 = 该矿石比重 / 池中全部矿石比重之和），以“铁矿石 = 1”为基准，
 * 未列出的矿石（含模组矿石）默认比重 1，同样预留给 KubeJS 增删。
 */
public enum VeinOreRegistry implements ResourceManagerReloadListener {
    INSTANCE;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 配置版本号，每次重载自增，用于让 {@link VeinManager} 的缓存自动失效。 */
    private static int version = 0;

    /**
     * 矿脉矿石的「展示映射」例外表：某些矿石在矿脉信息中应显示为另一种物品
     * （例如下界矿石 远古残骸 对应 下界合金碎片）。仅影响展示，不改变实际生成的矿石方块。
     *
     * <p><b>预留给 KubeJS 修改</b>：这是一个公开的、可变的映射表。在 KubeJS 的
     * <em>startup</em> 脚本中可直接调用 {@link #registerDisplayOverride(String, String)}
     * 来新增 / 覆盖某矿石的展示物品，无需重编译本模组。例如：
     * <pre>
     *   // kubejs/startup_scripts/vein_overrides.js
     *   VeinOreRegistry.registerDisplayOverride("minecraft:ancient_debris", "minecraft:netherite_scrap");
     * </pre>
     * 内置默认条目见下方 static 初始化块；KubeJS 调用会覆盖同名条目。
     */
    public static final Map<ResourceLocation, ResourceLocation> VEIN_ORE_DISPLAY_OVERRIDE = new LinkedHashMap<>();
    static {
        // 下界特殊例外（内置默认）：远古残骸 → 下界合金碎片
        VEIN_ORE_DISPLAY_OVERRIDE.put(new ResourceLocation("minecraft:ancient_debris"),
                new ResourceLocation("minecraft:netherite_scrap"));
    }

    /** 注册 / 覆盖一条「矿脉矿石 → 展示物品」映射（供 KubeJS startup 脚本调用）。 */
    public static void registerDisplayOverride(ResourceLocation oreId, ResourceLocation displayId) {
        VEIN_ORE_DISPLAY_OVERRIDE.put(oreId, displayId);
    }

    /** 字符串形式的便捷重载（KubeJS 脚本中最易调用）。 */
    public static void registerDisplayOverride(String oreId, String displayId) {
        ResourceLocation a = ResourceLocation.tryParse(oreId);
        ResourceLocation b = ResourceLocation.tryParse(displayId);
        if (a == null || b == null) {
            LOGGER.warn("registerDisplayOverride 参数无效：{} -> {}", oreId, displayId);
            return;
        }
        VEIN_ORE_DISPLAY_OVERRIDE.put(a, b);
    }

    /** 移除一条「矿脉矿石 → 展示物品」映射（KubeJS 可用来取消内置默认条目）。 */
    public static void removeDisplayOverride(String oreId) {
        ResourceLocation a = ResourceLocation.tryParse(oreId);
        if (a != null) VEIN_ORE_DISPLAY_OVERRIDE.remove(a);
    }

    /**
     * 矿石「生成比重」：影响该矿石在矿脉池中被抽中的概率，概率 = 该矿石比重 / 池中全部矿石比重之和。
     *
     * <p><b>默认值与来源</b>：表中未列出的矿石（含全部模组矿石）一律取 {@link #DEFAULT_ORE_WEIGHT}=1，
     * 即「默认所有矿石比重为 1」。下表为<b>检索原版 1.20.1 矿石生成概率</b>后给出的相对比重，
     * 以「铁矿石 = 1」为基准：每个矿石按其<b>每区块生成尝试次数</b>等比换算（铁矿约 11 次/区块）。
     * 数值反映相对稀有度，仅影响矿脉中各类矿石的出现频率，不改变掉落物与储量。
     *
     * <p><b>预留给 KubeJS 修改</b>：用 {@link #registerOreWeight(String, double)} 在 startup 脚本中
     * 增删/覆盖某矿石的比重（如 {@code registerOreWeight("minecraft:diamond_ore", 0.5)}），无需重编译。
     */
    public static final double DEFAULT_ORE_WEIGHT = 0.8;

    public static final Map<ResourceLocation, Double> ORE_WEIGHT = new LinkedHashMap<>();
    static {
        // —— 主世界（每区块生成尝试次数 → 以铁矿 11 次为 1.0 归一化）——
        //   coal 30 / iron 11 / copper 16 / gold 4 / redstone 8 / lapis 2 / diamond 1 / emerald 4
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "coal_ore"),    3.0);   // 30/11 ≈ 2.73
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "copper_ore"),  2.0);   // 16/11 ≈ 1.45
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "iron_ore"),    1.0);   // 基准
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "redstone_ore"), 1.0); // 8/11  ≈ 0.73
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "gold_ore"),     0.6); // 4/11  ≈ 0.36
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "emerald_ore"),  0.4); // 4/11  ≈ 0.36（仅山地群系）
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "lapis_ore"),    1.0); // 2/11  ≈ 0.18
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "diamond_ore"),   0.4); // 1/11  ≈ 0.09

        // —— 下界（同以铁矿 11 次为基准）——
        //   nether_quartz ~32 / nether_gold ~20 / ancient_debris ~2（低比重，自然稀有）
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "nether_quartz_ore"), 2.9);  // 32/11 ≈ 2.91
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "nether_gold_ore"),   1.8);  // 20/11 ≈ 1.82
        ORE_WEIGHT.put(new ResourceLocation("minecraft", "ancient_debris"),    0.18); // 2/11  ≈ 0.18
    }

    /**
     * 矿石「展示颜色」：用于矿脉信息 GUI / 叠加层 / 聊天结果按矿上色。
     *
     * <p><b>来源</b>：检索 1.20.1 常见科技 mod（Mekanism / Thermal / 沉浸工程 / 机械动力 / AE2 /
     * 逐星之旅 / Ender IO / 更大的反应堆等）矿石的标准材质色（详见
     * {@code .workbuddy/memory/ore_color_preset_2026-07-12.md}），以原版
     * {@code material_*} 官方材质色与金属/宝石标准材质色为基准填表；共享金属（铜/锡/铅/银/镍/铀等）
     * 跨 mod 统一颜色，避免同矿异色。
     *
     * <p><b>查找规则</b>（与 {@link #getOreWeight(Item)} 同构）：先按完整 id 查 → 去掉
     * {@code deepslate_}/{@code nether_} 前缀按基础矿名查 → 仍无则 {@link #hashColor(String)} 用
     * id 字符串哈希确定性生成（同矿永远同色，异矿色相分散）。
     *
     * <p><b>预设颜色均为 0xFFrrggbb（alpha=0xFF）</b>，可直接作为
     * {@code GuiGraphics.drawString}/{@code fill} 的颜色参数。
     *
     * <p><b>预留给 KubeJS 修改</b>：用 {@link #registerOreColor(String, int)} 在 startup 脚本中
     * 增删/覆盖某矿石颜色（int 为 0xRRGGBB 或带 0xFF 前缀皆可），无需重编译。
     */
    public static final int DEFAULT_ORE_COLOR = 0xFFAAAAAA;

    public static final Map<ResourceLocation, Integer> ORE_COLOR = new LinkedHashMap<>();
    static {
        // —— 原版（material_* 官方材质色 / 矿石方块色）——
        ORE_COLOR.put(rl("minecraft", "coal_ore"),            0xFF333333);
        ORE_COLOR.put(rl("minecraft", "iron_ore"),            0xFFCECACA);
        ORE_COLOR.put(rl("minecraft", "copper_ore"),          0xFFB4684D);
        ORE_COLOR.put(rl("minecraft", "gold_ore"),            0xFFDEB12D);
        ORE_COLOR.put(rl("minecraft", "redstone_ore"),        0xFF971607);
        ORE_COLOR.put(rl("minecraft", "lapis_ore"),           0xFF21497B);
        ORE_COLOR.put(rl("minecraft", "diamond_ore"),         0xFF2CBAA8);
        ORE_COLOR.put(rl("minecraft", "emerald_ore"),         0xFF11A036);
        ORE_COLOR.put(rl("minecraft", "nether_quartz_ore"),   0xFFE3D4D1);
        ORE_COLOR.put(rl("minecraft", "ancient_debris"),      0xFF443A3B);
        ORE_COLOR.put(rl("minecraft", "amethyst_cluster"),    0xFF9A5CC6);

        // —— 原版岩石（用于矿脉 GUI / 叠加层 / 聊天结果按岩石上色）——
        ORE_COLOR.put(rl("minecraft", "stone"),               0xFF8B8B8B);
        ORE_COLOR.put(rl("minecraft", "deepslate"),           0xFF373745);
        ORE_COLOR.put(rl("minecraft", "netherrack"),          0xFF8B3A3A);
        ORE_COLOR.put(rl("minecraft", "end_stone"),           0xFFF2F0C6);
        ORE_COLOR.put(rl("minecraft", "andesite"),            0xFF888889);
        ORE_COLOR.put(rl("minecraft", "diorite"),            0xFFF0F0F0);
        ORE_COLOR.put(rl("minecraft", "granite"),             0xFF926456);
        ORE_COLOR.put(rl("minecraft", "tuff"),                0xFF656558);
        ORE_COLOR.put(rl("minecraft", "basalt"),              0xFF4A4A4A);
        ORE_COLOR.put(rl("minecraft", "blackstone"),          0xFF2E2E2E);
        ORE_COLOR.put(rl("minecraft", "crimson_nylium"),    0xFF6B0E0E);
        ORE_COLOR.put(rl("minecraft", "warped_nylium"),     0xFF167E86);
        // —— Mekanism（mekanism:）——
        ORE_COLOR.put(rl("mekanism", "osmium_ore"),   0xFF6FA8C7);
        ORE_COLOR.put(rl("mekanism", "tin_ore"),      0xFFC5C9CA);
        ORE_COLOR.put(rl("mekanism", "lead_ore"),     0xFF45505A);
        ORE_COLOR.put(rl("mekanism", "uranium_ore"),  0xFFB0E000);
        ORE_COLOR.put(rl("mekanism", "fluorite_ore"), 0xFFF26DA4);

        // —— Thermal（thermal:）——
        ORE_COLOR.put(rl("thermal", "copper_ore"),    0xFFB4684D);
        ORE_COLOR.put(rl("thermal", "tin_ore"),       0xFFC5C9CA);
        ORE_COLOR.put(rl("thermal", "lead_ore"),      0xFF45505A);
        ORE_COLOR.put(rl("thermal", "silver_ore"),    0xFFD9D9D9);
        ORE_COLOR.put(rl("thermal", "nickel_ore"),    0xFFC8C8C8);
        ORE_COLOR.put(rl("thermal", "ruby_ore"),      0xFFE0115F);
        ORE_COLOR.put(rl("thermal", "sapphire_ore"),  0xFF1E5BB8);
        ORE_COLOR.put(rl("thermal", "apatite_ore"),   0xFF3BB9C4);
        ORE_COLOR.put(rl("thermal", "cinnabar_ore"),  0xFF9B2D1F);
        ORE_COLOR.put(rl("thermal", "niter_ore"),     0xFFE0E0E0);
        ORE_COLOR.put(rl("thermal", "sulfur_ore"),    0xFFE1C10B);

        // —— Immersive Engineering（immersiveengineering:）⚠️ 部分 id 待按 jar 核对 ——
        ORE_COLOR.put(rl("immersiveengineering", "ore_copper"),  0xFFB4684D);
        ORE_COLOR.put(rl("immersiveengineering", "ore_lead"),    0xFF45505A);
        ORE_COLOR.put(rl("immersiveengineering", "ore_nickel"),  0xFFC8C8C8);
        ORE_COLOR.put(rl("immersiveengineering", "ore_silver"),  0xFFD9D9D9);
        ORE_COLOR.put(rl("immersiveengineering", "ore_uranium"), 0xFFB0E000);
        ORE_COLOR.put(rl("immersiveengineering", "ore_aluminum"),0xFFA66A3D);

        // —— Create（create:）——
        ORE_COLOR.put(rl("create", "zinc_ore"), 0xFF7A8B99);

        // —— AE2（ae2:）——
        ORE_COLOR.put(rl("ae2", "certus_quartz_ore"),        0xFF3FC9D6);
        ORE_COLOR.put(rl("ae2", "charged_certus_quartz_ore"),0xFF6FE9E9);

        // —— Ad Astra（ad_astra:）推断色 ——
        ORE_COLOR.put(rl("ad_astra", "desh_ore"),    0xFFB5462E);
        ORE_COLOR.put(rl("ad_astra", "ostrum_ore"),  0xFF2E8B74);
        ORE_COLOR.put(rl("ad_astra", "calorite_ore"),0xFFE25822);

        // —— Ender IO（enderio:）——
        ORE_COLOR.put(rl("enderio", "electrotine_ore"), 0xFF2EE6D6);
        ORE_COLOR.put(rl("enderio", "graphite_ore"),    0xFF3A3A3A);

        // —— Bigger Reactors（biggerreactors:）⚠️ id 待核对 ——
        ORE_COLOR.put(rl("biggerreactors", "yellorite_ore"), 0xFFE4D00A);
    }

    private static ResourceLocation rl(String ns, String path) {
        return new ResourceLocation(ns, path);
    }

    /** 取某矿石（按 Item）的展示颜色；未配置则哈希确定性生成。 */
    public static int getOreColor(Item ore) {
        return getOreColor(ForgeRegistries.ITEMS.getKey(ore));
    }

    /**
     * 取某矿石 id 的展示颜色。查找顺序：完整 id → 去 {@code deepslate_}/{@code nether_} 前缀按基础矿名 → 哈希回退。
     * 返回 0xFFrrggbb（alpha=0xFF），可直接用于绘制。
     */
    public static int getOreColor(@Nullable ResourceLocation id) {
        if (id == null) return DEFAULT_ORE_COLOR;
        Integer c = ORE_COLOR.get(id);
        if (c != null) return c;
        String path = id.getPath();
        for (String p : new String[]{"deepslate_", "nether_"}) {
            if (path.startsWith(p)) {
                Integer c2 = ORE_COLOR.get(new ResourceLocation(id.getNamespace(), path.substring(p.length())));
                if (c2 != null) return c2;
            }
        }
        return hashColor(id.toString());
    }

    /** id 字符串哈希 → 确定性颜色（HSL，固定饱和度/明度，仅色相随矿变化）。同矿永远同色，异矿色相自然分散。 */
    private static int hashColor(String key) {
        int h = key.hashCode();
        float hue = (h & 0xFFFF) / 65536.0f;        // 取低 16 位归一化为 [0,1)
        return Mth.hsvToRgb(hue, 0.6f, 0.55f);       // alpha = 0xFF
    }

    /** 注册 / 覆盖某矿石的展示颜色（供 KubeJS startup 脚本调用）。 */
    public static void registerOreColor(ResourceLocation oreId, int color) {
        ORE_COLOR.put(oreId, color);
    }

    /** 字符串便捷重载（KubeJS 脚本中最易调用）。 */
    public static void registerOreColor(String oreId, int color) {
        ResourceLocation a = ResourceLocation.tryParse(oreId);
        if (a == null) {
            LOGGER.warn("registerOreColor 参数无效：{}", oreId);
            return;
        }
        ORE_COLOR.put(a, color);
    }

    /** 移除某矿石的颜色配置（之后回退为哈希生成）。 */
    public static void removeOreColor(String oreId) {
        ResourceLocation a = ResourceLocation.tryParse(oreId);
        if (a != null) ORE_COLOR.remove(a);
    }

    /** 取某矿石的生成比重；未显式配置的矿石（含全部模组矿石）返回 {@link #DEFAULT_ORE_WEIGHT}=1。 */
    public static double getOreWeight(Item ore) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(ore);
        if (id == null) return DEFAULT_ORE_WEIGHT;
        Double w = ORE_WEIGHT.get(id);
        if (w != null) return w;
        // 深层/下界变种按“矿物名”复用基础矿石的比重（如 deepslate_iron_ore 同 iron_ore）
        String path = id.getPath();
        for (String p : new String[]{"deepslate_", "nether_"}) {
            if (path.startsWith(p)) {
                Double w2 = ORE_WEIGHT.get(new ResourceLocation(id.getNamespace(), path.substring(p.length())));
                if (w2 != null) return w2;
            }
        }
        return DEFAULT_ORE_WEIGHT;
    }

    /** 注册 / 覆盖某矿石的生成比重（供 KubeJS startup 脚本调用）。 */
    public static void registerOreWeight(ResourceLocation oreId, double weight) {
        ORE_WEIGHT.put(oreId, weight);
    }

    /** 字符串形式的便捷重载（KubeJS 脚本中最易调用）。 */
    public static void registerOreWeight(String oreId, double weight) {
        ResourceLocation a = ResourceLocation.tryParse(oreId);
        if (a == null) {
            LOGGER.warn("registerOreWeight 参数无效：{}", oreId);
            return;
        }
        ORE_WEIGHT.put(a, weight);
    }

    /** 移除某矿石的比重配置（之后回退为默认 1）。 */
    public static void removeOreWeight(String oreId) {
        ResourceLocation a = ResourceLocation.tryParse(oreId);
        if (a != null) ORE_WEIGHT.remove(a);
    }

    /** 矿石 → 矿池类别（依据方块 id 路径启发式；未知矿石归为主世界组）。
     * 用于把生成式矿石列表（{@code #forge:ores}，含全部模组矿石）按类别归入对应 {@link VeinProfile}。 */
    static PoolCategory categoryOfOre(Item ore) {
        Block block = Block.byItem(ore);
        if (block == null || block == Blocks.AIR) return PoolCategory.OVERWORLD;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        if (id == null) return PoolCategory.OVERWORLD;
        String path = id.getPath();
        if (path.startsWith("nether_") || path.contains("_nether_") || path.equals("ancient_debris")) {
            return PoolCategory.NETHER;
        }
        if (path.startsWith("end_") || path.contains("_end_")) {
            return PoolCategory.END;
        }
        return PoolCategory.OVERWORLD;
    }

    /** 矿池类别：用于生成式矿石列表按类别归入 {@link VeinProfile}。 */
    public enum PoolCategory { OVERWORLD, NETHER, END }

    /**
     * 按「生成比重」加权随机取一个矿石：概率 = 该矿石比重 / 池中全部矿石比重之和。
     * 主矿应传入（不含稀有的）普通池；次矿可传入剔除主矿后的全池。
     */
    public static Item getWeightedRandomOre(List<Item> pool, RandomSource r) {
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

    /** 矿脉矿石的展示物品：命中例外表则返回对应物品，否则原样返回。 */
    public static Item getVeinDisplayItem(Item ore) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(ore);
        if (id != null) {
            ResourceLocation override = VEIN_ORE_DISPLAY_OVERRIDE.get(id);
            if (override != null) {
                Item it = ForgeRegistries.ITEMS.getValue(override);
                if (it != null && it != Items.AIR) return it;
            }
        }
        return ore;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        // 1) 确保默认实例存在（overworld/nether/end）并应用 config 维度绑定（增量，保留 KubeJS 自定义）
        VeinProfiles.ensureDefaults();

        // 2) 只清空各实例的「矿石/岩石池」（内容来自 json）；维度绑定与矿石比重保留
        for (VeinProfile p : VeinProfiles.all()) {
            p.normalOres.clear();
            p.rocks.clear();
        }

        // 3) 逐个 json 按其 dimension 解析到对应实例并填充池（沿用原 json 内容）
        for (ResourceLocation id : resourceManager.listResources("vein_ores",
                rl -> rl.getPath().endsWith(".json")).keySet()) {
            resourceManager.getResource(id).ifPresent(res -> {
                try (InputStreamReader reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

                    ResourceLocation dim = ResourceLocation.tryParse(
                            json.has("dimension") ? json.get("dimension").getAsString() : "");
                    if (dim == null) {
                        LOGGER.warn("vein_ores {} 缺少或无效的 'dimension' 字段，已跳过", id);
                        return;
                    }

                    VeinProfile profile = VeinProfiles.resolve(dim);
                    if (profile == null) {
                        LOGGER.warn("vein_ores {}: 维度 {} 未绑定任何实例且无默认实例，已跳过", id, dim);
                        return;
                    }

                    readList(json, "normal", profile.normalOres);
                    // 兼容旧配置：rare 矿直接并入 normal 池（rare 机制已取消，稀有度由比重决定）
                    readList(json, "rare", profile.normalOres);
                    readRockList(json, "rock", profile.rocks);
                    LOGGER.info("已把维度 {} 的矿脉矿石池填入实例 [{}]：矿石 {} / 岩石 {}",
                            dim, profile.id, profile.normalOres.size(), profile.rocks.size());
                } catch (Exception e) {
                    LOGGER.error("加载 vein_ores 失败 {}", id, e);
                }
            });
        }

        version++;
        LOGGER.info("矿脉矿石池重载完成：{} 个实例，版本 {}", VeinProfiles.all().size(), version);
    }

    private void readList(JsonObject json, String key, List<Item> out) {
        if (!json.has(key)) return;
        JsonArray arr = json.getAsJsonArray(key);
        for (int i = 0; i < arr.size(); i++) {
            ResourceLocation loc = ResourceLocation.tryParse(arr.get(i).getAsString());
            if (loc == null) {
                LOGGER.warn("vein_ores: 无法解析物品 {}", arr.get(i).getAsString());
                continue;
            }
            Item rawItem = ForgeRegistries.ITEMS.getValue(loc);
            if (rawItem == null || rawItem == Items.AIR) {
                LOGGER.warn("vein_ores: 未知物品 {}", loc);
                continue;
            }

            // 将可能的掉落物/粗矿 id 归一化为矿石方块 id，避免配置里写 redstone 却显示红石粉
            ResourceLocation oreLoc = normalizeToOreBlockId(loc, rawItem);
            if (oreLoc == null) {
                LOGGER.warn("vein_ores: {} 无法归一化为矿石方块，已跳过", loc);
                continue;
            }
            Item oreItem = ForgeRegistries.ITEMS.getValue(oreLoc);
            if (oreItem == null || oreItem == Items.AIR) {
                LOGGER.warn("vein_ores: 归一化后的矿石 {} 不存在，已跳过", oreLoc);
                continue;
            }
            out.add(oreItem);
        }
    }

    /** 读取岩石列表（不执行矿石归一化，因为石头/深板岩/地狱岩/末地岩本身不是矿石）。 */
    private void readRockList(JsonObject json, String key, List<Item> out) {
        if (!json.has(key)) return;
        JsonArray arr = json.getAsJsonArray(key);
        for (int i = 0; i < arr.size(); i++) {
            ResourceLocation loc = ResourceLocation.tryParse(arr.get(i).getAsString());
            if (loc == null) {
                LOGGER.warn("vein_ores: 无法解析岩石 {}", arr.get(i).getAsString());
                continue;
            }
            Item item = ForgeRegistries.ITEMS.getValue(loc);
            if (item == null || item == Items.AIR) {
                LOGGER.warn("vein_ores: 未知岩石 {}", loc);
                continue;
            }
            // 确保确实对应一个方块（防止写错成物品 id）
            if (Block.byItem(item) == Blocks.AIR) {
                LOGGER.warn("vein_ores: {} 不是有效的方块物品，已跳过", loc);
                continue;
            }
            out.add(item);
        }
    }

    /**
     * 把配置中写错的“掉落物/粗矿” id 归一化为对应的矿石方块 id。
     * <p>优先判断该 item 是否已经是 #forge:ores 中的方块；若不是，则按命名约定推导：
     * 去掉 {@code raw_} 前缀与 {@code _nugget/_gem/_ingot/_dust/_lazuli} 等后缀，
     * 尝试 {@code <mineral>_ore} / {@code deepslate_<mineral>_ore} / {@code nether_<mineral>_ore}。
     * 返回推导后且确实在 #forge:ores 中的方块 id；无法归一化时返回 null。
     */
    @Nullable
    private static ResourceLocation normalizeToOreBlockId(ResourceLocation itemId, Item item) {
        // 1) 本身已是矿石方块，直接返回方块 id。
        //    判定放宽：只要「原 item id 对应的方块存在，且方块注册 id 与原 id 完全一致」即采用，
        //    兼容 ancient_debris 这类未被打上 #forge:ores 标签、但其 id 本身就是方块 id 的矿石。
        //    （原逻辑要求 is(Tags.Blocks.ORES)，而 ancient_debris 不在 forge:ores 标签中，会被错误丢弃。）
        Block directBlock = Block.byItem(item);
        if (directBlock != null && directBlock != Blocks.AIR) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(directBlock);
            if (blockId != null && (isOreBlock(directBlock) || blockId.equals(itemId))) {
                return blockId;
            }
        }

        // 2) 从 item id 推导矿物名
        String path = itemId.getPath();
        String ns = itemId.getNamespace();
        String mineral = path;
        if (mineral.startsWith("raw_")) {
            mineral = mineral.substring("raw_".length());
        }
        for (String suffix : new String[]{"_nugget", "_gem", "_ingot", "_dust", "_lazuli"}) {
            if (mineral.endsWith(suffix)) {
                mineral = mineral.substring(0, mineral.length() - suffix.length());
                break;
            }
        }
        if (mineral.isEmpty()) return null;

        // 3) 按命名约定尝试候选矿石方块，优先原命名空间，再回退 minecraft
        String[] prefixes = {"", "deepslate_", "nether_"};
        String[] namespaces = {ns, "minecraft"};
        for (String candidateNs : namespaces) {
            for (String prefix : prefixes) {
                ResourceLocation candidate = new ResourceLocation(candidateNs, prefix + mineral + "_ore");
                if (isExistingOreBlock(candidate)) return candidate;
            }
        }
        return null;
    }

    private static boolean isOreBlock(Block block) {
        return block.defaultBlockState().is(Tags.Blocks.ORES);
    }

    private static boolean isExistingOreBlock(ResourceLocation id) {
        if (!ForgeRegistries.BLOCKS.containsKey(id)) return false;
        Block block = ForgeRegistries.BLOCKS.getValue(id);
        return block != null && block != Blocks.AIR && isOreBlock(block);
    }

    public static int getVersion() {
        return version;
    }

    /**
     * 取某维度可用的“全部矿石”池（委托给该维度解析到的 {@link VeinProfile} 实例）：
     * <ol>
     *   <li>先并入<b>生成式矿石列表</b>（{@link OreListManager} 的 {@code #forge:ores} 方块，
     *       含全部原版与模组矿石），按实例的 {@link VeinProfile#category 类别} 过滤，
     *       避免下界矿石出现在主世界等；</li>
     *   <li>再并入实例的 normal 矿石池（沿用 vein_ores json 内容；旧配置里的 rare 矿在加载时已并入此池）。</li>
     * </ol>
     * 池内矿石的稀有度由各自比重（{@link VeinProfile#getOreWeight}）决定，
     * 不再有「rare 仅次矿可取」的特殊处理。返回已去重的列表。
     */
    public static List<Item> getOrePool(ResourceLocation dim) {
        VeinProfile p = VeinProfiles.resolve(dim);
        List<Item> pool = new ArrayList<>();
        if (p == null) return pool;

        // 1) 生成式矿石列表：含全部 mod 矿石，按实例类别过滤
        for (Block blk : OreListManager.getOres()) {
            Item it = blk.asItem();
            if (it != null && it != Items.AIR && categoryOfOre(it) == p.category) pool.add(it);
        }

        // 2) 实例自定义 normal 矿石池（沿用 json 内容，含旧 rare 矿）
        pool.addAll(p.normalOres);

        return pool.stream().distinct().collect(Collectors.toList());
    }

    /** 随机（均匀）取一个矿石，无则返回 null。稀有度应交给 {@link #getWeightedRandomOre} 按比重决定。 */
    @Nullable
    public static Item getRandomOre(ResourceLocation dim, RandomSource r) {
        List<Item> pool = getOrePool(dim);
        if (pool.isEmpty()) return null;
        return pool.get(r.nextInt(pool.size()));
    }

    /** 随机取一种岩石（委托给该维度解析到的实例）；未配置时回退为石头。 */
    public static Item getRandomRock(ResourceLocation dim, RandomSource r) {
        VeinProfile p = VeinProfiles.resolve(dim);
        if (p == null || p.rocks.isEmpty()) return Items.STONE;
        return p.rocks.get(r.nextInt(p.rocks.size()));
    }

    /**
     * 把一个可能是掉落物/粗矿的 id 归一化为对应的矿石方块物品；无法归一化返回 null。
     * 供 {@link VeinProfile#addNormalOre}（KubeJS）复用原有归一化逻辑。
     */
    @Nullable
    public static Item normalizeOreItem(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) return null;
        Item raw = ForgeRegistries.ITEMS.getValue(loc);
        if (raw == null || raw == Items.AIR) return null;
        ResourceLocation oreLoc = normalizeToOreBlockId(loc, raw);
        if (oreLoc == null) return null;
        Item ore = ForgeRegistries.ITEMS.getValue(oreLoc);
        return (ore == null || ore == Items.AIR) ? null : ore;
    }
}
