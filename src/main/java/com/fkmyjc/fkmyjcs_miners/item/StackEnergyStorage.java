package com.fkmyjc.fkmyjcs_miners.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.energy.IEnergyStorage;

/**
 * 把 FE/RF 能量持久化进持有它的 {@link ItemStack} 的 NBT（键 "energy"）。
 * 这样能量随物品一起存档、转移，且每次从 NBT 重建后仍能读到上次剩余电量。
 */
public class StackEnergyStorage implements IEnergyStorage {

    private final ItemStack stack;
    private final int capacity;

    public StackEnergyStorage(ItemStack stack, int capacity) {
        this.stack = stack;
        this.capacity = capacity;
    }

    private int getEnergy() {
        return stack.getOrCreateTag().getInt("energy");
    }

    private void setEnergy(int v) {
        stack.getOrCreateTag().putInt("energy", v);
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        int cur = getEnergy();
        int room = capacity - cur;
        int add = Math.min(room, maxReceive);
        if (!simulate && add > 0) setEnergy(cur + add);
        return add;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        int cur = getEnergy();
        int take = Math.min(cur, maxExtract);
        if (!simulate && take > 0) setEnergy(cur - take);
        return take;
    }

    @Override
    public int getEnergyStored() {
        return getEnergy();
    }

    @Override
    public int getMaxEnergyStored() {
        return capacity;
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return true;
    }
}
