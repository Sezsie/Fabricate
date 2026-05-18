package com.sabbs.fabricate.integration.emi;

/**
 * Shared client-side throttle for EMI-originated Fabricate craft packets.
 *
 * <p>Fabricate's EMI integration has more than one packet-send path:
 * recipe-fill interception and hovered-stack craft-keybind interception.
 * This class makes both paths share the same cooldown so they cannot bypass
 * each other by alternating.
 */
public final class EmiCraftThrottle {

    private EmiCraftThrottle() {}

    private static final long CLIENT_CLICK_COOLDOWN_MS = 300L;

    private static long lastCraftPacketSentMs = 0L;

    public static boolean isOnCooldown() {
        long now = System.currentTimeMillis();
        return now - lastCraftPacketSentMs < CLIENT_CLICK_COOLDOWN_MS;
    }

    public static void markAccepted() {
        lastCraftPacketSentMs = System.currentTimeMillis();
    }

    public static long remainingMs() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastCraftPacketSentMs;
        return Math.max(0L, CLIENT_CLICK_COOLDOWN_MS - elapsed);
    }
}