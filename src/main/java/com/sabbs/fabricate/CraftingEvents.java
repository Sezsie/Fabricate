package com.sabbs.fabricate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.sabbs.fabricate.recipe.RefundRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Handles consumption and refunding for Fabricate synthetic recipes.
 * Supports both single-material and multi-material recipes.
 *
 * Fires during ItemCraftedEvent (before vanilla's consumption loop).
 * Restructures the container so vanilla's "remove 1 per non-empty slot"
 * produces the correct final state. Items stay in their original slots.
 */
@Mod.EventBusSubscriber(modid = Fabricate.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CraftingEvents {

    @SubscribeEvent
    public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        // Opted-out players never get our refund/restructure behavior, even if
        // the server's CraftingMenuMixin somehow let a synthetic through. In
        // that case vanilla's default "remove 1 per non-empty slot" runs and
        // no refund fires consistent with "mod is off for this player".
        if (OptOutRegistry.isOptedOut(player.getUUID())) return;

        Container matrix = event.getInventory();
        int size = matrix.getContainerSize();

        // Tally items in the grid by type
        Map<Item, Integer> gridTotals = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            ItemStack stack = matrix.getItem(i);
            if (stack.isEmpty()) continue;
            gridTotals.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }

        if (gridTotals.isEmpty()) return;

        // Build a candidate recipe ID from the grid contents + output
        Item outputItem = event.getCrafting().getItem();
        var outputKey = ForgeRegistries.ITEMS.getKey(outputItem);
        if (outputKey == null) return;

        // Try to find a matching recipe in the registry
        // Sort grid items alphabetically to match generateId() format
        // Try all possible ID formats: with and without counts
        // The ID uses counts > 1 (e.g., "bamboo.iron_ingot_to_iron_shovel" or "bamboox4.iron_ingot_to_iron_shovel")
        // We need to match the exact ID that was generated
        ResourceLocation recipeId = findRecipeId(gridTotals, outputKey.getPath());
        if (recipeId == null) return;

        Map<Item, Integer> requiredItems = RefundRegistry.getRequiredItems(recipeId);
        if (requiredItems.isEmpty()) return;

        // Calculate desired amounts per slot after consumption
        // Track how much of each item type still needs to be consumed
        Map<Item, Integer> toConsume = new HashMap<>(requiredItems);

        // Record original slot contents
        Item[] slotItems = new Item[size];
        int[] slotCounts = new int[size];
        for (int i = 0; i < size; i++) {
            ItemStack stack = matrix.getItem(i);
            if (!stack.isEmpty()) {
                slotItems[i] = stack.getItem();
                slotCounts[i] = stack.getCount();
            }
        }

        // Consume from the end first so items stay in their original positions
        int[] desired = Arrays.copyOf(slotCounts, size);
        for (int i = size - 1; i >= 0; i--) {
            if (slotItems[i] == null) continue;
            int remaining = toConsume.getOrDefault(slotItems[i], 0);
            if (remaining <= 0) continue;

            int take = Math.min(remaining, desired[i]);
            desired[i] -= take;
            toConsume.merge(slotItems[i], -take, Integer::sum);
        }

        // Set up the container for vanilla's "remove 1 per non-empty slot" loop
        for (int i = 0; i < size; i++) {
            if (desired[i] <= 0 || slotItems[i] == null) {
                matrix.setItem(i, ItemStack.EMPTY);
            } else {
                int maxStack = slotItems[i].getMaxStackSize();
                int amount = desired[i] + 1; // +1 because vanilla removes 1
                if (amount <= maxStack) {
                    matrix.setItem(i, new ItemStack(slotItems[i], amount));
                } else {
                    // Edge case: can't fit +1. Put maxStack, find overflow slot.
                    matrix.setItem(i, new ItemStack(slotItems[i], maxStack));
                    for (int j = 0; j < size; j++) {
                        if (desired[j] == 0 && (slotItems[j] == null || slotItems[j] == slotItems[i])) {
                            if (matrix.getItem(j).isEmpty()) {
                                matrix.setItem(j, new ItemStack(slotItems[i], 2));
                                desired[j] = 1;
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Handle refunds
        if (ModConfig.ENABLE_REFUNDS.get()) {
            List<ItemStack> refund = RefundRegistry.getRefund(recipeId);
            for (ItemStack stack : refund) {
                ItemStack copy = stack.copy();
                if (!player.getInventory().add(copy)) {
                    player.drop(copy, false);
                }
            }
        }
    }

    /**
     * Resolves the synthetic recipe ID for a given grid + output. IDs include
     * per-item counts only when the required count is {@code > 1}, so we try
     * the count-less form first and fall back to scanning {@link RefundRegistry}
     * for every id matching the output suffix.
     */
    private static ResourceLocation findRecipeId(Map<Item, Integer> gridTotals, String outputPath) {
        String suffix = "_to_" + outputPath;

        // Fast path: build the count-less id from alphabetical item names and
        // look it up directly. This handles the majority (single-count ingredients).
        String baseNames = gridTotals.keySet().stream()
            .map(item -> ForgeRegistries.ITEMS.getKey(item))
            .filter(Objects::nonNull)
            .map(ResourceLocation::getPath)
            .sorted()
            .collect(Collectors.joining("."));
        ResourceLocation candidate = new ResourceLocation(Fabricate.MOD_ID, baseNames + suffix);
        if (RefundRegistry.has(candidate)) {
            Map<Item, Integer> req = RefundRegistry.getRequiredItems(candidate);
            if (gridSatisfies(gridTotals, req)) return candidate;
        }

        // Slow path: IDs registered with counts (e.g. "bamboox4.iron_ingot_to_iron_shovel")
        // don't match the fast-path form. RefundRegistry keeps a byOutput
        // index, so this is O(k) in synthetics producing the same output
        // rather than O(N) across every registered synthetic.
        for (ResourceLocation registered : RefundRegistry.idsForOutputPath(outputPath)) {
            Map<Item, Integer> req = RefundRegistry.getRequiredItems(registered);
            if (req.isEmpty()) continue;
            if (!req.keySet().equals(gridTotals.keySet())) continue;
            if (gridSatisfies(gridTotals, req)) return registered;
        }
        return null;
    }

    private static boolean gridSatisfies(Map<Item, Integer> gridTotals, Map<Item, Integer> required) {
        for (var entry : required.entrySet()) {
            if (gridTotals.getOrDefault(entry.getKey(), 0) < entry.getValue()) return false;
        }
        return true;
    }
}
