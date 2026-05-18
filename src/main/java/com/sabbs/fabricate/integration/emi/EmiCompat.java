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
}
