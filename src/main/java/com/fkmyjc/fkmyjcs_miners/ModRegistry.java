package com.fkmyjc.fkmyjcs_miners;

import com.fkmyjc.fkmyjcs_miners.block.BigTestMachineBlock;
import com.fkmyjc.fkmyjcs_miners.block.GiantTestMachineBlock;
import com.fkmyjc.fkmyjcs_miners.block.TestMachineBlock;
import com.fkmyjc.fkmyjcs_miners.item.AdvancedProspectDeviceItem;
import com.fkmyjc.fkmyjcs_miners.item.ProspectDeviceItem;
import com.fkmyjc.fkmyjcs_miners.item.ProspectWandItem;
import com.fkmyjc.fkmyjcs_miners.menu.VeinMapMenu;
import com.fkmyjc.fkmyjcs_miners.menu.VeinMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Central registry for this mod's registered objects.
 * Registers: vein info GUI menu type, test multiblock machine block + item.
 * Call {@link #register(IEventBus)} from {@link Fkmyjcs_miners} constructor.
 */
public final class ModRegistry {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Fkmyjcs_miners.MODID);

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Fkmyjcs_miners.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Fkmyjcs_miners.MODID);

    /* ---- Vein Info GUI Menu ---- */
    public static final RegistryObject<MenuType<VeinMenu>> VEIN_MENU =
            MENUS.register("vein", () -> IForgeMenuType.create(VeinMenu::new));

    /** 7×7 区块矿脉地图 GUI 菜单类型。 */
    public static final RegistryObject<MenuType<VeinMapMenu>> VEIN_MAP_MENU =
            MENUS.register("vein_map", () -> IForgeMenuType.create(VeinMapMenu::new));

    /* ---- Test Multiblock Machine ---- */
    public static final RegistryObject<Block> TEST_MACHINE =
            BLOCKS.register("test_machine", () -> new TestMachineBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> TEST_MACHINE_ITEM =
            ITEMS.register("test_machine", () -> new BlockItem(TEST_MACHINE.get(), new Item.Properties()));

    /* ---- Big Test Multiblock Machine (larger 3x3x3 structure) ---- */
    public static final RegistryObject<Block> BIG_TEST_MACHINE =
            BLOCKS.register("big_test_machine", () -> new BigTestMachineBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> BIG_TEST_MACHINE_ITEM =
            ITEMS.register("big_test_machine", () -> new BlockItem(BIG_TEST_MACHINE.get(), new Item.Properties()));

    /* ---- Giant Test Multiblock Machine (~500 block stepped pyramid) ---- */
    public static final RegistryObject<Block> GIANT_TEST_MACHINE =
            BLOCKS.register("giant_test_machine", () -> new GiantTestMachineBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()));

    public static final RegistryObject<Item> GIANT_TEST_MACHINE_ITEM =
            ITEMS.register("giant_test_machine", () -> new BlockItem(GIANT_TEST_MACHINE.get(), new Item.Properties()));

    /* ---- 探矿道具 ---- */
    public static final RegistryObject<Item> PROSPECT_WAND =
            ITEMS.register("prospect_wand", () -> new ProspectWandItem());

    public static final RegistryObject<Item> PROSPECT_DEVICE =
            ITEMS.register("prospect_device", () -> new ProspectDeviceItem());

    /** 高级探矿仪：大容量 FE/RF（默认 100M 缓存），右键打开 7×7 矿脉地图 GUI，并按能量逐格探矿。 */
    public static final RegistryObject<Item> ADVANCED_PROSPECT_DEVICE =
            ITEMS.register("advanced_prospect_device", () -> new AdvancedProspectDeviceItem());

    private ModRegistry() {}

    public static void register(IEventBus bus) {
        MENUS.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
