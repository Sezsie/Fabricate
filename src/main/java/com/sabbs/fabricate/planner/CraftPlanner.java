package com.sabbs.fabricate.planner;

import com.sabbs.fabricate.planner.CraftGraph.IngredientSlot;
import com.sabbs.fabricate.planner.CraftGraph.RecipeEdge;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;

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
 *
 * <p><b>Planning budget:</b> modded recipe graphs can branch insanely hard.
 * A depth cap alone prevents infinite recursion, but it does not prevent
 * combinatorial explosion. This planner has a hard budget for recursive
 * resolve calls, recipe attempts, and wall-clock time so a single click
 * cannot freeze the server thread.
 */
public final class CraftPlanner {

    private static final int DEFAULT_MAX_DEPTH = 16;

    /**
     * Max recursive resolve calls per plan.
     *
     * <p>This counts every request to obtain some quantity of an item,
     * including intermediate ingredients.
     */
    private static final int DEFAULT_MAX_RESOLVE_CALLS = 2_500;

    /**
     * Max recipe attempts per plan.
     *
     * <p>This counts every candidate recipe the planner tries while searching.
     */
    private static final int DEFAULT_MAX_RECIPE_ATTEMPTS = 1_500;

    /**
     * Max wall-clock planning time per plan.
     *
     * <p>This is deliberately low because planning runs on the server thread.
     * If you want more permissive behavior later, increase this carefully.
     */
    private static final long DEFAULT_MAX_PLAN_TIME_MS = 35L;

    /**
     * How often the budget checks the wall clock.
     *
     * <p>Checking System.nanoTime constantly adds noise. Checking every 64
     * operations is frequent enough to stop runaway searches quickly.
     */
    private static final int TIME_CHECK_INTERVAL = 64;

    private final CraftGraph graph;
    private final int maxDepth;
    private final int maxResolveCalls;
    private final int maxRecipeAttempts;
    private final long maxPlanTimeNanos;

    public CraftPlanner(CraftGraph graph) {
        this(
            graph,
            DEFAULT_MAX_DEPTH,
            DEFAULT_MAX_RESOLVE_CALLS,
            DEFAULT_MAX_RECIPE_ATTEMPTS,
            DEFAULT_MAX_PLAN_TIME_MS
        );
    }

    public CraftPlanner(CraftGraph graph, int maxDepth) {
        this(
            graph,
            maxDepth,
            DEFAULT_MAX_RESOLVE_CALLS,
            DEFAULT_MAX_RECIPE_ATTEMPTS,
            DEFAULT_MAX_PLAN_TIME_MS
        );
    }

    public CraftPlanner(CraftGraph graph, int maxDepth, int maxResolveCalls,
                        int maxRecipeAttempts, long maxPlanTimeMs) {
        this.graph = graph;
        this.maxDepth = maxDepth;
        this.maxResolveCalls = Math.max(1, maxResolveCalls);
        this.maxRecipeAttempts = Math.max(1, maxRecipeAttempts);
        this.maxPlanTimeNanos = Math.max(1L, maxPlanTimeMs) * 1_000_000L;
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
        Budget budget = new Budget(
            target,
            qty,
            maxResolveCalls,
            maxRecipeAttempts,
            maxPlanTimeNanos
        );

        try {
            // Working copy of inventory; mutated as we consume during planning.
            // Exclude top-level target so we always craft fresh.
            Map<Item, Integer> remainingInv = new HashMap<>(inventory);
            remainingInv.remove(target);

            Map<Item, Integer> baseCost = new HashMap<>();
            Map<Item, Integer> byproducts = new HashMap<>();
            Map<Item, Integer> toolDamage = new HashMap<>();
            List<CraftPlan.Step> steps = new ArrayList<>();

            boolean ok = resolve(
                target,
                qty,
                remainingInv,
                baseCost,
                byproducts,
                toolDamage,
                steps,
                new HashSet<>(),
                0,
                has3x3,
                budget
            );

            if (!ok) return Optional.empty();

            return Optional.of(new CraftPlan(target, qty, steps, baseCost, byproducts, toolDamage));
        } catch (BudgetExceededException e) {
            com.sabbs.fabricate.Fabricate.LOGGER.debug(
                "[FAB-planner] planning budget exceeded for {}x {}: {}",
                qty,
                target,
                e.getMessage()
            );
            return Optional.empty();
        }
    }

    /**
     * Resolve {@code qty} of {@code target}. Takes what's available from
     * {@code remainingInv}, recurses for the shortfall via a recipe. On
     * failure, mutations are rolled back so callers can try alternatives.
     */
    private boolean resolve(
        Item target, int qty, Map<Item, Integer> remainingInv,
        Map<Item, Integer> baseCost, Map<Item, Integer> byproducts,
        Map<Item, Integer> toolDamage,
        List<CraftPlan.Step> steps, Set<Item> visited, int depth, boolean has3x3,
        Budget budget
    ) {
        budget.countResolve(target, qty, depth);

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
                budget.countRecipeAttempt(recipe, target, depth);

                // Workstation gating: at a 2x2 inventory grid we can't
                // execute recipes that require a full 3x3 crafting table,
                // even as intermediate steps. The planner mimics real
                // crafting, so each step must be runnable in the player's
                // current grid.
                if (!has3x3 && recipe.requiresCraftingTable()) continue;

                Map<Item, Integer> invSnap = new HashMap<>(remainingInv);
                Map<Item, Integer> baseSnap = new HashMap<>(baseCost);
                Map<Item, Integer> bpSnap = new HashMap<>(byproducts);
                Map<Item, Integer> tdSnap = new HashMap<>(toolDamage);
                int stepsSnap = steps.size();

                if (tryRecipe(recipe, target, stillNeed, remainingInv, baseCost,
                    byproducts, toolDamage, steps, visited, depth, has3x3, budget)) {
                    return true;
                }

                // Recipe failed - restore state and try the next one.
                restore(remainingInv, invSnap);
                restore(baseCost, baseSnap);
                restore(byproducts, bpSnap);
                restore(toolDamage, tdSnap);
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
        Map<Item, Integer> toolDamage,
        List<CraftPlan.Step> steps, Set<Item> visited, int depth, boolean has3x3,
        Budget budget
    ) {
        budget.checkTimeOnly();

        int outputPerBatch = Math.max(1, recipe.outputCount());
        int batches = ceilDiv(qty, outputPerBatch);
        int produced = batches * outputPerBatch;
        int waste = produced - qty;

        // Aggregate slots with identical accepted-sets so multiple identical
        // slots batch together (e.g., 8 plank slots in a chest recipe -> one
        // "need 8 planks" entry).
        //
        // Reusable-tool slots (hammer, buzzsaw, wrench, ...) get aggregated
        // at 1-per-slot instead of batches-per-slot: a single hammer
        // satisfies all N batches because the recipe returns it untouched
        // (per addRecipeRemainders below). Without this, "craft 11 plates
        // with 1 hammer" fails because the planner thinks it needs 11
        // hammers.
        Map<Set<Item>, Integer> aggregated = new LinkedHashMap<>();
        for (IngredientSlot slot : recipe.inputs()) {
            budget.checkTimeOnly();
            int perSlot = IngredientHeuristics.isReusableSlot(slot.acceptedItems()) ? 1 : batches;
            aggregated.merge(slot.acceptedItems(), perSlot, Integer::sum);
        }

        Map<Item, Integer> invSnap = new HashMap<>(remainingInv);
        Map<Item, Integer> baseSnap = new HashMap<>(baseCost);
        Map<Item, Integer> bpSnap = new HashMap<>(byproducts);
        Map<Item, Integer> tdSnap = new HashMap<>(toolDamage);
        int stepsSnap = steps.size();

        Map<Item, Integer> stepConsumed = new HashMap<>();

        // Reusable items this recipe has already claimed for an earlier slot.
        // Subtracted from byproducts visibility in Phase A so sibling slots
        // can't double-dip the same tool that the recipe instructions show
        // occupying a separate grid cell at the same time. Sub-recipes
        // invoked from Phase B don't see this map, so they DO get to see
        // the freshly-flushed tool in byproducts - which is the whole point
        // of the immediate flush below (the consumption-timing fix).
        Map<Item, Integer> reservedByThisRecipe = new HashMap<>();

        for (var entry : aggregated.entrySet()) {
            budget.checkTimeOnly();

            Set<Item> acceptedSet = entry.getKey();
            int needQty = entry.getValue();
            Map<Item, Integer> consumedThisSlot = new HashMap<>();

            // Phase A: reuse from byproducts pool. Sibling slots from earlier
            // in this recipe may have produced waste this slot can consume.
            // Items already reserved by an earlier sibling slot in THIS
            // recipe are hidden so they can't be double-counted as sibling
            // tools.
            for (Item candidate : acceptedSet) {
                budget.checkTimeOnly();

                if (needQty == 0) break;
                int avail = byproducts.getOrDefault(candidate, 0)
                    - reservedByThisRecipe.getOrDefault(candidate, 0);
                if (avail <= 0) continue;
                int use = Math.min(avail, needQty);
                dec(byproducts, candidate, use);
                stepConsumed.merge(candidate, use, Integer::sum);
                consumedThisSlot.merge(candidate, use, Integer::sum);
                needQty -= use;
            }

            if (needQty > 0) {
                // Phase B: resolve the remaining need via inventory + recursion.
                // Try accepted items in inventory-preferred order; resolve handles
                // partial inventory takes + recipe recursion internally.
                Item chosen = null;
                int chosenQty = needQty;

                List<Item> orderedCandidates = preferredOrder(acceptedSet, remainingInv);
                for (Item accepted : orderedCandidates) {
                    budget.checkTimeOnly();

                    if (resolve(accepted, needQty, remainingInv, baseCost, byproducts,
                        toolDamage, steps, visited, depth + 1, has3x3, budget)) {
                        chosen = accepted;
                        break;
                    }
                }

                if (chosen == null) {
                    // No accepted item could be satisfied; abandon this recipe.
                    restore(remainingInv, invSnap);
                    restore(baseCost, baseSnap);
                    restore(byproducts, bpSnap);
                    restore(toolDamage, tdSnap);
                    while (steps.size() > stepsSnap) steps.remove(steps.size() - 1);
                    return false;
                }

                stepConsumed.merge(chosen, chosenQty, Integer::sum);
                consumedThisSlot.merge(chosen, chosenQty, Integer::sum);
            }

            // Consumption-timing fix: flush reusable-tool remainders to
            // byproducts immediately at slot-end (not batched until end of
            // recipe via addRecipeRemainders). This makes the tool visible
            // to sub-recipes invoked by SUBSEQUENT sibling slots in this
            // recipe.
            //
            // Without this, a recipe like "craft iron_ring (needs iron_rod
            // + file)" calls resolve(iron_rod) AFTER the file slot has been
            // satisfied. resolve(iron_rod) recurses into the iron_rod recipe
            // which also needs a file - but at end-of-tryRecipe-timing, the
            // file isn't in byproducts yet, so the sub-recipe goes hunting
            // through the tag and either eats a higher-tier tool from
            // inventory or exhausts the planning budget.
            //
            // The reservedByThisRecipe entry prevents sibling slots (which
            // run their own Phase A) from treating this freshly-flushed
            // tool as available - they still need their own tool, matching
            // the recipe instructions.
            for (var c : consumedThisSlot.entrySet()) {
                Item it = c.getKey();
                if (!IngredientHeuristics.isReusableItem(it)) continue;
                int count = c.getValue();
                byproducts.merge(it, count, Integer::sum);
                reservedByThisRecipe.merge(it, count, Integer::sum);
            }
        }

        if (waste > 0) byproducts.merge(target, waste, Integer::sum);

        // Compute remainders via the recipe's own getRemainingItems, which
        // catches recipe-level overrides (GregTech hammers, modded tools)
        // that the Item-level hasCraftingRemainingItem check misses. Also
        // extracts the durability cost the recipe inflicts on reusable tools
        // and accumulates it into toolDamage so the execute layer can damage
        // the actual ItemStack instead of returning a pristine clone.
        addRecipeRemainders(recipe, stepConsumed, batches, byproducts, toolDamage);

        steps.add(new CraftPlan.Step(recipe, batches, stepConsumed));
        return true;
    }

    /**
     * Add crafting remainders to {@code byproducts} by invoking the recipe's
     * own {@code getRemainingItems}. Buckets and Item-level remainders are
     * handled by the default impl; modded recipes that override (GregTech
     * hammers, Tinkers tools, etc.) are caught via the override.
     *
     * <p>Builds a per-batch 3x3 {@link net.minecraft.world.inventory.TransientCraftingContainer}
     * from {@code stepConsumed} divided by {@code batches}, calls the recipe,
     * then scales the returned per-batch remainders by {@code batches}.
     * Slot positions are arbitrary - the default {@code getRemainingItems}
     * is position-agnostic and modded overrides typically iterate slots
     * looking for specific items rather than checking specific positions.
     *
     * <p>Falls back to the Item-level remainder check on any exception
     * (e.g. a position-sensitive override that doesn't like our arbitrary
     * placement, or a recipe class that NPEs on the null menu reference).
     *
     * <p>Reusable-tool remainders are SKIPPED here - they were already
     * flushed to byproducts immediately at slot-end during {@link #tryRecipe}
     * (the consumption-timing fix), so re-adding them here would double-
     * count the tool. Only non-reusable remainders (empty buckets, glass
     * bottles, etc.) are emitted at end-of-recipe.
     */
    private static void addRecipeRemainders(RecipeEdge edge, Map<Item, Integer> stepConsumed,
                                            int batches, Map<Item, Integer> byproducts,
                                            Map<Item, Integer> toolDamage) {
        Recipe<?> raw = edge.sourceRecipe();
        if (!(raw instanceof net.minecraft.world.item.crafting.CraftingRecipe craftingRecipe)) {
            addItemLevelRemainders(stepConsumed, byproducts);
            return;
        }

        // Per-batch container reflects one craft cycle (1 hammer + 2 ingots,
        // not N hammers + 2N ingots). Recipe returns per-batch remainders,
        // we scale non-reusable remainders by batches when merging.
        //
        // stepConsumed has two flavors of value:
        //   - consumables (ingots, planks, etc):  N * batches    (multiplied at aggregation)
        //   - reusable tools (hammer, buzzsaw):   slot-count     (NOT multiplied at aggregation)
        //
        // For the per-batch container slot count we want "items present
        // during one craft cycle" - that's stepConsumed/batches for
        // consumables and just stepConsumed for reusable tools (each batch
        // sees the same tool again).
        net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> slots =
            net.minecraft.core.NonNullList.withSize(9, net.minecraft.world.item.ItemStack.EMPTY);
        int slotIdx = 0;
        for (var e : stepConsumed.entrySet()) {
            int perBatch = IngredientHeuristics.isReusableItem(e.getKey())
                ? e.getValue()
                : e.getValue() / batches;
            for (int i = 0; i < perBatch && slotIdx < 9; i++) {
                slots.set(slotIdx++, new net.minecraft.world.item.ItemStack(e.getKey()));
            }
        }

        try {
            net.minecraft.world.inventory.CraftingContainer container =
                new net.minecraft.world.inventory.TransientCraftingContainer(null, 3, 3, slots);
            net.minecraft.core.NonNullList<net.minecraft.world.item.ItemStack> remainders =
                craftingRecipe.getRemainingItems(container);
            for (net.minecraft.world.item.ItemStack r : remainders) {
                if (r.isEmpty()) continue;
                if (IngredientHeuristics.isReusableItem(r.getItem())) {
                    // Reusable tools were already flushed to byproducts at
                    // slot-end during tryRecipe (consumption-timing fix);
                    // skip the byproduct re-add so we don't double-count.
                    //
                    // But: the recipe damaged the per-batch container's
                    // pristine tool by r.getDamageValue() points per batch.
                    // Accumulate total damage so the execute layer can apply
                    // it to the player's actual tool stack.
                    int dmgPerBatch = r.getDamageValue();
                    if (dmgPerBatch > 0) {
                        toolDamage.merge(r.getItem(), dmgPerBatch * batches, Integer::sum);
                    }
                    continue;
                }
                // Non-reusable remainders (empty buckets, glass bottles, etc.)
                // come back once per batch, so scale by batches.
                byproducts.merge(r.getItem(), r.getCount() * batches, Integer::sum);
            }
        } catch (Throwable t) {
            com.sabbs.fabricate.Fabricate.LOGGER.debug(
                "[FAB] recipe.getRemainingItems failed for {}: {} - falling back to Item-level",
                edge.id(), t.toString());
            addItemLevelRemainders(stepConsumed, byproducts);
        }
    }

    /**
     * Fallback: produce remainders using the Item-level API. Catches
     * vanilla-style remainders (buckets, anything that overrides
     * {@code Item.hasCraftingRemainingItem}) but misses recipe-level
     * overrides. Used when {@link #addRecipeRemainders} can't get a
     * working CraftingContainer.
     *
     * <p>Reusable-tool items are skipped here for the same reason as in
     * {@link #addRecipeRemainders}: they were flushed immediately during
     * {@link #tryRecipe} and re-adding would double-count.
     */
    private static void addItemLevelRemainders(Map<Item, Integer> stepConsumed, Map<Item, Integer> byproducts) {
        for (var e : stepConsumed.entrySet()) {
            Item item = e.getKey();
            if (IngredientHeuristics.isReusableItem(item)) continue;
            if (!item.hasCraftingRemainingItem()) continue;
            Item remainder = item.getCraftingRemainingItem();
            if (remainder == null) continue;
            byproducts.merge(remainder, e.getValue(), Integer::sum);
        }
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

    /**
     * Per-plan hard budget.
     *
     * <p>Thrown budget failures are caught by {@link #plan}, which discards
     * the local mutated planning state and returns Optional.empty().
     */
    private static final class Budget {
        private final Item rootTarget;
        private final int rootQty;
        private final int maxResolveCalls;
        private final int maxRecipeAttempts;
        private final long deadlineNanos;

        private int resolveCalls = 0;
        private int recipeAttempts = 0;
        private int operationsSinceTimeCheck = 0;

        private Budget(Item rootTarget, int rootQty, int maxResolveCalls,
                       int maxRecipeAttempts, long maxPlanTimeNanos) {
            this.rootTarget = rootTarget;
            this.rootQty = rootQty;
            this.maxResolveCalls = maxResolveCalls;
            this.maxRecipeAttempts = maxRecipeAttempts;
            this.deadlineNanos = System.nanoTime() + maxPlanTimeNanos;
        }

        private void countResolve(Item target, int qty, int depth) {
            resolveCalls++;
            if (resolveCalls > maxResolveCalls) {
                throw new BudgetExceededException(
                    "resolve-call limit hit"
                        + " root=" + rootQty + "x " + rootTarget
                        + " current=" + qty + "x " + target
                        + " depth=" + depth
                        + " resolves=" + resolveCalls + "/" + maxResolveCalls
                        + " recipeAttempts=" + recipeAttempts + "/" + maxRecipeAttempts
                );
            }
            checkTimeMaybe();
        }

        private void countRecipeAttempt(RecipeEdge recipe, Item target, int depth) {
            recipeAttempts++;
            if (recipeAttempts > maxRecipeAttempts) {
                throw new BudgetExceededException(
                    "recipe-attempt limit hit"
                        + " root=" + rootQty + "x " + rootTarget
                        + " currentTarget=" + target
                        + " recipe=" + recipe.id()
                        + " depth=" + depth
                        + " resolves=" + resolveCalls + "/" + maxResolveCalls
                        + " recipeAttempts=" + recipeAttempts + "/" + maxRecipeAttempts
                );
            }
            checkTimeMaybe();
        }

        private void checkTimeOnly() {
            operationsSinceTimeCheck++;
            checkTimeMaybe();
        }

        private void checkTimeMaybe() {
            operationsSinceTimeCheck++;
            if (operationsSinceTimeCheck < TIME_CHECK_INTERVAL) return;
            operationsSinceTimeCheck = 0;

            if (System.nanoTime() > deadlineNanos) {
                throw new BudgetExceededException(
                    "wall-clock limit hit"
                        + " root=" + rootQty + "x " + rootTarget
                        + " resolves=" + resolveCalls + "/" + maxResolveCalls
                        + " recipeAttempts=" + recipeAttempts + "/" + maxRecipeAttempts
                );
            }
        }
    }

    private static final class BudgetExceededException extends RuntimeException {
        private BudgetExceededException(String message) {
            super(message);
        }
    }
}