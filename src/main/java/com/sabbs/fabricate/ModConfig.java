package com.sabbs.fabricate;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Mod configuration. Currently only client-side settings: the master
 * opt-out toggle plus a few knobs for the failure overlay's position
 * and width.
 */
public class ModConfig {

    public static final ForgeConfigSpec CLIENT_SPEC;

    public static final ForgeConfigSpec SERVER_SPEC;

    /**
     * Server-side toggle: when true, items inside an open Sophisticated
     * Backpacks / Sophisticated Storage container count as available crafting
     * materials (and may be consumed) in addition to the player's inventory.
     *
     * <p>This is a server config because it changes what the planner is
     * allowed to source from. The recipe-viewer mixins don't need to know
     * about it; the server reads it directly when building the material pool.
     */
    public static final ForgeConfigSpec.BooleanValue INCLUDE_BACKPACK_INVENTORY;

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

    /**
     * Where craft-failure messages are delivered: as a red overlay above
     * the active container screen, or as a chat message.
     */
    public static final ForgeConfigSpec.EnumValue<FailureDisplay> FAILURE_DISPLAY;

    /**
     * How click-to-craft requests are fulfilled server-side when the player
     * is short on materials.
     */
    public static final ForgeConfigSpec.EnumValue<CraftMode> CRAFT_MODE;

    /**
     * Delivery target for craft-failure messages.
     */
    public enum FailureDisplay {
        /** Red text rendered above the active container screen. */
        OVERLAY,
        /** Normal chat message (visible from any screen, persists in chat log). */
        CHAT
    }

    /**
     * Behavior when the player can't fully complete the requested craft.
     */
    public enum CraftMode {
        /**
         * All-or-nothing: only craft when every required material is in
         * inventory. On any shortfall, refuse the craft and surface the
         * missing-ingredients message.
         */
        BATCH,
        /**
         * Best-effort: craft as far down the chain as inventory allows, then
         * report what's still missing for the original target. Useful for
         * modpack chains where some inputs come from non-crafting processes
         * (smelters, machines) - the player gets the intermediates
         * pre-assembled and only has to gather the rest.
         */
        UP_TO
    }

    static {
        ForgeConfigSpec.Builder clientBuilder = new ForgeConfigSpec.Builder();
        clientBuilder.comment("Client-side settings").push("client");
        CLIENT_ENABLED = clientBuilder
            .comment("Master switch for Fabricate's click-to-craft.",
                     "Set to false to disable sidebar clicks, if this mod takes the fun out of crafting for you.")
            .define("enabled", true);
        clientBuilder.pop();

        clientBuilder.comment("Failure overlay. Placement and width of the red",
                              "text shown when Fabricate can't craft the requested item. Only works if failureDisplay is set to OVERLAY.")
            .push("overlay");
        OVERLAY_X_FRACTION = clientBuilder
            .comment("Horizontal position as a fraction of screen width.",
                     "0.0 = left edge, 0.5 = center, 1.0 = right edge.")
            .defineInRange("xFraction", 0.5, 0.0, 1.0);
        OVERLAY_Y_FRACTION = clientBuilder
            .comment("Vertical position as a fraction of screen height.",
                     "0.0 = top edge, 1.0 = bottom edge.")
            .defineInRange("yFraction", 0.05, 0.0, 1.0);
        OVERLAY_MAX_WIDTH_FRACTION = clientBuilder
            .comment("Maximum width of the wrapped text as a fraction of screen width.",
                     "Smaller values produce more line wrapping in a narrower column.")
            .defineInRange("maxWidthFraction", 0.20, 0.05, 1.0);
        OVERLAY_TEXT_SCALE = clientBuilder
            .comment("Font scale multiplier. 1.0 is vanilla size, 2.0 is double,",
                     "0.5 is half. Width and position aren't auto-adjusted when",
                     "scale changes, so you may want to tweak maxWidthFraction",
                     "to match.")
            .defineInRange("textScale", 1.0, 0.5, 4.0);
        FAILURE_DISPLAY = clientBuilder
            .comment("Where craft-failure messages appear.",
                     "OVERLAY = red text above the active crafting/container screen",
                     "          (may overlap with modded UI).",
                     "CHAT = standard chat message (persists in chat history,",
                     "          visible from any screen).")
            .defineEnum("failureDisplay", FailureDisplay.OVERLAY);
        clientBuilder.pop();

        clientBuilder.comment("How click-to-craft handles partial inventories.")
            .push("crafting");
        CRAFT_MODE = clientBuilder
            .comment("BATCH (default) = all-or-nothing. The whole recipe tree must",
                     "                  be satisfiable from current inventory or the",
                     "                  craft is refused.",
                     "UP_TO           = best-effort. If the full recipe can't be",
                     "                  crafted, the server still crafts whatever",
                     "                  intermediates it can from your materials,",
                     "                  then reports what's still needed for the",
                     "                  final target. Useful for modpack chains",
                     "                  where some inputs come from non-crafting",
                     "                  processes (smelters, machines).")
            .defineEnum("craftMode", CraftMode.BATCH);
        clientBuilder.pop();

        CLIENT_SPEC = clientBuilder.build();

        ForgeConfigSpec.Builder serverBuilder = new ForgeConfigSpec.Builder();
        serverBuilder.comment("Server-side crafting behavior.").push("crafting");
        INCLUDE_BACKPACK_INVENTORY = serverBuilder
            .comment("When true, items inside any Sophisticated Backpack you can",
                     "reach count as available materials and may be consumed when",
                     "you click-to-craft. This includes backpacks in your inventory,",
                     "hotbar, or offhand, AND backpacks worn in a Curios slot (e.g.",
                     "the 'back' slot) if Curios is installed. The backpack does NOT",
                     "need to be open, and no crafting upgrade is required. When",
                     "false, only the player's own inventory is used.",
                     "Has no effect if Sophisticated Backpacks is not installed.")
            .define("includeBackpackInventory", true);
        serverBuilder.pop();

        SERVER_SPEC = serverBuilder.build();
    }
}
