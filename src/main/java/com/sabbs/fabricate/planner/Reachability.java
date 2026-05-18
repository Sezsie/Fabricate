package com.sabbs.fabricate.planner;

import com.sabbs.fabricate.planner.CraftGraph.IngredientSlot;
import com.sabbs.fabricate.planner.CraftGraph.RecipeEdge;
import net.minecraft.world.item.Item;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Computes the set of items reachable from a player's inventory via the
 * recipes in a {@link CraftGraph}.
 *
 * <p>Intentionally <b>optimistic</b>: this ignores quantities. An item
 * whose ingredient <em>kinds</em> are all available in the inventory shows
 * as reachable even if the inventory has too few of them. The
 * {@link CraftPlanner} is the authoritative quantity-aware check at click
 * time. False positives mean a craftable-marked item produces a click that
 * the planner rejects; false negatives would hide actually-craftable
 * items in the viewer. The trade-off favors viewer UX responsiveness.
 *
 * <p>Algorithm: BFS in availability-space. Each time a new item becomes
 * available, re-check the recipes that consume it; if every input slot
 * can now be satisfied, the recipe's output becomes available too.
 * O(edges + items) on any practical graph.
 */
public final class Reachability {

    private Reachability() {}

    public static Set<Item> compute(Map<Item, Integer> inventory, CraftGraph graph) {
        Set<Item> available = new HashSet<>(inventory.keySet());
        Deque<Item> frontier = new ArrayDeque<>(available);

        while (!frontier.isEmpty()) {
            Item just = frontier.poll();
            for (RecipeEdge edge : graph.getRecipesConsuming(just)) {
                Item out = edge.outputItem();
                if (available.contains(out)) continue;
                if (allSlotsSatisfied(edge, available)) {
                    available.add(out);
                    frontier.offer(out);
                }
            }
        }
        return available;
    }

    private static boolean allSlotsSatisfied(RecipeEdge edge, Set<Item> available) {
        for (IngredientSlot slot : edge.inputs()) {
            boolean any = false;
            for (Item accepted : slot.acceptedItems()) {
                if (available.contains(accepted)) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }
}
