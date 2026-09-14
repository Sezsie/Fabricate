package com.sabbs.fabricate.planner;

import com.sabbs.fabricate.planner.CraftGraph.RecipeEdge;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;

/**
 * The result of planning how to craft {@code targetCount} of {@code target}
 * from the player's inventory. Carries the ordered sequence of recipe
 * executions, the total cost (items consumed from inventory), and the
 * byproducts that will be refunded after execution.
 *
 * <p>{@code steps} is emitted in execution order: each step's ingredients are
 * either inventory items or were produced by a prior step. The final step
 * always produces {@code targetCount} (or more, with any excess in
 * {@code byproducts}) of {@code target}.
 *
 * <p>{@code toolDamage} is the total durability cost the plan will inflict on
 * each reusable tool item (hammer, file, buzzsaw, etc.). The execute layer
 * uses this to damage the actual ItemStack in inventory instead of returning
 * a pristine clone — so tools wear down naturally and eventually break.
 * Tools that don't take durability damage (or aren't damageable items at all)
 * simply don't appear in this map.
 *
 * <p>{@code protectedToolDamage} is the durability cost for reusable tools that
 * are {@link com.sabbs.fabricate.ItemProtection protected} (enchanted gear).
 * These are loaned to a reusable slot only as a last resort, are never
 * consumed, and are worn in place with a hard floor so they never actually
 * break. They live in their own map because, unlike {@code toolDamage}, they
 * are deliberately absent from {@code baseCost} and {@code byproducts}: a
 * protected tool is only ever damaged, never spent or refunded.
 */
public record CraftPlan(
    Item target,
    int targetCount,
    List<Step> steps,
    Map<Item, Integer> baseCost,
    Map<Item, Integer> byproducts,
    Map<Item, Integer> toolDamage,
    Map<Item, Integer> protectedToolDamage
) {
    public CraftPlan {
        steps = List.copyOf(steps);
        baseCost = Map.copyOf(baseCost);
        byproducts = Map.copyOf(byproducts);
        toolDamage = Map.copyOf(toolDamage);
        protectedToolDamage = Map.copyOf(protectedToolDamage);
    }

    /**
     * Sum of all {@code baseCost} quantities. Used as the planner's cost
     * function: lower = preferred. Multiple plans for the same target are
     * compared on this value.
     */
    public int totalBaseCost() {
        int sum = 0;
        for (int v : baseCost.values()) sum += v;
        return sum;
    }

    public boolean isTrivial() {
        return steps.isEmpty();
    }

    /**
     * One recipe execution within a plan. {@code consumed} aggregates what
     * this batch run pulls from the available pool (inventory + prior steps'
     * outputs); execution machinery uses it to know which specific items to
     * place in each tag-flexible slot.
     */
    public record Step(
        RecipeEdge edge,
        int batches,
        Map<Item, Integer> consumed
    ) {
        public Step {
            consumed = Map.copyOf(consumed);
        }
        public int producedCount() {
            return edge.outputCount() * batches;
        }
        public Item producedItem() {
            return edge.outputItem();
        }
    }
}
