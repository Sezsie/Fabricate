package com.sabbs.fabricate.planner;

import com.sabbs.fabricate.Fabricate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single entry point for planner-driven crafting. Wraps {@link CraftGraph},
 * {@link CraftPlanner}, and {@link Reachability} so callers (debug commands,
 * EMI/JEI integrations, network handlers) all go through the same code path.
 *
 * <p>State: one cached {@link CraftGraph} per {@link MinecraftServer}. The
 * graph is rebuilt automatically when the server identity changes. Datapack
 * reloads keep the same server instance, so reload handling should call
 * {@link #invalidate()}.
 */
public final class PlannerService {

    private static volatile CraftGraph cachedGraph;
    private static volatile MinecraftServer cachedGraphServer;

    private PlannerService() {}

    public record ExecuteResult(boolean ok, String reason, CraftPlan plan) {
        public static ExecuteResult fail(String reason) {
            return new ExecuteResult(false, reason, null);
        }

        public static ExecuteResult ok(CraftPlan p) {
            return new ExecuteResult(true, null, p);
        }
    }

    public record FailureFeedback(Component title, Component detail) {}

    /** Build-or-reuse the graph for {@code server}. */
    public static synchronized CraftGraph getGraph(MinecraftServer server) {
        if (cachedGraph != null && cachedGraphServer == server) {
            return cachedGraph;
        }

        long t0 = System.nanoTime();
        CraftGraph g = CraftGraph.build(server.getRecipeManager(), server.registryAccess());
        long ms = (System.nanoTime() - t0) / 1_000_000;

        Fabricate.LOGGER.info(
            "[FAB-planner] built CraftGraph: {} edges, {} output items, {} input items ({}ms)",
            g.edgeCount(),
            g.outputItemCount(),
            g.inputItemCount(),
            ms
        );

        cachedGraph = g;
        cachedGraphServer = server;
        return g;
    }

    /** Force the next {@link #getGraph} call to rebuild. Call on datapack reload. */
    public static synchronized void invalidate() {
        cachedGraph = null;
        cachedGraphServer = null;
    }

    /** Compute a {@link CraftPlan} for {@code target} from the player's inventory. */
    public static Optional<CraftPlan> plan(ServerPlayer player, Item target, int qty) {
        Map<Item, Integer> inv = inventoryToMap(player.getInventory());
        boolean has3x3 = has3x3Access(player);

        /*
         * Useful while debugging modpack issues. If this gets too loud later,
         * drop it to debug or gate it behind a config flag.
         */
        Fabricate.LOGGER.info(
            "[FAB-planner] planning request: player={}, target={}, qty={}, has3x3={}, inventory={}",
            player.getGameProfile().getName(),
            ForgeRegistries.ITEMS.getKey(target),
            qty,
            has3x3,
            formatItemMap(inv)
        );

        return new CraftPlanner(getGraph(player.server)).plan(target, qty, inv, has3x3);
    }

    /**
     * True if the player's current menu exposes a 3x3 crafting grid.
     *
     * <p>Vanilla is simple: InventoryMenu is 2x2, CraftingMenu is 3x3.
     * Modded crafting tables may use custom menu classes, so this delegates to
     * a CraftingTweaks-style registry keyed by menu class name and grid metadata.
     */
    private static boolean has3x3Access(ServerPlayer player) {
        return CraftingGridRegistry.has3x3Access(player);
    }

    /** Items currently craftable from the player's inventory. This is optimistic and quantity-light. */
    public static Set<Item> reachable(ServerPlayer player) {
        return Reachability.compute(inventoryToMap(player.getInventory()), getGraph(player.server));
    }

    /** Where the target output goes after a successful execute. */
    public enum DeliveryMode {
        /** Target output goes to inventory. Drops at feet if full. */
        INVENTORY,

        /** Target output goes to the player's cursor. Overflow falls back to inventory. */
        CURSOR_FIRST
    }

    /**
     * Plan + execute in one call, target routed to inventory.
     */
    public static ExecuteResult planAndExecute(ServerPlayer player, Item target, int qty) {
        return planAndExecute(player, target, qty, DeliveryMode.INVENTORY);
    }

    /** Plan + execute in one call with explicit delivery mode. */
    public static ExecuteResult planAndExecute(ServerPlayer player, Item target, int qty, DeliveryMode mode) {
        Optional<CraftPlan> p = plan(player, target, qty);

        if (p.isEmpty()) {
            Fabricate.LOGGER.info(
                "[FAB-planner] no plan: player={}, target={}, qty={}, mode={}",
                player.getGameProfile().getName(),
                ForgeRegistries.ITEMS.getKey(target),
                qty,
                mode
            );

            return ExecuteResult.fail("no plan");
        }

        return execute(player, p.get(), mode);
    }

    /** Convenience overload. Target output goes to inventory. */
    public static ExecuteResult execute(ServerPlayer player, CraftPlan plan) {
        return execute(player, plan, DeliveryMode.INVENTORY);
    }

    /**
     * Execute a pre-built plan against the player's current inventory.
     * Re-checks affordability defensively in case the inventory changed between
     * planning and execution.
     *
     * <p>{@code mode} controls where the target output goes. Byproducts always
     * go to inventory.
     */
    public static ExecuteResult execute(ServerPlayer player, CraftPlan plan, DeliveryMode mode) {
        Inventory pInv = player.getInventory();
        Map<Item, Integer> current = inventoryToMap(pInv);

        Fabricate.LOGGER.info(
            "[FAB-exec] executing plan: player={}, target={}, targetCount={}, mode={}, baseCost={}, byproducts={}, currentInventory={}",
            player.getGameProfile().getName(),
            ForgeRegistries.ITEMS.getKey(plan.target()),
            plan.targetCount(),
            mode,
            formatItemMap(plan.baseCost()),
            formatItemMap(plan.byproducts()),
            formatItemMap(current)
        );

        for (var entry : plan.baseCost().entrySet()) {
            Item item = entry.getKey();
            int needed = entry.getValue();
            int available = current.getOrDefault(item, 0);

            if (available < needed) {
                Fabricate.LOGGER.info(
                    "[FAB-exec] inventory changed or missing cost: player={}, item={}, needed={}, available={}",
                    player.getGameProfile().getName(),
                    ForgeRegistries.ITEMS.getKey(item),
                    needed,
                    available
                );

                return ExecuteResult.fail("inventory changed between plan and execute");
            }
        }

        // Consume base cost slot-by-slot.
        for (var entry : plan.baseCost().entrySet()) {
            Item item = entry.getKey();
            int toConsume = entry.getValue();

            for (int i = 0; i < pInv.getContainerSize() && toConsume > 0; i++) {
                ItemStack stack = pInv.getItem(i);

                if (stack.getItem() == item) {
                    int take = Math.min(stack.getCount(), toConsume);
                    stack.shrink(take);
                    toConsume -= take;
                }
            }
        }

        // Give the target output.
        if (mode == DeliveryMode.CURSOR_FIRST) {
            deliverToCursor(player, plan.target(), plan.targetCount());
        } else {
            giveOrDrop(player, plan.target(), plan.targetCount());
        }

        // Byproducts always go to inventory regardless of delivery mode.
        for (var entry : plan.byproducts().entrySet()) {
            giveOrDrop(player, entry.getKey(), entry.getValue());
        }

        Fabricate.LOGGER.info(
            "[FAB-exec] executed successfully: player={}, target={}, targetCount={}, finalInventory={}",
            player.getGameProfile().getName(),
            ForgeRegistries.ITEMS.getKey(plan.target()),
            plan.targetCount(),
            formatItemMap(inventoryToMap(player.getInventory()))
        );

        return ExecuteResult.ok(plan);
    }

    /**
     * Places {@code qty} of {@code target} on the player's cursor, merging with
     * any matching carried stack. Any overflow goes to inventory.
     */
    private static void deliverToCursor(ServerPlayer player, Item target, int qty) {
        ItemStack output = new ItemStack(target, qty);
        ItemStack carried = player.containerMenu.getCarried();
        int limit = output.getMaxStackSize();

        Fabricate.LOGGER.info(
            "[FAB-cursor] before delivery: player={}, target={}, qty={}, carried={}",
            player.getGameProfile().getName(),
            ForgeRegistries.ITEMS.getKey(target),
            qty,
            describeStack(carried)
        );

        if (!carried.isEmpty() && ItemStack.isSameItemSameTags(carried, output)) {
            int room = Math.max(0, Math.min(carried.getMaxStackSize(), limit) - carried.getCount());
            int add = Math.min(room, output.getCount());

            if (add > 0) {
                carried.grow(add);
                output.shrink(add);
            }
        } else if (carried.isEmpty()) {
            int take = Math.min(limit, output.getCount());

            ItemStack onCursor = output.copy();
            onCursor.setCount(take);

            player.containerMenu.setCarried(onCursor);
            output.shrink(take);
        }

        if (!output.isEmpty()) {
            Fabricate.LOGGER.info(
                "[FAB-cursor] cursor overflow or mismatch, giving remainder to inventory: player={}, remainder={}",
                player.getGameProfile().getName(),
                describeStack(output)
            );

            giveOrDrop(player, output.getItem(), output.getCount());
        }

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
            -1,
            stateId,
            -1,
            player.containerMenu.getCarried()
        ));

        Fabricate.LOGGER.info(
            "[FAB-cursor] after delivery: player={}, carried={}",
            player.getGameProfile().getName(),
            describeStack(player.containerMenu.getCarried())
        );
    }

    private static void giveOrDrop(ServerPlayer player, Item item, int total) {
        int remaining = total;
        int max = item.getMaxStackSize();

        while (remaining > 0) {
            int give = Math.min(remaining, max);
            ItemStack stack = new ItemStack(item, give);

            player.getInventory().add(stack);

            if (!stack.isEmpty()) {
                Fabricate.LOGGER.info(
                    "[FAB-give] inventory full or partial, dropping leftover: player={}, stack={}",
                    player.getGameProfile().getName(),
                    describeStack(stack)
                );

                player.drop(stack, false);
            }

            remaining -= give;
        }
    }

    /**
     * Build a short player-facing failure explanation for a failed craft request.
     *
     * <p>This is intentionally conservative. It does not try to fully explain
     * the recursive search tree. Instead, it looks at direct recipes producing
     * the requested item and reports the closest actionable missing ingredient
     * slots.
     *
     * <p>Important: this dry-runs slot consumption against a temporary inventory
     * copy. That matters for recipes with duplicate ingredients. For example,
     * an iron sword has two iron ingot slots. If the player has only one ingot,
     * the first slot consumes that ingot in the temporary map, and the second
     * slot is correctly reported as missing.
     *
     * <p>Actionable missing-item messages are preferred over generic
     * "looked affordable but failed" messages. This matters for modpacks like
     * GregTech where one recipe candidate may look affordable by simple item
     * identity, while another candidate reveals the useful truth: the player is
     * missing a required tool.
     */
    public static FailureFeedback explainFailure(ServerPlayer player, Item target, int qty) {
        Map<Item, Integer> inventory = inventoryToMap(player.getInventory());
        CraftGraph graph = getGraph(player.server);

        ResourceLocation targetId = ForgeRegistries.ITEMS.getKey(target);
        String targetName = displayItem(target);

        List<CraftGraph.RecipeEdge> producers = graph.getRecipesProducing(target);

        Component title = Component.literal("Cannot craft " + targetName);

        if (producers.isEmpty()) {
            return new FailureFeedback(
                title,
                Component.literal("No crafting recipe produces " + idOrName(targetId, targetName) + ".")
            );
        }

        MissingCandidate best = null;

        for (CraftGraph.RecipeEdge edge : producers) {
            MissingCandidate candidate = evaluateDirectRecipe(edge, qty, inventory);

            if (candidate == null) {
                continue;
            }

            if (isBetterCandidate(candidate, best)) {
                best = candidate;
            }
        }

        if (best == null) {
            return new FailureFeedback(
                title,
                Component.literal("A recipe exists, but Fabricate could not explain why it failed.")
            );
        }

        return new FailureFeedback(title, Component.literal(best.message()));
    }

    /**
     * Candidate explanation for a failed direct recipe.
     *
     * @param score lower is better among candidates of the same kind
     * @param actionable true when this message tells the player a concrete item
     *                   they are missing
     * @param message message shown to the player
     */
    private record MissingCandidate(int score, boolean actionable, String message) {}

    /**
     * Prefer actionable messages over generic diagnostic messages.
     *
     * <p>This avoids the bad UX where a weird recipe that looked affordable
     * by item identity wins with score 0 and hides a useful "Missing: 1x Saw"
     * explanation from another recipe candidate.
     */
    private static boolean isBetterCandidate(MissingCandidate candidate, MissingCandidate currentBest) {
        if (currentBest == null) {
            return true;
        }

        if (candidate.actionable() != currentBest.actionable()) {
            return candidate.actionable();
        }

        return candidate.score() < currentBest.score();
    }

    /**
     * Evaluate one direct producer recipe and return a compact missing-ingredient
     * explanation.
     *
     * <p>This does not recurse. It answers the player-facing question:
     * "For this recipe, what obvious ingredients are missing from my inventory?"
     */
    private static MissingCandidate evaluateDirectRecipe(
        CraftGraph.RecipeEdge edge,
        int requestedQty,
        Map<Item, Integer> inventory
    ) {
        int outputPerBatch = Math.max(1, edge.outputCount());
        int batches = ceilDiv(Math.max(1, requestedQty), outputPerBatch);

        Map<Item, Integer> remaining = new HashMap<>(inventory);
        Map<Item, Integer> missing = new HashMap<>();
        int missingSlots = 0;

        for (int batch = 0; batch < batches; batch++) {
            for (CraftGraph.IngredientSlot slot : edge.inputs()) {
                Item consumed = consumeOneAccepted(slot.acceptedItems(), remaining);

                if (consumed != null) {
                    continue;
                }

                Item representative = chooseRepresentativeMissingItem(slot.acceptedItems());

                if (representative != null) {
                    missing.merge(representative, 1, Integer::sum);
                }

                missingSlots++;
            }
        }

        if (missing.isEmpty()) {
            String recipeId = edge.id() == null ? "unknown recipe" : edge.id().toString();

            return new MissingCandidate(
                Integer.MAX_VALUE,
                false,
                "Recipe " + recipeId + " looked affordable, but Fabricate still could not plan it."
            );
        }

        return new MissingCandidate(
            missingSlots,
            true,
            "Missing: " + formatMissingItems(missing)
        );
    }

    /**
     * Consume one item that can satisfy this ingredient slot from a temporary
     * inventory map.
     *
     * <p>Preference order:
     * 1. Any accepted item already present, highest available count first.
     * 2. Alphabetical display name as a stable tie-breaker.
     */
    private static Item consumeOneAccepted(Set<Item> acceptedItems, Map<Item, Integer> remaining) {
        Item best = null;
        int bestCount = 0;

        for (Item item : acceptedItems) {
            int count = remaining.getOrDefault(item, 0);

            if (count <= 0) {
                continue;
            }

            if (best == null
                || count > bestCount
                || (count == bestCount && displayItem(item).compareToIgnoreCase(displayItem(best)) < 0)) {
                best = item;
                bestCount = count;
            }
        }

        if (best == null) {
            return null;
        }

        dec(remaining, best, 1);
        return best;
    }

    /**
     * Pick the most readable representative item for a missing ingredient slot.
     *
     * <p>Since CraftGraph currently stores flattened accepted item sets instead
     * of original tag names, this chooses a stable, human-readable representative
     * item from the accepted set.
     */
    private static Item chooseRepresentativeMissingItem(Set<Item> acceptedItems) {
        return acceptedItems.stream()
            .min(Comparator.comparing(PlannerService::displayItem, String.CASE_INSENSITIVE_ORDER))
            .orElse(null);
    }

    private static String formatMissingItems(Map<Item, Integer> missing) {
        return missing.entrySet().stream()
            .sorted(Map.Entry.<Item, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(e -> displayItem(e.getKey()), String.CASE_INSENSITIVE_ORDER))
            .map(e -> e.getValue() + "x " + displayItem(e.getKey()))
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private static Map<Item, Integer> inventoryToMap(Inventory inv) {
        Map<Item, Integer> counts = new HashMap<>();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);

            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        return counts;
    }

    private static void dec(Map<Item, Integer> map, Item item, int amount) {
        int v = map.getOrDefault(item, 0) - amount;

        if (v <= 0) {
            map.remove(item);
        } else {
            map.put(item, v);
        }
    }

    private static String formatItemMap(Map<Item, Integer> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;

        for (var entry : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }

            first = false;

            ResourceLocation id = ForgeRegistries.ITEMS.getKey(entry.getKey());

            sb.append(id == null ? String.valueOf(entry.getKey()) : id.toString())
                .append("=")
                .append(entry.getValue());
        }

        sb.append("}");
        return sb.toString();
    }

    private static String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());

        return (id == null ? String.valueOf(stack.getItem()) : id.toString())
            + " x"
            + stack.getCount();
    }

    private static String displayItem(Item item) {
        return item.getDefaultInstance().getHoverName().getString();
    }

    private static String idOrName(ResourceLocation id, String fallback) {
        return id == null ? fallback : id.toString();
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}