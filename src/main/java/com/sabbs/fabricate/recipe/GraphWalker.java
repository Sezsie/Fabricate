package com.sabbs.fabricate.recipe;

import java.util.ArrayList;
import java.util.Collections;
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

        // Build production map: item → list of ways to produce it from
        // fully-reduced base materials. The "fully reduced" invariant is
        // load-bearing: Option B in the iteration loop copies a producer's
        // baseCosts verbatim into the substituted synthetic's cost vector,
        // so a producer carrying an intermediate (shaft, book, paper) would
        // propagate that intermediate forward forever. Only producers whose
        // baseCosts contain exclusively non-producible items extend the
        // chain deeper.
        Map<Item, List<SyntheticRecipe>> productionMap = new HashMap<>();

        // Items producible by some vanilla recipe. Anything not in this set
        // is a true base material (raw drop, mob drop, world-gen, smelting
        // result, etc.). Computed once, used both for the initial productionMap
        // filter and the per-iteration fold-back filter.
        final Set<Item> producibleItems = new HashSet<>(index.getAllOutputItems());

        // Source 1: walkSingle results (e.g., oak_log → sticks through planks)
        for (SyntheticRecipe syn : singleResults) {
            productionMap.computeIfAbsent(syn.output().getItem(), k -> new ArrayList<>()).add(syn);
        }

        // Source 2: vanilla single-input-type recipes (e.g., iron_block → 9 iron_ingots)
        addVanillaProducers(productionMap, allBaseItems, index);

        // Dedup initial productionMap to the cheapest single-base producer
        // per (output, base) pair. We intentionally DO NOT filter out
        // single-base producers whose base is itself a recipe output:
        // metals (iron_ingot, gold_ingot, copper_ingot) are recipe outputs
        // via block↔ingot↔nugget conversions but the player obtains them
        // primarily via smelting, which lives outside RecipeType.CRAFTING
        // and so is invisible to us. Dropping {iron_block:1}->9 iron_ingot
        // here would prevent iron_sword from substituting iron_ingot with
        // iron_block, which is the substitution Fabricate is meant to
        // enable. The fold-back step below handles the "no intermediates
        // propagating forward" invariant where it actually matters (in the
        // synthetics phase 2 generates), without breaking the smelting
        // substitution path.
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
        // in some recipe. Folding synthetics whose output is never used as an
        // ingredient (terminal outputs like comparator itself) is wasted work
        // and only inflates productionMap.
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
        // MAX_PRODUCERS_PER_ITEM is the memory backstop. Without it, every
        // iteration's fold-back accumulates more fully-reduced producers per
        // item (one per distinct base-material combination - typically one
        // per log type, dye color, ingot variant), and the producer count
        // compounds geometrically across iterations. Iter 3 of a heavy
        // modpack would balloon best.size() from 80k to 700k to 3M+ and OOM
        // the JVM during Option B's per-producer SubOption allocation.
        //
        // Cap of 10 producers per item. The slot-ordering in phase 2
        // (high-variety slot outer) ensures the top 10 already covers
        // both primary axes (e.g., 5 wood types × 2 dust forms for
        // torch) so cap=10 isn't a correctness bottleneck. Higher caps
        // compounded combinatorial explosion across iterations without
        // proportionate correctness gain.
        final int MAX_ITERATIONS = 5;
        final int MAX_PRODUCERS_PER_ITEM = 10;
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

                    // Sort producers by:
                    //   1. totalInputCount (cheapest first - already had this),
                    //   2. shortest first-base-item path (oak_log = 7 chars
                    //      beats stripped_dark_oak_log = 20 - canonical
                    //      vanilla items players actually have in their
                    //      inventory get preferred positions),
                    //   3. generateId() alphabetical (deterministic
                    //      tiebreaker so the same trim survivors come out
                    //      across mod reloads, regardless of HashMap
                    //      iteration order on baseItemSet).
                    // This decides which producers survive the trim's
                    // stable sort downstream when the (intermediateCount,
                    // totalInputCount) primary criterion ties.
                    bestProducers.stream()
                        .sorted(Comparator
                            .comparingInt(SyntheticRecipe::totalInputCount)
                            .thenComparingInt(GraphWalker::firstBasePathLength)
                            .thenComparing(SyntheticRecipe::generateId))
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
                // Sort slot options by size descending so the high-variety
                // slot becomes the OUTER loop in generateCombinations.
                // Without this, low-variety slots like {raw, block} dust
                // group all their variant insertions together in `best`,
                // which causes the trim's "first-N by insertion" cut to
                // drop one entire variant axis (e.g., all torch+block
                // producers cut while torch+raw all survive). With the
                // high-variety slot outer, insertions alternate variants
                // per outer option, so both axes survive the trim.
                allOptions.sort(Comparator.comparingInt((List<SubOption> opts) -> opts.size()).reversed());
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

            // Fold this iteration's multi-base synthetics into productionMap
            // so the next pass can substitute them. ONE filter applies:
            // output must appear as an ingredient somewhere (no point
            // folding terminal outputs like comparator).
            //
            // We deliberately do NOT filter on intermediateCount here.
            // Items like leather (4 rabbit_hide -> 1 leather), iron_ingot
            // (block <-> ingot <-> nugget), and other circular-subgraph
            // members are technically "producible by crafting" but the
            // player obtains them primarily via non-crafting sources
            // (mob drops, smelting). Filtering them out would prevent
            // writable_book from substituting book = {leather, sugar_cane},
            // iron_sword from substituting iron_ingot = {iron_block}, etc.
            // The trim's (intermediateCount, totalInputCount) sort already
            // gives fully-reduced producers priority - intermediate ones
            // only survive when fully-reduced producers don't fill the
            // MAX_PRODUCERS_PER_ITEM slots.
            int folded = 0;
            Set<Item> touchedOutputs = new HashSet<>();
            for (SyntheticRecipe syn : best.values()) {
                Item out = syn.output().getItem();
                if (!usedAsIngredient.contains(out)) continue;
                Set<String> ids = producerIds.computeIfAbsent(out, k -> new HashSet<>());
                if (ids.add(syn.generateId())) {
                    productionMap.computeIfAbsent(out, k -> new ArrayList<>()).add(syn);
                    folded++;
                    touchedOutputs.add(out);
                }
            }

            // Trim down to MAX_PRODUCERS_PER_ITEM per output. Sort prefers
            // fully-reduced producers (intermediateCount == 0) - critical
            // for the chain to extend - then cheapest by input count.
            // Memory backstop: without this, productionMap grows
            // unboundedly and iter 3+ blows up the heap.
            //
            // Note: we do NOT clear and re-build producerIds after trim.
            // Dropped synthetics keep their ids in the set so the next
            // iteration's fold doesn't re-add them. Without this, every
            // iteration's fold would re-fold the previous iteration's
            // dropped synthetics (since their ids were cleared from the
            // set), trim would drop them again, and the cycle would burn
            // CPU producing identical work each pass. Producer pools
            // stabilize after the first trim instead of oscillating.
            Comparator<SyntheticRecipe> trimOrder =
                Comparator.comparingInt((SyntheticRecipe s) -> intermediateCount(s, producibleItems))
                          .thenComparingInt(SyntheticRecipe::totalInputCount);
            int trimmed = 0;
            for (Item out : touchedOutputs) {
                List<SyntheticRecipe> producers = productionMap.get(out);
                if (producers == null || producers.size() <= MAX_PRODUCERS_PER_ITEM) continue;
                producers.sort(trimOrder);
                int over = producers.size() - MAX_PRODUCERS_PER_ITEM;
                producers.subList(MAX_PRODUCERS_PER_ITEM, producers.size()).clear();
                trimmed += over;
            }
            Fabricate.LOGGER.info("[FAB-gen] phase 2 iteration {}: {} multi-material synthetics (delta={}, folded={}, trimmed={})",
                iteration, best.size(), best.size() - prevBestSize, folded, trimmed);
        }

        Fabricate.LOGGER.info("Fabricate Multi: examined {} recipes, {} had 2+ ingredient types, generated {} multi-material recipes ({} iterations)",
            recipesExamined, recipesWithMultiIng, best.size(), iteration);

        // Stage 2: compaction. Phase 2 does per-slot substitution that
        // can't share intermediate waste across batches: comparator's 3
        // torches each substitute the {stick, redstone_block} producer
        // independently, so the synthetic asks for 3 blocks even though
        // 1 block (= 9 dust) covers all 3 torches' dust demand. The fix
        // is to re-resolve each synthetic from its own baseCosts as
        // baseItems via CostResolver, which DOES share byproducts across
        // siblings during recursion. The new baseCost is at-or-below the
        // original's totalInputCount; when strictly below, we swap in
        // the compacted version. Walk-single synthetics (baseCosts size
        // 1) are skipped because CostResolver already built them
        // optimally.
        //
        // Memoized by (outputItem, sorted baseCosts.keySet()): all
        // synthetics with the same output and same set of input items
        // resolve to the same CostResolver answer regardless of their
        // pre-compaction counts. With this cache, vanilla dev env runs
        // a few thousand CostResolver calls instead of hundreds of
        // thousands.
        Map<String, CostResolver.ResolutionResult> compactCache = new HashMap<>();
        Set<String> compactCacheMiss = new HashSet<>();
        int compactedCount = 0;
        Map<String, SyntheticRecipe> compactedBest = new LinkedHashMap<>();
        long compactStart = System.currentTimeMillis();
        for (SyntheticRecipe syn : best.values()) {
            SyntheticRecipe replacement = compact(syn, index, compactCache, compactCacheMiss);
            if (replacement != syn) compactedCount++;
            compactedBest.merge(replacement.generateId(), replacement, (old, neu) ->
                neu.totalInputCount() < old.totalInputCount() ? neu : old);
        }
        Fabricate.LOGGER.info("[FAB-gen] phase 2 compaction: {} of {} synthetics shrunk (final count: {}, {} cached groups, {}ms)",
            compactedCount, best.size(), compactedBest.size(),
            compactCache.size() + compactCacheMiss.size(),
            System.currentTimeMillis() - compactStart);

        return new ArrayList<>(compactedBest.values());
    }

    /**
     * Re-resolves a phase-2 synthetic from its own baseCosts items as
     * baseItems, using CostResolver's byproduct-sharing recursion. If the
     * result has fewer total inputs than the original, returns a new
     * SyntheticRecipe with the compacted cost and refund list; otherwise
     * returns the original unchanged.
     *
     * <p>Filters out unprofitable calls cheaply before invoking
     * CostResolver: skips single-base synthetics (CostResolver already
     * built them optimally) and skips synthetics whose refund items don't
     * overlap with any cost item's recipe ingredients (no batch-sharing
     * opportunity).
     */
    private static SyntheticRecipe compact(SyntheticRecipe syn, RecipeIndex index,
                                           Map<String, CostResolver.ResolutionResult> cache,
                                           Set<String> negativeCache) {
        // Walk-single synthetics are already optimal; compaction can only
        // match, not improve. Skip.
        if (syn.baseCosts().size() < 2) return syn;

        // Cache key: outputItem + sorted item paths of baseCosts.keySet.
        // Synthetics that share this key resolve to the same CostResolver
        // answer regardless of their pre-compaction quantity counts.
        Item outputItem = syn.output().getItem();
        var outKey = ForgeRegistries.ITEMS.getKey(outputItem);
        if (outKey == null) return syn;
        Set<Item> costItems = syn.baseCosts().keySet();
        List<String> sortedPaths = new ArrayList<>(costItems.size());
        for (Item it : costItems) {
            var k = ForgeRegistries.ITEMS.getKey(it);
            if (k == null) return syn;
            sortedPaths.add(k.toString());
        }
        Collections.sort(sortedPaths);
        String cacheKey = outKey + "|" + String.join(",", sortedPaths);

        CostResolver.ResolutionResult result;
        if (cache.containsKey(cacheKey)) {
            result = cache.get(cacheKey);
        } else if (negativeCache.contains(cacheKey)) {
            return syn;
        } else {
            // Without refunds there's no waste to recover via batch sharing.
            // Apply this filter only on first encounter for this cache key -
            // a later synthetic with the same (output, keySet) may have
            // refunds even if this one doesn't, so the cache key result
            // applies regardless of per-synthetic refund presence.
            if (syn.refundItems().isEmpty()) {
                // Skip without caching; another syn with same key + refunds
                // may still trigger compaction.
                return syn;
            }
            // Opportunity check: is any refund item produced by a recipe
            // whose ingredients include a cost item?
            boolean opportunity = false;
            outer:
            for (ItemStack refund : syn.refundItems()) {
                Item refundItem = refund.getItem();
                for (Recipe<?> r : index.getRecipesProducing(refundItem)) {
                    for (Ingredient ing : r.getIngredients()) {
                        if (ing.isEmpty()) continue;
                        for (ItemStack s : ing.getItems()) {
                            if (costItems.contains(s.getItem())) {
                                opportunity = true;
                                break outer;
                            }
                        }
                    }
                }
            }
            if (!opportunity) {
                negativeCache.add(cacheKey);
                return syn;
            }

            // Re-resolve via CostResolver with the synthetic's baseCosts
            // items as the base material set. Fresh inner cache because
            // baseItems varies per cache-key call.
            result = CostResolver.resolveWithByproducts(
                outputItem,
                syn.output().getCount(),
                costItems,
                index,
                12,
                new HashSet<>(),
                new HashMap<>()
            );
            cache.put(cacheKey, result);
        }

        if (result == null) return syn;

        int newTotal = 0;
        for (int v : result.baseCost().values()) newTotal += v;
        if (newTotal >= syn.totalInputCount()) return syn;

        List<ItemStack> newRefunds = buildRefundList(result.byproducts());
        return new SyntheticRecipe(
            new LinkedHashMap<>(result.baseCost()),
            syn.output().copy(),
            newRefunds
        );
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

    /**
     * Length of the first {@code baseCosts} item's registry path. Used as
     * a deterministic tiebreaker that prefers canonical short-named items
     * (e.g. {@code oak_log} over {@code stripped_dark_oak_log}) when many
     * producers tie on totalInputCount, so the trim's stable-sort
     * survival selection deterministically keeps player-familiar variants.
     */
    private static int firstBasePathLength(SyntheticRecipe s) {
        var it = s.baseCosts().keySet().iterator();
        if (!it.hasNext()) return Integer.MAX_VALUE;
        var key = ForgeRegistries.ITEMS.getKey(it.next());
        return key == null ? Integer.MAX_VALUE : key.getPath().length();
    }

    /**
     * Number of distinct items in {@code syn.baseCosts()} that are
     * themselves outputs of some recipe (i.e., not raw base materials).
     * Used by the fold-back trim to prefer fully-reduced producers, so the
     * iterative substitution chain can reach base materials at depth 3+.
     */
    private static int intermediateCount(SyntheticRecipe syn, Set<Item> producibleItems) {
        int n = 0;
        for (Item i : syn.baseCosts().keySet()) {
            if (producibleItems.contains(i)) n++;
        }
        return n;
    }
}
