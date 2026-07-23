package com.fkmyjc.fkmyjcs_miners.multiblock;

import com.fkmyjc.fkmyjcs_miners.Fkmyjcs_miners;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从数据包加载多方块结构定义（data/&lt;ns&gt;/multiblock/*.json）。
 *
 * <p>因为走数据包重载，KubeJS 用户把修改后的 json 放到
 * <pre>  kubejs/data/&lt;ns&gt;/multiblock/&lt;id&gt;.json</pre>
 * 后执行 <b>/reload</b> 即可生效，无需重编译。</p>
 */
public enum MultiBlockRegistry implements ResourceManagerReloadListener {
    INSTANCE;

    private static final Logger LOGGER = LogUtils.getLogger();

    // key = 数据包资源位置，如 fkmyjcs_miners:multiblock/miner
    private final Map<ResourceLocation, MultiBlockPattern> patterns = new ConcurrentHashMap<>();

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        Map<ResourceLocation, MultiBlockPattern> loaded = new HashMap<>();
        for (ResourceLocation id : resourceManager.listResources("multiblock",
                rl -> rl.getPath().endsWith(".json")).keySet()) {
            resourceManager.getResource(id).ifPresent(res -> {
                try (InputStreamReader reader = new InputStreamReader(res.open(), StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    MultiBlockPattern pattern = MultiBlockPattern.fromJson(json);
                    loaded.put(id, pattern);
                    LOGGER.info("Loaded multiblock pattern {} (controller={})",
                            id, pattern.getControllerBlock());
                } catch (Exception e) {
                    LOGGER.error("Failed to load multiblock pattern {}", id, e);
                }
            });
        }
        patterns.clear();
        patterns.putAll(loaded);
        LOGGER.info("Multiblock registry reloaded: {} pattern(s)", patterns.size());
    }

    /** 返回当前已加载的全部多方块结构（供预览标签页等消费）。 */
    public static java.util.Collection<MultiBlockPattern> getAllPatterns() {
        return INSTANCE.patterns.values();
    }

    /** 按 controller 方块反查其多方块结构（找不到返回 null）。 */
    @Nullable
    public static MultiBlockPattern getPattern(Block controller) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(controller);
        if (id == null) return null;
        for (MultiBlockPattern p : INSTANCE.patterns.values()) {
            if (p.getControllerBlock() != null && p.getControllerBlock() == controller) return p;
        }
        return null;
    }

    /**
     * 校验某位置处的矿机多方块结构是否已成形。
     * 若结构 {@link MultiBlockPattern#isBindDirection()} 为 true（默认），只按控制器当前朝向校验；
     * 若为 false，遍历四个水平朝向，任一成形即成立。
     */
    public static boolean isFormed(Level level, BlockPos pos, Direction facing) {
        Block controller = level.getBlockState(pos).getBlock();
        MultiBlockPattern pattern = getPattern(controller);
        if (pattern == null) return false;
        return pattern.isBindDirection()
                ? pattern.matches(level, pos, facing)
                : pattern.matchesAnyDirection(level, pos);
    }
}
