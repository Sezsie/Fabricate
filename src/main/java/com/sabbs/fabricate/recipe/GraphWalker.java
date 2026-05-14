package com.sabbs.fabricate.recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.sabbs.fabricate.Fabricate;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Walks the recipe graph finding all reachable products and generating
 * synthetic recipes with proper material refunds.
 * Supports both single-material and multi-material recipes.
 */
public class GraphWalker {

    public record SyntheticRecipe(Map<Item, Integer> baseCosts, ItemStack output, List<ItemStack> refundItems) {
        public int totalInputCount() {
            return baseCosts.values().stream().mapToInt(Integer::intValue).sum();
        }

        public String generateId() {
            String basePart = baseCosts.entrySet().stream()
                .sorted(Comparator.comparing(e -> ForgeRegistries.ITEMS.getKey(e.getKey()).getPath()))
                .map(e -> ForgeRegistries.ITEMS.getKey(e.getKey()).getPath()
                    + (e.getValue() > 1 ? "x" + e.getValue() : ""))
                .collect(Collectors.joining("."));
            var outputKey = ForgeRegistries.ITEMS.getKey(output.getItem());
            return basePart + "_to_" + outputKey.getPath();
        }
    }

    /**
     * Generate single-material synthetic recipes reachable from one base item.
     * Uses a per-call memoization cache for performance.
     * Folds output-item waste into the output count for accurate yields.
     */
    public static List<SyntheticRecipe> walkSingle(Item baseItem, RecipeIndex index, int maxDepth,
                                                    int minInputCount, int maxInputCount) {
        Map<Item, SyntheticRecipe> best = new LinkedHashMap<>();
        Set<Item> baseItems = Set.of(baseItem);
        Map<Item, CostResolver.ResolutionResult> cache = new HashMap<>();

        for (Item outputItem : index.getAllOutputItems()) {
            if (outputItem == baseItem) continue;

            CostResolver.ResolutionResult result = CostResolver.resolveWithByproducts(
                outputItem, 1, baseItems, index, maxDepth, new HashSet<>(), cache
            );

            if (result == null) continue;
            if (result.baseCost().size() != 1 || !result.baseCost().containsKey(baseItem)) continue;

            int count = result.baseCost().get(baseItem);
            if (count < minInputCount || count > maxInputCount) continue;

            // Fold waste of the output item into the output count.
            // E.g., resolving "stick" from oak_log produces 1 desired + 3 waste = 4 sticks total.
            // This gives accurate yields for buildMultiMaterial's production map.
            int outputWaste = result.byproducts().getOrDefault(outputItem, 0);
            int outputCount = 1 + outputWaste;
            Map<Item, Integer> adjustedByproducts = new HashMap<>(result.byproducts());
            adjustedByproducts.remove(outputItem);

            List<ItemStack> refunds = buildRefundList(adjustedByproducts);
            SyntheticRecipe syn = new SyntheticRecipe(
                Map.of(baseItem, count), new ItemStack(outputItem, outputCount), refunds
            );
            best.merge(outputItem, syn, (old, neu) ->
                neu.totalInputCount() < old.totalInputCount() ? neu : old);
        }

        return new ArrayList<>(best.values());
    }

    /**
     * Build multi-material recipes by combining vanilla recipes with production knowledge.
     *
     * Production map is built from:
     * 1. Single-material walkSingle results (e.g., 1 oak_log → 4 sticks)
     * 2. Vanilla single-input-type recipes (e.g., 1 iron_block → 9 iron_ingots)
     *
     * For each vanilla recipe with 2+ ingredient types, generates all useful
     * material combinations by trying each ingredient kept as-is or substituted
     * with each available producer.
     */
    public static List<SyntheticRecipe> buildMultiMaterial(
            Set<Item> allBaseItems, List<SyntheticRecipe> singleResults,
            RecipeIndex index, int minInputCount, int maxInputCount) {

        // Build production map: item → list of ways to produce it from a single base material
        Map<Item, List<SyntheticRecipe>> productionMap = new HashMap<>();

        // Source 1: walkSingle results (e.g., oak_log → sticks through planks)
        for (SyntheticRecipe syn : singleResults) {
            productionMap.computeIfAbsent(syn.output().getItem(), k -> new ArrayList<>()).add(syn);
        }

        // Source 2: vanilla single-input-type recipes (e.g., iron_block → 9 iron_ingots)
        addVanillaProducers(productionMap, allBaseItems, index);

        // Deduplicate: keep cheapest producer per (output item, base item) pair
        for (var mapEntry : productionMap.entrySet()) {
            Map<Item, SyntheticRecipe> bestPerBase = new LinkedHashMap<>();
            for (SyntheticRecipe syn : mapEntry.getValue()) {
                if (syn.baseCosts().size() != 1) continue;
                Item base = syn.baseCosts().keySet().iterator().next();
                bestPerBase.merge(base, syn, (old, neu) ->
                    neu.totalInputCount() < old.totalInputCount() ? neu : old);
            }
            mapEntry.setValue(new ArrayList<>(bestPerBase.values()));
        }

        Map<String, SyntheticRecipe> best = new LinkedHashMap<>();
        int recipesExamined = 0;
        int recipesWithMultiIng = 0;

        // Pre-compute the set of items that actually appear as an ingredient
        // in some recipe. Folding multi-base synthetics whose output is never
        // used as an ingredient (terminal outputs like comparator itself) is
        // wasted work and only inflates productionMap.
        Set<Item> usedAsIngredient = new HashSet<>();
        for (Item out : index.getAllOutputItems()) {
            for (Recipe<?> r : index.getRecipesProducing(out)) {
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    for (ItemStack is : ing.getItems()) {
                        if (!is.isEmpty()) usedAsIngredient.add(is.getItem());
                    }
                }
            }
        }

        // Iterate phase 2: each pass can produce multi-base synthetics that
        // feed back into productionMap as substitutes for the next pass. This
        // is what unlocks recipes whose vanilla form requires an intermediate
        // that itself needs 2+ base types (comparator needs redstone_torch =
        // stick+redstone, etc.) and chains of those (a recipe needing
        // comparator as ingredient, etc.).
        //
        // Safety: MAX_PRODUCERS_PER_ITEM is a GLOBAL cap on productionMap
        // entries per item, applied after every fold. This bounds per-slot
        // option count regardless of iteration depth, so iteration count
        // costs linear extra work (one more outer-loop pass), not
        // exponential blow-up.
        final int MAX_ITERATIONS = 5;
        final int MAX_PRODUCERS_PER_ITEM = 3;
        int prevBestSize = -1;
        int iteration = 0;

        // Parallel id-set per item for O(1) fold-back dedup. Using
        // List.contains on SyntheticRecipe triggers the record's
        // auto-generated deep equals (recursively compares baseCosts Map
        // and refundItems List<ItemStack>), which on a large modpack
        // cumulatively exceeds the 60s ServerHangWatchdog budget across
        // 5 iterations. generateId() returns a String, so HashSet
        // operations are O(1) amortized.
        Map<Item, Set<String>> producerIds = new HashMap<>();
        for (var entry : productionMap.entrySet()) {
            Set<String> ids = new HashSet<>();
            for (SyntheticRecipe s : entry.getValue()) ids.add(s.generateId());
            producerIds.put(entry.getKey(), ids);
        }
        while (iteration < MAX_ITERATIONS && best.size() != prevBestSize) {
            prevBestSize = best.size();
            iteration++;
            boolean firstPass = (iteration == 1);

        for (Item outputItem : index.getAllOutputItems()) {
            for (Recipe<?> recipe : index.getRecipesProducing(outputItem)) {
                if (firstPass) recipesExamined++;
                Map<Item, Integer> ingredients = flattenVanillaIngredients(recipe, allBaseItems);
                if (ingredients == null || ingredients.size() < 2) continue;
                if (firstPass) recipesWithMultiIng++;

                // Build map: chosen item → all items accepted by the original tag/ingredient.
                // This lets us find producers for ALL tag members (e.g., all plank types),
                // not just the one concrete item flattenVanillaIngredients picked.
                Map<Item, Set<Item>> tagMembers = new HashMap<>();
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    ItemStack[] accepted = ingredient.getItems();
                    if (accepted.length == 0) continue;

                    Item chosen = null;
                    for (ItemStack is : accepted) {
                        if (allBaseItems.contains(is.getItem())) { chosen = is.getItem(); break; }
                    }
                    if (chosen == null) chosen = accepted[0].getItem();

                    if (ingredients.containsKey(chosen)) {
                        Set<Item> members = tagMembers.computeIfAbsent(chosen, k -> new HashSet<>());
                        for (ItemStack is : accepted) {
                            members.add(is.getItem());
                        }
                    }
                }

                // For each ingredient, collect all options (keep as-is + each producer)
                List<Item> ingItems = new ArrayList<>(ingredients.keySet());
                List<Integer> ingQtys = new ArrayList<>(ingredients.values());
                List<List<SubOption>> allOptions = new ArrayList<>();
                boolean tooMany = false;

                for (int i = 0; i < ingItems.size(); i++) {
                    Item ing = ingItems.get(i);
                    int qty = ingQtys.get(i);
                    List<SubOption> options = new ArrayList<>();

                    // Option A: keep as-is  for ALL base items accepted by the tag.
                    Set<Item> tagAccepted = tagMembers.getOrDefault(ing, Set.of(ing));
                    for (Item member : tagAccepted) {
                        if (allBaseItems.contains(member)) {
                            options.add(SubOption.of(Map.of(member, qty), Map.of()));
                        }
                    }

                    // Option B: substitute with producers from ALL tag members.
                    // Multi-base producers are allowed so an ingredient like
                    // redstone_torch (which itself decomposes to stick+redstone)
                    // can be substituted, enabling synthetics for outputs whose
                    // vanilla recipe contains intermediates with 2+ base types
                    // (comparator, blaze rod compounds, paper variants, ...).
                    // Dedup by record identity  SyntheticRecipe is a value
                    // type, so equal cost+output+refunds collapse naturally.
                    Set<Item> searchItems = tagMembers.getOrDefault(ing, Set.of(ing));
                    Set<SyntheticRecipe> bestProducers = new LinkedHashSet<>();
                    for (Item searchItem : searchItems) {
                        bestProducers.addAll(productionMap.getOrDefault(searchItem, List.of()));
                    }

                    bestProducers.stream()
                        .sorted(Comparator.comparingInt(SyntheticRecipe::totalInputCount))
                        .forEach(producer -> {
                            int synOutputCount = producer.output().getCount();
                            int batches = ceilDiv(qty, synOutputCount);
                            int produced = batches * synOutputCount;
                            int waste = produced - qty;

                            Map<Item, Integer> cost = new LinkedHashMap<>();
                            for (var ce : producer.baseCosts().entrySet()) {
                                cost.put(ce.getKey(), ce.getValue() * batches);
                            }

                            Map<Item, Integer> byproducts = new HashMap<>();
                            if (waste > 0) byproducts.put(producer.output().getItem(), waste);
                            for (ItemStack refund : producer.refundItems()) {
                                byproducts.merge(refund.getItem(), refund.getCount() * batches, Integer::sum);
                            }

                            options.add(SubOption.of(cost, byproducts));
                        });

                    if (options.isEmpty()) { tooMany = true; break; }
                    allOptions.add(options);
                }

                if (tooMany) continue;
                // Skip recipes with too many ingredient types. Matches the
                // 9-base-material cap in generateCombinations so the source
                // ceiling and the synthetic ceiling agree.
                if (ingItems.size() > 9) continue;
                // Guard against combinatorial explosion from tag-expanded
                // options. Bumped from 50k to 200k to give 6-9-ingredient
                // recipes room to fully enumerate (worst realistic case
                // ~4^9 = 262k, but the prune in generateCombinations cuts
                // most of that subtree).
                long totalCombinations = 1;
                for (List<SubOption> opts : allOptions) {
                    totalCombinations *= opts.size();
                    if (totalCombinations > 200000) { tooMany = true; break; }
                }
                if (tooMany) continue;

                // Recipe-natural output count. Stair recipes give 4, milk-
                // bottle-style recipes give 4, etc. Hardcoding 1 here meant
                // a player consuming 4 bottles + 1 bucket got only 1 bottle
                // back, AND every recipe that used the multi-output as an
                // intermediate over-paid because batch counts assumed
                // producer-yield = 1. Falling back to 1 only when the
                // recipe somehow reports an empty result.
                int outputCount = Math.max(1, index.getOutput(recipe).getCount());

                // Generate all valid combinations
                generateCombinations(allOptions, 0,
                    new LinkedHashMap<>(), new HashMap<>(), 0,
                    outputItem, outputCount, minInputCount, maxInputCount, best);
            }
        }

            // Fold this iteration's results into productionMap so the next
            // pass can substitute them, but only for outputs that are
            // actually used as ingredients elsewhere. Then trim each
            // affected item's full producer list to MAX_PRODUCERS_PER_ITEM
            // cheapest globally. This is what makes raising MAX_ITERATIONS
            // safe: per-slot option count stays bounded across all passes.
            Set<Item> touchedOutputs = new HashSet<>();
            for (SyntheticRecipe syn : best.values()) {
                Item out = syn.output().getItem();
                if (!usedAsIngredient.contains(out)) continue;
                Set<String> ids = producerIds.computeIfAbsent(out, k -> new HashSet<>());
                if (ids.add(syn.generateId())) {
                    productionMap.computeIfAbsent(out, k -> new ArrayList<>()).add(syn);
                }
                touchedOutputs.add(out);
            }
            int trimmed = 0;
            for (Item out : touchedOutputs) {
                List<SyntheticRecipe> producers = productionMap.get(out);
                if (producers == null || producers.size() <= MAX_PRODUCERS_PER_ITEM) continue;
                producers.sort(Comparator.comparingInt(SyntheticRecipe::totalInputCount));
                int over = producers.size() - MAX_PRODUCERS_PER_ITEM;
                producers.subList(MAX_PRODUCERS_PER_ITEM, producers.size()).clear();
                // Resync id-set with the trimmed list so future passes
                // don't see ghost ids for entries we just dropped.
                Set<String> ids = producerIds.computeIfAbsent(out, k -> new HashSet<>());
                ids.clear();
                for (SyntheticRecipe s : producers) ids.add(s.generateId());
                trimmed += over;
            }
            Fabricate.LOGGER.info("[FAB-gen] phase 2 iteration {}: {} multi-material synthetics (delta={}, trimmed={})",
                iteration, best.size(), best.size() - prevBestSize, trimmed);
        }

        Fabricate.LOGGER.info("Fabricate Multi: examined {} recipes, {} had 2+ ingredient types, generated {} multi-material recipes ({} iterations)",
            recipesExamined, recipesWithMultiIng, best.size(), iteration);
        return new ArrayList<>(best.values());
    }

    /**
     * Substitution option for one ingredient slot. {@code costTotal} is the
     * sum of {@code cost} values precomputed at construction so the DFS
     * pruner can check against {@code maxInputCount} in O(1).
     */
    private record SubOption(Map<Item, Integer> cost, Map<Item, Integer> byproducts, int costTotal) {
        static SubOption of(Map<Item, Integer> cost, Map<Item, Integer> byproducts) {
            int total = 0;
            for (int v : cost.values()) total += v;
            return new SubOption(cost, byproducts, total);
        }
    }

    private static void generateCombinations(
            List<List<SubOption>> allOptions, int idx,
            Map<Item, Integer> currentCost, Map<Item, Integer> currentByproducts,
            int currentTotal,
            Item outputItem, int outputCount, int minInputCount, int maxInputCount,
            Map<String, SyntheticRecipe> best) {

        // Prune: any extension of this branch only grows currentTotal, so
        // once we're over the cap the whole subtree is dead. Cuts the 50k
        // combinatorial cap by ~orders of magnitude on recipes with bulky
        // substitutions (e.g. iron_block → 9 ingots per slot).
        if (currentTotal > maxInputCount) return;

        if (idx == allOptions.size()) {
            if (currentCost.size() < 2) return;
            if (currentTotal < minInputCount) return;
            if (currentCost.size() > 9) return;

            List<ItemStack> refunds = buildRefundList(currentByproducts);
            SyntheticRecipe syn = new SyntheticRecipe(
                new LinkedHashMap<>(currentCost), new ItemStack(outputItem, outputCount), refunds
            );
            String id = syn.generateId();
            best.merge(id, syn, (old, neu) ->
                neu.totalInputCount() < old.totalInputCount() ? neu : old);
            return;
        }

        // Backtrack: apply option in-place, recurse, then undo. Avoids two
        // map clones per recursion step  with up to 50k combinations per
        // recipe × N recipes during reload this is the dominant allocation.
        for (SubOption option : allOptions.get(idx)) {
            option.cost.forEach((item, count) -> currentCost.merge(item, count, Integer::sum));
            option.byproducts.forEach((item, count) -> currentByproducts.merge(item, count, Integer::sum));

            generateCombinations(allOptions, idx + 1, currentCost, currentByproducts,
                currentTotal + option.costTotal,
                outputItem, outputCount, minInputCount, maxInputCount, best);

            option.cost.forEach((item, count) -> {
                int v = currentCost.get(item) - count;
                if (v == 0) currentCost.remove(item); else currentCost.put(item, v);
            });
            option.byproducts.forEach((item, count) -> {
                int v = currentByproducts.get(item) - count;
                if (v == 0) currentByproducts.remove(item); else currentByproducts.put(item, v);
            });
        }
    }

    /**
     * Add vanilla single-input-type recipes to the production map.
     * These are recipes where all non-empty ingredients accept the same base item.
     * E.g., 1 iron_block → 9 iron_ingots, 1 oak_log → 4 oak_planks.
     */
    private static void addVanillaProducers(Map<Item, List<SyntheticRecipe>> productionMap,
                                             Set<Item> allBaseItems, RecipeIndex index) {
        for (Item outputItem : index.getAllOutputItems()) {
            for (Recipe<?> recipe : index.getRecipesProducing(outputItem)) {
                // Find all base items that can fill every non-empty ingredient slot
                Set<Item> candidates = null;
                int slotCount = 0;

                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) continue;
                    slotCount++;

                    Set<Item> accepted = new HashSet<>();
                    for (ItemStack is : ingredient.getItems()) {
                        if (allBaseItems.contains(is.getItem())) {
                            accepted.add(is.getItem());
                        }
                    }

                    if (candidates == null) {
                        candidates = new HashSet<>(accepted);
                    } else {
                        candidates.retainAll(accepted);
                    }

                    if (candidates.isEmpty()) break;
                }

                if (candidates == null || candidates.isEmpty() || slotCount == 0) continue;

                ItemStack recipeOutput = index.getOutput(recipe);
                int outputCount = recipeOutput.getCount();

                for (Item candidate : candidates) {
                    if (candidate == outputItem) continue;

                    SyntheticRecipe producer = new SyntheticRecipe(
                        Map.of(candidate, slotCount),
                        new ItemStack(outputItem, outputCount),
                        List.of()
                    );
                    productionMap.computeIfAbsent(outputItem, k -> new ArrayList<>()).add(producer);
                }
            }
        }
    }

    /**
     * Flatten a vanilla recipe's ingredients into item → count.
     * For tag-based ingredients, picks a base item if available, otherwise the first accepted.
     */
    private static Map<Item, Integer> flattenVanillaIngredients(Recipe<?> recipe, Set<Item> baseItems) {
        Map<Item, Integer> result = new LinkedHashMap<>();

        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            ItemStack[] accepted = ingredient.getItems();
            if (accepted.length == 0) return null;

            Item chosen = null;
            // Prefer base items
            for (ItemStack is : accepted) {
                if (baseItems.contains(is.getItem())) {
                    chosen = is.getItem();
                    break;
                }
            }
            if (chosen == null) {
                chosen = accepted[0].getItem();
            }

            result.merge(chosen, 1, Integer::sum);
        }

        return result.isEmpty() ? null : result;
    }

    private static List<ItemStack> buildRefundList(Map<Item, Integer> byproducts) {
        List<ItemStack> refunds = new ArrayList<>();
        byproducts.forEach((item, qty) -> {
            if (qty > 0) {
                refunds.add(new ItemStack(item, qty));
            }
        });
        return refunds;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
