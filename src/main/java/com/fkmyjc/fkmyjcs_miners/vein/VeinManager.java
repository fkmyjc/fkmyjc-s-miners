package com.fkmyjc.fkmyjcs_miners.vein;

import com.fkmyjc.fkmyjcs_miners.Config;
import com.fkmyjc.fkmyjcs_miners.chunkdata.ChunkVeinData;
import com.fkmyjc.fkmyjcs_miners.chunkdata.VeinSavedData;
import com.fkmyjc.fkmyjcs_miners.prospect.ProspectStore;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每区块（Chunk）确定性地生成并缓存一个 {@link Vein}。
 *
 * <p>生成使用「世界种子 + 维度 + 区块坐标」派生的种子（<b>不含任何代码 / 配置版本号</b>），因此：
 * <ul>
 *   <li>同一个区块每次进入 / 重启后矿脉一致；</li>
 *   <li>无论模组代码或矿石配置如何变更（版本不同），已落盘的矿脉
 *       （{@code ChunkVeinData} 的 {@code initialized=true}）始终作为权威来源被原样读回，
 *       绝不会被新代码重新生成并覆盖；</li>
 *   <li>仅从未访问过、无存档记录的区块，才会在首次进入时按“当前代码”确定性生成。</li>
 * </ul>
 *
 * <p>需要整体重刷矿脉时，使用 {@code /vein reset}（当前区块）或 {@code /vein reset all}（全维度全部区块）。</p>
 */
public final class VeinManager {

    private static final Map<Long, Vein> cache = new ConcurrentHashMap<>();

    private VeinManager() {}

    public static Vein getVein(Level level, ChunkPos cp) {
        long key = keyOf(level, cp);
        Vein cached = cache.get(key);
        if (cached != null) return cached;
        Vein v = loadOrGenerate(level, cp);
        cache.put(key, v);
        return v;
    }

    /**
     * 读取真实矿脉：服务端从维度级 {@link VeinSavedData} 取（未初始化则先由确定性随机生成并
     * 写回，使 SavedData 成为此区块矿脉的权威）；客户端回退为纯确定性生成（不持久化）。
     */
    private static Vein loadOrGenerate(Level level, ChunkPos cp) {
        if (level instanceof ServerLevel sl) {
            VeinSavedData sd = VeinSavedData.get(sl);
            ChunkVeinData data = sd.getOrCreate(cp);
            if (!data.isInitialized()) {
                data.initFromVein(generate(level, cp, data.getGenSalt()));
                sd.setDirty();
            }
            return data.toVein();
        }
        return generate(level, cp, 0L);
    }

    /**
     * 使某区块的矿脉缓存失效。{@code /vein} 系列指令就地修改 {@link ChunkVeinData} 后调用本方法，
     * 使下次 {@link #getVein} 重新从 capability 读取（已反映修改后的值）。
     */
    public static void invalidate(Level level, ChunkPos cp) {
        cache.remove(keyOf(level, cp));
    }

    /** 清空缓存（一般无需手动调用，版本号机制已保证失效）。 */
    public static void clearCache() {
        cache.clear();
    }

    /**
     * 公开生成入口（不查缓存、不写回）。{@code /vein reset} 传入一个“重新随机”的 salt，
     * 得到本区块一个全新的矿脉；salt=0 时等价于确定性首生成（同区块跨重启一致）。
     */
    public static Vein generateFor(Level level, ChunkPos cp, long salt) {
        return generate(level, cp, salt);
    }

    /* ===================== 坐标驱动的查询 / 修改 API ===================== */

    /** 世界坐标（方块坐标）重载：返回所在区块的矿脉情况。 */
    public static Vein getVein(Level level, BlockPos pos) {
        return getVein(level, new ChunkPos(pos));
    }

    /**
     * 返回指定世界坐标所在区块的矿脉情况（全面快照，含维度 / 区块坐标 / 初始化状态 / 生成盐
     * + 完整矿脉字段与派生判定）。非服务端维度也能查询，但 {@code initialized=false}、
     * {@code genSalt=0}（客户端不持久化）。
     */
    public static VeinReport queryVein(Level level, BlockPos pos) {
        return queryVein(level, new ChunkPos(pos));
    }

    /** 区块坐标重载：返回指定区块的矿脉情况快照。 */
    public static VeinReport queryVein(Level level, int chunkX, int chunkZ) {
        return queryVein(level, new ChunkPos(chunkX, chunkZ));
    }

    private static VeinReport queryVein(Level level, ChunkPos cp) {
        ResourceLocation dim = level.dimension().location();
        boolean initialized = false;
        long salt = 0L;
        if (level instanceof ServerLevel sl) {
            ChunkVeinData data = VeinSavedData.get(sl).getOrCreate(cp);
            initialized = data.isInitialized();
            salt = data.getGenSalt();
        }
        Vein v = getVein(level, cp);
        return new VeinReport(dim, cp.x, cp.z, initialized, salt, v);
    }

    /**
     * 按世界坐标与给定的 {@link VeinEdit} 参数，全面修改该区块的矿脉情况，并落盘 / 使缓存失效 /
     * 重同步正在查看该区块的客户端叠加层。仅服务端维度有效；客户端或坐标无效返回失败。
     *
     * <p>修改语义（与 {@code /vein} 指令一致）：
     * <ul>
     *   <li>矿石 id 解析：主 / 次矿优先经 {@link VeinOreRegistry#normalizeOreItem} 归一化为矿石方块，
     *       失败则回退为 {@code ForgeRegistries.ITEMS} 直接取值；岩石按物品 id 直接取值。</li>
     *   <li>权重：任一权重被设置即按 {@code /vein prob} 规则重新归一化，使三者和为 100。</li>
     *   <li>设次矿且当前非双矿脉：自动升级为双矿脉并补默认次矿权重；清除次矿且当前为双则降级为单。</li>
     *   <li>{@code reset=true}：忽略其余字段，重新随机生成整条矿脉（{@code genSalt} 可指定盐）。</li>
     * </ul>
     */
    public static ModifyResult modifyVeinAt(Level level, BlockPos pos, VeinEdit edit) {
        return modifyVeinAt(level, new ChunkPos(pos), edit);
    }

    /** 区块坐标重载：按区块坐标与参数修改矿脉情况。 */
    public static ModifyResult modifyVeinAt(Level level, int chunkX, int chunkZ, VeinEdit edit) {
        return modifyVeinAt(level, new ChunkPos(chunkX, chunkZ), edit);
    }

    private static ModifyResult modifyVeinAt(Level level, ChunkPos cp, VeinEdit edit) {
        if (edit == null) return ModifyResult.fail("修改参数为空");
        if (!(level instanceof ServerLevel sl)) {
            return ModifyResult.fail("仅服务端维度可修改矿脉");
        }
        VeinSavedData sd = VeinSavedData.get(sl);
        ChunkVeinData data = sd.getOrCreate(cp);

        // 未初始化时先触发确定性生成，作为修改基底（loadOrGenerate 会 initFromVein + setDirty）
        if (!data.isInitialized()) {
            getVein(level, cp);
        }

        if (edit.isReset()) {
            long salt = (edit.genSalt() != null) ? edit.genSalt() : randomSalt(sl);
            data.setGenSalt(salt);
            data.initFromVein(generateFor(level, cp, salt));
        } else {
            applyEdit(sl, data, edit);
        }

        // 落盘 + 缓存失效 + 仅重同步正在查看该区块的在线玩家叠加层
        sd.setDirty();
        invalidate(level, cp);
        syncOverlay(sl, cp);

        return ModifyResult.ok(queryVein(level, cp));
    }

    /** 应用除 reset 外的全部字段（逻辑对齐 {@code /vein mode} 与 {@code /vein prob}）。 */
    private static void applyEdit(ServerLevel sl, ChunkVeinData data, VeinEdit edit) {
        ResourceLocation dim = sl.dimension().location();

        // 1) 类型（含双矿脉补次矿 / 单·贫穷清次矿的配套处理）
        Integer tc = edit.typeCode();
        if (tc != null && tc >= 0 && tc <= 2) {
            VeinType t = (tc == 0) ? VeinType.POVERTY : (tc == 2) ? VeinType.DOUBLE : VeinType.SINGLE;
            int mainW = data.getMainWeight();
            if (t == VeinType.DOUBLE) {
                int secW = data.getSecondaryWeight();
                if (secW <= 0) {
                    secW = Math.min(20, Math.max(0, 100 - mainW));
                    data.setSecondaryWeight(secW);
                }
                if (data.getSecondaryOreId() == null) {
                    List<Item> pool = VeinOreRegistry.getOrePool(dim);
                    Item mainItem = (data.getMainOreId() != null)
                            ? ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(data.getMainOreId())) : null;
                    Item pick = pool.stream().filter(i -> i != mainItem).findAny().orElse(null);
                    if (pick != null) data.setSecondaryOre(ForgeRegistries.ITEMS.getKey(pick));
                }
                int rockW = 100 - mainW - secW;
                if (rockW < 0) {
                    secW = Math.max(0, 100 - mainW);
                    rockW = 100 - mainW - secW;
                    data.setSecondaryWeight(secW);
                }
                data.setRockWeight(rockW);
            } else {
                data.setSecondaryOre(null);
                data.setSecondaryWeight(0);
                data.setRockWeight(100 - mainW);
            }
            data.setType(tc);
        }

        // 2) 主矿（set / clear / 保持）
        if (edit.isMainOreClear()) {
            data.setMainOre(null);
        } else if (edit.hasMainOre() && edit.mainOreId() != null) {
            Item ore = resolveOre(edit.mainOreId().toString());
            if (ore != null) data.setMainOre(ForgeRegistries.ITEMS.getKey(ore));
        }

        // 3) 次矿（set 且非双则升级；clear 且为双则降级）
        if (edit.isSecondaryOreClear()) {
            data.setSecondaryOre(null);
            data.setSecondaryWeight(0);
            if (data.getTypeCode() == 2) {
                data.setType(1);
                data.setRockWeight(100 - data.getMainWeight());
            }
        } else if (edit.hasSecondaryOre() && edit.secondaryOreId() != null) {
            Item ore = resolveOre(edit.secondaryOreId().toString());
            if (ore != null) {
                data.setSecondaryOre(ForgeRegistries.ITEMS.getKey(ore));
                if (data.getTypeCode() != 2) {
                    data.setType(2);
                    if (data.getSecondaryWeight() <= 0) {
                        int secW = Math.min(20, Math.max(0, 100 - data.getMainWeight()));
                        data.setSecondaryWeight(secW);
                        data.setRockWeight(100 - data.getMainWeight() - secW);
                    }
                }
            }
        }

        // 4) 岩石（set / clear 回退石头 / 保持）
        if (edit.isRockClear()) {
            data.setRock(null);
        } else if (edit.hasRock() && edit.rockId() != null) {
            Item rk = ForgeRegistries.ITEMS.getValue(edit.rockId());
            if (rk != null && rk != Items.AIR) data.setRock(edit.rockId());
        }

        // 5) 权重归一化（任一非 null 触发；等比缩放使三者和为 100）
        Integer mW = edit.mainW(), sW = edit.secW(), rW = edit.rockW();
        if (mW != null || sW != null || rW != null) {
            int mainW = (mW != null) ? Math.max(0, Math.min(100, mW)) : data.getMainWeight();
            int secW = (sW != null) ? Math.max(0, Math.min(100, sW)) : data.getSecondaryWeight();
            int rockW = (rW != null) ? Math.max(0, Math.min(100, rW)) : data.getRockWeight();
            int sum = mainW + secW + rockW;
            if (sum <= 0) {
                mainW = 34; secW = 33; rockW = 33;
            } else if (sum != 100) {
                mainW = Math.round(mainW * 100f / sum);
                secW = Math.round(secW * 100f / sum);
                rockW = 100 - mainW - secW;   // 兜底保证精确和为 100
            }
            data.setMainWeight(mainW);
            data.setSecondaryWeight(secW);
            data.setRockWeight(rockW);
        }

        // 6) 储量（设值 / 增量）
        if (edit.reserves() != null) data.setReserves(edit.reserves());
        if (edit.addReserves() != null) data.addReserves(edit.addReserves());
    }

    /** 把可能是掉落物 / 粗矿的 id 归一化为矿石方块物品，失败回退为直接按物品 id 取值。 */
    private static Item resolveOre(String id) {
        Item ore = VeinOreRegistry.normalizeOreItem(id);
        if (ore == null) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                Item direct = ForgeRegistries.ITEMS.getValue(rl);
                if (direct != null && direct != Items.AIR) ore = direct;
            }
        }
        return ore;
    }

    /** 重新随机一个生成盐：服务端随机源 + 纳秒时间，确保每次 reset 都得到不同的矿脉。 */
    private static long randomSalt(ServerLevel sl) {
        return sl.getRandom().nextLong() ^ System.nanoTime();
    }

    /** 仅对“当前正在查看该区块”的在线玩家刷新右侧叠加层（避免污染其他玩家数据）。 */
    private static void syncOverlay(ServerLevel sl, ChunkPos cp) {
        for (ServerPlayer player : sl.players()) {
            if (new ChunkPos(player.blockPosition()).equals(cp)) {
                ProspectStore.prospect(player, cp, false);
            }
        }
    }

    private static long keyOf(Level level, ChunkPos cp) {
        ResourceLocation dim = level.dimension().location();
        long h = dim.hashCode() * 31L
                + (long) cp.x * 73856093L
                + (long) cp.z * 19349663L;
        h = h * 31L + ((level instanceof ServerLevel sl) ? sl.getSeed() : 0L);
        return h;
    }

    private static Vein generate(Level level, ChunkPos cp, long salt) {
        // 种子 = 缓存键 ^ 黄金比例常量 ^ 生成盐；salt 变化即可让同一区块产出不同矿脉
        RandomSource r = RandomSource.create(keyOf(level, cp) ^ 0x9E3779B97F4A7C15L ^ salt);
        ResourceLocation dim = level.dimension().location();

        // 解析该维度对应的矿脉实例（模块化：矿石池 / 岩石池 / 比重 / 末地标记 皆取自实例）
        VeinProfile profile = VeinProfiles.resolve(dim);

        // 矿石池：rare 机制已取消，所有矿石（含原 rare）统一进一个池，稀有度由比重决定。
        // 主矿与次矿均从此池按比重加权抽取。
        List<Item> orePool = VeinOreRegistry.getOrePool(dim);

        // 岩石：按实例抽取
        Item rock = VeinOreRegistry.getRandomRock(dim, r);

        // 末地矿物开关（默认关闭）：实例标记为末地(endLike)且未开启矿物时只生成纯岩石矿脉。
        // 当该维度没有任何可用矿物（矿石池为空）时，也生成纯岩石矿脉（100% 岩石）。
        boolean dimIsEnd = (profile != null) && profile.endLike;
        boolean noMineral = orePool.isEmpty();
        if (noMineral || (dimIsEnd && !Config.veinEndMinerals)) {
            return new Vein(VeinType.SINGLE, null, null, rock, 0, 0, 100, 0);
        }

        // 矿脉类型：单 20% / 双 60% / 贫穷 20%（= 普通矿 80% + 贫穷矿 20%）
        double t = r.nextDouble();
        VeinType type;
        if (t < 0.20) type = VeinType.SINGLE;
        else if (t < 0.80) type = VeinType.DOUBLE;
        else type = VeinType.POVERTY;

        // 主矿：从矿石池按实例“生成比重”加权抽取
        Item mainOre = (profile != null)
                ? profile.weightedRandomOre(orePool, r)
                : VeinOreRegistry.getWeightedRandomOre(orePool, r);
        Item secondaryOre = null;
        int mainW, secW, rockW;

        if (type == VeinType.DOUBLE) {
            // 双类矿：主矿 45±10，次矿 20±10，岩石补足至 100
            mainW = 45 + jitter(r, 10);
            secW = 20 + jitter(r, 10);
            rockW = 100 - mainW - secW;
            // 次矿：从矿石池中剔除主矿后按比重加权抽取，保证与主矿不同；
            // 若该维度可区分矿石不足（剔除主矿后为空），退化为单矿脉。
            List<Item> secPool = new ArrayList<>(orePool);
            secPool.remove(mainOre);
            if (secPool.isEmpty()) {
                type = VeinType.SINGLE;
                secW = 0;
                rockW = 100 - mainW;
            } else {
                secondaryOre = (profile != null)
                        ? profile.weightedRandomOre(secPool, r)
                        : VeinOreRegistry.getWeightedRandomOre(secPool, r);
            }
        } else if (type == VeinType.SINGLE) {
            // 普通单类矿：主矿 65±10，岩石 35∓10
            mainW = 65 + jitter(r, 10);
            secW = 0;
            rockW = 100 - mainW;
        } else { // POVERTY
            // 贫穷矿脉：主矿 30±10，岩石 70∓10，单矿
            mainW = 30 + jitter(r, 10);
            secW = 0;
            rockW = 100 - mainW;
        }

        // 储量初始值：基准 reservesBase，在 ±reservesSpread 范围内确定性随机浮动。
        // 用同一个 RandomSource 派生，保证同一区块每次进入/重启时「初始值」一致。
        // 注意：这只是初始值；reserves 字段本身是可变变量，后续可由开采逻辑修改。
        int base = Config.veinReservesBase > 0 ? Config.veinReservesBase : 200000;
        int spread = Config.veinReservesSpread > 0 ? Config.veinReservesSpread : 50000;
        int reserves = base + r.nextInt(spread * 2 + 1) - spread;

        return new Vein(type, mainOre, secondaryOre, rock, mainW, secW, rockW, reserves);
    }

    /** 返回 [-mag, +mag] 的整数抖动（含两端）。用于「上下 N」式随机浮动。 */
    private static int jitter(RandomSource r, int mag) {
        return r.nextInt(mag * 2 + 1) - mag;
    }
}
