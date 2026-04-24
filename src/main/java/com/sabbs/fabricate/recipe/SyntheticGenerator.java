package com.sabbs.fabricate.recipe;

import com.sabbs.fabricate.ModConfig;
import com.sabbs.fabricate.Fabricate;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared synthetic-recipe generation pipeline. Invoked from both the
 * client-side {@code RecipesUpdatedEvent} path and the server-side reload
 * listener, so dedicated servers and integrated servers produce the same
 * synthetic set.
 */
public final class SyntheticGenerator {

    private SyntheticGenerator() {}

    public record Result(int injected, int singleCount, int multiCount, int baseItemCount, long elapsedMs) {}

    /** Generates synthetics against {@code manager} and injects them in-place. */
    public static Result generate(RecipeManager manager, RegistryAccess registryAccess) {
        if (!ModConfig.ENABLED.get()) {
            Fabricate.LOGGER.info("[FAB-gen] skipped  ModConfig.ENABLED is false");
            return new Result(0, 0, 0, 0, 0);
        }

        long start = System.currentTimeMillis();
        Fabricate.LOGGER.info("[FAB-gen] starting synthetic-recipe generation");
        RecipeIndex index = new RecipeIndex(manager, registryAccess);
        Fabricate.LOGGER.info("[FAB-gen] indexed {} distinct output items from vanilla CRAFTING",
            index.getAllOutputItems().size());

        Set<String> blacklistedBase = new HashSet<>(ModConfig.BLACKLISTED_BASE_ITEMS.get());
        Set<String> blacklistedOutput = new HashSet<>(ModConfig.BLACKLISTED_OUTPUT_ITEMS.get());
        boolean useWhitelist = ModConfig.USE_BASE_ITEM_WHITELIST.get();
        Set<String> whitelistedBase = new HashSet<>(ModConfig.WHITELISTED_BASE_ITEMS.get());

        Set<Item> baseItemSet = new HashSet<>();
        for (Recipe<?> recipe : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            // Skip our own synthetics  on the client these are already in the
            // manager from server sync and would pollute base-item collection.
            if (recipe instanceof FabricateRecipe) continue;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                for (ItemStack accepted : ingredient.getItems()) {
                    Item item = accepted.getItem();
                    ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
                    if (key == null) continue;
                    String id = key.toString();
                    if (useWhitelist) {
                        if (whitelistedBase.contains(id)) baseItemSet.add(item);
                    } else {
                        if (!blacklistedBase.contains(id)) baseItemSet.add(item);
                    }
                }
            }
        }

        if (baseItemSet.isEmpty()) {
            Fabricate.LOGGER.warn("[FAB-gen] no ingredient items found, skipping generation");
            return new Result(0, 0, 0, 0, System.currentTimeMillis() - start);
        }
        Fabricate.LOGGER.info("[FAB-gen] collected {} candidate base items (whitelist={})",
            baseItemSet.size(), useWhitelist);

        int maxDepth = ModConfig.MAX_DEPTH.get();
        int minInput = ModConfig.MIN_INPUT_COUNT.get();
        int maxInput = ModConfig.MAX_INPUT_COUNT.get();
        boolean debug = ModConfig.DEBUG_LOGGING.get();

        List<GraphWalker.SyntheticRecipe> all = new ArrayList<>();
        int singleDepth = Math.min(maxDepth, 6);
        Fabricate.LOGGER.info("[FAB-gen] phase 1: single-material walk (depth={}, minInput={}, maxInput={})",
            singleDepth, minInput, maxInput);
        long phase1Start = System.currentTimeMillis();
        for (Item baseItem : baseItemSet) {
            all.addAll(GraphWalker.walkSingle(baseItem, index, singleDepth, minInput, maxInput));
        }
        int singleCount = all.size();
        Fabricate.LOGGER.info("[FAB-gen] phase 1 done  {} single-material synthetics in {}ms",
            singleCount, System.currentTimeMillis() - phase1Start);

        Fabricate.LOGGER.info("[FAB-gen] phase 2: multi-material combinations");
        long phase2Start = System.currentTimeMillis();
        all.addAll(GraphWalker.buildMultiMaterial(baseItemSet, all, index, minInput, maxInput));
        int multiCount = all.size() - singleCount;
        Fabricate.LOGGER.info("[FAB-gen] phase 2 done  {} multi-material synthetics in {}ms",
            multiCount, System.currentTimeMillis() - phase2Start);

        if (!blacklistedOutput.isEmpty()) {
            all.removeIf(syn -> {
                ResourceLocation outputKey = ForgeRegistries.ITEMS.getKey(syn.output().getItem());
                return outputKey != null && blacklistedOutput.contains(outputKey.toString());
            });
        }

        if (debug) {
            for (GraphWalker.SyntheticRecipe syn : all) {
                Fabricate.LOGGER.info("Fabricate [DEBUG]: {} -> {}",
                    syn.baseCosts().entrySet().stream()
                        .map(e -> e.getValue() + "x " + ForgeRegistries.ITEMS.getKey(e.getKey()))
                        .reduce((a, b) -> a + " + " + b).orElse("?"),
                    ForgeRegistries.ITEMS.getKey(syn.output().getItem()));
            }
        }

        Fabricate.LOGGER.info("[FAB-gen] injecting {} synthetics into RecipeManager", all.size());
        int injected = RecipeInjector.inject(manager, all, index);
        long elapsed = System.currentTimeMillis() - start;
        Fabricate.LOGGER.info("[FAB-gen] complete  injected {} recipes in {}ms total", injected, elapsed);
        return new Result(injected, singleCount, multiCount, baseItemSet.size(), elapsed);
    }
}
