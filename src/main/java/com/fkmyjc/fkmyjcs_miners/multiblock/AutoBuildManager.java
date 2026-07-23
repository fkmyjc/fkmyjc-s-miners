package com.fkmyjc.fkmyjcs_miners.multiblock;

import com.fkmyjc.fkmyjcs_miners.Config;
import com.fkmyjc.fkmyjcs_miners.Fkmyjcs_miners;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 多方块结构的「自动建造」管理器。
 *
 * <p>玩家用 config 指定的触发物品（默认木棍）右键控制器方块时，调用 {@link #startBuild} 新建一个
 * 建造任务。任务由服务端 tick 推进（不能在普通线程里改世界），按玩家游戏模式区分：</p>
 * <ul>
 *   <li><b>创造模式</b>：在 config 设定的 tick 数内（默认 10 tick = 0.5 秒）均分放置全部方块。</li>
 *   <li><b>生存模式</b>：基础速度按 config 的「每秒方块数」（默认 4，即 1 秒 4 个）平滑放置；
 *       当结构方块总数超过 {@value #BIG_STRUCTURE_THRESHOLD} 时自动加速，使总耗时趋近于
 *       {@value #TARGET_SECONDS} 秒 × 生存时间倍率（config，默认 1 → 约 60 秒）。
 *       每个方块都要从玩家背包扣除对应材料，背包不足则该方块跳过（统计为「材料不足」）。</li>
 * </ul>
 *
 * <p>放置规则：<b>不替换已有的固体方块</b>（保留玩家手摆的结构），<b>流体（水/岩浆，无论源或非源）则替换</b>，
 * 遇到空气直接放置；<b>无视其中的实体</b>（直接放方块，不处理实体）。控制器自身不在待放置列表中。</p>
 *
 * <p>事件监听由主类在构造时通过 {@code MinecraftForge.EVENT_BUS.register(new AutoBuildManager())} 注册，
 * 避免依赖注解扫描的时机问题。</p>
 */
public class AutoBuildManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<BuildTask> tasks = new ArrayList<>();
    private static final Set<BlockPos> activeControllers = new HashSet<>();

    /** 结构方块总数超过此值时，生存模式自动加速使总耗时趋近于 TARGET_SECONDS。 */
    private static final int BIG_STRUCTURE_THRESHOLD = 240;
    /** 大结构在生存模式下趋近的目标总耗时（秒），再乘以生存时间倍率。 */
    private static final double TARGET_SECONDS = 60.0;

    public AutoBuildManager() {
    }

    /**
     * 触发一次自动建造。返回是否成功启动（失败原因会以消息告知玩家）。
     */
    public static boolean startBuild(Player player, Level level, BlockPos controllerPos,
                                     Direction facing, MultiBlockPattern pattern) {
        if (level.isClientSide()) return false;
        if (!(level instanceof ServerLevel serverLevel)) return false;

        if (activeControllers.contains(controllerPos)) {
            player.sendSystemMessage(Component.translatable("message.fkmyjcs_miners.autobuild.already"));
            return false;
        }

        List<MultiBlockPattern.BlockPlacement> placements = pattern.computeWorldPlacements(controllerPos, facing);
        if (placements.isEmpty()) {
            LOGGER.warn("AutoBuild: pattern for {} produced no placements", controllerPos);
            return false;
        }

        // 以随机顺序放置（每次建造都重新洗牌），避免按固定顺序逐层铺开
        Collections.shuffle(placements, new Random());

        boolean creative = player.isCreative();
        tasks.add(new BuildTask(serverLevel, player, controllerPos, placements, creative));
        activeControllers.add(controllerPos);
        player.sendSystemMessage(Component.translatable("message.fkmyjcs_miners.autobuild.started", placements.size()));
        return true;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;  // 每 tick 只推进一次
        if (tasks.isEmpty()) return;

        Iterator<BuildTask> it = tasks.iterator();
        while (it.hasNext()) {
            BuildTask task = it.next();
            // 玩家离线 / 切换维度：取消该任务
            if (!task.player.isAlive() || task.player.level() != task.level) {
                activeControllers.remove(task.controllerPos);
                it.remove();
                continue;
            }
            if (task.tick()) {
                activeControllers.remove(task.controllerPos);
                it.remove();
            }
        }
    }

    /** 单个建造任务的推进状态机。 */
    static class BuildTask {
        final ServerLevel level;
        final Player player;
        final BlockPos controllerPos;
        final List<MultiBlockPattern.BlockPlacement> placements;
        final boolean creative;

        int index = 0;
        int ticksElapsed = 0;
        float survivalAccumulator = 0f;
        int missing = 0;            // 因材料不足而跳过的方块数

        BuildTask(ServerLevel level, Player player, BlockPos controllerPos,
                  List<MultiBlockPattern.BlockPlacement> placements, boolean creative) {
            this.level = level;
            this.player = player;
            this.controllerPos = controllerPos;
            this.placements = placements;
            this.creative = creative;
        }

        /** @return true 表示建造完成（无论是否有方块缺失）。 */
        boolean tick() {
            ticksElapsed++;
            int budget = creative
                    ? Math.max(1, (int) Math.ceil((double) placements.size() / Math.max(1, Config.autoBuildCreativeTicks)))
                    : budgetSurvival();
            for (int i = 0; i < budget && index < placements.size(); i++) {
                placeOne();
            }
            if (index >= placements.size()) {
                report();
                return true;
            }
            return false;
        }

        /** 生存模式：按 config 的每秒方块数，用浮点累加换算本 tick 可放置数量。
         *  当结构方块总数超过 {@link #BIG_STRUCTURE_THRESHOLD} 时，自动加速使总耗时趋近于
         *  {@link #TARGET_SECONDS} 秒 × 生存时间倍率。 */
        int budgetSurvival() {
            int total = placements.size();
            double perSecond;
            if (total > BIG_STRUCTURE_THRESHOLD) {
                // 加速：让总耗时 ≈ 60s × 倍率 → 速率 = 总块数 / (目标秒数)
                double targetSeconds = TARGET_SECONDS * Math.max(0.01, Config.autoBuildSurvivalTimeMultiplier);
                perSecond = (double) total / Math.max(0.05, targetSeconds);
            } else {
                // 小结构：使用基础速度（默认 1 秒 4 个）
                perSecond = Config.autoBuildSurvivalRate;
            }
            survivalAccumulator += perSecond / 20.0;   // 每 tick = 每秒速率 / 20
            int n = (int) survivalAccumulator;
            survivalAccumulator -= n;
            return n;
        }

        void placeOne() {
            MultiBlockPattern.BlockPlacement p = placements.get(index++);
            if (p.worldPos().equals(controllerPos)) return;   // 控制器本身，保险跳过

            BlockState cur = level.getBlockState(p.worldPos());
            // 不替换方块：当前是固体（非空气且无流体）则保留，跳过（不消耗材料）
            if (!cur.isAir() && cur.getFluidState().isEmpty()) return;
            // 当前是空气或流体 → 都要放置（流体被替换）

            if (!creative) {
                if (p.cost().isEmpty()) {       // 该方块没有对应物品（罕见），无法在生存模式提供
                    missing++;
                    return;
                }
                // 从玩家背包精确扣除所需数量。
                // 注意：Inventory.removeItem(ItemStack) 会一次性移除整个匹配物品堆，
                // 而不是只移除 cost 指定的数量，因此不能用于精确扣除；这里改为逐格 shrink。
                // 对于 tag 映射（source 以 '#' 开头），接受该 tag 下任意方块对应的物品作为材料。
                if (!consumeItems(player, p)) {
                    missing++;
                    return;
                }
            }

            level.setBlock(p.worldPos(), p.state(), 3);  // UPDATE_NEIGHBORS | UPDATE_CLIENTS
            // 若该候选要求写入 NBT，则放置后把 NBT 载入其方块实体
            // （大部分带 NBT 的候选默认 writeNbt=false，仅用于校验匹配，不覆盖世界里已有的数据）
            if (p.writeNbt() && p.nbt() != null) {
                BlockEntity be = level.getBlockEntity(p.worldPos());
                if (be != null) {
                    be.load(p.nbt());
                    be.setChanged();
                    level.sendBlockUpdated(p.worldPos(), p.state(), p.state(), 3);
                }
            }
        }

        /**
         * 从玩家背包（主背包 + 副手）中精确扣除一个方块所需的材料（数量 = p.cost().getCount()）。
         * <ul>
         *   <li>若 source 为 tag 映射（'#' 前缀）：接受背包中「对应方块属于该 tag」的任意物品。</li>
         *   <li>若为具体方块：要求物品与 p.cost() 完全一致（含 NBT）。</li>
         * </ul>
         * 逐槽 shrink 直到扣够或遍历完，返回是否成功扣够（不足则返回 false，不放置该方块）。
         */
        boolean consumeItems(Player player, MultiBlockPattern.BlockPlacement p) {
            int needed = p.cost().getCount();
            if (needed <= 0) return true;

            // 主背包
            for (ItemStack slot : player.getInventory().items) {
                if (needed <= 0) break;
                if (slotMatches(slot, p)) {
                    int take = Math.min(needed, slot.getCount());
                    slot.shrink(take);
                    needed -= take;
                }
            }
            // 副手
            if (needed > 0) {
                ItemStack off = player.getInventory().offhand.get(0);
                if (slotMatches(off, p)) {
                    int take = Math.min(needed, off.getCount());
                    off.shrink(take);
                    needed -= take;
                }
            }
            return needed == 0;
        }

        /** 判断某个背包槽位中的物品是否满足该放置项的材料需求。 */
        boolean slotMatches(ItemStack slot, MultiBlockPattern.BlockPlacement p) {
            if (slot.isEmpty()) return false;
            String source = p.source();
            if (source.startsWith("#")) {
                Block block = Block.byItem(slot.getItem());
                if (block == Blocks.AIR) return false;
                ResourceLocation tagId = ResourceLocation.tryParse(source.substring(1));
                if (tagId == null) return false;
                TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
                return BuiltInRegistries.BLOCK.getTag(tag)
                        .map(holders -> holders.stream().anyMatch(h -> h.value() == block))
                        .orElse(false);
            }
            return ItemStack.isSameItemSameTags(slot, p.cost());
        }

        void report() {
            if (missing > 0) {
                player.sendSystemMessage(Component.translatable("message.fkmyjcs_miners.autobuild.incomplete", missing));
            } else {
                player.sendSystemMessage(Component.translatable("message.fkmyjcs_miners.autobuild.done"));
            }
        }
    }
}
