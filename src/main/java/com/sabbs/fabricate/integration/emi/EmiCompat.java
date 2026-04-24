package com.sabbs.fabricate.integration.emi;

import com.sabbs.fabricate.Fabricate;
import net.minecraftforge.common.MinecraftForge;

/**
 * Isolated EMI initialization. The main mod class only touches this class
 * after confirming EMI is loaded, which prevents its references to EMI types
 * from triggering class-load failures on EMI-less installs.
 *
 * <p>{@link FabricateEmiPlugin} itself is registered by EMI via
 * {@code @EmiEntrypoint} scanning  not here  so only event-subscriber
 * classes need manual hookup.
 */
public final class EmiCompat {

    private EmiCompat() {}

    /** Called once from the main mod constructor if EMI is installed. */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(CraftingTableSidebarHandler.class);
        Fabricate.LOGGER.info("[FAB-EMI] EMI integration enabled");
    }

    /**
     * EMI scans the vanilla {@code RecipeManager} once at startup, before
     * {@code RecipesUpdatedEvent} fires, so our synthetics are invisible
     * until we force a reload. Called only when EMI is loaded and JEI is
     * not  JEMI (JEI-in-EMI) re-imports JEI's recipes here and otherwise
     * produces hundreds of "duplicate recipe id" errors.
     */
    public static void reloadRecipes() {
        try {
            Fabricate.LOGGER.info("[FAB-EMI] requesting EmiReloadManager.reloadRecipes()");
            dev.emi.emi.runtime.EmiReloadManager.reloadRecipes();
        } catch (Throwable t) {
            Fabricate.LOGGER.warn("[FAB-EMI] EmiReloadManager.reloadRecipes() failed", t);
        }
    }
}
