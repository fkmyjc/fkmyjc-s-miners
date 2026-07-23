package com.fkmyjc.fkmyjcs_miners.ore;

import com.fkmyjc.fkmyjcs_miners.Fkmyjcs_miners;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceKey;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public final class OreListManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final TagKey<Block> FORGE_ORES = TagKey.create(Registries.BLOCK,
            new ResourceLocation("forge", "ores"));
    private static final TagKey<Block> DEEPSLATE_ORES = TagKey.create(Registries.BLOCK,
            new ResourceLocation("forge", "ores_in_ground/deepslate"));

    private static final Path SAVE_PATH = FMLPaths.CONFIGDIR.get()
            .resolve(Fkmyjcs_miners.MODID)
            .resolve("ore_list.json");

    private static volatile List<Block> ores = List.of();
    private static volatile List<ResourceLocation> oreIds = List.of();

    /**
     * 「额外矿石」集合：在「原逻辑筛查（#forge:ores 标签）」之后、覆写 json 之前，
     * 追加进矿池的矿石。默认含 {@code minecraft:ancient_debris}——它真实存在为矿石方块，
     * 但 Forge 默认未将其纳入 #forge:ores 标签，因此原筛查逻辑（标签收集 + "_ore" 后缀兜底扫描）
     * 都抓不到，必须显式追加。
     *
     * <p><b>可用 KubeJS 修改</b>：在 startup 脚本中调用 {@link #addExtraOre(String)} /
     * {@link #removeExtraOre(String)} 增删，例如：
     * <pre>
     *   // kubejs/startup_scripts/ore_list.js
     *   OreListManager.addExtraOre("minecraft:ancient_debris");
     *   OreListManager.removeExtraOre("some:mod_ore");
     * </pre>
     * 这些调用须在实例启动完成（FMLLoadComplete，即 {@link #init()}）之前执行；
     * KubeJS 的 startup 脚本天然满足该时序。修改后下一次进入世界 / /reload
     * 会重新生成 ore_list.json、fortune_mining_list.json 与 fortune_mining_map.json。
     */
    public static final Set<ResourceLocation> EXTRA_ORES = new LinkedHashSet<>();
    static {
        EXTRA_ORES.add(new ResourceLocation("minecraft", "ancient_debris"));
    }

    /** 追加一个额外矿石（按 ResourceLocation）；该矿会被并入矿池并写入 ore_list.json。 */
    public static void addExtraOre(ResourceLocation id) {
        if (id != null) EXTRA_ORES.add(id);
    }

    /** 追加一个额外矿石（字符串形式，KubeJS 最易调用）；id 非法时忽略。 */
    public static void addExtraOre(String id) {
        ResourceLocation r = ResourceLocation.tryParse(id);
        if (r != null) EXTRA_ORES.add(r);
    }

    /** 移除一个额外矿石（字符串形式，KubeJS 最易调用）；id 非法时忽略。 */
    public static void removeExtraOre(String id) {
        ResourceLocation r = ResourceLocation.tryParse(id);
        if (r != null) EXTRA_ORES.remove(r);
    }

    private OreListManager() {}

    public static void init() {
        // ★ 每次进入实例都重新生成矿石列表并覆盖落盘文件，不读取旧缓存
        generateAndSave();
    }

    public static List<Block> getOres() {
        return ores;
    }

    public static List<ResourceLocation> getOreIds() {
        return oreIds;
    }

    private static void generateAndSave() {
        List<ResourceLocation> generated = buildOreList();
        List<Block> generatedBlocks = generated.stream()
                .map(ForgeRegistries.BLOCKS::getValue)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        oreIds = Collections.unmodifiableList(generated);
        ores = Collections.unmodifiableList(generatedBlocks);

        try {
            Files.createDirectories(SAVE_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(SAVE_PATH)) {
                GSON.toJson(generated.stream().map(ResourceLocation::toString).toList(), writer);
            }
            LOGGER.info("Generated and saved {} ores to {}", generated.size(), SAVE_PATH);
        } catch (IOException e) {
            LOGGER.error("Failed to save ore list to {}", SAVE_PATH, e);
        }
    }

    private static List<ResourceLocation> buildOreList() {
        List<Block> allOres = new ArrayList<>();
        var tagOpt = BuiltInRegistries.BLOCK.getTag(FORGE_ORES);
        if (tagOpt.isPresent()) {
            tagOpt.get().forEach(holder -> allOres.add(holder.value()));
            LOGGER.info("buildOreList: got {} blocks from #forge:ores tag", allOres.size());
        } else {
            LOGGER.warn("#forge:ores tag not found, falling back to registry scan");
            for (var entry : ForgeRegistries.BLOCKS.getEntries()) {
                ResourceLocation id = entry.getKey().location();
                Block block = entry.getValue();
                String path = id.getPath();
                if (path.endsWith("_ore") || path.contains("_ore_")) {
                    if (isDeepslateOreByPath(id)) continue;
                    allOres.add(block);
                }
            }
            LOGGER.info("buildOreList: fallback scan got {} blocks", allOres.size());
        }

        // 追加「额外矿石」：默认含 minecraft:ancient_debris（真实存在但未被纳入 #forge:ores 标签，
        // 原筛查逻辑抓不到）。该集合对外公开且可变，KubeJS 可在 startup 脚本中通过
        // addExtraOre / removeExtraOre 增删，无需重编译。
        for (ResourceLocation extra : EXTRA_ORES) {
            if (!ForgeRegistries.BLOCKS.containsKey(extra)) continue;
            Block b = ForgeRegistries.BLOCKS.getValue(extra);
            if (b != null && b != Blocks.AIR) allOres.add(b);
        }

        if (allOres.isEmpty()) {
            LOGGER.warn("buildOreList: NO ores found at all, ore_list.json will be empty");
            return List.of();
        }

        Map<String, Block> deduplicated = new LinkedHashMap<>();
        for (Block block : allOres) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
            if (blockId == null) continue;

            // ★ 关键修复：使用 BuiltInRegistries 获取 Holder，确保标签完整 ★
            ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, blockId);
            Optional<ResourceLocation> subTag = BuiltInRegistries.BLOCK.getHolder(key)
                    .flatMap(OreListManager::getOreTypeTagFromHolder);

            String dedupeKey;
            if (subTag.isPresent()) {
                dedupeKey = subTag.get().toString();  // 例如 "forge:ores/lead"
            } else {
                // 后备：从 ID 提取矿物名
                String path = blockId.getPath();
                String mineralName = path.replaceAll("(?i)(?:deepslate_)?(.+?)_ore$", "$1");
                dedupeKey = mineralName.equals(path) ? blockId.toString() : mineralName;
            }

            deduplicated.merge(dedupeKey, block, (existing, incoming) ->
                    !isVanilla(existing) && isVanilla(incoming) ? incoming : existing);
        }

        return deduplicated.values().stream()
                .sorted(Comparator
                        .<Block>comparingInt(b -> isVanilla(b) ? 0 : 1)
                        .thenComparing(b -> ForgeRegistries.BLOCKS.getKey(b).toString()))
                .map(ForgeRegistries.BLOCKS::getKey)
                .collect(Collectors.toList());
    }

    /** 根据路径判断是否为深板岩变种（兜底扫描专用） */
    private static boolean isDeepslateOreByPath(ResourceLocation id) {
        if (id == null) return false;
        String path = id.getPath();
        return path.startsWith("deepslate_") || path.contains("/deepslate_");
    }

    /** 从 Holder 获取矿石类型标签（forge:ores/xxx） */
    private static Optional<ResourceLocation> getOreTypeTagFromHolder(Holder<Block> holder) {
        return holder.tags()
                .map(TagKey::location)
                .filter(loc -> "forge".equals(loc.getNamespace()))
                .filter(loc -> {
                    String path = loc.getPath();
                    return path.startsWith("ores/") && !path.equals("ores");
                })
                .min(Comparator.comparing(ResourceLocation::getPath));
    }

    /** 判断是否为原版方块 */
    private static boolean isVanilla(Block block) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        return id != null && "minecraft".equals(id.getNamespace());
    }
}