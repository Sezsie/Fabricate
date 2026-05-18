package com.sabbs.fabricate;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Mod configuration. The planner architecture has no tuning knobs that
 * benefit from user exposure - everything that mattered for synthetic
 * generation (depth caps, producer limits, synthetic-count budget) is
 * obsolete. The only remaining setting is the client-side opt-out toggle.
 */
public class ModConfig {

    public static final ForgeConfigSpec CLIENT_SPEC;

    /**
     * Client-side master switch. When false, sidebar clicks are ignored and
     * the opt-out flag is sent to the server so it rejects packets from
     * this player.
     */
    public static final ForgeConfigSpec.BooleanValue CLIENT_ENABLED;

    static {
        ForgeConfigSpec.Builder clientBuilder = new ForgeConfigSpec.Builder();
        clientBuilder.comment("Client-side settings").push("client");
        CLIENT_ENABLED = clientBuilder
            .comment("Master switch for Fabricate's click-to-craft.",
                     "Set to false to disable sidebar clicks and tell the server",
                     "to reject craft requests from this player.")
            .define("enabled", true);
        clientBuilder.pop();
        CLIENT_SPEC = clientBuilder.build();
    }
}
