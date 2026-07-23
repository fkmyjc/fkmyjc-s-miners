package com.fkmyjc.fkmyjcs_miners.command;

import com.fkmyjc.fkmyjcs_miners.chunkdata.ChunkVeinData;
import com.fkmyjc.fkmyjcs_miners.chunkdata.VeinSavedData;
import com.fkmyjc.fkmyjcs_miners.menu.VeinData;
import com.fkmyjc.fkmyjcs_miners.menu.VeinMapData;
import com.fkmyjc.fkmyjcs_miners.menu.VeinMapMenu;
import com.fkmyjc.fkmyjcs_miners.menu.VeinMenu;
import com.fkmyjc.fkmyjcs_miners.network.ModNetwork;
import com.fkmyjc.fkmyjcs_miners.prospect.OverlayTogglePacket;
import com.fkmyjc.fkmyjcs_miners.prospect.ProspectStore;
import com.fkmyjc.fkmyjcs_miners.prospect.VeinSummary;
import com.fkmyjc.fkmyjcs_miners.vein.VeinManager;
import com.fkmyjc.fkmyjcs_miners.vein.VeinOreRegistry;
import com.fkmyjc.fkmyjcs_miners.vein.VeinType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 指令 {@code /vein} 系列：
 * <ul>
 *   <li>{@code /vein} —— 打开当前区块矿脉信息面板（GUI）；</li>
 *   <li>{@code /vein gui} —— 打开 7×7 区块矿脉地图，并顺带探明周边 49 个区块（写入玩家探矿数据）；</li>
 *   <li>{@code /vein display} —— 切换右侧矿脉叠加层显示；</li>
 *   <li>{@code /vein add <int>} —— 增加储量；</li>
 *   <li>{@code /vein set <int>} —— 设置储量；</li>
 *   <li>{@code /vein reset} —— 重新按确定性随机生成并覆盖本区块矿脉；</li>
 *   <li>{@code /vein reset all} —— 按当前代码重新生成全维度所有已记录区块的矿脉；</li>
 *   <li>{@code /vein prob main <int>} —— 设置主矿概率（0~100，其余权重自动补足使总和为 100）；</li>
 *   <li>{@code /vein prob sec <int>} —— 设置次矿概率（0~100，主矿优先保留，岩石补足至 100）；</li>
 *   <li>{@code /vein prob rock <int>} —— 设置岩石概率（0~100，主次矿按比例压缩至剩余预算）；</li>
 *   <li>{@code /vein mode type <int>} —— 设置矿脉类型（0=贫穷 / 1=单 / 2=双）；</li>
 *   <li>{@code /vein mode mainore <id>} —— 设置主矿（物品 id，可写 {@code none} 清除）；</li>
 *   <li>{@code /vein mode second <id>} —— 设置次矿（id，写 {@code none} 清除；设置后若非双矿脉自动升级为双）；</li>
 *   <li>{@code /vein mode rock <id>} —— 设置岩石（id）。</li>
 * </ul>
 *
 * <p>所有修改都落在区块 {@link ChunkVeinData} 并写回存档，同时通过 {@link VeinManager#invalidate}
 * 使缓存失效、重新 {@link ProspectStore#prospect} 同步右侧叠加层。</p>
 */
public final class VeinCommand {

    private VeinCommand() {}

    /** 统一的“无法访问区块矿脉数据”提示（避免散落多处的重复文案）。 */
    private static final String FAIL_ACCESS =
            "无法访问本区块矿脉数据（请离开并重新进入该区块，或重启世界）";

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vein")
                .requires(src -> src.getEntity() instanceof Player)
                // 无子命令：打开面板
                .executes(ctx -> openPanel(ctx.getSource()))
                // display：切换右侧叠加层
                .then(Commands.literal("display")
                        .executes(ctx -> toggleDisplay(ctx.getSource())))
                // gui：打开 7×7 区块矿脉地图
                .then(Commands.literal("gui")
                        .executes(ctx -> openMap(ctx.getSource())))
                // add <int>：增加储量
                .then(Commands.literal("add")
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .executes(ctx -> addReserves(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "amount")))))
                // set <int>：设置储量
                .then(Commands.literal("set")
                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                .executes(ctx -> setReserves(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "value")))))
                // reset：重新生成
                .then(Commands.literal("reset")
                        .executes(ctx -> resetVein(ctx.getSource()))
                        .then(Commands.literal("all")
                                .executes(ctx -> resetAll(ctx.getSource()))))
                // prob 子命令组：直接设置各层概率（权重），三者总和恒为 100
                .then(Commands.literal("prob")
                        .then(Commands.literal("main")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> prob(ctx.getSource(), "main",
                                                IntegerArgumentType.getInteger(ctx, "value")))))
                        .then(Commands.literal("sec")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> prob(ctx.getSource(), "sec",
                                                IntegerArgumentType.getInteger(ctx, "value")))))
                        .then(Commands.literal("rock")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> prob(ctx.getSource(), "rock",
                                                IntegerArgumentType.getInteger(ctx, "value"))))))
                // mode 子命令组
                .then(Commands.literal("mode")
                        .then(Commands.literal("type")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 2))
                                        .executes(ctx -> modeType(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "value")))))
                        .then(Commands.literal("mainore")
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .executes(ctx -> modeOre(ctx.getSource(), "main",
                                                ResourceLocationArgument.getId(ctx, "id")))))
                        .then(Commands.literal("second")
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .executes(ctx -> modeOre(ctx.getSource(), "second",
                                                ResourceLocationArgument.getId(ctx, "id")))))
                        .then(Commands.literal("rock")
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .executes(ctx -> modeOre(ctx.getSource(), "rock",
                                                ResourceLocationArgument.getId(ctx, "id")))))
                )
        );
    }

    /* ===================== 面板 ===================== */

    private static int openPanel(CommandSourceStack src) throws CommandSyntaxException {
        openPanel(src.getPlayerOrException());
        return 1;
    }

    private static int openMap(CommandSourceStack src) throws CommandSyntaxException {
        openMap(src.getPlayerOrException());
        return 1;
    }

    /**
     * 打开当前区块矿脉信息面板（GUI）。供 {@code /vein} 指令与探矿仪 shift+右键共用。
     * 直接读取本区块矿脉（{@link VeinManager#getVein}），不依赖是否已探矿。
     */
    public static void openPanel(ServerPlayer player) {
        Level level = player.level();
        ChunkPos cp = new ChunkPos(player.blockPosition());

        VeinData data = VeinData.from(
                VeinManager.getVein(level, cp),
                level.dimension().location());

        NetworkHooks.openScreen(player,
                new MenuProvider() {
                    @Override
                    public Component getDisplayName() {
                        return Component.literal("矿脉信息");
                    }

                    @Override
                    public AbstractContainerMenu createMenu(int id,
                                                           net.minecraft.world.entity.player.Inventory inv,
                                                           net.minecraft.world.entity.player.Player player) {
                        return new VeinMenu(id, inv, data);
                    }
                },
                buf -> data.encode(buf));
    }

    /**
     * 打开 7×7 区块矿脉地图（GUI）。以玩家当前区块为中心，向四周各 ±3 共 49 个区块，
     * 逐个读取确定性矿脉（{@link VeinManager#getVein}）并序列化为 {@link VeinMapData}。
     * 默认 {@link #openMap(ServerPlayer)} 会顺带为 7×7 内每个区块执行一次免费探矿
     * （{@link ProspectStore#prospect}，等价于右键探矿仪的数据采集，写入玩家探矿数据并同步到客户端叠加层，
     * 但不在聊天栏逐条播报以免刷屏），供 {@code /vein gui} 指令使用。
     * 物品（如高级探矿仪）应按能量逐格计费探矿，故传 {@code autoProspect=false} 跳过免费探矿，
     * 自行在调用本方法后按能量逐格 {@link ProspectStore#prospect}；无论是否探矿，地图本身显示不受影响。
     */
    public static void openMap(ServerPlayer player) {
        openMap(player, true);
    }

    /**
     * 打开 7×7 区块矿脉地图（GUI）。
     *
     * @param autoProspect 是否在读取地图数据的同时，顺带为 7×7 内每个区块执行一次探矿动作。
     *                     {@code true}=指令免费探明周边 49 区块；{@code false}=由调用方自行按能量计费探矿。
     */
    public static void openMap(ServerPlayer player, boolean autoProspect) {
        Level level = player.level();
        ChunkPos center = new ChunkPos(player.blockPosition());

        VeinSummary[] cells = new VeinSummary[VeinMapData.CELLS];
        int idx = 0;
        for (int dz = -VeinMapData.RADIUS; dz <= VeinMapData.RADIUS; dz++) {
            for (int dx = -VeinMapData.RADIUS; dx <= VeinMapData.RADIUS; dx++) {
                ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                cells[idx++] = VeinSummary.from(VeinManager.getVein(level, cp));
                if (autoProspect) {
                    // 顺带对 7×7 内每个区块执行一次探矿（数据写入 + 客户端同步），
                    // 即右键探矿仪的核心效果；announce=false 避免 49 条聊天播报刷屏。
                    ProspectStore.prospect(player, cp, false);
                }
            }
        }
        VeinMapData data = new VeinMapData(center.x, center.z, cells);
        openMap(player, data);
    }

    /**
     * 用已构造好的 {@link VeinMapData} 打开 7×7 矿脉地图 GUI。
     * 供高级探矿仪按能量自定义可见格子（能量不足格留空）使用。
     */
    public static void openMap(ServerPlayer player, VeinMapData data) {
        NetworkHooks.openScreen(player,
                new MenuProvider() {
                    @Override
                    public Component getDisplayName() {
                        return Component.literal("矿脉地图");
                    }

                    @Override
                    public AbstractContainerMenu createMenu(int id,
                                                           net.minecraft.world.entity.player.Inventory inv,
                                                           net.minecraft.world.entity.player.Player player) {
                        return new VeinMapMenu(id, inv, data);
                    }
                },
                buf -> data.encode(buf));
    }

    /* ===================== 叠加层切换 ===================== */

    private static int toggleDisplay(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ModNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new OverlayTogglePacket());
        src.sendSuccess(() -> Component.literal("已切换右侧矿脉叠加层显示"), false);
        return 1;
    }

    /* ===================== 储量 ===================== */

    private static int addReserves(CommandSourceStack src, int amount) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ChunkVeinData data = editable(player);
        if (data == null) return fail(src, FAIL_ACCESS);
        int before = data.getReserves();
        data.addReserves(amount);
        commit(player);
        src.sendSuccess(() -> Component.literal(
                String.format("储量 +%d → %s", amount, String.format("%,d", data.getReserves()))), false);
        return 1;
    }

    private static int setReserves(CommandSourceStack src, int value) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ChunkVeinData data = editable(player);
        if (data == null) return fail(src, FAIL_ACCESS);
        data.setReserves(value);
        commit(player);
        src.sendSuccess(() -> Component.literal(
                "储量 → " + String.format("%,d", data.getReserves())), false);
        return 1;
    }

    /* ===================== 重新生成 ===================== */

    private static int resetVein(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ChunkVeinData data = editable(player);
        if (data == null) return fail(src, FAIL_ACCESS);
        Level level = player.level();
        ChunkPos cp = new ChunkPos(player.blockPosition());
        // 重新生成（非还原）：重新随机生成盐，使本区块获得一个全新的矿脉
        // （主矿/次矿/岩石/概率/储量全部重抽），与旧矿脉不保证相同。
        long newSalt = reseed(player);
        data.setGenSalt(newSalt);
        data.initFromVein(VeinManager.generateFor(level, cp, newSalt));
        commit(player);
        src.sendSuccess(() -> Component.literal("已重新生成本区块矿脉（全新矿石组合 / 概率 / 储量）"), false);
        return 1;
    }

    /** 重新随机一个生成盐：服务端随机源 + 纳秒时间，确保每次 reset 都得到不同的矿脉。 */
    private static long reseed(ServerPlayer player) {
        return player.serverLevel().getRandom().nextLong() ^ System.nanoTime();
    }

    /* ===================== 全维度重新生成 ===================== */

    /**
     * {@code /vein reset all}：按当前代码重新生成<b>所有已记录</b>区块的矿脉（遍历服务端全部维度）。
     * 每个区块都被赋予一个新的随机生成盐，得到一组全新的矿石组合 / 概率 / 储量，
     * 与单区块 {@link #resetVein} 行为一致，只是作用范围为全维度。
     * 尚未访问过的区块（无存档记录）会在各自首次进入时按当前代码确定性生成，无需在此处理。
     */
    private static int resetAll(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        MinecraftServer server = src.getServer();
        int total = 0;
        for (ServerLevel sl : server.getAllLevels()) {
            VeinSavedData sd = VeinSavedData.get(sl);
            int[] count = {0};
            sd.forEach((cp, data) -> {
                long newSalt = sl.getRandom().nextLong() ^ System.nanoTime();
                data.setGenSalt(newSalt);
                data.initFromVein(VeinManager.generateFor(sl, cp, newSalt));
                count[0]++;
            });
            if (count[0] > 0) sd.setDirty();
            total += count[0];
        }
        VeinManager.clearCache();
        // 重新同步玩家当前区块的叠加层
        ChunkPos cp = new ChunkPos(player.blockPosition());
        ProspectStore.prospect(player, cp, false);
        final int regenerated = total;
        src.sendSuccess(() -> Component.literal(
                String.format("已按当前代码重新生成 %d 个区块的矿脉（全维度）", regenerated)), false);
        return 1;
    }

    /* ===================== mode：概率 / 类型 / 矿石 ===================== */

    private static int prob(CommandSourceStack src, String which, int value) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ChunkVeinData data = editable(player);
        if (data == null) return fail(src, FAIL_ACCESS);
        value = Math.max(0, Math.min(100, value));   // 严格限幅 [0,100]

        int mainW = data.getMainWeight();
        int secW = data.getSecondaryWeight();
        int rockW = data.getRockWeight();

        switch (which) {
            case "main" -> {
                mainW = value;
                if (mainW + secW > 100) secW = 100 - mainW;   // 主矿+次矿超 100 时压缩次矿
                rockW = 100 - mainW - secW;                   // 岩石补足，三者恒为 100
            }
            case "sec" -> {
                secW = value;
                if (mainW + secW > 100) mainW = 100 - secW;   // 次矿+主矿超 100 时压缩主矿
                rockW = 100 - mainW - secW;
            }
            case "rock" -> {
                rockW = value;
                int budget = 100 - rockW;                     // 主+次可用的剩余预算
                int sum = mainW + secW;
                if (sum <= 0) {                               // 主次矿均为 0：对半分预算
                    mainW = budget / 2;
                    secW = budget - mainW;
                } else if (sum > budget) {                    // 超出预算：按比例压缩，保持主次矿相对比例
                    mainW = Math.max(0, Math.min(budget, Math.round((float) mainW * budget / sum)));
                    secW = budget - mainW;
                }
                // sum <= budget 时保持 main/sec 原值不变
            }
        }

        data.setMainWeight(mainW);
        data.setSecondaryWeight(secW);
        data.setRockWeight(rockW);
        commit(player);
        final String msg = String.format(
                "主矿 → %d%% / 次矿 → %d%% / 岩石 → %d%%", mainW, secW, rockW);
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int modeType(CommandSourceStack src, int code) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ChunkVeinData data = editable(player);
        if (data == null) return fail(src, FAIL_ACCESS);
        VeinType t = (code == 0) ? VeinType.POVERTY : (code == 2) ? VeinType.DOUBLE : VeinType.SINGLE;
        int mainW = data.getMainWeight();

        if (t == VeinType.DOUBLE) {
            int secW = data.getSecondaryWeight();
            if (secW <= 0) {
                secW = Math.min(20, Math.max(0, 100 - mainW));
                data.setSecondaryWeight(secW);
            }
            // 次矿物品缺失时，从矿石池挑一个不同于主矿的补上
            if (data.getSecondaryOreId() == null) {
                ResourceLocation dim = player.level().dimension().location();
                List<Item> pool = VeinOreRegistry.getOrePool(dim);
                Item mainItem = (data.getMainOreId() != null)
                        ? ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(data.getMainOreId())) : null;
                Item pick = pool.stream().filter(i -> i != mainItem).findAny().orElse(null);
                if (pick != null) data.setSecondaryOre(ForgeRegistries.ITEMS.getKey(pick));
            }
            int rockW = 100 - mainW - secW;
            if (rockW < 0) {                 // 主矿过高时压缩次矿
                secW = Math.max(0, 100 - mainW);
                rockW = 100 - mainW - secW;
                data.setSecondaryWeight(secW);
            }
            data.setRockWeight(rockW);
        } else {
            // 单 / 贫穷：无次矿
            data.setSecondaryOre(null);
            data.setSecondaryWeight(0);
            data.setRockWeight(100 - mainW);
        }
        data.setType(code);
        commit(player);
        src.sendSuccess(() -> Component.literal("矿脉类型 → " + typeName(t)), false);
        return 1;
    }

    private static int modeOre(CommandSourceStack src, String which, ResourceLocation id)
            throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        boolean clear = id.getPath().equals("none");
        Item ore = null;
        if (!clear) {
            ore = VeinOreRegistry.normalizeOreItem(id.toString());
            if (ore == null) {
                Item direct = ForgeRegistries.ITEMS.getValue(id);
                if (direct != null && direct != Items.AIR) ore = direct;
            }
            if (ore == null) return fail(src, "无效的矿石/物品 id: " + id);
        }
        ResourceLocation oreId = (ore == null) ? null : ForgeRegistries.ITEMS.getKey(ore);

        ChunkVeinData data = editable(player);
        if (data == null) return fail(src, FAIL_ACCESS);

        switch (which) {
            case "main" -> data.setMainOre(oreId);
            case "second" -> {
                data.setSecondaryOre(oreId);
                if (oreId != null && data.getTypeCode() != 2) {
                    // 设置次矿但当前非双矿脉：自动升级为双，并给出默认次矿权重便于显示
                    data.setType(2);
                    if (data.getSecondaryWeight() <= 0) {
                        int secW = Math.min(20, Math.max(0, 100 - data.getMainWeight()));
                        data.setSecondaryWeight(secW);
                        data.setRockWeight(100 - data.getMainWeight() - secW);
                    }
                }
            }
            case "rock" -> data.setRock(oreId);
        }
        commit(player);
        String label = switch (which) {
            case "main" -> "主矿";
            case "second" -> "次矿";
            default -> "岩石";
        };
        src.sendSuccess(() -> Component.literal(label + " → " + (oreId == null ? "无" : oreId)), false);
        return 1;
    }

    /* ===================== 工具 ===================== */

    /** 取当前区块的可编辑矿脉数据；若尚未初始化则先触发确定性生成写回。 */
    private static ChunkVeinData editable(ServerPlayer player) {
        Level level = player.level();
        if (!(level instanceof ServerLevel sl)) return null;
        ChunkPos cp = new ChunkPos(player.blockPosition());
        VeinSavedData sd = VeinSavedData.get(sl);
        ChunkVeinData data = sd.getOrCreate(cp);
        if (!data.isInitialized()) {
            VeinManager.getVein(level, cp);   // 触发确定性生成并写回 SavedData
        }
        return data;
    }

    /** 落盘 + 使缓存失效 + 重新同步右侧叠加层。 */
    private static void commit(ServerPlayer player) {
        Level level = player.level();
        ChunkPos cp = new ChunkPos(player.blockPosition());
        if (level instanceof ServerLevel sl) {
            VeinSavedData.get(sl).setDirty();
        }
        VeinManager.invalidate(level, cp);
        ProspectStore.prospect(player, cp, false); // 指令修正后的重同步不发聊天结果
    }

    private static int fail(CommandSourceStack src, String msg) {
        src.sendFailure(Component.literal(msg));
        return 0;
    }

    private static String typeName(VeinType t) {
        return switch (t) {
            case POVERTY -> "贫穷 (0)";
            case SINGLE -> "单矿 (1)";
            case DOUBLE -> "双矿 (2)";
        };
    }
}
