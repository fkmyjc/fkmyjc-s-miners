package com.fkmyjc.fkmyjcs_miners;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber(modid = Fkmyjcs_miners.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    // === Common config ===
    private static final ForgeConfigSpec.Builder COMMON_BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue LOG_DIRT_BLOCK = COMMON_BUILDER.comment("Whether to log the dirt block on common setup").define("logDirtBlock", true);

    private static final ForgeConfigSpec.IntValue MAGIC_NUMBER = COMMON_BUILDER.comment("A magic number").defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = COMMON_BUILDER.comment("What you want the introduction message to be for the magic number").define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = COMMON_BUILDER.comment("A list of items to log on common setup.").defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // === 自动建造（用指定物品右键多方块核心触发） ===
    private static final ForgeConfigSpec.Builder AUTO_BUILD = COMMON_BUILDER.push("autoBuild");
    // 触发物品：右键核心方块时，手中持有此物品才会触发自动建造
    private static final ForgeConfigSpec.ConfigValue<String> AUTO_BUILD_TRIGGER_ITEM =
            AUTO_BUILD.comment("用此物品右键多方块核心方块时，触发自动建造其结构。格式为 \"namespace:item_id\" 。")
                    .define("triggerItem", "minecraft:stick");
    // 生存模式下每秒建造的方块数（结构方块数不超过 240 时使用的「基础速度」）
    public static final ForgeConfigSpec.IntValue AUTO_BUILD_SURVIVAL_RATE =
            AUTO_BUILD.comment("生存模式下自动建造的基础速度：每秒放置的方块数量（适用于结构方块数不超过 240 的情况）。")
                    .defineInRange("survivalBlocksPerSecond", 4, 1, 1000);
    // 创造模式下建造整个结构所用的 tick 数（20 tick = 1 秒，默认 10 = 0.5 秒）
    public static final ForgeConfigSpec.IntValue AUTO_BUILD_CREATIVE_TICKS =
            AUTO_BUILD.comment("创造模式下自动建造整个结构所用的 tick 数（20 tick = 1 秒，默认 10 即约 0.5 秒完成）。")
                    .defineInRange("creativeDurationTicks", 10, 1, 1200);
    // 生存模式时间倍率：结构方块数 > 240 时自动加速使总耗时趋近于 60s，此倍率缩放目标时间
    public static final ForgeConfigSpec.DoubleValue AUTO_BUILD_SURVIVAL_TIME_MULTIPLIER =
            AUTO_BUILD.comment("生存模式时间倍率：当结构方块数超过 240 时，自动加速使总建造时间趋近于 60 秒；此值缩放目标时间（默认 1.0 → 目标 60 秒，2.0 → 目标 120 秒）。")
                    .defineInRange("survivalTimeMultiplier", 1.0, 0.01, 100.0);
    static {
        AUTO_BUILD.pop();
    }

    // === 探矿道具（探矿杖 / 探矿仪）===
    private static final ForgeConfigSpec.Builder PROSPECT = COMMON_BUILDER.push("prospect");
    // 探矿仪（FE/RF 设备）的能量容量
    public static final ForgeConfigSpec.IntValue PROSPECT_ENERGY_CAPACITY =
            PROSPECT.comment("探矿仪（FE/RF 设备）的能量容量（FE）。")
                    .defineInRange("energyCapacity", 1000000, 1, Integer.MAX_VALUE);
    // 每次探矿消耗的能量
    public static final ForgeConfigSpec.IntValue PROSPECT_ENERGY_PER_USE =
            PROSPECT.comment("探矿仪每次探矿消耗的能量（FE）。")
                    .defineInRange("energyPerUse", 500, 1, Integer.MAX_VALUE);
    // === 高级探矿仪（FE/RF，默认 100M 缓存）===
    // 能量容量（FE）。默认 100,000,000（即 100M FE）。
    public static final ForgeConfigSpec.IntValue ADVANCED_PROSPECT_ENERGY_CAPACITY =
            PROSPECT.comment("高级探矿仪的能量容量（FE）。默认 100,000,000（即 100M FE）。")
                    .defineInRange("advancedEnergyCapacity", 100000000, 1, Integer.MAX_VALUE);
    // 每次打开 7×7 矿脉地图 GUI 的固定消耗（FE）。
    public static final ForgeConfigSpec.IntValue ADVANCED_PROSPECT_ENERGY_PER_OPEN =
            PROSPECT.comment("高级探矿仪每次打开 7×7 矿脉地图 GUI 的固定消耗（FE）。")
                    .defineInRange("advancedEnergyPerOpen", 1000, 0, Integer.MAX_VALUE);
    // 每探明一个「尚未探明」的区块消耗的能量（FE）。7×7 共 49 格，仅对未知区块计费。
    public static final ForgeConfigSpec.IntValue ADVANCED_PROSPECT_ENERGY_PER_CHUNK =
            PROSPECT.comment("高级探矿仪每探明一个「尚未探明」的区块消耗的能量（FE）。7×7 共 49 格，仅对未知区块计费。")
                    .defineInRange("advancedEnergyPerChunk", 800, 0, Integer.MAX_VALUE);
    static {
        PROSPECT.pop();
    }

    // === 矿脉储量 ===
    private static final ForgeConfigSpec.Builder VEIN = COMMON_BUILDER.push("vein");
    // 每个区块矿脉的储量基准值（实际储量在此基础上 ± reservesSpread 随机浮动）
    public static final ForgeConfigSpec.IntValue VEIN_RESERVES_BASE =
            VEIN.comment("每个区块矿脉的储量基准值。实际储量在此基础上，于 ±reservesSpread 范围内随机浮动。")
                    .defineInRange("reservesBase", 200000, 1, Integer.MAX_VALUE);
    // 储量随机浮动的半幅（实际储量 = reservesBase ± reservesSpread）
    public static final ForgeConfigSpec.IntValue VEIN_RESERVES_SPREAD =
            VEIN.comment("储量随机浮动的半幅：实际储量 = reservesBase ± reservesSpread（默认 50000 → 区间 [150000, 250000]）。")
                    .defineInRange("reservesSpread", 50000, 0, Integer.MAX_VALUE);
    // 末地是否生成矿物矿脉（默认 false：末地只生成 100% 岩石矿脉，无主/次矿）
    public static final ForgeConfigSpec.BooleanValue VEIN_END_MINERALS =
            VEIN.comment("末地是否生成矿物矿脉。默认 false：末地只生成 100% 岩石矿脉（无主矿/次矿）。"
                    + "设为 true 后末地按普通规则（普通单/双、贫穷）生成矿脉。")
                    .define("endMinerals", false);

    // 各矿池对应的维度列表：决定某维度使用哪一组矿石池（主世界/下界/末地）。
    // 默认值仅含原版对应维度；可追加其它 mod 维度或自定义维度。
    // 接受维度 id（如 minecraft:overworld），也接受维度注册名（dim，即同形式的 ResourceLocation）。
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> VEIN_OVERWORLD_DIMS =
            VEIN.comment("主世界矿池对应的维度列表（默认仅 minecraft:overworld）。"
                    + "列表中的维度使用「主世界矿石池」（煤炭/铁/铜/金/红石/青金石/绿宝石/钻石等，不含下界与末地矿石）。"
                    + "可填写维度 id（如 minecraft:overworld）。")
                    .defineListAllowEmpty("overworldDimensions", List.of("minecraft:overworld"), Config::validateDimensionName);
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> VEIN_NETHER_DIMS =
            VEIN.comment("下界矿池对应的维度列表（默认仅 minecraft:the_nether）。"
                    + "列表中的维度使用「下界矿石池」（下界石英/下界金矿/远古残骸等）。")
                    .defineListAllowEmpty("netherDimensions", List.of("minecraft:the_nether"), Config::validateDimensionName);
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> VEIN_END_DIMS =
            VEIN.comment("末地矿池对应的维度列表（默认仅 minecraft:the_end）。"
                    + "列表中的维度使用「末地矿石池」（含 end_ 前缀的矿石）。")
                    .defineListAllowEmpty("endDimensions", List.of("minecraft:the_end"), Config::validateDimensionName);
    static {
        VEIN.pop();
    }

    public static final ForgeConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // === Client config ===
    private static final ForgeConfigSpec.Builder CLIENT_BUILDER = new ForgeConfigSpec.Builder();

    // 探矿叠加层位置/外观调整（仅客户端）
    private static final ForgeConfigSpec.Builder OVERLAY = CLIENT_BUILDER.push("overlay");
    public static final ForgeConfigSpec.IntValue PROSPECT_OVERLAY_Y_OFFSET =
            OVERLAY.comment("矿脉叠加层顶部垂直偏移（像素，越大越向下）。当右上角有小地图等叠加层时，可手动下移避免重叠。")
                    .defineInRange("yOffset", 0, 0, Integer.MAX_VALUE);
    public static final ForgeConfigSpec.DoubleValue PROSPECT_OVERLAY_SCALE =
            OVERLAY.comment("矿脉叠加层缩放比例（1.0 为默认大小，越大面板越大，越小越紧凑）。")
                    .defineInRange("scale", 1.0, 0.5, 4.0);
    // 7×7 矿脉地图右侧图例每行高度（像素，越小行距越紧凑；色块会自动适配不溢出）
    public static final ForgeConfigSpec.IntValue MAP_LEGEND_ROW_HEIGHT =
            OVERLAY.comment("7×7 矿脉地图右侧图例的每行高度（像素）。值越小行距越紧凑，可显示更多矿石条目；色块大小会自动适配行高。")
                    .defineInRange("legendRowHeight", 11, 6, 40);
    static {
        OVERLAY.pop();
    }

    public static final ForgeConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // Runtime values
    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;

    public static Item autoBuildTriggerItem;
    public static int autoBuildSurvivalRate;
    public static int autoBuildCreativeTicks;
    public static double autoBuildSurvivalTimeMultiplier;

    public static int prospectEnergyCapacity;
    public static int prospectEnergyPerUse;

    public static int advancedProspectEnergyCapacity;
    public static int advancedProspectEnergyPerOpen;
    public static int advancedProspectEnergyPerChunk;

    public static int veinReservesBase;
    public static int veinReservesSpread;
    public static boolean veinEndMinerals;
    public static Set<ResourceLocation> veinOverworldDims;
    public static Set<ResourceLocation> veinNetherDims;
    public static Set<ResourceLocation> veinEndDims;

    public static int prospectOverlayYOffset;
    public static double prospectOverlayScale;
    public static int mapLegendRowHeight;

    private static boolean validateItemName(final Object obj) {
        if (!(obj instanceof final String itemName)) {
            return false;
        }
        ResourceLocation location = ResourceLocation.tryParse(itemName);
        return location != null && ForgeRegistries.ITEMS.containsKey(location);
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getType() == ModConfig.Type.CLIENT) {
            prospectOverlayYOffset = PROSPECT_OVERLAY_Y_OFFSET.get();
            prospectOverlayScale = PROSPECT_OVERLAY_SCALE.get();
            mapLegendRowHeight = MAP_LEGEND_ROW_HEIGHT.get();
            return;
        }

        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        items = ITEM_STRINGS.get().stream()
                .map(ResourceLocation::tryParse)
                .filter(Objects::nonNull)
                .map(ForgeRegistries.ITEMS::getValue)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        ResourceLocation triggerId = ResourceLocation.tryParse(AUTO_BUILD_TRIGGER_ITEM.get());
        autoBuildTriggerItem = (triggerId != null) ? ForgeRegistries.ITEMS.getValue(triggerId) : null;
        autoBuildSurvivalRate = AUTO_BUILD_SURVIVAL_RATE.get();
        autoBuildCreativeTicks = AUTO_BUILD_CREATIVE_TICKS.get();
        autoBuildSurvivalTimeMultiplier = AUTO_BUILD_SURVIVAL_TIME_MULTIPLIER.get();

        prospectEnergyCapacity = PROSPECT_ENERGY_CAPACITY.get();
        prospectEnergyPerUse = PROSPECT_ENERGY_PER_USE.get();
        advancedProspectEnergyCapacity = ADVANCED_PROSPECT_ENERGY_CAPACITY.get();
        advancedProspectEnergyPerOpen = ADVANCED_PROSPECT_ENERGY_PER_OPEN.get();
        advancedProspectEnergyPerChunk = ADVANCED_PROSPECT_ENERGY_PER_CHUNK.get();

        veinReservesBase = VEIN_RESERVES_BASE.get();
        veinReservesSpread = VEIN_RESERVES_SPREAD.get();
        veinEndMinerals = VEIN_END_MINERALS.get();
        veinOverworldDims = parseDims(VEIN_OVERWORLD_DIMS);
        veinNetherDims = parseDims(VEIN_NETHER_DIMS);
        veinEndDims = parseDims(VEIN_END_DIMS);
    }

    private static Set<ResourceLocation> parseDims(ForgeConfigSpec.ConfigValue<List<? extends String>> value) {
        return value.get().stream()
                .map(ResourceLocation::tryParse)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** 维度名校验：只要能解析为 ResourceLocation 即可（维度可能在配置加载时尚未注册，故不校验存在性）。 */
    private static boolean validateDimensionName(final Object obj) {
        return obj instanceof String s && ResourceLocation.tryParse(s) != null;
    }
}
