package com.sabbs.fabricate.recipe;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

import java.util.Map;

/**
 * A crafting recipe that matches when the grid contains the required quantities
 * of each item type. Supports stacked items, multiple material types, and any
 * grid size. Consumption is handled by CraftingEvents.
 */
public class FabricateRecipe extends ShapelessRecipe {
    private final Map<Item, Integer> requiredItems;
    private final boolean requiresCraftingTable;

    // Parallel arrays mirroring requiredItems, used by the per-frame matches()
    // hot path to avoid HashMap allocation and Integer boxing. requiredItems
    // typically has 1–4 entries so a linear scan is faster than hashing.
    private final Item[] reqKeys;
    private final int[] reqVals;

    public FabricateRecipe(ResourceLocation id, String group, CraftingBookCategory category,
                          ItemStack result, NonNullList<Ingredient> ingredients,
                          Map<Item, Integer> requiredItems, boolean requiresCraftingTable) {
        super(id, group, category, result, ingredients);
        this.requiredItems = Map.copyOf(requiredItems);
        this.requiresCraftingTable = requiresCraftingTable;

        int n = this.requiredItems.size();
        this.reqKeys = new Item[n];
        this.reqVals = new int[n];
        int i = 0;
        for (var e : this.requiredItems.entrySet()) {
            this.reqKeys[i] = e.getKey();
            this.reqVals[i] = e.getValue();
            i++;
        }
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        // Defensive mirror of canCraftInDimensions  Polymorph and other recipe
        // scanners iterate recipes and call matches() directly without always
        // gating on grid size, so we enforce the crafting-table restriction
        // here too.
        if (requiresCraftingTable && (container.getWidth() < 3 || container.getHeight() < 3)) {
            return false;
        }

        // Called per-frame per-recipe while a crafting UI is open and there may
        // be tens of thousands of synthetics  keep this allocation-free on the
        // reject path. Walk the grid once; any unknown item fast-rejects with
        // zero allocations. Tally per-key counts into a small int[] (1–4
        // entries typical) keyed by the recipe's stable iteration order; a
        // linear scan beats hashing + Integer boxing at this size.
        final Item[] keys = this.reqKeys;
        final int n = keys.length;
        int[] tally = null; // lazily allocated only if we actually need to tally

        int gridSize = container.getContainerSize();
        for (int i = 0; i < gridSize; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            int idx = -1;
            for (int k = 0; k < n; k++) {
                if (keys[k] == item) { idx = k; break; }
            }
            if (idx < 0) return false;
            if (tally == null) tally = new int[n];
            tally[idx] += stack.getCount();
        }

        if (tally == null) return false;
        for (int k = 0; k < n; k++) {
            if (tally[k] < reqVals[k]) return false;
        }
        return true;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        // Mirror vanilla: if the upstream vanilla recipe for this output needs
        // a 3x3 grid, the synthetic also requires a crafting table. This keeps
        // things like wooden pickaxes out of the 2x2 player-inventory grid.
        if (requiresCraftingTable && (width < 3 || height < 3)) return false;
        return width * height >= 1;
    }

    public boolean requiresCraftingTable() {
        return requiresCraftingTable;
    }

    public Map<Item, Integer> getRequiredItems() {
        return requiredItems;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.TRUE_POLYMORPH.get();
    }

    /**
     * Keeps synthetics out of the player recipe book. With tens of thousands of
     * injected recipes, allowing them to be unlocked bloats each player's NBT 
     * and any datapack function running {@code execute if/store data entity @e}
     * then serializes the entire recipe list on every predicate check, turning
     * one tick into a 60-second watchdog kill. Vanilla's
     * {@code ServerRecipeBook.addRecipes} skips recipes where {@code isSpecial}
     * is {@code true}, which is exactly what we want.
     */
    @Override
    public boolean isSpecial() {
        return true;
    }
}
