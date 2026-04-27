package com.sabbs.fabricate;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;

    // Client-only: master switch for every client-facing feature of this mod.
    // When false, synthetic recipes are hidden from EMI/JEI, click-to-craft
    // inputs are ignored, and the client-side recipe-regen pass on
    // RecipesUpdatedEvent skips injection.
    public static final ForgeConfigSpec.BooleanValue CLIENT_ENABLED;

    // General
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue MAX_DEPTH;
    public static final ForgeConfigSpec.IntValue MIN_INPUT_COUNT;
    public static final ForgeConfigSpec.IntValue MAX_INPUT_COUNT;
    public static final ForgeConfigSpec.BooleanValue ENABLE_REFUNDS;
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;

    // Item Filtering
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLISTED_BASE_ITEMS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLISTED_OUTPUT_ITEMS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> WHITELISTED_BASE_ITEMS;
    public static final ForgeConfigSpec.BooleanValue USE_BASE_ITEM_WHITELIST;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("General Settings").push("general");
        {
            ENABLED = builder
                .comment("Master switch to enable or disable synthetic recipe generation.")
                .define("enabled", true);

            MAX_DEPTH = builder
                .comment("Maximum recipe chain depth for graph traversal.",
                         "Higher values find more recipes but take longer to compute.",
                         "Example: depth 3 handles log -> planks -> sticks -> wooden sword.")
                .defineInRange("maxDepth", 6, 1, 250);

            MIN_INPUT_COUNT = builder
                .comment("Minimum number of base items required for a synthetic recipe.",
                         "Set to 1 to include recipes like 1 log -> wooden shovel.",
                         "Set to 2 to exclude single-item conversions.")
                .defineInRange("minInputCount", 1, 1, 64);

            MAX_INPUT_COUNT = builder
                .comment("Maximum number of base items allowed for a synthetic recipe.",
                         "Default 128 covers all vanilla recipes with some headroom to spare.",
                         "Recipes requiring more than this many items will be skipped.")
                .defineInRange("maxInputCount", 128, 1, 2048);

            ENABLE_REFUNDS = builder
                .comment("Whether to refund intermediate byproduct items after crafting.",
                         "For example, crafting a wooden pickaxe from logs produces",
                         "leftover planks and sticks that get returned to the player.")
                .define("enableRefunds", true);

            DEBUG_LOGGING = builder
                .comment("Enable verbose debug logging for recipe generation.",
                         "Logs each synthetic recipe as it's created. Useful for troubleshooting.")
                .define("debugLogging", false);
        }
        builder.pop();

        builder.comment("Item Filtering",
                        "Control which items can be used as base materials or produced as outputs.",
                        "Item IDs should be in the format 'namespace:path' (e.g. 'minecraft:diamond').")
                .push("filtering");
        {
            BLACKLISTED_BASE_ITEMS = builder
                .comment("Items that should never be used as base materials for synthetic recipes.",
                         "Example: [\"minecraft:netherite_ingot\", \"minecraft:diamond\"]")
                .defineListAllowEmpty(
                    List.of("blacklistedBaseItems"),
                    ArrayList::new,
                    ModConfig::isValidItemId
                );

            BLACKLISTED_OUTPUT_ITEMS = builder
                .comment("Items that should never be produced by synthetic recipes.",
                         "Example: [\"minecraft:enchanted_golden_apple\"]")
                .defineListAllowEmpty(
                    List.of("blacklistedOutputItems"),
                    ArrayList::new,
                    ModConfig::isValidItemId
                );

            USE_BASE_ITEM_WHITELIST = builder
                .comment("If true, ONLY items in the whitelist below will be used as base materials.",
                         "The blacklist is ignored when this is enabled.")
                .define("useBaseItemWhitelist", false);

            WHITELISTED_BASE_ITEMS = builder
                .comment("When useBaseItemWhitelist is true, only these items will be used as base materials.",
                         "Example: [\"minecraft:oak_log\", \"minecraft:iron_ingot\"]")
                .defineListAllowEmpty(
                    List.of("whitelistedBaseItems"),
                    ArrayList::new,
                    ModConfig::isValidItemId
                );
        }
        builder.pop();

        SPEC = builder.build();

        ForgeConfigSpec.Builder clientBuilder = new ForgeConfigSpec.Builder();
        clientBuilder.comment("Client-side settings").push("client");
        CLIENT_ENABLED = clientBuilder
            .comment("Master switch for every Fabricate client-side feature.",
                     "Set to false to disable synthetic recipes in your recipe viewer",
                     "and click-to-craft.")
            .define("enabled", true);
        clientBuilder.pop();
        CLIENT_SPEC = clientBuilder.build();
    }

    private static boolean isValidItemId(Object obj) {
        if (!(obj instanceof String s)) return false;
        return s.contains(":");
    }
}
