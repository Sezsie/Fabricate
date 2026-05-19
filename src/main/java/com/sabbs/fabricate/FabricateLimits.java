package com.sabbs.fabricate;

/**
 * Single source of truth for Fabricate's tunable limits.
 *
 * <p>Previously these constants were scattered across {@link com.sabbs.fabricate.network.PlannerCraftPacket}
 * (server cooldown), {@link com.sabbs.fabricate.integration.jei.JeiSidebarHandler}
 * and {@link com.sabbs.fabricate.integration.emi.EmiCraftThrottle} (client
 * cooldowns), and {@link com.sabbs.fabricate.client.CraftFailureOverlay}
 * (display duration). Centralizing them here keeps tuning a single-file
 * change and makes it obvious that the client cooldown is meant to be
 * longer than the server cooldown (client filters most spam before it
 * hits the wire; server still rejects the rest).
 */
public final class FabricateLimits {

    private FabricateLimits() {}

    /**
     * Minimum delay between accepted client→server craft requests from one
     * player on the server. Each accepted request triggers a full planner
     * pass, so this bounds how often planner work can be queued per player.
     */
    public static final long SERVER_CRAFT_COOLDOWN_MS = 150L;

    /**
     * Minimum delay between craft packets a client will send. Intentionally
     * longer than {@link #SERVER_CRAFT_COOLDOWN_MS} so most spam is filtered
     * before it crosses the network; the server cooldown is still the
     * authoritative gate against malicious clients.
     */
    public static final long CLIENT_CLICK_COOLDOWN_MS = 300L;

    /**
     * How long the client-side {@link com.sabbs.fabricate.client.CraftFailureOverlay}
     * stays visible after a failed craft.
     */
    public static final long FAILURE_DISPLAY_MS = 10_000L;
}
