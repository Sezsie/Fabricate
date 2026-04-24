package com.sabbs.fabricate.recipe;

import com.sabbs.fabricate.Fabricate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks the "best" craftable recipe for a target output from whatever's in the
 * local player's inventory. Used by both the EMI click mixin and the EMI
 * recipe handler so both entry points agree on which variant to dispatch.
 *
 * <p>Scoring, lowest-first:
 * <ol>
 *   <li><b>Distance from vanilla</b>: count of the recipe's input items that
 *       do NOT appear in any non-FAB recipe's ingredient list for the same
 *       output. A vanilla recipe scores 0 here (all its items are vanilla
 *       items by definition); synthetics score by how many substitutions
 *       they make away from base. Example for iron sword (vanilla wants
 *       sticks + iron_ingot):
 *       <ul>
 *         <li>{stick, iron_block} substitutes 1 item (ingot→block)  score 1</li>
 *         <li>{oak_log, iron_block} substitutes 2 items (stick→log, ingot→block)  score 2</li>
 *       </ul>
 *       The first wins.</li>
 *   <li><b>Total material count</b>: sum of per-batch item quantities. Breaks
 *       ties between equally-close variants toward the cheaper one.</li>
 * </ol>
 */
public final class RecipeSelector {

    private RecipeSelector() {}

    // Cached per-output indexes over the client RecipeManager. Rebuilt lazily
    // whenever a different RecipeManager instance is observed, and explicitly
    // invalidated on RecipesUpdatedEvent via {@link #invalidate()}. The
    // RecipeManager reference typically stays stable across reloads, so
    // reference-identity alone would miss content changes.
    private static RecipeManager cachedRm = null;
    private static final Map<Item, List<Recipe<?>>> recipesByOutput = new HashMap<>();
    private static final Map<Item, Set<Item>> vanillaItemsByOutput = new HashMap<>();
    // Per-recipe memo of (input-item set, total cost) so the sort comparator
    // doesn't re-walk getIngredients()/getItems() on every compare.
    private static final Map<Recipe<?>, RecipeMeta> metaCache = new IdentityHashMap<>();

    private record RecipeMeta(Set<Item> inputItems, int totalCost) {}

    /** Invalidate all caches. Call on RecipesUpdatedEvent. */
    public static void invalidate() {
        cachedRm = null;
        recipesByOutput.clear();
        vanillaItemsByOutput.clear();
        metaCache.clear();
    }

    /** Best craftable recipe producing {@code target}, or {@code null} if none. */
    public static Recipe<?> pickBest(Item target) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.getConnection() == null) return null;
        RecipeManager rm = mc.getConnection().getRecipeManager();
        RegistryAccess ra = level.registryAccess();

        ensureCache(rm, ra);

        List<Recipe<?>> producers = recipesByOutput.getOrDefault(target, Collections.emptyList());
        if (producers.isEmpty()) return null;
        Set<Item> vanillaItems = vanillaItemsByOutput.getOrDefault(target, Collections.emptySet());

        List<Recipe<?>> candidates = new ArrayList<>(producers.size());
        for (Recipe<?> r : producers) {
            if (CraftabilityCheck.playerCanCraft(r)) candidates.add(r);
        }
        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator
            .comparingInt((Recipe<?> r) -> distanceFromVanilla(r, vanillaItems))
            .thenComparingInt(RecipeSelector::totalCost));
        return candidates.get(0);
    }

    /** Rebuild {@link #recipesByOutput} and {@link #vanillaItemsByOutput} if the manager changed. */
    private static void ensureCache(RecipeManager rm, RegistryAccess ra) {
        if (cachedRm == rm) return;
        cachedRm = rm;
        recipesByOutput.clear();
        vanillaItemsByOutput.clear();
        metaCache.clear();
        // CRAFTING only!!! {@code rm.getRecipes()} yields every type (smelting,
        // blasting, smoking, stonecutting, …). Pulling a blast-furnace recipe
        // like {@code iron_nugget_from_blasting} (which accepts any iron tool)
        // through the crafting grid would let the player sacrifice an iron
        // sword for a single nugget.
        for (Recipe<?> r : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack out = r.getResultItem(ra);
            if (out.isEmpty()) continue;
            Item outItem = out.getItem();
            recipesByOutput.computeIfAbsent(outItem, k -> new ArrayList<>()).add(r);
            if (r.getId() != null && !Fabricate.MOD_ID.equals(r.getId().getNamespace())) {
                Set<Item> set = vanillaItemsByOutput.computeIfAbsent(outItem, k -> new HashSet<>());
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    for (ItemStack s : ing.getItems()) {
                        if (!s.isEmpty()) set.add(s.getItem());
                    }
                }
            }
        }
    }

    /** Items the recipe consumes that are NOT in the vanilla ingredient set. */
    private static int distanceFromVanilla(Recipe<?> r, Set<Item> vanillaItems) {
        Set<Item> inputs = meta(r).inputItems();
        int distance = 0;
        for (Item in : inputs) if (!vanillaItems.contains(in)) distance++;
        return distance;
    }

    /** Per-batch item count. FAB synthetics use the pre-computed required map. */
    private static int totalCost(Recipe<?> r) {
        return meta(r).totalCost();
    }

    /** Cached per-recipe metadata. Derived once per (RecipeManager, Recipe). */
    private static RecipeMeta meta(Recipe<?> r) {
        RecipeMeta m = metaCache.get(r);
        if (m != null) return m;

        Set<Item> inputs;
        int cost;
        if (r.getId() != null && RefundRegistry.has(r.getId())) {
            Map<Item, Integer> req = RefundRegistry.getRequiredItems(r.getId());
            inputs = req.keySet();
            int total = 0;
            for (int q : req.values()) total += q;
            cost = total;
        } else {
            Set<Item> items = new HashSet<>();
            int total = 0;
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) continue;
                total++;
                ItemStack[] matches = ing.getItems();
                if (matches.length > 0 && !matches[0].isEmpty()) items.add(matches[0].getItem());
            }
            inputs = items;
            cost = total;
        }
        m = new RecipeMeta(inputs, cost);
        metaCache.put(r, m);
        return m;
    }
}
