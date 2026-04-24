package com.sabbs.fabricate.recipe;

import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.*;

/**
 * Indexes all crafting recipes by their output item for fast lookup.
 */
public class RecipeIndex {
    private final Map<Item, List<Recipe<?>>> outputToRecipes = new HashMap<>();
    private final Map<Recipe<?>, ItemStack> recipeOutputs = new IdentityHashMap<>();

    public RecipeIndex(RecipeManager manager, RegistryAccess registryAccess) {
        for (Recipe<?> recipe : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            // Skip our own synthetics  on the client, previously-injected
            // synthetics are already in the RecipeManager (synced from server),
            // and re-indexing them would generate synthetics-of-synthetics,
            // producing a different id set than the server's.
            if (recipe instanceof FabricateRecipe) continue;

            ItemStack output = recipe.getResultItem(registryAccess);
            if (output.isEmpty()) continue;

            recipeOutputs.put(recipe, output.copy());
            outputToRecipes.computeIfAbsent(output.getItem(), k -> new ArrayList<>()).add(recipe);
        }
    }

    public List<Recipe<?>> getRecipesProducing(Item item) {
        return outputToRecipes.getOrDefault(item, Collections.emptyList());
    }

    public ItemStack getOutput(Recipe<?> recipe) {
        return recipeOutputs.getOrDefault(recipe, ItemStack.EMPTY);
    }

    public Set<Item> getAllOutputItems() {
        return outputToRecipes.keySet();
    }

    /**
     * True iff this item cannot be crafted in vanilla's 2x2 player-inventory
     * grid by any known recipe  i.e. every producing recipe fails
     * {@link Recipe#canCraftInDimensions(int, int)} for 2x2. Synthetic recipes
     * for such outputs should inherit that restriction.
     */
    public boolean requiresCraftingTable(Item output) {
        List<Recipe<?>> recipes = outputToRecipes.get(output);
        if (recipes == null || recipes.isEmpty()) return false;
        for (Recipe<?> r : recipes) {
            if (r.canCraftInDimensions(2, 2)) return false;
        }
        return true;
    }
}
