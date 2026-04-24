package com.sabbs.fabricate;

import com.google.common.collect.ImmutableMap;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.network.OptOutPacket;
import com.sabbs.fabricate.recipe.RecipeSelector;
import com.sabbs.fabricate.recipe.RefundRegistry;
import com.sabbs.fabricate.recipe.FabricateRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side recipe sync handler. Whenever the server pushes an updated
 * recipe list, we walk it for {@link FabricateRecipe} instances and seed
 * {@link RefundRegistry} from their {@code requiredItems} maps so click-to-craft
 * and craftability checks have the data they need. The actual synthetic
 * generation happens authoritatively on the server in {@link ServerEvents};
 * the client never runs the graph walker.
 *
 * <p>On integrated servers the server JVM is the same as the client's, so
 * {@link ServerEvents} has already populated {@code RefundRegistry} (with real
 * refund data); we skip in that case to avoid overwriting refunds with the
 * empty lists we'd derive from recipe objects alone.
 *
 * <p>When the user has set {@code CLIENT_ENABLED=false} (the consent toggle)
 * and we're on a dedicated server, we physically excise every Fabricate
 * recipe from the client's {@link RecipeManager}. UI-level hiding in
 * EMI/JEI/Polymorph isn't enough  the vanilla crafting output slot still
 * resolves the first matching recipe regardless of which viewer is filtering
 * them, so a player who hasn't opted in would still see synthetic outputs pop
 * up as they fill the grid. Stripping the recipes from the manager itself
 * closes that hole. Never do this on integrated servers: the
 * {@code RecipeManager} instance is shared with the server logic and the
 * authoritative SyntheticGenerator output would be destroyed.
 *
 * <p>The legacy {@code truepolymorph:} namespace is <em>always</em> stripped,
 * regardless of opt-in state. It's a no-op on a clean install; on users who
 * migrated from the old mod (or whose server still has stale datapacks) it
 * prevents stale recipes from leaking into the viewer.
 */
@Mod.EventBusSubscriber(modid = Fabricate.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientEvents {

    /** Namespaces whose recipes we strip on opt-out; {@code truepolymorph} is always stripped. */
    public static final String LEGACY_NAMESPACE = "truepolymorph";

    // Resolved once ObfuscationReflectionHelper.findField walks the class'
    // fields on every call, which would be wasteful to repeat per login.
    private static final Field RECIPES_FIELD =
        ObfuscationReflectionHelper.findField(RecipeManager.class, "f_44007_");
    private static final Field BY_NAME_FIELD =
        ObfuscationReflectionHelper.findField(RecipeManager.class, "f_199900_");

    /**
     * Tell the server this player's opt-out state as soon as the network is up.
     * Server uses it to filter synthetic recipe matches for this player (see
     * {@code CraftingMenuMixin}) and to reject CraftPackets outright. The packet
     * is cheap (1 byte) and idempotent — safe to resend if the server happens
     * to miss the first one because the channel wasn't quite wired yet.
     *
     * <p>Fires on every new server join, including reconnects. Doesn't fire on
     * integrated servers (ClientPlayerNetworkEvent only fires for remote
     * connections... except it does fire on integrated servers too; the send is
     * still safe. The server handler just records state that the integrated
     * path ignores anyway).
     */
    @SubscribeEvent
    public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        boolean optedOut = !ModConfig.CLIENT_ENABLED.get();
        lastSentOptedOut = optedOut;
        Fabricate.LOGGER.info("[FAB-client] sending opt-out status to server: optedOut={}", optedOut);
        NetworkHandler.sendToServer(new OptOutPacket(optedOut));
    }

    /** Last opt-out value we told the server about, so config reloads can skip redundant resends. */
    private static Boolean lastSentOptedOut = null;

    /**
     * Re-sync opt-out state when the client config reloads (e.g. the player
     * toggled {@code CLIENT_ENABLED} in-game via Configured). Without this the
     * server keeps the login-time value in {@link com.sabbs.fabricate.OptOutRegistry}
     * and keeps rejecting {@link com.sabbs.fabricate.network.CraftPacket}s after
     * the player re-enables the mod.
     *
     * <p>Registered on the mod event bus from {@link Fabricate}; guarded on an
     * active connection so the initial config load during mod init is a no-op.
     */
    public static void onClientConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != ModConfig.CLIENT_SPEC) return;
        if (Minecraft.getInstance().getConnection() == null) return;
        boolean optedOut = !ModConfig.CLIENT_ENABLED.get();
        if (lastSentOptedOut != null && lastSentOptedOut == optedOut) return;
        lastSentOptedOut = optedOut;
        Fabricate.LOGGER.info("[FAB-client] config reload, resending opt-out status: optedOut={}", optedOut);
        NetworkHandler.sendToServer(new OptOutPacket(optedOut));
    }

    // HIGHEST so our RecipeManager mutation lands before EMI/JEI's own listeners
    // on the same event scan the manager to rebuild their indexes. If we ran at
    // default priority, EMI's aggregate-card builder in FabricateEmiPlugin.register
    // could still see synthetics we're about to strip.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        Fabricate.LOGGER.info("[FAB-client] RecipesUpdatedEvent received");
        // Flush the per-RecipeManager indexes kept by RecipeSelector so the
        // next click resolves against the fresh recipe set. The RecipeManager
        // reference itself can persist across reloads while its contents
        // change; reference-identity isn't a reliable invalidation trigger.
        RecipeSelector.invalidate();
        if (!ModConfig.ENABLED.get()) {
            Fabricate.LOGGER.info("[FAB-client] ModConfig.ENABLED=false  skipping");
            return;
        }

        RecipeManager clientManager = event.getRecipeManager();
        boolean integrated = ServerLifecycleHooks.getCurrentServer() != null;

        // Decide which namespaces to purge from the client's RecipeManager.
        // truepolymorph: always (legacy leftovers). fabricate: only when the
        // player opted out AND we're on a dedicated server (integrated would
        // nuke the server-side synthetic registry too since the manager is
        // shared).
        Set<String> toStrip = new HashSet<>();
        toStrip.add(LEGACY_NAMESPACE);
        boolean optedOut = !ModConfig.CLIENT_ENABLED.get();
        if (optedOut && !integrated) {
            toStrip.add(Fabricate.MOD_ID);
        }

        int stripped = stripByNamespace(clientManager, toStrip);
        Fabricate.LOGGER.info("[FAB-client] stripped {} recipes from namespaces {}", stripped, toStrip);

        if (optedOut) {
            if (integrated) {
                Fabricate.LOGGER.info("[FAB-client] CLIENT_ENABLED=false + integrated server  UI-level hiding only (manager is shared)");
            } else {
                RefundRegistry.clear();
                Fabricate.LOGGER.info("[FAB-client] CLIENT_ENABLED=false. cleared RefundRegistry");
            }
            notifyViewers();
            return;
        }

        if (integrated) {
            Fabricate.LOGGER.info("[FAB-client] integrated server present. RefundRegistry already populated server-side, skipping");
        } else {
            int count = 0;
            RefundRegistry.clear();
            for (Recipe<?> recipe : clientManager.getAllRecipesFor(RecipeType.CRAFTING)) {
                if (!(recipe instanceof FabricateRecipe tp)) continue;
                ResourceLocation id = tp.getId();
                // Refund items aren't synced over the wire  the server holds
                // the authoritative refund list and issues refunds itself via
                // CraftPacket. Client only needs requiredItems for UI gates.
                RefundRegistry.register(id, Collections.emptyList(), tp.getRequiredItems());
                count++;
            }
            Fabricate.LOGGER.info("[FAB-client] seeded RefundRegistry from {} synced synthetic recipes", count);
        }

        notifyViewers();
    }

    private static void notifyViewers() {
        boolean emi = ModList.get().isLoaded("emi");
        boolean jei = ModList.get().isLoaded("jei");
        // EMI + JEI together use JEMI (JEI-in-EMI), which re-imports JEI's
        // recipe list into EMI; only nudging EMI on a both-loaded setup lets
        // the JEI half linger with stale entries. Nudge whichever is present.
        if (emi) {
            Fabricate.LOGGER.info("[FAB-client] triggering EMI recipe reload");
            com.sabbs.fabricate.integration.emi.EmiCompat.reloadRecipes();
        }
        // JEI rebuilds its own index off RecipesUpdatedEvent at default priority,
        // which runs after our HIGHEST-priority strip — nothing to nudge here.
    }

    /**
     * Rebuild the {@link RecipeManager}'s internal {@code recipes} and
     * {@code byName} maps with every recipe whose id namespace is in
     * {@code namespaces} removed. Returns the total number of recipes stripped.
     *
     * <p>Matching by namespace (not {@code instanceof FabricateRecipe}) catches
     * legacy {@code truepolymorph:} entries that were serialized under the old
     * mod's recipe class and wouldn't satisfy the instanceof check, plus is
     * robust against future class renames.
     *
     * <p>The vanilla maps are {@link ImmutableMap}s populated in
     * {@code RecipeManager.apply}, so we can't mutate in place  we allocate
     * fresh immutables and reflectively swap the fields.
     */
    @SuppressWarnings("unchecked")
    private static int stripByNamespace(RecipeManager manager, Set<String> namespaces) {
        if (namespaces.isEmpty()) return 0;
        int removed = 0;
        try {
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> recipes =
                (Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>) RECIPES_FIELD.get(manager);
            Map<ResourceLocation, Recipe<?>> byName =
                (Map<ResourceLocation, Recipe<?>>) BY_NAME_FIELD.get(manager);

            ImmutableMap.Builder<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> newRecipes = ImmutableMap.builder();
            for (Map.Entry<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> typeEntry : recipes.entrySet()) {
                Map<ResourceLocation, Recipe<?>> inner = typeEntry.getValue();
                ImmutableMap.Builder<ResourceLocation, Recipe<?>> newInner = ImmutableMap.builder();
                for (Map.Entry<ResourceLocation, Recipe<?>> e : inner.entrySet()) {
                    if (namespaces.contains(e.getKey().getNamespace())) {
                        removed++;
                        continue;
                    }
                    newInner.put(e.getKey(), e.getValue());
                }
                newRecipes.put(typeEntry.getKey(), newInner.build());
            }

            ImmutableMap.Builder<ResourceLocation, Recipe<?>> newByName = ImmutableMap.builder();
            for (Map.Entry<ResourceLocation, Recipe<?>> e : byName.entrySet()) {
                if (namespaces.contains(e.getKey().getNamespace())) continue;
                newByName.put(e.getKey(), e.getValue());
            }

            RECIPES_FIELD.set(manager, newRecipes.build());
            BY_NAME_FIELD.set(manager, newByName.build());
        } catch (Throwable t) {
            Fabricate.LOGGER.error("[FAB-client] failed to strip recipes from RecipeManager", t);
        }
        return removed;
    }
}
