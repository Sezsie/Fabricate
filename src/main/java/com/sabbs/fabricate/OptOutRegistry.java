package com.sabbs.fabricate;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which connected players have set {@code CLIENT_ENABLED=false} on their
 * client. Populated via {@code OptOutPacket} on login and cleared on logout.
 * Read from the server thread (recipe-match filter, CraftPacket handler,
 * ItemCraftedEvent); written from the network worker thread  backed by a
 * {@link ConcurrentHashMap.KeySetView} to avoid requiring a mutex on every
 * recipe-match check.
 *
 * <p>This only matters on dedicated servers. Integrated servers short-circuit
 * synthetic generation entirely when the host player opts out (see
 * {@link ServerEvents#run}), so the registry stays empty and every query is a
 * near-free hash miss.
 */
public final class OptOutRegistry {

    private OptOutRegistry() {}

    private static final Set<UUID> OPTED_OUT = ConcurrentHashMap.newKeySet();

    public static void setOptedOut(UUID id, boolean optedOut) {
        if (optedOut) OPTED_OUT.add(id);
        else OPTED_OUT.remove(id);
    }

    public static boolean isOptedOut(UUID id) {
        return OPTED_OUT.contains(id);
    }

    public static void forget(UUID id) {
        OPTED_OUT.remove(id);
    }

    /** Test/diagnostic hook. Not used at runtime. */
    public static int size() {
        return OPTED_OUT.size();
    }
}
