package com.sabbs.fabricate.integration.jei;

import com.sabbs.fabricate.Fabricate;
import net.minecraftforge.common.MinecraftForge;

/**
 * Isolated JEI initialization. Only called by the main mod class when JEI is
 * installed, so the JEI-dependent classes ({@link JeiSidebarHandler},
 * {@link FabricateJeiPlugin}) never load on JEI-less setups.
 *
 * <p>{@link FabricateJeiPlugin} is picked up by JEI via {@code @JeiPlugin}
 * scanning; we only have to register Forge-event subscribers here.
 */
public final class JeiCompat {

    private JeiCompat() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.register(JeiSidebarHandler.class);
        Fabricate.LOGGER.info("[FAB-JEI] JEI integration enabled");
    }
}
