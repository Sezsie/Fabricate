package com.sabbs.fabricate.recipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Static registry mapping synthetic recipe IDs to their refund item lists
 * and required input materials. Populated during recipe injection, read during
 * crafting events for consumption and refund handling.
 */
public class RefundRegistry {
    private static final Map<ResourceLocation, List<ItemStack>> REFUNDS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Map<Item, Integer>> REQUIRED_ITEMS = new ConcurrentHashMap<>();
    // Secondary index: output-item path ("iron_shovel") → recipe ids producing
    // it. Lets CraftingEvents.findRecipeId do an O(k) lookup instead of an
    // O(N) scan across every registered synthetic.
    private static final Map<String, List<ResourceLocation>> BY_OUTPUT_PATH = new ConcurrentHashMap<>();

    public static void clear() {
        REFUNDS.clear();
        REQUIRED_ITEMS.clear();
        BY_OUTPUT_PATH.clear();
    }

    public static void register(ResourceLocation recipeId, List<ItemStack> refundItems,
                                Map<Item, Integer> requiredItems) {
        REQUIRED_ITEMS.put(recipeId, Map.copyOf(requiredItems));
        if (refundItems != null && !refundItems.isEmpty()) {
            REFUNDS.put(recipeId, List.copyOf(refundItems));
        }
        String outputPath = extractOutputPath(recipeId);
        if (outputPath != null) {
            BY_OUTPUT_PATH.computeIfAbsent(outputPath, k -> new CopyOnWriteArrayList<>()).add(recipeId);
        }
    }

    public static boolean has(ResourceLocation recipeId) {
        return REQUIRED_ITEMS.containsKey(recipeId);
    }

    public static Map<Item, Integer> getRequiredItems(ResourceLocation recipeId) {
        return REQUIRED_ITEMS.getOrDefault(recipeId, Collections.emptyMap());
    }

    public static List<ItemStack> getRefund(ResourceLocation recipeId) {
        return REFUNDS.getOrDefault(recipeId, Collections.emptyList());
    }

    public static Set<ResourceLocation> allIds() {
        return Collections.unmodifiableSet(REQUIRED_ITEMS.keySet());
    }

    /** Returns every registered recipe id whose output is {@code outputPath}. */
    public static List<ResourceLocation> idsForOutputPath(String outputPath) {
        return BY_OUTPUT_PATH.getOrDefault(outputPath, Collections.emptyList());
    }

    private static String extractOutputPath(ResourceLocation id) {
        String path = id.getPath();
        int i = path.lastIndexOf("_to_");
        return i >= 0 ? path.substring(i + 4) : null;
    }
}
