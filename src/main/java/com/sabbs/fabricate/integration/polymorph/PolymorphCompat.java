package com.sabbs.fabricate.integration.polymorph;

import com.sabbs.fabricate.Fabricate;
import net.minecraftforge.common.MinecraftForge;

/**
 * Registers Polymorph-side client handlers. Polymorph is a mandatory dependency
 * so this always runs, but the init call stays gated in the main mod class to
 * match the EMI/JEI compat pattern.
 */
public final class PolymorphCompat {

    private PolymorphCompat() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.register(PolymorphScrollHandler.class);
        Fabricate.LOGGER.info("[FAB-Polymorph] Polymorph integration enabled");
    }
}
