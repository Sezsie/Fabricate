package com.sabbs.fabricate;

import java.lang.reflect.Field;
import java.util.Set;

import com.sabbs.fabricate.recipe.SyntheticGenerator;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/**
 * Server-side synthetic-recipe generation. We deliberately run <em>after</em>
 * the datapack reload completes instead of inside an {@code AddReloadListener}
 * apply phase: during apply, iterating {@code ingredient.getItems()} before
 * every vanilla listener has finished can cache empty {@code ItemStack[]} into
 * tag-based {@link net.minecraft.world.item.crafting.Ingredient}s, and those
 * empty arrays then get shipped to clients verbatim through
 * {@code ClientboundUpdateRecipesPacket}  producing the "Empty Tag" tooltips
 * and breaking recipes like bowls (which require a populated {@code minecraft:planks}
 * tag).
 *
 * <p>{@link ServerStartedEvent} covers initial server boot. {@link OnDatapackSyncEvent}
 * fires once (with {@code getPlayer() == null}) after every {@code /reload},
 * so we pick up runtime datapack changes too. Per-player sync firings are
 * ignored  the registry is already populated from the earlier pass.
 */
@Mod.EventBusSubscriber(modid = Fabricate.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEvents {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Fabricate.LOGGER.info("[FAB-server] ServerStartedEvent  generating synthetics");
        run(event.getServer());
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return; // per-player login  skip
        MinecraftServer server = event.getPlayerList().getServer();
        Fabricate.LOGGER.info("[FAB-server] OnDatapackSyncEvent (post-reload)  regenerating synthetics");
        run(server);
    }

    /**
     * One-time cleanup on login: strip any Fabricate recipe IDs that
     * accumulated in a player's recipe book from a pre-fix version of the mod.
     * Left behind, those entries bloat the player NBT and trigger 60s watchdog
     * hangs whenever a datapack {@code execute if data entity} runs against
     * the player. New unlocks are
     * already prevented by {@code FabricateRecipe.isSpecial() == true}.
     */
    /**
     * Drop the player's opt-out entry when they disconnect so the registry
     * doesn't leak UUIDs across a long-running server's lifetime. On rejoin
     * the client re-sends its current state via OptOutPacket.
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OptOutRegistry.forget(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            ServerRecipeBook book = player.getRecipeBook();
            int removed = 0;
            removed += stripTpIds(book, "f_12680_"); // known
            removed += stripTpIds(book, "f_12681_"); // highlight
            if (removed > 0) {
                Fabricate.LOGGER.info("[FAB-server] purged {} stale FAB recipe-book entries from {}",
                    removed, player.getGameProfile().getName());
            }
        } catch (Throwable t) {
            Fabricate.LOGGER.warn("[FAB-server] recipe-book cleanup failed for {}: {}",
                player.getGameProfile().getName(), t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static int stripTpIds(ServerRecipeBook book, String srgField) throws Exception {
        Field f = ObfuscationReflectionHelper.findField(net.minecraft.stats.RecipeBook.class, srgField);
        Set<ResourceLocation> set = (Set<ResourceLocation>) f.get(book);
        int before = set.size();
        set.removeIf(id -> Fabricate.MOD_ID.equals(id.getNamespace()));
        return before - set.size();
    }

    private static void run(MinecraftServer server) {
        if (!ModConfig.ENABLED.get()) {
            Fabricate.LOGGER.info("[FAB-server] ModConfig.ENABLED=false  skipping");
            return;
        }
        // On an integrated server (singleplayer) the RecipeManager is shared
        // with the client, so the client-side strip pass in ClientEvents is a
        // no-op. Honor CLIENT_ENABLED here instead: if the local player has
        // turned the mod off client-side, just don't generate synthetics at
        // all. This branch never runs on dedicated servers  CLIENT_SPEC only
        // loads on the physical client, so ForgeConfigSpec#isLoaded will be
        // false there and .get() would throw.
        if (server.isSingleplayer() && ModConfig.CLIENT_SPEC.isLoaded() && !ModConfig.CLIENT_ENABLED.get()) {
            Fabricate.LOGGER.info("[FAB-server] integrated server + CLIENT_ENABLED=false  skipping synthetic generation");
            return;
        }
        SyntheticGenerator.Result r = SyntheticGenerator.generate(
            server.getRecipeManager(), server.registryAccess());
        Fabricate.LOGGER.info("[FAB-server] summary  {} recipes ({} single, {} multi) from {} bases in {}ms",
            r.injected(), r.singleCount(), r.multiCount(), r.baseItemCount(), r.elapsedMs());
    }
}
