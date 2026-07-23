package com.fkmyjc.fkmyjcs_miners.item;

import com.fkmyjc.fkmyjcs_miners.Config;
import com.fkmyjc.fkmyjcs_miners.command.VeinCommand;
import com.fkmyjc.fkmyjcs_miners.prospect.ProspectStore;
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
 * 探矿仪：使用 FE/RF，每次探矿消耗 {@link Config#prospectEnergyPerUse} 能量（来自自身能量容量），
 * 效果与探矿杖一致。带 {@code #fkmyjcs_miners:vein_display} 标签。
 * <p>
 * 右键探矿：把本区块矿脉结果发到聊天栏。
 * 按住 {@code Shift} 右键：同样执行探矿（刷新本区块数据），但<b>不</b>在聊天栏发结果，
 * 而是直接打开 {@code /vein} 矿脉信息面板（GUI）。
 * <p>
 * 能量持久化在物品 NBT 的 {@link #ENERGY_KEY} 键中（与 {@link StackEnergyStorage} 共用），
 * 因而在物品栏里以近似原版「耐久条」的形式显示剩余电量（绿→红），并在物品详情栏显示「电量/上限」。
 */
public class ProspectDeviceItem extends Item {

    /** 能量持久化键名，必须与 {@link StackEnergyStorage} 中使用的键保持一致。 */
    private static final String ENERGY_KEY = "energy";

    public ProspectDeviceItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack); // 实际逻辑在服务端
        // 首次使用前 NBT 尚未写入能量，视为满电
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.contains(ENERGY_KEY)) tag.putInt(ENERGY_KEY, Config.prospectEnergyCapacity);
        int energy = tag.getInt(ENERGY_KEY);
        if (energy >= Config.prospectEnergyPerUse) {
            tag.putInt(ENERGY_KEY, energy - Config.prospectEnergyPerUse);
            boolean shift = player.isShiftKeyDown();
            if (shift) {
                // shift+右键：探矿但不发聊天结果，并打开 /vein 矿脉信息面板（GUI）
                ProspectStore.prospect((ServerPlayer) player, player.chunkPosition(), false);
                VeinCommand.openPanel((ServerPlayer) player);
            } else {
                // 普通右键：探矿并把结果发到聊天栏
                ProspectStore.prospect((ServerPlayer) player, player.chunkPosition());
            }
        } else {
            player.displayClientMessage(
                    Component.translatable("message.fkmyjcs_miners.prospect.no_energy"), true);
        }
        return InteractionResultHolder.consume(stack);
    }

    /** 读取用于显示的能量值（NBT 未写入时视为满电）。 */
    private int getDisplayEnergy(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ENERGY_KEY)) return Config.prospectEnergyCapacity;
        return tag.getInt(ENERGY_KEY);
    }

    // === 物品栏电力条（外观近似原版耐久条）===

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        float ratio = (float) getDisplayEnergy(stack) / (float) Config.prospectEnergyCapacity;
        return Math.min(13, Math.round(13.0F * ratio));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        float ratio = (float) getDisplayEnergy(stack) / (float) Config.prospectEnergyCapacity;
        // 满电绿 → 低电红，与原版耐久条配色一致
        return Mth.hsvToRgb(Math.max(0.0F, ratio) / 3.0F, 1.0F, 1.0F);
    }

    // === 物品详情栏显示电量/上限 ===

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int energy = getDisplayEnergy(stack);
        int capacity = Config.prospectEnergyCapacity;
        tooltip.add(Component.translatable("tooltip.fkmyjcs_miners.prospect_device.energy", energy, capacity)
                .withStyle(ChatFormatting.GRAY));
    }

    /** 为物品挂上 FE/RF 能量能力，能量持久化在物品 NBT 中。 */
    @Nullable
    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new ICapabilityProvider() {
            private final LazyOptional<IEnergyStorage> energy =
                    LazyOptional.of(() -> new StackEnergyStorage(stack, Config.prospectEnergyCapacity));

            @NotNull
            @Override
            public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
                return cap == ForgeCapabilities.ENERGY ? energy.cast() : LazyOptional.empty();
            }
        };
    }
}
