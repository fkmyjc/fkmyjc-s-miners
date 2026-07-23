package com.fkmyjc.fkmyjcs_miners.ore;

import com.fkmyjc.fkmyjcs_miners.Fkmyjcs_miners;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITagManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 时运（挖掘掉落物）矿石列表管理器。
 *
 * <p><b>核心逻辑（与 ore_list 一一对应，且不使用任何硬编码「矿→物」对照表）</b>：遍历
 * {@link OreListManager} 给出的每一个矿石方块，<b>由矿石 id 推导 + 物品注册表存在性校验</b>反查其
 * 「时运应作用的目标物品（掉落物）」：
 * <ol>
 *   <li>由矿石 id 拆解出矿物名 {@code <name>}（去 {@code deepslate_} / {@code nether_} 前缀与 {@code _ore} 后缀）；</li>
 *   <li>按候选 id 顺序在<b>物品注册表</b>中筛查第一个真实存在的物品：
 *       {@code <ns>:raw_<name>} → {@code <ns>:<name>} → {@code <ns>:<name>_gem}
 *       → {@code <ns>:<name>_ingot} → {@code <ns>:<name>_nugget}
 *       （并兼容 {@code minecraft:} 命名空间）；</li>
 *   <li>标签候选（forge 动态标签，重载后可用）：{@code #forge:raw_materials/<name>} → {@code #forge:gems/<name>}
 *       → {@code #forge:ingots/<name>} → {@code #forge:nuggets/<name>} → {@code #forge:dusts/<name>}
 *       ，用于辅助发现 mod 矿石；</li>
 *   <li>特例规则（按 id 筛查）：{@code nether_gold_ore} → {@code minecraft:gold_nugget}（用户指定「下界金矿出金粒」）；</li>
 *   <li>兜底：矿石自身的 {@code asItem()}，保证「其它 mod 的矿石」永远不被漏掉。</li>
 * </ol>
 *
 * <p><b>产物（共 3 个文件，连同 OreListManager 的 ore_list.json）</b>：
 * <ul>
 *   <li>{@code fortune_mining_list.json} —— 去重排序后的「掉落物 id 列表」；</li>
 *   <li>{@code fortune_mining_map.json}   —— 「原矿(方块) → 掉落物(物品)」对应表；</li>
 *   <li>{@code ore_list.json}             —— 由 OreListManager 生成，原矿方块列表。</li>
 * </ul>
 *
 * <p><b>触发时机</b>：实例启动完成（{@code FMLLoadComplete}，此时 forge 标签已就绪，满足“启动时生成”）；
 * 另外注册为数据包重载监听器，进入世界 / {@code /reload} 时若标签更完整会再次覆盖生成，
 * 确保不依赖脆弱的标签就绪时机。
 */
public final class FortuneMiningListManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path SAVE_DIR = FMLPaths.CONFIGDIR.get().resolve(Fkmyjcs_miners.MODID);
    private static final Path LIST_PATH = SAVE_DIR.resolve("fortune_mining_list.json");
    private static final Path MAP_PATH = SAVE_DIR.resolve("fortune_mining_map.json");

    /**
     * 矿石 → 时运掉落物的标签候选顺序。
     * 第一优先是「粗矿」(raw_materials，铁/铜/金等原版与多数 mod 的标准掉落)，
     * 其次宝石(gems，钻石/绿宝石/青金石/石英)，再是锭/粒/粉用于扩展兼容更多 mod。
     */
    private static final List<String> PRODUCT_TAG_PREFIXES = List.of(
            "raw_materials", "gems", "ingots", "nuggets", "dusts"
    );

    /**
     * <b>不使用任何硬编码的「矿→物」对应表。</b>原矿 → 掉落物的对应完全由矿石 id 推导 +
     * 物品注册表存在性校验得到（见 {@link #resolveDrop}）：由 id 拆解矿物名 {@code <name>}
     * 后，按 {@code raw_<name> / <name> / <name>_gem / <name>_ingot / <name>_nugget} 等候选 id
     * 在<b>物品注册表</b>中筛查第一个真实存在的物品。物品注册表在 {@code FMLLoadComplete} 已就绪，
     * 因此既规避了 {@code forge:*} 动态标签尚未加载的时序坑，也无需随游戏版本手写对照表。
     * （仅下界金矿这一例外由用户明确指定「出金粒」，以 id 规则单独处理。）
     */


    /** 掉落物查询（去重后的物品列表）。 */
    private static volatile List<Item> fortuneItems = List.of();
    /** 掉落物 id 列表（去重、排序）。 */
    private static volatile List<ResourceLocation> fortuneItemIds = List.of();
    /**
     * 原矿(方块) → 掉落物(物品) 对应表，<b>支持 1 对多</b>：每个矿石可对应多个产出，
     * 每个产出带数量（如 铜矿石 → 2 个粗铜）。自动推导得到的为单条（count=1），
     * 可通过 {@link #registerDropOverride} 手动覆盖为 1 对多。
     */
    private static volatile Map<ResourceLocation, List<OutputEntry>> fortuneMap = Map.of();
    /** 手动覆盖表：矿石 → 多个产出（含数量）。优先级高于自动推导。 */
    private static final Map<ResourceLocation, List<OutputEntry>> OVERRIDE_DROPS = new LinkedHashMap<>();
    private static volatile boolean initialized = false;

    private FortuneMiningListManager() {}

    /* ========================= 初始化（启动 + 重载两次触发） ========================= */

    /** 实例启动完成时调用（FMLLoadComplete）。 */
    public static void init() {
        if (initialized) {
            LOGGER.debug("FortuneMiningListManager already initialized, skipping.");
            return;
        }
        registerDefaultOverrides();
        var oreIds = OreListManager.getOreIds();
        if (oreIds.isEmpty()) {
            LOGGER.error("[Fortune] OreListManager.getOreIds() is EMPTY! Check OreListManager.init() timing.");
            return;
        }
        generateAndSave(oreIds);
        initialized = true;
    }

    /** 数据包重载（进入世界 / /reload）时再次生成，确保标签完整后结果正确。 */
    public static void onResourceManagerReload(ResourceManager resourceManager) {
        var oreIds = OreListManager.getOreIds();
        if (oreIds.isEmpty()) {
            LOGGER.warn("[Fortune] Reload: OreListManager not ready yet, skip regenerating fortune list.");
            return;
        }
        LOGGER.info("[Fortune] Reload: regenerating fortune mining files ({} ores).", oreIds.size());
        generateAndSave(oreIds);
    }

    public static List<Item> getFortuneItems() {
        return fortuneItems;
    }

    public static List<ResourceLocation> getFortuneItemIds() {
        return fortuneItemIds;
    }

    /** 原矿 → 多产出(含数量) 对应表（1 对多）。 */
    public static Map<ResourceLocation, List<OutputEntry>> getFortuneMap() {
        return fortuneMap;
    }

    /** 取某矿石的全部产出（含数量）；无记录返回空列表。 */
    public static List<OutputEntry> getOutputs(ResourceLocation oreId) {
        return fortuneMap.getOrDefault(oreId, List.of());
    }

    /**
     * 注册「原矿 → 产出」的手动覆盖（支持 1 对多、带数量）。
     * 覆盖项的优先级高于自动 id 推导；同一矿石多次调用会追加。
     * 示例：{@code registerDropOverride("minecraft:copper_ore", "minecraft:raw_copper", 2)}
     * 表示 1 个铜矿石产出 2 个粗铜。
     */
    public static void registerDropOverride(String oreId, String itemId, int count) {
        ResourceLocation o = ResourceLocation.tryParse(oreId);
        ResourceLocation it = ResourceLocation.tryParse(itemId);
        if (o == null || it == null || count <= 0) {
            LOGGER.warn("registerDropOverride 参数无效：{} -> {} x{}", oreId, itemId, count);
            return;
        }
        OVERRIDE_DROPS.computeIfAbsent(o, k -> new ArrayList<>()).add(new OutputEntry(it, count));
    }

    /** 便捷重载：count 默认为 1（单数量，兼容 1 对 1 写法）。 */
    public static void registerDropOverride(String oreId, String itemId) {
        registerDropOverride(oreId, itemId, 1);
    }

    /**
     * 注册默认掉落覆盖（预设矿物表）。
     * <ul>
     *   <li>通用机械 (Mekanism) 已加载时：盐块 → 4 个盐；</li>
     *   <li>应用能源 (AE2) 已加载时：赛特斯石英块 → 4 个赛特斯石英水晶。</li>
     * </ul>
     * 说明：盐块/赛特斯石英块已通过 {@code OreListManager} 的额外矿石集合（与 {@code ancient_debris} 同源）
     * 纳入矿石池，会自然流入矿脉 map 与另外两个表；此处仅用 {@code OVERRIDE_DROPS} 把其掉落数量定为 4
     * （自动 id 推导只能得到 1，故需显式覆盖）。铜矿石等普通矿石保持 1→1（原逻辑，不额外覆盖）。
     * 注册前会校验方块与物品真实存在，任一缺失仅打 WARN 日志、不抛异常。
     */
    private static void registerDefaultOverrides() {
        // 通用机械 (Mekanism)：盐块 → 4 个盐（仅当 mod 已加载且条目真实存在）
        if (ModList.get().isLoaded("mekanism")) {
            registerPreset("mekanism:block_salt", "mekanism:salt", 4);
        }
        // 应用能源 (AE2)：赛特斯石英块 → 4 个赛特斯石英水晶（仅当 mod 已加载且条目真实存在）
        if (ModList.get().isLoaded("ae2")) {
            registerPreset("ae2:quartz_block", "ae2:certus_quartz_crystal", 4);
        }
    }

    /**
     * 注册一个「掉落预设」：仅在方块与物品均真实存在时才写入 {@code OVERRIDE_DROPS}，
     * 避免指向不存在的注册名；任一缺失仅打 WARN 日志、不抛异常、不阻断整体生成。
     */
    private static void registerPreset(String oreId, String itemId, int count) {
        ResourceLocation o = ResourceLocation.tryParse(oreId);
        ResourceLocation it = ResourceLocation.tryParse(itemId);
        if (o == null || it == null) {
            LOGGER.warn("registerPreset 参数无效：{} -> {} x{}", oreId, itemId, count);
            return;
        }
        Block b = ForgeRegistries.BLOCKS.getValue(o);
        if (b == null || b == Blocks.AIR || !itemExists(it)) {
            LOGGER.warn("[Fortune] 预设未注册：条目不存在（block={}, item={}）", o, it);
            return;
        }
        registerDropOverride(oreId, itemId, count);
    }

    /** 单个产出条目：物品 id + 数量。 */
    public static final class OutputEntry {
        public final ResourceLocation item;
        public final int count;
        public OutputEntry(ResourceLocation item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    /* ========================= 文件 IO ========================= */

    private static void generateAndSave(List<ResourceLocation> oreIds) {
        // 1) 建立 原矿(block) → 掉落物(item) 的对应表（支持 1 对多，value 为产出列表）
        LinkedHashMap<ResourceLocation, List<OutputEntry>> mapping = buildFortuneMapping(oreIds);

        // 2) 掉落物列表（去重、排序：原版在前，其余按字符串）
        List<ResourceLocation> drops = mapping.values().stream()
                .flatMap(List::stream)
                .map(e -> e.item)
                .distinct()
                .sorted(Comparator
                        .<ResourceLocation>comparingInt(id -> "minecraft".equals(id.getNamespace()) ? 0 : 1)
                        .thenComparing(ResourceLocation::toString))
                .collect(Collectors.toList());

        List<Item> dropItems = drops.stream()
                .map(ForgeRegistries.ITEMS::getValue)
                .filter(Objects::nonNull)
                .filter(it -> it != Items.AIR)
                .toList();

        fortuneMap = Collections.unmodifiableMap(mapping);
        fortuneItemIds = Collections.unmodifiableList(drops);
        fortuneItems = Collections.unmodifiableList(dropItems);

        // 3) 落盘：列表 + 对应表
        try {
            Files.createDirectories(SAVE_DIR);
            try (Writer w = Files.newBufferedWriter(LIST_PATH)) {
                GSON.toJson(drops.stream().map(ResourceLocation::toString).toList(), w);
            }
            try (Writer w = Files.newBufferedWriter(MAP_PATH)) {
                Map<String, List<Map<String, Object>>> jsonMap = mapping.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().toString(),
                                e -> e.getValue().stream()
                                        .map(en -> {
                                            Map<String, Object> m = new LinkedHashMap<>();
                                            m.put("item", en.item.toString());
                                            m.put("count", en.count);
                                            return m;
                                        })
                                        .collect(Collectors.toList()),
                                (a, b) -> a,
                                LinkedHashMap::new));
                GSON.toJson(jsonMap, w);
            }
            LOGGER.info("Generated fortune mining list ({} drops) and map ({} ores) to {}",
                    drops.size(), mapping.size(), SAVE_DIR);
        } catch (IOException e) {
            LOGGER.error("Failed to save fortune mining files to {}", SAVE_DIR, e);
        }
    }

    /* ========================= 核心：原矿 → 掉落物 ========================= */

    private static LinkedHashMap<ResourceLocation, List<OutputEntry>> buildFortuneMapping(List<ResourceLocation> oreIds) {
        LinkedHashMap<ResourceLocation, List<OutputEntry>> map = new LinkedHashMap<>();
        // 统一管线：oreIds 即矿石池（含 OreListManager 的额外矿石集合，默认 ancient_debris，
        // 以及 mod 加载时的盐块/赛特斯石英块），它们会自然流入此处生成矿脉 map、掉落列表并落盘。
        for (ResourceLocation oreId : oreIds) {
            Block ore = ForgeRegistries.BLOCKS.getValue(oreId);
            if (ore == null || ore == Blocks.AIR) continue;

            // 1) 手动覆盖优先（支持 1 对多、带数量；盐块/赛特斯石英块的 1→4 在此生效）
            List<OutputEntry> override = OVERRIDE_DROPS.get(oreId);
            if (override != null && !override.isEmpty()) {
                map.put(oreId, new ArrayList<>(override));
                LOGGER.debug("[Fortune] (override) {} -> {}", oreId, override);
                continue;
            }

            // 2) 自动 id 推导（单条，count=1）—— 普通矿石（含铜矿石）保持 1→1
            ResourceLocation drop = resolveDrop(oreId, ore);
            if (drop != null) {
                map.put(oreId, List.of(new OutputEntry(drop, 1)));
                LOGGER.debug("[Fortune] {} -> {}", oreId, drop);
            } else {
                LOGGER.warn("[Fortune] 无法为矿石 {} 找到时运掉落物，已跳过", oreId);
            }
        }

        return map;
    }

    /**
     * 由矿石 id 「筛查」其应作用时运的目标物品，<b>不依赖任何硬编码对照表</b>，
     * 全部由物品 id 推导 + 物品注册表存在性校验得到：
     * <ol>
     *   <li>特例规则（按 id 筛查）：{@code nether_gold_ore} → {@code minecraft:gold_nugget}
     *       （用户明确指定的「下界金矿出金粒」）；</li>
     *   <li>由 id 拆解矿物名 {@code <name>}（去 deepslate_ / nether_ 前缀与 _ore 后缀），
     *       按候选 id 顺序在<b>物品注册表</b>中筛查第一个【真实存在】的物品：
     *       {@code <ns>:raw_<name>} → {@code <ns>:<name>} → {@code <ns>:<name>_gem}
     *       → {@code <ns>:<name>_ingot} → {@code <ns>:<name>_nugget}
     *       （并同样尝试 {@code minecraft:} 命名空间，兼容跨命名空间的掉落物）；</li>
     *   <li>标签候选顺序（forge 动态标签，重载后可用）：raw_materials → gems → ingots → nuggets → dusts，
     *       用于辅助发现 mod 矿石的真实掉落物；</li>
     *   <li>兜底：矿石自身 asItem()（保证其它 mod 的矿石不被漏掉）。</li>
     * </ol>
     * 注意：第 2 步直接用「物品注册表存在性」判定，规避了 forge 动态标签在 FMLLoadComplete 尚未就绪的时序坑。
     */
    private static ResourceLocation resolveDrop(ResourceLocation oreId, Block ore) {
        String path = oreId.getPath();
        String ns = oreId.getNamespace();

        // 1) 特例规则（按 id 筛查）：下界金矿 → 金粒
        if (path.equals("nether_gold_ore")) {
            ResourceLocation nugget = new ResourceLocation("minecraft", "gold_nugget");
            if (itemExists(nugget)) return nugget;
        }

        // 2) 由 id 拆解 <name>，按候选 id 在物品注册表中筛查第一个真实存在的掉落物
        String mineral = path.replaceAll("(?i)(?:(?:deepslate|nether)_)?(.+?)_ore$", "$1");
        if (!mineral.equals(path)) {            // 确实形如 *_ore 才走 id 推导
            String[] candidates = {
                    "raw_" + mineral, mineral, mineral + "_gem", mineral + "_ingot", mineral + "_nugget"
            };
            for (String cand : candidates) {
                ResourceLocation id = new ResourceLocation(ns, cand);
                if (itemExists(id)) return id;
                // 跨命名空间兼容：掉落物可能在 minecraft 命名空间（如 iron_ore 的 raw_iron 仍在 minecraft）
                if (!ns.equals("minecraft")) {
                    ResourceLocation mcId = new ResourceLocation("minecraft", cand);
                    if (itemExists(mcId)) return mcId;
                }
            }
        }

        // 3) 标签候选顺序（forge 动态标签，重载后可用）—— 辅助发现 mod 矿石的真实掉落物
        if (!mineral.equals(path)) {
            for (String prefix : PRODUCT_TAG_PREFIXES) {
                ResourceLocation drop = firstItemInTag("forge", prefix + "/" + mineral);
                if (drop != null) return drop;
            }
        }

        // 4) 兜底：矿石自身物品（多数 mod 矿石直接掉落自身，确保不被漏掉）
        Item self = ore.asItem();
        if (self != null && self != Items.AIR) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(self);
            if (id != null) return id;
        }
        return null;
    }

    /** 物品注册表中是否存在该 id（缺失 key 时 DefaultedRegistry 返回默认 AIR，据此判定不存在）。 */
    private static boolean itemExists(ResourceLocation id) {
        return BuiltInRegistries.ITEM.get(id) != Items.AIR;
    }

    /**
     * 返回 {@code <ns>:<path>} 标签中的第一个非 AIR 物品 id；标签不存在或为空返回 null。
     * <p>优先使用 Forge 的 {@link ITagManager}（{@code forge:*} 命名空间标签的可靠来源，
     * 数据包重载后填充），再以静态注册表 {@link BuiltInRegistries#ITEM} 作兜底。</p>
     */
    private static ResourceLocation firstItemInTag(String ns, String path) {
        TagKey<Item> tag = TagKey.create(Registries.ITEM, new ResourceLocation(ns, path));
        // 1) Forge 动态标签管理器（forge 命名空间标签的可靠来源）
        try {
            ITagManager<Item> tagManager = ForgeRegistries.ITEMS.tags();
            if (tagManager != null) {
                for (Item it : tagManager.getTag(tag)) {
                    if (it == Items.AIR) continue;
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(it);
                    if (id != null) return id;
                }
            }
        } catch (Exception ignored) { /* 标签尚未就绪，回落到静态注册表 */ }
        // 2) 静态注册表兜底
        Optional<HolderSet.Named<Item>> tagOpt = BuiltInRegistries.ITEM.getTag(tag);
        if (tagOpt.isPresent()) {
            for (Holder<Item> holder : tagOpt.get()) {
                Item it = holder.value();
                if (it == Items.AIR) continue;
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(it);
                if (id != null) return id;
            }
        }
        return null;
    }
}
