package com.sabbs.fabricate;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Mod configuration. Currently only client-side settings: the master
 * opt-out toggle plus a few knobs for the failure overlay's position
 * and width.
 */
public class ModConfig {

    public static final ForgeConfigSpec CLIENT_SPEC;

    /**
     * Client-side master switch. When false, sidebar clicks are ignored and
     * the opt-out flag is sent to the server so it rejects packets from
     * this player.
     */
    public static final ForgeConfigSpec.BooleanValue CLIENT_ENABLED;

    /**
     * Horizontal position of the failure overlay as a fraction of screen
     * width. 0.0 = left edge, 0.5 = horizontally centered, 1.0 = right
     * edge.
     */
    public static final ForgeConfigSpec.DoubleValue OVERLAY_X_FRACTION;

    /**
     * Vertical position of the failure overlay as a fraction of screen
     * height. 0.0 = top, 1.0 = bottom.
     */
    public static final ForgeConfigSpec.DoubleValue OVERLAY_Y_FRACTION;

    /**
     * Maximum width of the wrapped overlay text as a fraction of screen
     * width. Smaller = more line wrapping, narrower text column.
     */
    public static final ForgeConfigSpec.DoubleValue OVERLAY_MAX_WIDTH_FRACTION;

    /**
     * Font scale multiplier for the failure overlay. 1.0 = vanilla size,
     * 2.0 = double size, 0.5 = half size.
     */
    public static final ForgeConfigSpec.DoubleValue OVERLAY_TEXT_SCALE;

    static {
        ForgeConfigSpec.Builder clientBuilder = new ForgeConfigSpec.Builder();
        clientBuilder.comment("Client-side settings").push("client");
        CLIENT_ENABLED = clientBuilder
            .comment("Master switch for Fabricate's click-to-craft.",
                     "Set to false to disable sidebar clicks and tell the server",
                     "to reject craft requests from this player.")
            .define("enabled", true);
        clientBuilder.pop();

        clientBuilder.comment("Failure overlay - placement and width of the red",
                              "text shown when Fabricate can't craft the requested item.")
            .push("overlay");
        OVERLAY_X_FRACTION = clientBuilder
            .comment("Horizontal position as a fraction of screen width.",
                     "0.0 = left edge, 0.5 = center, 1.0 = right edge.")
            .defineInRange("xFraction", 0.5, 0.0, 1.0);
        OVERLAY_Y_FRACTION = clientBuilder
            .comment("Vertical position as a fraction of screen height.",
                     "0.0 = top edge, 1.0 = bottom edge. Default sits just below",
                     "the top of the screen so it doesn't overlap the inventory UI.")
            .defineInRange("yFraction", 0.05, 0.0, 1.0);
        OVERLAY_MAX_WIDTH_FRACTION = clientBuilder
            .comment("Maximum width of the wrapped text as a fraction of screen width.",
                     "Smaller values produce more line wrapping in a narrower column.",
                     "Default 0.20 keeps messages compact next to the crafting UI.")
            .defineInRange("maxWidthFraction", 0.20, 0.05, 1.0);
        OVERLAY_TEXT_SCALE = clientBuilder
            .comment("Font scale multiplier. 1.0 is vanilla size, 2.0 is double,",
                     "0.5 is half. Width and position aren't auto-adjusted when",
                     "scale changes, so you may want to tweak maxWidthFraction",
                     "to match.")
            .defineInRange("textScale", 1.0, 0.5, 4.0);
        clientBuilder.pop();

        CLIENT_SPEC = clientBuilder.build();
    }
}
