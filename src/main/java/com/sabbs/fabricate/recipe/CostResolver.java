package com.sabbs.fabricate.recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Resolves the cost of crafting any item back to a set of base raw materials.
 * Handles multi-step recipe chains, tag-based ingredients, and cycle detection.
 * Tracks intermediate waste (byproducts) at each resolution step and reuses
 * byproducts across sibling ingredients to minimize over-counting.
 *
 * Supports an optional memoization cache for repeated resolutions of the same item.
 */
public class CostResolver {

    public record ResolutionResult(Map<Item, Integer> baseCost, Map<Item, Integer> byproducts) {}

    /**
     * Resolve with byproducts, using an optional cache for memoization.
     * Pass null for cache to disable memoization.
     */
    public static ResolutionResult resolveWithByproducts(
            Item target, int quantity, Set<Item> baseItems,
            RecipeIndex index, int depthLimit, Set<Item> visited,
            Map<Item, ResolutionResult> cache) {

        if (baseItems.contains(target)) {
            return new ResolutionResult(Map.of(target, quantity), Map.of());
        }

        if (depthLimit <= 0 || visited.contains(target)) {
            return null;
        }

        if (cache != null && quantity == 1 && cache.containsKey(target)) {
            return cache.get(target);
        }

        visited.add(target);

        for (Recipe<?> recipe : orderedRecipesFor(target, index, quantity)) {
            ItemStack output = index.getOutput(recipe);
            int outputCount = Math.max(1, output.getCount());
            int batches = ceilDiv(quantity, outputCount);
            int produced = batches * outputCount;
            int waste = produced - quantity;

            Map<Item, Integer> flatCost = flattenIngredients(recipe, batches, baseItems, index, depthLimit, cache);
            if (flatCost == null) continue;

            Map<Item, Integer> totalBaseCost = new HashMap<>();
            Map<Item, Integer> totalByproducts = new HashMap<>();
            boolean allResolved = true;

            // Resolve flatCost entries in dependency order: items whose
            // recipes produce other flatCost items as transitive byproducts
            // resolve first, so the byproduct waste is available when the
            // dependent items take their turn. Without this, HashMap
            // iteration order decides, and the wooden_shovel case
            // (1 plank + 2 sticks) gives a non-deterministic 1-log or
            // 2-log cost depending on which log variant's hash lands the
            // plank slot first in iteration. Stick's recipe (2 planks ->
            // 4 sticks) produces plank waste when resolved, so resolving
            // stick first lets the plank slot reuse that waste.
            List<Map.Entry<Item, Integer>> orderedEntries = new ArrayList<>(flatCost.entrySet());

            if (orderedEntries.size() > 1) {
                Map<Item, Integer> outDegree = new HashMap<>();

                for (Item x : flatCost.keySet()) {
                    int n = 0;

                    for (Recipe<?> r : index.getRecipesProducing(x)) {
                        for (Ingredient ing : r.getIngredients()) {
                            if (ing.isEmpty()) continue;

                            for (ItemStack s : ing.getItems()) {
                                Item dep = s.getItem();

                                if (dep != x && flatCost.containsKey(dep)) {
                                    n++;
                                }
                            }
                        }
                    }

                    outDegree.put(x, n);
                }

                orderedEntries.sort((a, b) -> Integer.compare(
                    outDegree.getOrDefault(b.getKey(), 0),
                    outDegree.getOrDefault(a.getKey(), 0)
                ));
            }

            for (var entry : orderedEntries) {
                Item needed = entry.getKey();
                int neededQty = entry.getValue();

                int available = totalByproducts.getOrDefault(needed, 0);

                if (available > 0) {
                    int used = Math.min(available, neededQty);
                    neededQty -= used;
                    totalByproducts.merge(needed, -used, Integer::sum);

                    if (totalByproducts.getOrDefault(needed, 0) <= 0) {
                        totalByproducts.remove(needed);
                    }
                }

                if (neededQty <= 0) continue;

                // Reuse the parent's visited set via DFS add/remove instead
                // of copying. Each recursion level adds itself at entry and
                // removes at exit, so siblings see a clean view. Copying
                // allocated O(depth) HashSets per ingredient on every call.
                ResolutionResult sub = resolveWithByproducts(
                    needed, neededQty,
                    baseItems, index, depthLimit - 1,
                    visited, cache
                );

                if (sub == null) {
                    allResolved = false;
                    break;
                }

                sub.baseCost().forEach((item, count) ->
                    totalBaseCost.merge(item, count, Integer::sum)
                );

                sub.byproducts().forEach((item, count) ->
                    totalByproducts.merge(item, count, Integer::sum)
                );
            }

            if (allResolved) {
                if (waste > 0) {
                    totalByproducts.merge(target, waste, Integer::sum);
                }

                visited.remove(target);

                ResolutionResult result = new ResolutionResult(totalBaseCost, totalByproducts);

                if (cache != null && quantity == 1) {
                    // Snapshot into immutable copies so future callers can't
                    // mutate the cached result through the returned maps.
                    cache.put(target, new ResolutionResult(
                        Map.copyOf(totalBaseCost),
                        Map.copyOf(totalByproducts)
                    ));
                }

                return result;
            }
        }

        visited.remove(target);

        if (cache != null && quantity == 1) {
            cache.put(target, null); // negative cache
        }

        return null;
    }

    private static Map<Item, Integer> flattenIngredients(
            Recipe<?> recipe, int batches, Set<Item> baseItems, RecipeIndex index,
            int depthLimit, Map<Item, ResolutionResult> cache) {

        Map<Item, Integer> flatCost = new HashMap<>();

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;

            ItemStack[] accepted = ingredient.getItems();
            if (accepted.length == 0) return null;

            Item chosen = null;

            // 1st priority: item is already a base item.
            for (ItemStack is : accepted) {
                if (baseItems.contains(is.getItem())) {
                    chosen = is.getItem();
                    break;
                }
            }

            // 2nd priority: item has recipes and can resolve back to our base items.
            if (chosen == null) {
                for (ItemStack is : accepted) {
                    Item candidate = is.getItem();

                    if (index.getRecipesProducing(candidate).isEmpty()) continue;

                    // Fresh visited here is intentional: this is a side trial
                    // testing whether the candidate is resolvable at all,
                    // independent of the parent's chain.
                    ResolutionResult trial = resolveWithByproducts(
                        candidate,
                        1,
                        baseItems,
                        index,
                        depthLimit - 1,
                        new HashSet<>(),
                        cache
                    );

                    if (trial != null) {
                        chosen = candidate;
                        break;
                    }
                }
            }

            if (chosen == null) {
                return null;
            }

            flatCost.merge(chosen, batches, Integer::sum);
        }

        return flatCost;
    }

    /**
     * Keeps the resolver greedy, but makes the first recipe less likely to be
     * a high-output bulk recipe when the requested quantity is tiny.
     *
     * This does not evaluate multiple routes. It only changes iteration order.
     * That preserves the old speed profile while nudging cases like:
     *
     * Quark: 2 logs -> 16 sticks
     * Vanilla-ish: planks -> sticks
     *
     * toward the smaller-output recipe when only 1-2 sticks are needed.
     */
    private static List<Recipe<?>> orderedRecipesFor(Item target, RecipeIndex index, int quantity) {
        List<Recipe<?>> recipes = new ArrayList<>(index.getRecipesProducing(target));

        if (recipes.size() <= 1) {
            return recipes;
        }

        recipes.sort(Comparator
            .comparingInt((Recipe<?> r) -> recipeBulkWaste(r, index, quantity))
            .thenComparingInt(CostResolver::recipeIngredientCount)
            .thenComparing(CostResolver::recipeIdString)
        );

        return recipes;
    }

    private static int recipeBulkWaste(Recipe<?> recipe, RecipeIndex index, int quantity) {
        ItemStack output = index.getOutput(recipe);
        if (output.isEmpty()) return 0;

        int outputCount = Math.max(1, output.getCount());
        int batches = ceilDiv(quantity, outputCount);
        int produced = batches * outputCount;

        return Math.max(0, produced - quantity);
    }

    private static int recipeIngredientCount(Recipe<?> recipe) {
        int count = 0;

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) {
                count++;
            }
        }

        return count;
    }

    private static String recipeIdString(Recipe<?> recipe) {
        var id = recipe.getId();
        return id == null ? "unknown" : id.toString();
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}