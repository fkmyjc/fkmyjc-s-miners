package com.fkmyjc.fkmyjcs_miners.menu;

import com.fkmyjc.fkmyjcs_miners.ModRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 承载 7×7 矿脉地图快照的容器菜单。本身没有物品槽（纯展示），只把 {@link VeinMapData} 透传给客户端 {@code VeinMapScreen}。
 * <ul>
 *   <li>服务端构造：由 {@code SimpleMenuProvider} 传入 {@link VeinMapData}；</li>
 *   <li>客户端构造：由 {@code MenuType} 工厂经数据包的 extra-data buffer 反序列化得到。</li>
 * </ul>
 */
public class VeinMapMenu extends AbstractContainerMenu {

    public final VeinMapData data;

    public VeinMapMenu(int id, Inventory inv, VeinMapData data) {
        super(ModRegistry.VEIN_MAP_MENU.get(), id);
        this.data = data;
    }

    public VeinMapMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv, VeinMapData.decode(buf));
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;  // 纯展示菜单，无槽位可移动
    }
}
