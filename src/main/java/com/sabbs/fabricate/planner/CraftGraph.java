package com.sabbs.fabricate.planner;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable hypergraph of crafting recipes. Built once per RecipeManager
 * and queried by {@link CraftPlanner} at click time.
 *
 * <p>Nodes are {@link Item}s. Edges are {@link RecipeEdge}s, each a
 * hyperedge from N input slots to 1 output item with a count. Indexed both
 * by output (for planning) and by input (for reachability BFS).
 *
 * <p>This replaces the legacy {@code RecipeIndex} + pre-generated synthetic
 * recipe model. The graph is small ({@code O(items + recipes)}), so building
 * it is cheap (~100ms on a heavy modpack) and it can be rebuilt cheaply on
 * datapack reload.
 */
public final class CraftGraph {

    /**
     * A single ingredient slot. Accepts any one of {@code acceptedItems}.
     *
     * <p>Iteration order is the order {@code Ingredient.getItems()} gave us -
     * i.e. tag order, which conventionally lists the canonical item first
     * ({@code minecraft:chest} before {@code minecraft:trapped_chest}). The
     * planner leans on that when picking which accepted item to actually
     * craft, so this must NOT be a plain {@code Set.copyOf}: that returns an
     * ImmutableCollections.SetN whose iteration order is perturbed by a
     * per-JVM-launch random salt, which both loses the tag's preference and
     * makes the same click plan differently across restarts.
     *
     * <p>Equality is still order-insensitive (AbstractSet semantics), so
     * slots with the same accepted items continue to aggregate together in
     * the planner.
     */
    public record IngredientSlot(Set<Item> acceptedItems) {
        public IngredientSlot {
            acceptedItems = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(acceptedItems));
        }
        public boolean accepts(Item item) {
            return acceptedItems.contains(item);
        }
    }

    /** A recipe as a hyperedge from input slots to an output. */
    public record RecipeEdge(
        ResourceLocation id,
        List<IngredientSlot> inputs,
        Item outputItem,
        int outputCount,
        boolean requiresCraftingTable,
        Recipe<?> sourceRecipe
    ) {
        public RecipeEdge {
            inputs = List.copyOf(inputs);
        }
    }

    private final Map<Item, List<RecipeEdge>> byOutput;
    private final Map<Item, List<RecipeEdge>> byInput;
    private final List<RecipeEdge> allEdges;

    private CraftGraph(Map<Item, List<RecipeEdge>> byOutput,
                       Map<Item, List<RecipeEdge>> byInput,
                       List<RecipeEdge> allEdges) {
        this.byOutput = byOutput;
        this.byInput = byInput;
        this.allEdges = allEdges;
    }

    public static CraftGraph build(RecipeManager manager, RegistryAccess registryAccess) {
        Map<Item, List<RecipeEdge>> byOutput = new HashMap<>();
        Map<Item, List<RecipeEdge>> byInput = new HashMap<>();
        List<RecipeEdge> allEdges = new ArrayList<>();

        for (Recipe<?> recipe : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            // Skip any synthetic recipes lingering in a save from a
            // pre-rewrite version of the mod, identified by namespace.
            // Without this they'd be re-walked by the planner and produce
            // ghost recipes the player can't actually craft.
            if (isLegacyFabricateId(recipe.getId())) continue;

            ItemStack output = recipe.getResultItem(registryAccess);
            if (output.isEmpty()) continue;

            List<IngredientSlot> slots = new ArrayList<>();
            for (Ingredient ing : recipe.getIngredients()) {
                if (ing.isEmpty()) continue;
                // LinkedHashSet: preserve the ingredient's declared item
                // order (see IngredientSlot) while still deduping.
                Set<Item> accepted = new LinkedHashSet<>();
                for (ItemStack is : ing.getItems()) {
                    if (!is.isEmpty()) accepted.add(is.getItem());
                }
                if (accepted.isEmpty()) continue;
                slots.add(new IngredientSlot(accepted));
            }
            if (slots.isEmpty()) continue;

            RecipeEdge edge = new RecipeEdge(
                recipe.getId(),
                slots,
                output.getItem(),
                output.getCount(),
                !recipe.canCraftInDimensions(2, 2),
                recipe
            );
            allEdges.add(edge);
            byOutput.computeIfAbsent(output.getItem(), k -> new ArrayList<>()).add(edge);

            // Index by every distinct item that could fill any input slot, for
            // reachability traversal. Dedup across slots since a recipe with two
            // plank slots should only appear once under each plank type.
            Set<Item> seenInputItems = new HashSet<>();
            for (IngredientSlot slot : slots) {
                for (Item item : slot.acceptedItems()) {
                    if (seenInputItems.add(item)) {
                        byInput.computeIfAbsent(item, k -> new ArrayList<>()).add(edge);
                    }
                }
            }
        }

        return new CraftGraph(freeze(byOutput), freeze(byInput), List.copyOf(allEdges));
    }

    /**
     * True for any synthetic recipe id from a pre-rewrite version of the mod
     * (fabricate:* or truepolymorph:*). Save-migration belt: those entries
     * may still exist in the {@link RecipeManager} on a world that was
     * loaded before {@link com.sabbs.fabricate.ClientEvents}' strip pass ran.
     */
    private static boolean isLegacyFabricateId(net.minecraft.resources.ResourceLocation id) {
        if (id == null) return false;
        String ns = id.getNamespace();
        return "fabricate".equals(ns) || "truepolymorph".equals(ns);
    }

    private static <K, V> Map<K, List<V>> freeze(Map<K, List<V>> map) {
        Map<K, List<V>> out = new HashMap<>(map.size());
        for (var e : map.entrySet()) out.put(e.getKey(), List.copyOf(e.getValue()));
        return Map.copyOf(out);
    }

    /** All recipes whose output item is {@code item}. Never null, possibly empty. */
    public List<RecipeEdge> getRecipesProducing(Item item) {
        return byOutput.getOrDefault(item, List.of());
    }

    /** All recipes that could accept {@code item} in some input slot. */
    public List<RecipeEdge> getRecipesConsuming(Item item) {
        return byInput.getOrDefault(item, List.of());
    }

    /** Every item that is the output of at least one recipe. */
    public Set<Item> getAllOutputItems() {
        return byOutput.keySet();
    }

    /** Every item that appears in at least one recipe input slot. */
    public Set<Item> getAllInputItems() {
        return byInput.keySet();
    }

    public int edgeCount() {
        return allEdges.size();
    }

    public int outputItemCount() {
        return byOutput.size();
    }

    public int inputItemCount() {
        return byInput.size();
    }
}
