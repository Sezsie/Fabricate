package com.sabbs.fabricate.recipe;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Client-side craftability queries for Fabricate synthetics, based on
 * whatever's in the local player's inventory. Kept free of EMI/JEI types so
 * it can be called from mixins and handlers that must load without either
 * recipe-viewer mod installed.
 */
public final class CraftabilityCheck {

    private CraftabilityCheck() {}

    /** True iff the local player has enough of every required material for one batch. */
    public static boolean playerHasMaterials(ResourceLocation recipeId) {
        if (recipeId == null || !RefundRegistry.has(recipeId)) return false;
        Player player = Minecraft.getInstance().player;
        if (player == null) return false;

        Map<Item, Integer> required = RefundRegistry.getRequiredItems(recipeId);
        if (required.isEmpty()) return false;
        Map<Item, Integer> invCounts = tallyAll(player.getInventory());
        for (var entry : required.entrySet()) {
            if (countWithEquivalents(invCounts, entry.getKey()) < entry.getValue()) return false;
        }
        return true;
    }

    /** Max number of times the recipe can be crafted from the local player's inventory. */
    public static int computeMaxBatches(ResourceLocation recipeId) {
        if (recipeId == null || !RefundRegistry.has(recipeId)) return 0;
        Player player = Minecraft.getInstance().player;
        if (player == null) return 0;

        Map<Item, Integer> required = RefundRegistry.getRequiredItems(recipeId);
        if (required.isEmpty()) return 0;

        Map<Item, Integer> invCounts = tallyAll(player.getInventory());
        int max = Integer.MAX_VALUE;
        for (var entry : required.entrySet()) {
            int perBatch = entry.getValue();
            if (perBatch <= 0) continue;
            // Count not just the exact required item, but also any item the
            // player has that's tag-equivalent (oak_log + stripped_oak_log
            // both count toward "needs oak_log"). This is how synthetics
            // become variant-agnostic without exploding the synthetic count
            // - the matching layer absorbs the variance.
            int have = countWithEquivalents(invCounts, entry.getKey());
            int batches = have / perBatch;
            if (batches < max) max = batches;
            if (max == 0) return 0;
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    /**
     * Max batches of an arbitrary recipe (vanilla or FAB synthetic) craftable
     * from the local player's inventory. FAB synthetics take the fast path via
     * {@link RefundRegistry}; vanilla uses a closed-form per-ingredient-group
     * min instead of simulating batch-by-batch consumption.
     *
     * <p>Algorithm: group ingredients by their accepted-item-set signature
     * (covers duplicate slots in shaped recipes regardless of whether the
     * deserializer reuses one Ingredient instance across positions or builds
     * one per slot. Identity-based grouping miscounts in the latter case: 6
     * separate plank-Ingredient instances would each report demand=1 with
     * supply=1 from a single plank in inventory, yielding limit=1 and a
     * false-positive craftable verdict). For each group, {@code demand} is
     * the occurrence count and {@code supply} is the sum of matching
     * inventory item counts. The result is {@code min(supply/demand)} across
     * groups.
     *
     * <p>Edge case: when two distinct Ingredient references in the same
     * recipe accept overlapping item sets, this over-counts supply because
     * the same items are claimed by both groups. In practice that's rare in
     * vanilla recipes, and the server-side {@code CraftPacket.consumeMaterials}
     * re-verifies with a proper greedy reservation before consuming. The UI
     * filter lighting up a card the player can't actually craft is benign.
     *
     * <p>Complexity: O(distinct_ingredients × inventory_size) ≈ 5 × 36 ≈ 180
     * ops per call.
     * 
     */
    public static int maxBatches(Recipe<?> recipe) {
        if (recipe == null) return 0;
        ResourceLocation id = recipe.getId();
        if (id != null && RefundRegistry.has(id)) return computeMaxBatches(id);

        Player player = Minecraft.getInstance().player;
        if (player == null) return 0;

        // Bucket by accepted-item-set, not by Ingredient reference. Some
        // recipe deserializers produce a fresh Ingredient instance per slot
        // even when the slots are functionally identical, which made the
        // old IdentityHashMap version count 6 plank slots as 6 demand=1
        // groups instead of one demand=6 group. Keep one representative
        // Ingredient per group so we can still call ing.test for supply
        // counting.
        LinkedHashMap<Set<Item>, Ingredient> repByKey = new LinkedHashMap<>();
        Map<Set<Item>, Integer> demandByKey = new HashMap<>();
        int nonEmpty = 0;
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            nonEmpty++;
            Set<Item> key = ingredientKey(ing);
            repByKey.putIfAbsent(key, ing);
            demandByKey.merge(key, 1, Integer::sum);
        }
        if (nonEmpty == 0) return 0;

        Inventory inv = player.getInventory();
        int invSize = inv.getContainerSize();
        int max = Integer.MAX_VALUE;
        for (var entry : repByKey.entrySet()) {
            Ingredient ing = entry.getValue();
            int demand = demandByKey.get(entry.getKey());
            int supply = 0;
            for (int s = 0; s < invSize; s++) {
                ItemStack stack = inv.getItem(s);
                if (!stack.isEmpty() && ing.test(stack)) supply += stack.getCount();
            }
            int limit = supply / demand;
            if (limit < max) max = limit;
            if (max == 0) return 0;
        }
        return max == Integer.MAX_VALUE ? 0 : max;
    }

    /** Convenience: true when {@link #maxBatches} is {@code >= 1}. */
    public static boolean playerCanCraft(Recipe<?> recipe) {
        return maxBatches(recipe) >= 1;
    }

    /**
     * Signature for grouping Ingredients that accept the same item set.
     * Two distinct {@code Ingredient} instances built from the same tag/list
     * yield equal sets and bucket together, so a 6-plank stair recipe
     * produces one demand=6 group regardless of deserializer behavior.
     */
    private static Set<Item> ingredientKey(Ingredient ing) {
        ItemStack[] items = ing.getItems();
        Set<Item> key = new HashSet<>(items.length * 2);
        for (ItemStack s : items) {
            if (!s.isEmpty()) key.add(s.getItem());
        }
        return key;
    }

    /** One-pass tally of {@code keys}' counts in {@code inv}. O(invSize) instead of O(keys × invSize). */
    private static Map<Item, Integer> tallyInventory(Inventory inv, java.util.Set<Item> keys) {
        Map<Item, Integer> counts = new HashMap<>(keys.size() * 2);
        int size = inv.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (keys.contains(item)) counts.merge(item, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /**
     * Full inventory tally, item → count, no filter. Used when callers
     * need to check tag-equivalent counts and the equivalence set isn't
     * known in advance (so filtering by required-set up front would miss
     * substituents).
     */
    private static Map<Item, Integer> tallyAll(Inventory inv) {
        Map<Item, Integer> counts = new HashMap<>();
        int size = inv.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /**
     * Sum of inventory counts for {@code required} plus any tag-equivalent
     * substitutes. Two items are tag-equivalent if they share at least one
     * tag that's used as an ingredient in a vanilla crafting recipe.
     */
    private static int countWithEquivalents(Map<Item, Integer> invCounts, Item required) {
        int sum = invCounts.getOrDefault(required, 0);
        for (Item eq : TagEquivalence.equivalents(required)) {
            if (eq == required) continue;
            sum += invCounts.getOrDefault(eq, 0);
        }
        return sum;
    }
}
