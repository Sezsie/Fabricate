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
 */
public record CraftPlan(
    Item target,
    int targetCount,
    List<Step> steps,
    Map<Item, Integer> baseCost,
    Map<Item, Integer> byproducts
) {
    public CraftPlan {
        steps = List.copyOf(steps);
        baseCost = Map.copyOf(baseCost);
        byproducts = Map.copyOf(byproducts);
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
