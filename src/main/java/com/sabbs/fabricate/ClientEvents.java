package com.sabbs.fabricate;

import com.google.common.collect.ImmutableMap;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.network.OptOutPacket;
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
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client-side opt-out wiring + legacy recipe migration cleanup.
 *
 * <p>The opt-out path mirrors the original mod: on login (and on client
 * config reload), tell the server whether this player wants Fabricate
 * behavior active. The server uses the result to reject
 * {@link com.sabbs.fabricate.network.PlannerCraftPacket}s from opted-out
 * players.
 *
 * <p>On {@code RecipesUpdatedEvent} we strip any leftover synthetic
 * recipes from the {@code RecipeManager}. Under the planner architecture
 * we don't generate any new ones; this is purely save-migration cleanup
 * for worlds loaded from a pre-rewrite version of the mod (or installs
 * carrying stale {@code truepolymorph:} entries).
 */
@Mod.EventBusSubscriber(modid = Fabricate.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientEvents {

    /** Namespace of the upstream mod we forked from; legacy recipes get stripped on every load. */
    public static final String LEGACY_NAMESPACE = "truepolymorph";

    private static final Field RECIPES_FIELD =
        ObfuscationReflectionHelper.findField(RecipeManager.class, "f_44007_");
    private static final Field BY_NAME_FIELD =
        ObfuscationReflectionHelper.findField(RecipeManager.class, "f_199900_");

    /** Last opt-out value we told the server about, so config reloads can skip redundant resends. */
    private static Boolean lastSentOptedOut = null;

    @SubscribeEvent
    public static void onClientLoggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        boolean optedOut = !ModConfig.CLIENT_ENABLED.get();
        lastSentOptedOut = optedOut;
        Fabricate.LOGGER.info("[FAB-client] sending opt-out status to server: optedOut={}", optedOut);
        NetworkHandler.sendToServer(new OptOutPacket(optedOut));
    }

    /**
     * Re-sync opt-out state when the client config reloads (e.g. the player
     * toggled {@code CLIENT_ENABLED} in-game). Registered on the mod event
     * bus from {@link Fabricate}; guarded on an active connection so the
     * initial config load during mod init is a no-op.
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        Set<String> toStrip = new HashSet<>();
        toStrip.add(LEGACY_NAMESPACE);
        // Pre-rewrite versions also injected fabricate:* synthetics into the
        // RecipeManager. Strip them on every load so an old save doesn't
        // surface stale recipes in JEI/EMI or the recipe book.
        toStrip.add(Fabricate.MOD_ID);

        int stripped = stripByNamespace(event.getRecipeManager(), toStrip);
        if (stripped > 0) {
            Fabricate.LOGGER.info("[FAB-client] stripped {} legacy synthetic recipes from namespaces {}", stripped, toStrip);
        }
    }

    /**
     * Rebuild the {@link RecipeManager}'s internal {@code recipes} and
     * {@code byName} maps with every recipe whose id namespace is in
     * {@code namespaces} removed.
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
                ImmutableMap.Builder<ResourceLocation, Recipe<?>> newInner = ImmutableMap.builder();
                for (Map.Entry<ResourceLocation, Recipe<?>> e : typeEntry.getValue().entrySet()) {
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
            Fabricate.LOGGER.error("[FAB-client] failed to strip legacy recipes", t);
        }
        return removed;
    }
}
