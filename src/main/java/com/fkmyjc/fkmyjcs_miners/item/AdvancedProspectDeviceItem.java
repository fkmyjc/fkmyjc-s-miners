package com.fkmyjc.fkmyjcs_miners.item;

import com.fkmyjc.fkmyjcs_miners.Config;
import com.fkmyjc.fkmyjcs_miners.command.VeinCommand;
import com.fkmyjc.fkmyjcs_miners.menu.VeinMapData;
import com.fkmyjc.fkmyjcs_miners.prospect.ProspectStore;
import com.fkmyjc.fkmyjcs_miners.prospect.VeinSummary;
import com.fkmyjc.fkmyjcs_miners.vein.VeinManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 高级探矿仪：大容量 FE/RF 设备（默认 100M FE 缓存）。
 * 右键打开 7×7 区块矿脉地图 GUI，并对 7×7 内<b>尚未探明</b>的区块逐一执行探矿动作，
 * 能耗为「打开 GUI 固定 {@code advancedEnergyPerOpen} FE + 每探明一个未知区块 {@code advancedEnergyPerChunk} FE」
 * （动态消耗，随未知区块数变化）。
 * 与基础探矿仪不同：此处探矿不再免费，而是由本物品按能量逐格计费；
 * 能量不足以扫描某未知区块时，该格在 7×7 地图中显示为空白（"?"）。
 * <p>
 * 能量持久化在物品 NBT 的 {@link #ENERGY_KEY} 键（与 {@link StackEnergyStorage} 共用），
 * 因而在物品栏以近似原版「耐久条」显示剩余电量（绿→红），并在详情栏显示「电量/上限」。
 */
public class AdvancedProspectDeviceItem extends Item {

    /** 能量持久化键名，必须与 {@link StackEnergyStorage} 中使用的键保持一致。 */
    private static final String ENERGY_KEY = "energy";

    public AdvancedProspectDeviceItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack); // 实际逻辑在服务端
        ServerPlayer sp = (ServerPlayer) player;

        // 首次使用前 NBT 尚未写入能量，视为满电
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.contains(ENERGY_KEY)) tag.putInt(ENERGY_KEY, Config.advancedProspectEnergyCapacity);
        int energy = tag.getInt(ENERGY_KEY);

        // 打开 GUI 的固定开销
        int openCost = Config.advancedProspectEnergyPerOpen;
        if (energy < openCost) {
            player.displayClientMessage(
                    Component.translatable("message.fkmyjcs_miners.advanced_prospect_device.no_energy"), true);
            return InteractionResultHolder.consume(stack);
        }
        // 扣除打开 GUI 的费用
        energy -= openCost;

        // 按能量逐格决定 7×7 中哪些区块可见；能量不足则留空
        Level lvl = sp.level();
        ChunkPos center = sp.chunkPosition();
        VeinSummary[] cells = new VeinSummary[VeinMapData.CELLS];
        int idx = 0;
        int perChunk = Config.advancedProspectEnergyPerChunk;
        boolean anyBlank = false;
        for (int dz = -VeinMapData.RADIUS; dz <= VeinMapData.RADIUS; dz++) {
            for (int dx = -VeinMapData.RADIUS; dx <= VeinMapData.RADIUS; dx++) {
                ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                boolean already = ProspectStore.hasChunk(sp, cp);
                if (already || energy >= perChunk) {
                    if (!already) {
                        energy -= perChunk;
                        ProspectStore.prospect(sp, cp, false);
                    }
                    cells[idx++] = VeinSummary.from(VeinManager.getVein(lvl, cp));
                } else {
                    cells[idx++] = null; // 能量不足，该格显示空白
                    anyBlank = true;
                }
            }
        }
        tag.putInt(ENERGY_KEY, energy);

        if (anyBlank) {
            player.displayClientMessage(
                    Component.translatable("message.fkmyjcs_miners.advanced_prospect_device.energy_low")
                            .withStyle(ChatFormatting.GOLD), true);
        }

        VeinMapData data = new VeinMapData(center.x, center.z, cells);
        VeinCommand.openMap(sp, data);
        return InteractionResultHolder.consume(stack);
    }

    /** 读取用于显示的能量值（NBT 未写入时视为满电）。 */
    private int getDisplayEnergy(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ENERGY_KEY)) return Config.advancedProspectEnergyCapacity;
        return tag.getInt(ENERGY_KEY);
    }

    // === 物品栏电力条（外观近似原版耐久条）===

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        float ratio = (float) getDisplayEnergy(stack) / (float) Config.advancedProspectEnergyCapacity;
        return Math.min(13, Math.round(13.0F * ratio));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        float ratio = (float) getDisplayEnergy(stack) / (float) Config.advancedProspectEnergyCapacity;
        // 满电绿 → 低电红，与原版耐久条配色一致
        return Mth.hsvToRgb(Math.max(0.0F, ratio) / 3.0F, 1.0F, 1.0F);
    }

    // === 物品详情栏显示电量/上限 ===

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int energy = getDisplayEnergy(stack);
        int capacity = Config.advancedProspectEnergyCapacity;
        tooltip.add(Component.translatable("tooltip.fkmyjcs_miners.advanced_prospect_device.energy", energy, capacity)
                .withStyle(ChatFormatting.GRAY));
    }

    /** 为物品挂上 FE/RF 能量能力，能量持久化在物品 NBT 中（可被外部充能）。 */
    @Nullable
    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new ICapabilityProvider() {
            private final LazyOptional<IEnergyStorage> energy =
                    LazyOptional.of(() -> new StackEnergyStorage(stack, Config.advancedProspectEnergyCapacity));

            @NotNull
            @Override
            public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
                return cap == ForgeCapabilities.ENERGY ? energy.cast() : LazyOptional.empty();
            }
        };
    }
}
