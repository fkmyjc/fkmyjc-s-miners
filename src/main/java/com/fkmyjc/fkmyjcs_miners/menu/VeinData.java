package com.fkmyjc.fkmyjcs_miners.menu;

import com.fkmyjc.fkmyjcs_miners.vein.Vein;
import com.fkmyjc.fkmyjcs_miners.vein.VeinOreRegistry;
import com.fkmyjc.fkmyjcs_miners.vein.VeinType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 矿脉的「可序列化快照」：在服务端由 {@link Vein} 构造，经 {@link FriendlyByteBuf} 发给客户端 GUI 显示。
 * 只存展示所需的简单数据（物品 id / 维度 / 权重），不依赖任何客户端类。
 */
public final class VeinData {
    public final VeinType type;
    public final ResourceLocation mainOre;       // 主矿物品 id（纯岩石矿脉为 null）
    public final ResourceLocation secondaryOre;  // 次矿物品 id（可为 null）
    public final ResourceLocation rock;          // 岩石物品 id
    public final ResourceLocation dimension;     // 维度 id
    public final int wMain;
    public final int wSec;
    public final int wRock;
    public final int reserves;

    public VeinData(VeinType type, ResourceLocation mainOre,
                    ResourceLocation secondaryOre, ResourceLocation rock,
                    ResourceLocation dimension, int wMain, int wSec, int wRock, int reserves) {
        this.type = type;
        this.mainOre = mainOre;
        this.secondaryOre = secondaryOre;
        this.rock = rock;
        this.dimension = dimension;
        this.wMain = wMain;
        this.wSec = wSec;
        this.wRock = wRock;
        this.reserves = reserves;
    }

    public static VeinData from(Vein vein, ResourceLocation dim) {
        // 应用矿脉矿石展示映射例外（如远古残骸→下界合金碎片）
        ResourceLocation mainOre = (vein.mainOre == null) ? null
                : ForgeRegistries.ITEMS.getKey(VeinOreRegistry.getVeinDisplayItem(vein.mainOre));
        ResourceLocation secondaryOre = vein.secondaryOre == null ? null
                : ForgeRegistries.ITEMS.getKey(VeinOreRegistry.getVeinDisplayItem(vein.secondaryOre));
        return new VeinData(
                vein.type,
                mainOre,
                secondaryOre,
                ForgeRegistries.ITEMS.getKey(vein.rock),
                dim,
                vein.mainWeight(), vein.secondaryWeight(), vein.rockWeight(),
                vein.reserves);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(type);
        writeRL(buf, mainOre);
        writeRL(buf, secondaryOre);
        writeRL(buf, rock);
        writeRL(buf, dimension);
        buf.writeInt(wMain);
        buf.writeInt(wSec);
        buf.writeInt(wRock);
        buf.writeInt(reserves);
    }

    public static VeinData decode(FriendlyByteBuf buf) {
        VeinType type = buf.readEnum(VeinType.class);
        ResourceLocation main = readRL(buf);
        ResourceLocation sec = readRL(buf);
        ResourceLocation rock = readRL(buf);
        ResourceLocation dim = readRL(buf);
        int wMain = buf.readInt();
        int wSec = buf.readInt();
        int wRock = buf.readInt();
        int reserves = buf.readInt();
        return new VeinData(type, main, sec, rock, dim, wMain, wSec, wRock, reserves);
    }

    private static void writeRL(FriendlyByteBuf buf, ResourceLocation rl) {
        buf.writeBoolean(rl != null);
        if (rl != null) buf.writeResourceLocation(rl);
    }

    private static ResourceLocation readRL(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readResourceLocation() : null;
    }
}
