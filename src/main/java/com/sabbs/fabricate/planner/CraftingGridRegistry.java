package com.sabbs.fabricate.planner;

import com.sabbs.fabricate.Fabricate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CraftingTweaks-style registry for menus that expose crafting grids.
 *
 * <p>Fabricate needs to know whether the player's current menu should count
 * as 2x2 or 3x3 crafting access. Vanilla makes this easy:
 * InventoryMenu = 2x2, CraftingMenu = 3x3. Modded crafting tables do not
 * always extend CraftingMenu, so instanceof checks are not enough.
 *
 * <p>This registry tracks menu class names plus basic grid metadata:
 * where the grid starts and how large it is. Fabricate currently only needs
 * grid size for planner gating, but keeping the slot metadata now makes this
 * useful later if we ever want deeper menu integration.
 *
 * <p>This intentionally does not hard-link against GregTech or any other
 * optional mod. Known modded menus can be registered by class-name string.
 */
public final class CraftingGridRegistry {

    private CraftingGridRegistry() {}

    private static final Map<String, CraftingGridInfo> REGISTERED_GRIDS =
        new ConcurrentHashMap<>();

    /**
     * Basic metadata for a crafting grid inside a menu.
     *
     * @param menuClassName fully-qualified menu/container class name
     * @param gridSlotNumber first crafting-grid slot index in the menu
     * @param gridSize number of crafting input slots, usually 4 or 9
     */
    public record CraftingGridInfo(String menuClassName, int gridSlotNumber, int gridSize) {
        public CraftingGridInfo {
            if (menuClassName == null || menuClassName.isBlank()) {
                throw new IllegalArgumentException("menuClassName cannot be blank");
            }
            if (gridSlotNumber < 0) {
                throw new IllegalArgumentException("gridSlotNumber cannot be negative");
            }
            if (gridSize < 1) {
                throw new IllegalArgumentException("gridSize must be positive");
            }
        }

        public boolean is3x3() {
            return gridSize >= 9;
        }
    }

    static {
        /*
         * Vanilla crafting table. Slot 0 is the result slot; grid starts at 1.
         * This mirrors the CraftingTweaks-style metadata model.
         */
        register(CraftingMenu.class.getName(), 1, 9);

        /*
         * Vanilla player inventory crafting grid. Slot 0 is result; grid starts
         * at 1 and has four input slots. Fabricate treats this as 2x2 access.
         */
        register(InventoryMenu.class.getName(), 1, 4);

        /*
         * GregTech / GTCEu note:
         *
         * Do not guess the exact class name here until you log it from your
         * instance. Open the GregTech crafting table and watch the
         * [FAB-grid] menu logs. Then add a precise register(...) call below.
         *
         * Example shape:
         *
         * register("com.gregtechceu.gtceu.common.menu.SomeCraftingTableMenu", 1, 9);
         */
    }

    public static void register(String menuClassName, int gridSlotNumber, int gridSize) {
        CraftingGridInfo info = new CraftingGridInfo(menuClassName, gridSlotNumber, gridSize);
        REGISTERED_GRIDS.put(menuClassName, info);

        Fabricate.LOGGER.debug("[FAB-grid] registered crafting grid: menuClass={}, gridSlotNumber={}, gridSize={}",
            menuClassName, gridSlotNumber, gridSize);
    }

    public static Optional<CraftingGridInfo> get(AbstractContainerMenu menu) {
        if (menu == null) return Optional.empty();

        Class<?> c = menu.getClass();
        while (c != null) {
            CraftingGridInfo direct = REGISTERED_GRIDS.get(c.getName());
            if (direct != null) return Optional.of(direct);
            c = c.getSuperclass();
        }

        return Optional.empty();
    }

    public static boolean has3x3Access(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;

        /*
         * Fast, explicit vanilla paths. These also protect us if something ever
         * removes or changes the registry entry.
         */
        if (menu instanceof CraftingMenu) {
            return true;
        }

        if (menu instanceof InventoryMenu) {
            return false;
        }

        Optional<CraftingGridInfo> registered = get(menu);
        if (registered.isPresent()) {
            boolean has3x3 = registered.get().is3x3();

            Fabricate.LOGGER.debug("[FAB-grid] registered menu: class={}, gridSize={}, has3x3={}",
                menu.getClass().getName(),
                registered.get().gridSize(),
                has3x3);

            return has3x3;
        }

        /*
         * Compatibility fallback:
         *
         * This intentionally exists while you're discovering GregTech's actual
         * menu class. It lets likely crafting-table menus work without treating
         * every random chest/furnace menu as 3x3 access.
         *
         * Once you know the class name, add a register(...) entry above and
         * this fallback becomes less important.
         */
        String className = menu.getClass().getName();
        String lower = className.toLowerCase(Locale.ROOT);

        boolean likelyModdedCraftingMenu =
            lower.contains("crafting")
                || lower.contains("crafttable")
                || lower.contains("craft_table")
                || lower.contains("workbench")
                || lower.contains("work_table");

        if (likelyModdedCraftingMenu) {
            Fabricate.LOGGER.debug("[FAB-grid] treating likely modded crafting menu as 3x3: class={}",
                className);
            return true;
        }

        Fabricate.LOGGER.debug("[FAB-grid] no crafting grid registered for menu class {}; treating as no 3x3 access",
            className);
        return false;
    }

    /**
     * Temporary debug helper. Call this when tracking a weird modded menu.
     */
    public static void logCurrentMenu(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;

        Fabricate.LOGGER.info("[FAB-grid] current menu class: {}", menu.getClass().getName());
        Fabricate.LOGGER.info("[FAB-grid] registered grid: {}", get(menu).map(Object::toString).orElse("(none)"));

        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            Fabricate.LOGGER.info("[FAB-grid] slot {}: slotClass={}, containerClass={}, slotIndex={}, hasItem={}",
                i,
                slot.getClass().getName(),
                slot.container.getClass().getName(),
                slot.getSlotIndex(),
                slot.hasItem());
        }
    }
}