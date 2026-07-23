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


    private static volatile List<Item> fortuneItems = List.of();
    private static volatile List<ResourceLocation> fortuneItemIds = List.of();
    private static volatile Map<ResourceLocation, ResourceLocation> fortuneMap = Map.of();
    private static volatile boolean initialized = false;

    private FortuneMiningListManager() {}

    /* ========================= 初始化（启动 + 重载两次触发） ========================= */

    /** 实例启动完成时调用（FMLLoadComplete）。 */
    public static void init() {
        if (initialized) {
            LOGGER.debug("FortuneMiningListManager already initialized, skipping.");
            return;
        }
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

    public static Map<ResourceLocation, ResourceLocation> getFortuneMap() {
        return fortuneMap;
    }

    /* ========================= 文件 IO ========================= */

    private static void generateAndSave(List<ResourceLocation> oreIds) {
        // 1) 建立 原矿(block) → 掉落物(item) 的对应表
        LinkedHashMap<ResourceLocation, ResourceLocation> mapping = buildFortuneMapping(oreIds);

        // 2) 掉落物列表（去重、排序：原版在前，其余按字符串）
        List<ResourceLocation> drops = mapping.values().stream()
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
                Map<String, String> jsonMap = mapping.entrySet().stream()
                        .collect(Collectors.toMap(
                                e -> e.getKey().toString(),
                                e -> e.getValue().toString(),
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

    private static LinkedHashMap<ResourceLocation, ResourceLocation> buildFortuneMapping(List<ResourceLocation> oreIds) {
        LinkedHashMap<ResourceLocation, ResourceLocation> map = new LinkedHashMap<>();
        for (ResourceLocation oreId : oreIds) {
            Block ore = ForgeRegistries.BLOCKS.getValue(oreId);
            if (ore == null || ore == Blocks.AIR) continue;

            ResourceLocation drop = resolveDrop(oreId, ore);
            if (drop != null) {
                map.put(oreId, drop);
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
            ResourceLocation nugget = new ResourceLocation("minecraft:gold_nugget");
            if (itemExists(nugget)) return nugget;
        }

        // 2) 由 id 拆解 <name>，按候选 id 在物品注册表中筛查第一个真实存在的掉落物
        String mineral = path.replaceAll("(?i)(?:(?:deepslate|nether)_)?(.+?)_ore$", "$1");
        if (!mineral.equals(path)) {            // 确实形如 *_ore 才走 id 推导
            String[] candidates = {
                    "raw_" + mineral, mineral, mineral + "_gem", mineral + "_ingot", mineral + "_nugget"
            };
            for (String cand : candidates) {
                ResourceLocation id = new ResourceLocation(ns + ":" + cand);
                if (itemExists(id)) return id;
                // 跨命名空间兼容：掉落物可能在 minecraft 命名空间（如 iron_ore 的 raw_iron 仍在 minecraft）
                if (!ns.equals("minecraft")) {
                    ResourceLocation mcId = new ResourceLocation("minecraft:" + cand);
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
        TagKey<Item> tag = TagKey.create(Registries.ITEM, new ResourceLocation(ns + ":" + path));
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
