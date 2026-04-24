package com.sabbs.fabricate.integration.emi;

/**
 * The click-to-craft intents EMI exposes through its keybinds. We map each
 * mouse/keyboard binding onto one of these so downstream dispatch (how many
 * batches, where the output lands) can decide behaviour from flags alone
 * without re-reading EMI's config.
 */
public enum CraftIntent {
    /** Craft as many batches as materials allow, deposit into inventory. */
    CRAFT_ALL_TO_INVENTORY(true, false),
    /** Craft one batch, deposit into inventory. */
    CRAFT_ONE_TO_INVENTORY(false, false),
    /** Craft one batch, deliver to the held cursor stack. */
    CRAFT_ONE_TO_CURSOR(false, true),
    /** Generic "craft all"  inventory delivery. */
    CRAFT_ALL(true, false),
    /** Generic "craft one"  inventory delivery. */
    CRAFT_ONE(false, false);

    public final boolean craftAll;
    public final boolean toCursor;

    CraftIntent(boolean craftAll, boolean toCursor) {
        this.craftAll = craftAll;
        this.toCursor = toCursor;
    }
}
