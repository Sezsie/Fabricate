package com.sabbs.fabricate;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side lifecycle hooks for the planner architecture.
 *
 * <p>{@link ServerStartedEvent} logs that we're alive (the planner builds its
 * graph lazily on first use, so there's no eager work to do at boot).
 * {@link OnDatapackSyncEvent} fires after {@code /reload} and invalidates the
 * cached graph so the next plan rebuilds against the fresh recipe set.
 * {@link PlayerEvent.PlayerLoggedOutEvent} clears the player's opt-out entry.
 */
@Mod.EventBusSubscriber(modid = Fabricate.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEvents {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Fabricate.LOGGER.info("[FAB-server] ServerStartedEvent (planner is on-demand; no pre-generation)");
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return; // per-player login - skip
        MinecraftServer server = event.getPlayerList().getServer();
        Fabricate.LOGGER.info("[FAB-server] OnDatapackSyncEvent (post-reload) - invalidating planner graph");
        com.sabbs.fabricate.planner.PlannerService.invalidate();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OptOutRegistry.forget(player.getUUID());
        }
    }
}
