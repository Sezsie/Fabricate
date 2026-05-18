package com.sabbs.fabricate.planner;

import com.sabbs.fabricate.Fabricate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single entry point for planner-driven crafting. Wraps {@link CraftGraph},
 * {@link CraftPlanner}, and {@link Reachability} so callers (debug commands,
 * EMI/JEI integrations, network handlers) all go through the same code path.
 *
 * <p>State: one cached {@link CraftGraph} per {@link MinecraftServer}. The
 * graph is rebuilt automatically when the server identity changes (datapack
 * reload swaps the manager via {@code MinecraftServer.reloadResources}; the
 * server reference itself is unchanged, but the swap callsite can invalidate
 * via {@link #invalidate()}).
 */
public final class PlannerService {

    private static volatile CraftGraph cachedGraph;
    private static volatile MinecraftServer cachedGraphServer;

    private PlannerService() {}

    public record ExecuteResult(boolean ok, String reason, CraftPlan plan) {
        public static ExecuteResult fail(String reason) { return new ExecuteResult(false, reason, null); }
        public static ExecuteResult ok(CraftPlan p) { return new ExecuteResult(true, null, p); }
    }

    /** Build-or-reuse the graph for {@code server}. */
    public static synchronized CraftGraph getGraph(MinecraftServer server) {
        if (cachedGraph != null && cachedGraphServer == server) return cachedGraph;
        long t0 = System.nanoTime();
        CraftGraph g = CraftGraph.build(server.getRecipeManager(), server.registryAccess());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Fabricate.LOGGER.info("[FAB-planner] built CraftGraph: {} edges, {} output items, {} input items ({}ms)",
            g.edgeCount(), g.outputItemCount(), g.inputItemCount(), ms);
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
        return new CraftPlanner(getGraph(player.server)).plan(
            target, qty, inventoryToMap(player.getInventory()), has3x3Access(player));
    }

    /**
     * True if the player's current container provides a 3x3 crafting grid
     * (i.e. they're at a {@code CraftingMenu} from a crafting table). The
     * 2x2 grid in their own inventory ({@code InventoryMenu}) doesn't count.
     * Any other menu (chest, furnace) is treated as having 3x3 access too,
     * matching the legacy behavior that only gated 2x2 specifically.
     */
    private static boolean has3x3Access(ServerPlayer player) {
        return !(player.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu);
    }

    /** Items currently craftable from the player's inventory (no quantity check). */
    public static Set<Item> reachable(ServerPlayer player) {
        return Reachability.compute(inventoryToMap(player.getInventory()), getGraph(player.server));
    }

    /** Where the target output goes after a successful execute. */
    public enum DeliveryMode {
        /** Target output goes to inventory (drops at feet if full). */
        INVENTORY,
        /** Target output goes to the player's cursor; overflow falls back to inventory. */
        CURSOR_FIRST
    }

    /**
     * Plan + execute in one call, target routed to inventory. Convenience
     * overload for {@code /fabricate-craft} and unit-test-style callers.
     */
    public static ExecuteResult planAndExecute(ServerPlayer player, Item target, int qty) {
        return planAndExecute(player, target, qty, DeliveryMode.INVENTORY);
    }

    /** Plan + execute in one call with explicit delivery mode. */
    public static ExecuteResult planAndExecute(ServerPlayer player, Item target, int qty, DeliveryMode mode) {
        Optional<CraftPlan> p = plan(player, target, qty);
        if (p.isEmpty()) return ExecuteResult.fail("no plan");
        return execute(player, p.get(), mode);
    }

    /** Convenience overload; target -> inventory. */
    public static ExecuteResult execute(ServerPlayer player, CraftPlan plan) {
        return execute(player, plan, DeliveryMode.INVENTORY);
    }

    /**
     * Execute a pre-built plan against the player's current inventory.
     * Re-checks affordability defensively; a tick could theoretically slip in
     * between plan and execute (cursor pickup, dropped item, etc.).
     *
     * <p>{@code mode} controls where the TARGET output goes; byproducts always
     * go to inventory regardless.
     */
    public static ExecuteResult execute(ServerPlayer player, CraftPlan plan, DeliveryMode mode) {
        Inventory pInv = player.getInventory();
        Map<Item, Integer> current = inventoryToMap(pInv);
        for (var entry : plan.baseCost().entrySet()) {
            if (current.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
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

        // Give the target output (cursor or inventory).
        if (mode == DeliveryMode.CURSOR_FIRST) {
            deliverToCursor(player, plan.target(), plan.targetCount());
        } else {
            giveOrDrop(player, plan.target(), plan.targetCount());
        }

        // Byproducts always go to inventory regardless of delivery mode.
        for (var entry : plan.byproducts().entrySet()) {
            giveOrDrop(player, entry.getKey(), entry.getValue());
        }

        return ExecuteResult.ok(plan);
    }

    /**
     * Places {@code qty} of {@code target} on the player's cursor, merging
     * with any matching stack already there. Any overflow (cursor full or
     * mismatched item) falls back to inventory via {@link #giveOrDrop}.
     * Sends a slot-update packet so the cursor visual stays in sync.
     */
    private static void deliverToCursor(ServerPlayer player, net.minecraft.world.item.Item target, int qty) {
        ItemStack output = new ItemStack(target, qty);
        ItemStack carried = player.containerMenu.getCarried();
        int limit = output.getMaxStackSize();

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
            // Cursor mismatched or full; remainder goes to inventory.
            giveOrDrop(player, output.getItem(), output.getCount());
        }

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
            -1, stateId, -1, player.containerMenu.getCarried()));
    }

    private static void giveOrDrop(ServerPlayer player, Item item, int total) {
        int remaining = total;
        int max = item.getMaxStackSize();
        while (remaining > 0) {
            int give = Math.min(remaining, max);
            ItemStack stack = new ItemStack(item, give);
            // Inventory.add shrinks stack by however many fit. Anything left
            // in `stack` after the call is what didn't fit. Drop the leftover
            // (creative mode voids it by setting count to 0 before returning,
            // matching vanilla item-pickup overflow behavior).
            player.getInventory().add(stack);
            if (!stack.isEmpty()) {
                player.drop(stack, false);
            }
            remaining -= give;
        }
    }

    private static Map<Item, Integer> inventoryToMap(Inventory inv) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }
}
