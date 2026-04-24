package com.sabbs.fabricate.recipe;

import com.sabbs.fabricate.Fabricate;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Injects synthetic recipes into the RecipeManager via reflection.
 * Handles replacing immutable maps with mutable copies.
 */
public class RecipeInjector {

    private static Field recipesField;
    private static Field byNameField;

    @SuppressWarnings("unchecked")
    public static int inject(RecipeManager manager, List<GraphWalker.SyntheticRecipe> synthetics, RecipeIndex index) {
        if (synthetics.isEmpty()) return 0;

        try {
            if (recipesField == null) {
                recipesField = ObfuscationReflectionHelper.findField(RecipeManager.class, "f_44007_");
            }

            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> allRecipes =
                (Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>) recipesField.get(manager);

            HashMap<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> mutableAll = new HashMap<>(allRecipes);
            HashMap<ResourceLocation, Recipe<?>> craftingMap = new HashMap<>(
                mutableAll.getOrDefault(RecipeType.CRAFTING, Collections.emptyMap())
            );
            mutableAll.put(RecipeType.CRAFTING, craftingMap);
            recipesField.set(manager, mutableAll);

            Map<ResourceLocation, Recipe<?>> byName = null;
            try {
                if (byNameField == null) {
                    byNameField = ObfuscationReflectionHelper.findField(RecipeManager.class, "f_199900_");
                }
                byName = new HashMap<>((Map<ResourceLocation, Recipe<?>>) byNameField.get(manager));
                byNameField.set(manager, byName);
            } catch (Exception e) {
                Fabricate.LOGGER.debug("Fabricate: byName field not accessible ({}), continuing without it",
                    e.getMessage());
            }

            RefundRegistry.clear();

            int count = 0;
            for (GraphWalker.SyntheticRecipe syn : synthetics) {
                try {
                    ResourceLocation id = new ResourceLocation(Fabricate.MOD_ID, syn.generateId());
                    if (craftingMap.containsKey(id)) continue;

                    // Expand the ingredient list per unit  EMI's craftables
                    // detection treats the list as "one required match per
                    // entry," so a compressed "one entry per material type"
                    // would falsely flag a synthetic as craftable the moment
                    // the player has a single stick + single plank, even when
                    // the actual recipe needs 3 planks + 2 sticks. Per-unit
                    // entries make EMI's quantity math match reality. Fall
                    // back to the compressed form when the total exceeds a
                    // 3x3 grid since EMI's viewer handles > 9 entries poorly.
                    int totalUnits = 0;
                    for (int q : syn.baseCosts().values()) totalUnits += q;
                    NonNullList<Ingredient> ingredients = NonNullList.create();
                    if (totalUnits <= 9) {
                        for (var e : syn.baseCosts().entrySet()) {
                            Ingredient ing = Ingredient.of(e.getKey());
                            for (int i = 0; i < e.getValue(); i++) ingredients.add(ing);
                        }
                    } else {
                        for (Item item : syn.baseCosts().keySet()) {
                            ingredients.add(Ingredient.of(item));
                        }
                    }

                    boolean requiresTable = index.requiresCraftingTable(syn.output().getItem());
                    FabricateRecipe recipe = new FabricateRecipe(
                        id, Fabricate.MOD_ID, CraftingBookCategory.MISC,
                        syn.output(), ingredients,
                        syn.baseCosts(),
                        requiresTable
                    );

                    RefundRegistry.register(id, syn.refundItems(), syn.baseCosts());
                    craftingMap.put(id, recipe);
                    if (byName != null) byName.put(id, recipe);
                    count++;
                } catch (Exception e) {
                    Fabricate.LOGGER.warn("Fabricate: Skipping recipe with invalid ID: {}", syn.generateId());
                }
            }

            return count;
        } catch (Exception e) {
            Fabricate.LOGGER.error("Fabricate: Failed to inject synthetic recipes", e);
            return 0;
        }
    }

}
