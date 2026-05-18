package com.sabbs.fabricate.planner;

import com.sabbs.fabricate.planner.CraftGraph.IngredientSlot;
import com.sabbs.fabricate.planner.CraftGraph.RecipeEdge;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plans how to craft a target item from a player's inventory using recipes
 * in {@link CraftGraph}. Returns a {@link CraftPlan} (ordered recipe steps
 * + base material cost + byproduct refunds) or empty if no reachable plan.
 *
 * <p><b>Inventory-quantity-aware:</b> when an intermediate is partially
 * available in inventory, the planner consumes what's there and recurses
 * to craft the shortfall from a recipe. So if you need 5 planks and have
 * 1 plank + a log, the plan is "use 1 plank + craft 4 planks from 1 log",
 * not "no plan because we'd need 5 planks and you only have 1".
 *
 * <p><b>Top-level target excluded from inventory:</b> always plans a recipe
 * execution for the target, even if some are already in inventory. Matches
 * click-to-craft semantics ("craft one more").
 *
 * <p><b>Byproduct sharing:</b> waste from earlier steps is added to a
 * byproducts pool that subsequent slots can consume from before falling
 * back to inventory or further recursion.
 */
public final class CraftPlanner {

    private static final int DEFAULT_MAX_DEPTH = 16;

    private final CraftGraph graph;
    private final int maxDepth;

    public CraftPlanner(CraftGraph graph) {
        this(graph, DEFAULT_MAX_DEPTH);
    }

    public CraftPlanner(CraftGraph graph, int maxDepth) {
        this.graph = graph;
        this.maxDepth = maxDepth;
    }

    /**
     * Convenience: plan assuming the player has 3x3 (workstation) access.
     */
    public Optional<CraftPlan> plan(Item target, int qty, Map<Item, Integer> inventory) {
        return plan(target, qty, inventory, true);
    }

    /**
     * Plans how to obtain {@code qty} of {@code target} given {@code inventory}.
     *
     * <p>If {@code has3x3} is {@code false}, recipes that require a crafting
     * table are filtered out at every depth, since the planner mimics real
     * crafting and the player would need a workstation to execute those
     * steps. So at a 2x2 inventory grid you can plan planks-from-logs and
     * sticks-from-planks but not wooden_shovel (top-level OR intermediate).
     */
    public Optional<CraftPlan> plan(Item target, int qty, Map<Item, Integer> inventory, boolean has3x3) {
        // Working copy of inventory; mutated as we consume during planning.
        // Exclude top-level target so we always craft fresh.
        Map<Item, Integer> remainingInv = new HashMap<>(inventory);
        remainingInv.remove(target);

        Map<Item, Integer> baseCost = new HashMap<>();
        Map<Item, Integer> byproducts = new HashMap<>();
        List<CraftPlan.Step> steps = new ArrayList<>();

        boolean ok = resolve(target, qty, remainingInv, baseCost, byproducts, steps, new HashSet<>(), 0, has3x3);
        if (!ok) return Optional.empty();

        return Optional.of(new CraftPlan(target, qty, steps, baseCost, byproducts));
    }

    /**
     * Resolve {@code qty} of {@code target}. Takes what's available from
     * {@code remainingInv}, recurses for the shortfall via a recipe. On
     * failure, mutations are rolled back so callers can try alternatives.
     */
    private boolean resolve(
        Item target, int qty, Map<Item, Integer> remainingInv,
        Map<Item, Integer> baseCost, Map<Item, Integer> byproducts,
        List<CraftPlan.Step> steps, Set<Item> visited, int depth, boolean has3x3
    ) {
        // Phase 1: take what we can from inventory.
        int available = remainingInv.getOrDefault(target, 0);
        int taken = Math.min(available, qty);
        int stillNeed = qty - taken;

        if (taken > 0) {
            dec(remainingInv, target, taken);
            baseCost.merge(target, taken, Integer::sum);
        }

        if (stillNeed == 0) return true;

        // Phase 2: cycle / depth guard.
        if (depth >= maxDepth || visited.contains(target)) {
            // Roll back the inventory take so the caller's state is consistent.
            if (taken > 0) {
                remainingInv.merge(target, taken, Integer::sum);
                dec(baseCost, target, taken);
            }
            return false;
        }

        // Phase 3: try recipes for the shortfall.
        visited.add(target);
        try {
            for (RecipeEdge recipe : graph.getRecipesProducing(target)) {
                // Workstation gating: at a 2x2 inventory grid we can't
                // execute recipes that require a full 3x3 crafting table,
                // even as intermediate steps. The planner mimics real
                // crafting, so each step must be runnable in the player's
                // current grid.
                if (!has3x3 && recipe.requiresCraftingTable()) continue;

                Map<Item, Integer> invSnap = new HashMap<>(remainingInv);
                Map<Item, Integer> baseSnap = new HashMap<>(baseCost);
                Map<Item, Integer> bpSnap = new HashMap<>(byproducts);
                int stepsSnap = steps.size();

                if (tryRecipe(recipe, target, stillNeed, remainingInv, baseCost, byproducts, steps, visited, depth, has3x3)) {
                    return true;
                }
                // Recipe failed - restore state and try the next one.
                restore(remainingInv, invSnap);
                restore(baseCost, baseSnap);
                restore(byproducts, bpSnap);
                while (steps.size() > stepsSnap) steps.remove(steps.size() - 1);
            }
            // No recipe worked - roll back the original inventory take.
            if (taken > 0) {
                remainingInv.merge(target, taken, Integer::sum);
                dec(baseCost, target, taken);
            }
            return false;
        } finally {
            visited.remove(target);
        }
    }

    /**
     * Attempt to execute {@code recipe} {@code batches} times for {@code qty}
     * of {@code target}. Each ingredient slot is resolved via the byproduct
     * pool first, then via inventory/recursion. On any failure, the full
     * recipe-attempt state is rolled back.
     */
    private boolean tryRecipe(
        RecipeEdge recipe, Item target, int qty, Map<Item, Integer> remainingInv,
        Map<Item, Integer> baseCost, Map<Item, Integer> byproducts,
        List<CraftPlan.Step> steps, Set<Item> visited, int depth, boolean has3x3
    ) {
        int outputPerBatch = Math.max(1, recipe.outputCount());
        int batches = ceilDiv(qty, outputPerBatch);
        int produced = batches * outputPerBatch;
        int waste = produced - qty;

        // Aggregate slots with identical accepted-sets so multiple identical
        // slots batch together (e.g., 8 plank slots in a chest recipe -> one
        // "need 8 planks" entry).
        Map<Set<Item>, Integer> aggregated = new LinkedHashMap<>();
        for (IngredientSlot slot : recipe.inputs()) {
            aggregated.merge(slot.acceptedItems(), batches, Integer::sum);
        }

        Map<Item, Integer> invSnap = new HashMap<>(remainingInv);
        Map<Item, Integer> baseSnap = new HashMap<>(baseCost);
        Map<Item, Integer> bpSnap = new HashMap<>(byproducts);
        int stepsSnap = steps.size();

        Map<Item, Integer> stepConsumed = new HashMap<>();

        for (var entry : aggregated.entrySet()) {
            Set<Item> acceptedSet = entry.getKey();
            int needQty = entry.getValue();

            // Phase A: reuse from byproducts pool. Sibling slots from earlier
            // in this recipe may have produced waste this slot can consume.
            for (Item candidate : acceptedSet) {
                if (needQty == 0) break;
                int avail = byproducts.getOrDefault(candidate, 0);
                if (avail <= 0) continue;
                int use = Math.min(avail, needQty);
                dec(byproducts, candidate, use);
                stepConsumed.merge(candidate, use, Integer::sum);
                needQty -= use;
            }

            if (needQty == 0) continue;

            // Phase B: resolve the remaining need via inventory + recursion.
            // Try accepted items in inventory-preferred order; resolve handles
            // partial inventory takes + recipe recursion internally.
            Item chosen = null;
            int chosenQty = needQty;
            for (Item accepted : preferredOrder(acceptedSet, remainingInv)) {
                if (resolve(accepted, needQty, remainingInv, baseCost, byproducts, steps, visited, depth + 1, has3x3)) {
                    chosen = accepted;
                    break;
                }
            }
            if (chosen == null) {
                // No accepted item could be satisfied; abandon this recipe.
                restore(remainingInv, invSnap);
                restore(baseCost, baseSnap);
                restore(byproducts, bpSnap);
                while (steps.size() > stepsSnap) steps.remove(steps.size() - 1);
                return false;
            }
            stepConsumed.merge(chosen, chosenQty, Integer::sum);
        }

        if (waste > 0) byproducts.merge(target, waste, Integer::sum);
        steps.add(new CraftPlan.Step(recipe, batches, stepConsumed));
        return true;
    }

    /**
     * Sort accepted items so the ones already in inventory come first,
     * highest-count first (gives the planner the best shot at satisfying
     * needQty entirely from inventory). Items not in inventory follow in
     * the accepted set's iteration order.
     */
    private static List<Item> preferredOrder(Set<Item> accepted, Map<Item, Integer> remainingInv) {
        List<Item> inInv = new ArrayList<>();
        List<Item> notInInv = new ArrayList<>();
        for (Item item : accepted) {
            if (remainingInv.containsKey(item)) inInv.add(item);
            else notInInv.add(item);
        }
        inInv.sort((a, b) -> Integer.compare(
            remainingInv.getOrDefault(b, 0),
            remainingInv.getOrDefault(a, 0)));
        List<Item> ordered = new ArrayList<>(accepted.size());
        ordered.addAll(inInv);
        ordered.addAll(notInInv);
        return ordered;
    }

    private static void dec(Map<Item, Integer> map, Item item, int amount) {
        int v = map.getOrDefault(item, 0) - amount;
        if (v <= 0) map.remove(item);
        else map.put(item, v);
    }

    private static <K, V> void restore(Map<K, V> target, Map<K, V> snap) {
        target.clear();
        target.putAll(snap);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
