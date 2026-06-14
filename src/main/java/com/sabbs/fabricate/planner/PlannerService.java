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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
        Map<Item, Integer> inv = buildMaterialMap(player);
        boolean has3x3 = CraftingGridRegistry.has3x3Access(player);

        // Per-call detail (full inventory dump) at debug so the live log isn't
        // flooded; outcome lines (no-plan / success / failure) stay at info.
        Fabricate.LOGGER.debug(
            "[FAB-planner] planning request: player={}, target={}, qty={}, has3x3={}, inventory={}",
            player.getGameProfile().getName(),
            ForgeRegistries.ITEMS.getKey(target),
            qty,
            has3x3,
            formatItemMap(inv)
        );

        return new CraftPlanner(getGraph(player.server)).plan(target, qty, inv, has3x3);
    }

    /** Items currently craftable from the player's inventory. This is optimistic and quantity-light. */
    public static Set<Item> reachable(ServerPlayer player) {
        return Reachability.compute(buildMaterialMap(player), getGraph(player.server));
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

    /** Max iterations of the "craft an intermediate, retry the target" loop. */
    private static final int UP_TO_MAX_ITERATIONS = 8;

    /**
     * Best-effort variant of {@link #planAndExecute}.
     *
     * <p>If the full plan succeeds, identical to the normal call. If the
     * full plan fails, instead of giving up the planner tries to craft any
     * intermediates the player's current inventory CAN support (e.g.
     * crafting redstone torches from blocks + sticks, even though the
     * top-level recipe also needs iron plates the player doesn't have).
     *
     * <p>Each round:
     * <ol>
     *   <li>Try the full target plan. If it succeeds, execute and return.</li>
     *   <li>For each producer recipe of the target whose workstation gate
     *       the player passes, look at every non-tool input slot. Compute
     *       how short the player is on each, then ask the planner to make
     *       just that shortfall.</li>
     *   <li>If at least one intermediate got crafted, restart the loop -
     *       maybe enough has changed for the top-level to now plan.</li>
     *   <li>If nothing progressed, stop. The remaining shortfall is
     *       reported via {@link #explainPartial}.</li>
     * </ol>
     *
     * <p>The result code distinguishes three cases for the packet handler:
     * <ul>
     *   <li>{@code ok=true}: the target was eventually crafted.</li>
     *   <li>{@code ok=false, reason="partial"}: some intermediates were
     *       crafted but the target wasn't. The packet handler turns this
     *       into a "Crafted X. Still missing Y" message via
     *       {@link #explainPartial}.</li>
     *   <li>{@code ok=false, reason="no plan"}: nothing could be made.
     *       Same UX as a normal failure.</li>
     * </ul>
     */
    public static ExecuteResult planAndExecuteUpTo(
        ServerPlayer player,
        Item target,
        int qty,
        DeliveryMode mode
    ) {
        // Fast path: the full plan works first try.
        Optional<CraftPlan> direct = plan(player, target, qty);
        if (direct.isPresent()) {
            return execute(player, direct.get(), mode);
        }

        boolean has3x3 = CraftingGridRegistry.has3x3Access(player);
        CraftGraph graph = getGraph(player.server);
        List<CraftGraph.RecipeEdge> producers = graph.getRecipesProducing(target);

        if (producers.isEmpty()) {
            Fabricate.LOGGER.info(
                "[FAB-planner] up-to: no producer recipes for {} (player={})",
                ForgeRegistries.ITEMS.getKey(target),
                player.getGameProfile().getName()
            );
            return ExecuteResult.fail("no plan");
        }

        List<CraftedIntermediate> crafted = new ArrayList<>();
        boolean progress = true;
        int iter = 0;

        while (progress && iter++ < UP_TO_MAX_ITERATIONS) {
            progress = false;

            for (CraftGraph.RecipeEdge recipe : producers) {
                if (!has3x3 && recipe.requiresCraftingTable()) continue;

                int outputPerBatch = Math.max(1, recipe.outputCount());
                int batches = ceilDiv(qty, outputPerBatch);

                // For each non-tool input slot, how short is the player?
                // We only try to plan ONE item per slot per pass - the
                // planner itself recurses through that item's sub-tree.
                for (CraftGraph.IngredientSlot slot : recipe.inputs()) {
                    if (IngredientHeuristics.isReusableSlot(slot.acceptedItems())) continue;

                    Map<Item, Integer> inv = buildMaterialMap(player);
                    int availableAcrossSet = 0;
                    for (Item accepted : slot.acceptedItems()) {
                        availableAcrossSet += inv.getOrDefault(accepted, 0);
                    }
                    int needed = batches;
                    int shortfall = needed - availableAcrossSet;
                    if (shortfall <= 0) continue;

                    Item chosen = pickIntermediateCandidate(slot.acceptedItems(), inv);
                    if (chosen == null) continue;

                    // Don't try to "craft" the top-level target as one of
                    // its own ingredients - that would loop.
                    if (chosen == target) continue;

                    Optional<CraftPlan> subPlan = plan(player, chosen, shortfall);
                    if (subPlan.isEmpty()) continue;

                    ExecuteResult subRes = execute(player, subPlan.get(), DeliveryMode.INVENTORY);
                    if (subRes.ok()) {
                        crafted.add(new CraftedIntermediate(chosen, shortfall));
                        progress = true;

                        Fabricate.LOGGER.debug(
                            "[FAB-planner] up-to: crafted intermediate {}x {} for {}",
                            shortfall,
                            ForgeRegistries.ITEMS.getKey(chosen),
                            ForgeRegistries.ITEMS.getKey(target)
                        );
                    }
                }
            }

            // After each pass that made progress, re-try the top-level.
            if (progress) {
                Optional<CraftPlan> retry = plan(player, target, qty);
                if (retry.isPresent()) {
                    ExecuteResult finalRes = execute(player, retry.get(), mode);
                    if (finalRes.ok()) {
                        Fabricate.LOGGER.info(
                            "[FAB-planner] up-to: completed {} via {} intermediate craft(s)",
                            ForgeRegistries.ITEMS.getKey(target),
                            crafted.size()
                        );
                        return finalRes;
                    }
                }
            }
        }

        // We made some progress but couldn't finish. Stash the crafted list
        // on the player for explainPartial to pick up.
        LAST_PARTIAL.put(player.getUUID(), crafted);

        Fabricate.LOGGER.info(
            "[FAB-planner] up-to: partial - crafted {} intermediates, target {} still unreachable",
            crafted.size(),
            ForgeRegistries.ITEMS.getKey(target)
        );

        return crafted.isEmpty()
            ? ExecuteResult.fail("no plan")
            : ExecuteResult.fail("partial");
    }

    /**
     * What was actually crafted on the way to the target, for
     * {@link #explainPartial}'s message.
     */
    private record CraftedIntermediate(Item item, int qty) {}

    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, List<CraftedIntermediate>> LAST_PARTIAL =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Pick which item out of an accepted-set the planner should try to
     * craft as an intermediate. Prefers anything already partially in
     * inventory (best chance of full satisfaction); otherwise the
     * alphabetically-first item, for determinism.
     */
    private static Item pickIntermediateCandidate(Set<Item> acceptedItems, Map<Item, Integer> inv) {
        Item bestInv = null;
        int bestInvCount = 0;
        for (Item item : acceptedItems) {
            int c = inv.getOrDefault(item, 0);
            if (c > bestInvCount) {
                bestInvCount = c;
                bestInv = item;
            }
        }
        if (bestInv != null) return bestInv;

        return acceptedItems.stream()
            .min(Comparator.comparing(PlannerService::displayItem, String.CASE_INSENSITIVE_ORDER))
            .orElse(null);
    }

    /**
     * Build a "partial craft" feedback message after a UP_TO-mode call
     * that crafted some intermediates but couldn't finish the target.
     * Combines a list of what got crafted with the standard "still
     * missing" walk against the player's now-updated inventory.
     */
    public static FailureFeedback explainPartial(ServerPlayer player, Item target, int qty, String reason) {
        List<CraftedIntermediate> crafted = LAST_PARTIAL.remove(player.getUUID());
        FailureFeedback standard = explainFailure(player, target, qty);

        if (crafted == null || crafted.isEmpty()) {
            return standard;
        }

        String craftedSummary = crafted.stream()
            .map(c -> c.qty() + "x " + displayItem(c.item()))
            .collect(Collectors.joining(", "));

        Component title = Component.literal("Partial craft: " + displayItem(target));
        Component detail = Component.literal(
            "Crafted " + craftedSummary + ". "
                + standard.detail().getString()
        );
        return new FailureFeedback(title, detail);
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
        Map<Item, Integer> current = buildMaterialMap(player);

        Fabricate.LOGGER.debug(
            "[FAB-exec] executing plan: player={}, target={}, targetCount={}, mode={}, baseCost={}, byproducts={}, toolDamage={}, currentInventory={}",
            player.getGameProfile().getName(),
            ForgeRegistries.ITEMS.getKey(plan.target()),
            plan.targetCount(),
            mode,
            formatItemMap(plan.baseCost()),
            formatItemMap(plan.byproducts()),
            formatItemMap(plan.toolDamage()),
            formatItemMap(current)
        );

        for (var entry : plan.baseCost().entrySet()) {
            Item item = entry.getKey();
            int needed = entry.getValue();
            int available = current.getOrDefault(item, 0);

            if (available < needed) {
                Fabricate.LOGGER.info(
                    "[FAB-exec] inventory changed mid-craft: player={}, item={}, needed={}, available={}",
                    player.getGameProfile().getName(),
                    ForgeRegistries.ITEMS.getKey(item),
                    needed,
                    available
                );

                return ExecuteResult.fail("inventory changed between plan and execute");
            }
        }

        // Working copies of baseCost / byproducts so the tool-damage pre-pass
        // can debit them without mutating the plan record.
        Map<Item, Integer> baseCost = new HashMap<>(plan.baseCost());
        Map<Item, Integer> byproducts = new HashMap<>(plan.byproducts());

        // Pre-pass: apply durability damage to reusable tools in-place. For
        // each (item, totalDamage) pair, the planner has also added one or
        // more matching entries to baseCost AND byproducts (the loan-and-
        // return accounting). Damaging the original stack and debiting both
        // sides by the loan count keeps the player's stack identity intact:
        // no shrink, no fresh clone, just the tool a little closer to
        // breaking. If the planned damage would actually break the tool, we
        // shrink the stack but still debit byproducts so we don't refund a
        // pristine replacement.
        applyToolDamage(player, plan.toolDamage(), baseCost, byproducts);

        // Consume remaining base cost slot-by-slot. Player inventory first,
        // then any reachable Sophisticated backpacks (carried or worn in a
        // Curios slot) for the remainder - matching the order materials were
        // offered to the planner.
        for (var entry : baseCost.entrySet()) {
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

            if (toConsume > 0 && com.sabbs.fabricate.ModConfig.INCLUDE_BACKPACK_INVENTORY.get()) {
                int fromStorage = com.sabbs.fabricate.integration.sophisticated.SophisticatedStorageAccess
                    .consume(player, item, toConsume);
                toConsume -= fromStorage;
            }
        }

        // Give the target output.
        if (mode == DeliveryMode.CURSOR_FIRST) {
            deliverToCursor(player, plan.target(), plan.targetCount());
        } else {
            giveOrDrop(player, plan.target(), plan.targetCount());
        }

        // Byproducts always go to inventory regardless of delivery mode.
        for (var entry : byproducts.entrySet()) {
            giveOrDrop(player, entry.getKey(), entry.getValue());
        }

        Fabricate.LOGGER.debug(
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
     * any matching carried stack and filling it up to the stack limit. Any
     * overflow that doesn't fit on the cursor is dropped on the ground (split
     * into stack-sized drops), NOT routed into the inventory - cursor-delivery
     * is a "give it to my hand" gesture, so the rest spills out rather than
     * quietly filling backpack/inventory slots the player didn't ask to use.
     */
    private static void deliverToCursor(ServerPlayer player, Item target, int qty) {
        ItemStack output = new ItemStack(target, qty);
        ItemStack carried = player.containerMenu.getCarried();
        int limit = output.getMaxStackSize();

        Fabricate.LOGGER.debug(
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
            Fabricate.LOGGER.debug(
                "[FAB-cursor] overflow beyond cursor stack, dropping remainder on ground: player={}, remainder={}",
                player.getGameProfile().getName(),
                describeStack(output)
            );

            dropOnGround(player, output.getItem(), output.getCount());
        }

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
            -1,
            stateId,
            -1,
            player.containerMenu.getCarried()
        ));

        Fabricate.LOGGER.debug(
            "[FAB-cursor] after delivery: player={}, carried={}",
            player.getGameProfile().getName(),
            describeStack(player.containerMenu.getCarried())
        );
    }

    /**
     * Apply planned durability damage to reusable tools currently in the
     * player's inventory, in-place, before the normal baseCost/byproducts
     * flow runs.
     *
     * <p>For each {@code (tool, totalDamage)} entry we:
     * <ol>
     *   <li>Compute how many tool instances this entry covers as
     *       {@code loans = min(baseCost[tool], byproducts[tool])} - the
     *       planner adds reusable tools to BOTH maps with the same count,
     *       so the matched pair size is the number of "loans" we need to
     *       reconcile.</li>
     *   <li>Distribute {@code totalDamage} across that many real stacks,
     *       splitting as evenly as possible (any leftover damage points
     *       go to the first few stacks).</li>
     *   <li>If the damage would equal or exceed {@code maxDamage}, the
     *       stack is shrunk by 1 (the tool breaks) and is NOT returned.
     *       Otherwise the stack's damage value is bumped and the stack
     *       stays in place.</li>
     *   <li>Either way, debit one count from both baseCost and byproducts
     *       so the caller's shrink-and-refund flow doesn't double-handle
     *       the same tool.</li>
     * </ol>
     *
     * <p>Stacks with more than one item (rare for damageable items, but
     * possible for some modded tools) are treated as a single stack -
     * vanilla {@code ItemStack#setDamageValue} applies to the whole stack
     * representation and we don't try to split it.
     *
     * <p>If the planner planned more loans than there are actual tool
     * stacks in inventory (edge case: planner crafted a fresh tool that
     * isn't here yet) the leftover damage is silently dropped. The total
     * damage applied is at most {@code totalDamage} and the count debited
     * is at most {@code loans}, so the normal flow correctly delivers the
     * unrefunded fresh tool.
     */
    private static void applyToolDamage(
        ServerPlayer player,
        Map<Item, Integer> toolDamage,
        Map<Item, Integer> baseCost,
        Map<Item, Integer> byproducts
    ) {
        if (toolDamage == null || toolDamage.isEmpty()) {
            return;
        }

        Inventory pInv = player.getInventory();

        for (var entry : toolDamage.entrySet()) {
            Item tool = entry.getKey();
            int totalDamage = entry.getValue();
            int loans = Math.min(
                baseCost.getOrDefault(tool, 0),
                byproducts.getOrDefault(tool, 0)
            );

            if (loans <= 0 || totalDamage <= 0) {
                continue;
            }

            // Distribute damage as evenly as possible across the loaned tools.
            int basePerTool = totalDamage / loans;
            int extra = totalDamage % loans;
            int remainingLoans = loans;

            for (int i = 0; i < pInv.getContainerSize() && remainingLoans > 0; i++) {
                ItemStack stack = pInv.getItem(i);
                if (stack.isEmpty() || stack.getItem() != tool) continue;
                if (!stack.isDamageableItem()) continue;

                int dmg = basePerTool + (extra > 0 ? 1 : 0);
                if (extra > 0) extra--;

                int newDmg = stack.getDamageValue() + dmg;

                if (newDmg >= stack.getMaxDamage()) {
                    Fabricate.LOGGER.debug(
                        "[FAB-exec] tool broke during craft: player={}, tool={}, slot={}, oldDamage={}, applied={}, max={}",
                        player.getGameProfile().getName(),
                        ForgeRegistries.ITEMS.getKey(tool),
                        i,
                        stack.getDamageValue(),
                        dmg,
                        stack.getMaxDamage()
                    );
                    stack.shrink(1);
                } else {
                    Fabricate.LOGGER.debug(
                        "[FAB-exec] tool damaged in place: player={}, tool={}, slot={}, oldDamage={}, applied={}, newDamage={}",
                        player.getGameProfile().getName(),
                        ForgeRegistries.ITEMS.getKey(tool),
                        i,
                        stack.getDamageValue(),
                        dmg,
                        newDmg
                    );
                    stack.setDamageValue(newDmg);
                }

                dec(baseCost, tool, 1);
                dec(byproducts, tool, 1);
                remainingLoans--;
            }
        }
    }

    private static void giveOrDrop(ServerPlayer player, Item item, int total) {
        int remaining = total;
        int max = item.getMaxStackSize();

        while (remaining > 0) {
            int give = Math.min(remaining, max);
            ItemStack stack = new ItemStack(item, give);

            player.getInventory().add(stack);

            if (!stack.isEmpty()) {
                Fabricate.LOGGER.debug(
                    "[FAB-give] inventory full, dropping leftover: player={}, stack={}",
                    player.getGameProfile().getName(),
                    describeStack(stack)
                );

                player.drop(stack, false);
            }

            remaining -= give;
        }
    }

    /**
     * Drops {@code total} of {@code item} on the ground at the player's feet,
     * split into stack-sized drops (each up to the item's max stack size).
     * Unlike {@link #giveOrDrop}, this never touches the inventory - used for
     * cursor-delivery overflow, which the player asked to receive on the cursor
     * rather than into storage.
     */
    private static void dropOnGround(ServerPlayer player, Item item, int total) {
        int remaining = total;
        int max = item.getMaxStackSize();

        while (remaining > 0) {
            int give = Math.min(remaining, max);
            player.drop(new ItemStack(item, give), false);
            remaining -= give;
        }
    }

    /** Max recursion depth for the failure-explanation walk. */
    private static final int FAILURE_MAX_DEPTH = 8;

    /**
     * Wall-clock budget for one {@link #explainFailure} call. Failure messages
     * run on the server thread right after a real craft attempt also failed,
     * so we cap how much extra time we spend producing the explanation.
     */
    private static final long FAILURE_BUDGET_MS = 30L;

    /**
     * Build a short player-facing failure explanation for a failed craft request.
     *
     * <p>The explanation walks the recipe tree recursively, consuming what the
     * player actually has at each level, and reports only the leaves it cannot
     * satisfy. For a 3-redstone-torch + 3-stone comparator recipe where the
     * player has 8 sticks + 2 redstone + 0 stone, the older single-level
     * explanation said "Missing: 3x Redstone Torch, 3x Stone." The recursive
     * walk instead reports "Missing: 1x Redstone, 3x Stone" - the actual base
     * materials the player still needs to gather, with the partial inventory
     * already factored in.
     *
     * <p>The walk has three terminal conditions:
     * <ul>
     *   <li>An item has no crafting producers (true base material like stone
     *       or redstone) - it is reported as itself.</li>
     *   <li>A cycle is detected (item A's recipe needs item B whose recipe
     *       needs item A) - the item is reported as itself.</li>
     *   <li>The depth or time budget is exhausted - the item is reported
     *       as itself.</li>
     * </ul>
     *
     * <p>At each recursion step the resolver considers both "break this down
     * via a recipe" and "give up and report the item as the shortfall," and
     * picks whichever produces the smallest total raw-material count - except
     * at the top level, where we always recurse (the target is the thing the
     * player is asking to craft, so reporting "Missing: 1x Comparator" would
     * be useless).
     *
     * <p>Reusable tool slots are reported by tag label rather than recursed
     * into. If the player is missing a hammer, "Missing: 1x #gtceu:tools/hammer"
     * is more useful than walking down into one specific hammer's recipe.
     */
    public static FailureFeedback explainFailure(ServerPlayer player, Item target, int qty) {
        boolean has3x3 = CraftingGridRegistry.has3x3Access(player);
        Map<Item, Integer> inventory = buildMaterialMap(player);
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

        // Workstation gate: at the 2x2 inventory grid the planner refuses 3x3
        // recipes, so the failure-explainer must mirror that filter before
        // walking the recipe tree. Otherwise the walker happily traverses a
        // 3x3 recipe, sees that all the materials are present, and concludes
        // "nothing missing" - while the real planner has already given up on
        // the recipe entirely.
        List<CraftGraph.RecipeEdge> usableProducers = new ArrayList<>(producers.size());
        for (CraftGraph.RecipeEdge edge : producers) {
            if (has3x3 || !edge.requiresCraftingTable()) {
                usableProducers.add(edge);
            }
        }

        if (usableProducers.isEmpty()) {
            return new FailureFeedback(
                title,
                Component.literal("Open a crafting table to craft " + targetName + ".")
            );
        }

        long deadlineNanos = System.nanoTime() + FAILURE_BUDGET_MS * 1_000_000L;
        Map<MissingIngredient, Integer> shortfall = resolveShortfallForRecipes(
            usableProducers,
            target,
            Math.max(1, qty),
            new HashMap<>(inventory),
            graph,
            deadlineNanos
        );

        if (shortfall == null || shortfall.isEmpty()) {
            // Walker found nothing missing, but the real planner still refused
            // the craft. Most likely a recipe-coverage gap or budget hit, not
            // a workstation issue (we already filtered for that above).
            return new FailureFeedback(
                title,
                Component.literal("All ingredients present, but no usable recipe path was found.")
            );
        }

        return new FailureFeedback(
            title,
            Component.literal("Missing: " + formatMissingItems(shortfall))
        );
    }

    /**
     * Top-level entry point for the shortfall walk that uses a pre-filtered
     * list of producer recipes instead of {@code graph.getRecipesProducing}.
     * Used by {@link #explainFailure} to respect workstation gating before
     * the recursive walk runs.
     *
     * <p>Click-to-craft semantics ("always craft one more, even if you
     * already have some") mean the planner deliberately removes the target
     * from its inventory copy before resolving. This walker mirrors that:
     * if the player has 2 comparators in their inventory and asks for one
     * more, we still walk the comparator recipe and report the materials
     * they need to gather - we do NOT silently say "no shortfall, you have
     * comparators already."
     */
    private static Map<MissingIngredient, Integer> resolveShortfallForRecipes(
        List<CraftGraph.RecipeEdge> producers,
        Item target,
        int qty,
        Map<Item, Integer> inventory,
        CraftGraph graph,
        long deadlineNanos
    ) {
        // Mirror CraftPlanner.plan: exclude any of the target item already in
        // inventory so we plan a fresh craft, not a "you already have one"
        // no-op.
        inventory.remove(target);

        int need = Math.max(1, qty);

        Set<Item> visited = new HashSet<>();
        visited.add(target);

        Map<MissingIngredient, Integer> bestShortfall = null;
        int bestTotal = Integer.MAX_VALUE;

        try {
            for (CraftGraph.RecipeEdge recipe : producers) {
                if (System.nanoTime() > deadlineNanos) break;

                int outputPerBatch = Math.max(1, recipe.outputCount());
                int batches = ceilDiv(need, outputPerBatch);

                Map<Item, Integer> invCopy = new HashMap<>(inventory);
                Map<MissingIngredient, Integer> shortfall =
                    evaluateRecipeShortfall(recipe, batches, invCopy, graph, visited, 0, deadlineNanos);

                int total = totalCount(shortfall);
                if (total < bestTotal) {
                    bestTotal = total;
                    bestShortfall = shortfall;
                }
            }
        } finally {
            visited.remove(target);
        }

        return bestShortfall == null ? new HashMap<>() : bestShortfall;
    }

    /**
     * A player-facing missing ingredient entry.
     *
     * @param label item display name or tag label, e.g. "Iron Ingot" or
     *              "#gtceu:tools/buzz_saws"
     * @param reusable true when this represents a returned tool-style ingredient
     */
    private record MissingIngredient(String label, boolean reusable) {}

    /**
     * Recursively figure out the smallest set of raw items the player would
     * have to acquire to satisfy a need of {@code qty} of {@code item}, given
     * a working copy of their {@code inventory}.
     *
     * <p>The {@code inventory} map IS mutated: when this method returns, items
     * it consumed (either directly or transitively through a recipe) have
     * been decremented. The caller passes a copy if it wants to preserve
     * the original.
     *
     * <p>{@code isTopLevel} controls whether the "this item is the shortfall"
     * fallback is considered against recipe candidates. At the top level the
     * fallback is rejected, because the user is explicitly asking how to
     * craft the target - they already know they need the target itself.
     * At sub-levels the fallback wins ties (a base material reported as
     * itself beats a longer chain through inter-craftable items).
     *
     * @return a shortfall map keyed by missing ingredient with positive counts,
     *         or an empty map when the need is fully satisfiable.
     */
    private static Map<MissingIngredient, Integer> resolveShortfall(
        Item item,
        int qty,
        Map<Item, Integer> inventory,
        CraftGraph graph,
        Set<Item> visited,
        int depth,
        long deadlineNanos,
        boolean isTopLevel
    ) {
        if (qty <= 0) return new HashMap<>();

        // Phase 1: take from inventory.
        int have = inventory.getOrDefault(item, 0);
        int take = Math.min(have, qty);
        if (take > 0) dec(inventory, item, take);
        int need = qty - take;
        if (need == 0) return new HashMap<>();

        // Phase 2: terminal conditions - report the item itself.
        boolean budgetExceeded = System.nanoTime() > deadlineNanos;
        boolean depthExceeded = depth >= FAILURE_MAX_DEPTH;
        boolean cycle = visited.contains(item);
        List<CraftGraph.RecipeEdge> producers = graph.getRecipesProducing(item);

        if (budgetExceeded || depthExceeded || cycle || producers.isEmpty()) {
            return singleton(describeConcrete(item), need);
        }

        // Phase 3: try producer recipes, pick the one yielding the lowest
        // raw-material total. Sub-levels also keep "report self" as a
        // candidate; the top level rejects it.
        //
        // UX rule: at sub-levels a recipe candidate is only preferred over
        // "report self" if the walk under that recipe actually USED some
        // inventory. A recipe whose ingredients are entirely missing and
        // whose sub-recurses just push the shortfall deeper is a worse
        // explanation than just naming the missing intermediate. So if the
        // player has 1 stick and the torch slot needs 3, telling them
        // "missing 2 sticks" is more useful than "missing 1 acacia log" -
        // the stick-from-planks chain doesn't help when they have no planks
        // OR logs to begin with.
        Map<MissingIngredient, Integer> bestShortfall;
        Map<Item, Integer> bestInventory;
        int bestTotal;

        if (isTopLevel) {
            bestShortfall = null;
            bestInventory = null;
            bestTotal = Integer.MAX_VALUE;
        } else {
            bestShortfall = singleton(describeConcrete(item), need);
            bestInventory = new HashMap<>(inventory);
            bestTotal = need;
        }

        visited.add(item);
        try {
            for (CraftGraph.RecipeEdge recipe : producers) {
                if (System.nanoTime() > deadlineNanos) break;

                int outputPerBatch = Math.max(1, recipe.outputCount());
                int batches = ceilDiv(need, outputPerBatch);

                Map<Item, Integer> invCopy = new HashMap<>(inventory);
                Map<Item, Integer> invBefore = new HashMap<>(invCopy);
                Map<MissingIngredient, Integer> shortfall =
                    evaluateRecipeShortfall(recipe, batches, invCopy, graph, visited, depth, deadlineNanos);

                int total = totalCount(shortfall);
                boolean consumedInventory = !invCopy.equals(invBefore);

                // Top level: always pick the best recipe; we're committed to
                // crafting the target. Sub level: a recipe only beats the
                // "report self" fallback when it actually uses inventory.
                boolean recipePrefers = isTopLevel
                    ? total < bestTotal
                    : consumedInventory && total < bestTotal;

                if (recipePrefers) {
                    bestTotal = total;
                    bestShortfall = shortfall;
                    bestInventory = invCopy;
                }
            }
        } finally {
            visited.remove(item);
        }

        if (bestShortfall == null) {
            // Top-level with no usable recipe (shouldn't happen since we
            // verified producers exist, but be safe).
            return singleton(describeConcrete(item), need);
        }

        // Commit the best path's inventory consumption back to the caller.
        inventory.clear();
        inventory.putAll(bestInventory);
        return bestShortfall;
    }

    /**
     * Compute the shortfall for one recipe attempt: aggregate the ingredient
     * slots, take what's available from the working inventory copy, and
     * recurse on whatever's still missing.
     */
    private static Map<MissingIngredient, Integer> evaluateRecipeShortfall(
        CraftGraph.RecipeEdge recipe,
        int batches,
        Map<Item, Integer> invCopy,
        CraftGraph graph,
        Set<Item> visited,
        int depth,
        long deadlineNanos
    ) {
        Map<MissingIngredient, Integer> shortfall = new HashMap<>();

        // Aggregate identical-accepted-set slots so e.g. four "any plank"
        // slots become one entry of qty=4 (or qty=1 for reusable-tool slots,
        // which are shared across all batches of one recipe attempt).
        Map<Set<Item>, Integer> aggregated = new LinkedHashMap<>();
        for (CraftGraph.IngredientSlot slot : recipe.inputs()) {
            int perSlot = IngredientHeuristics.isReusableSlot(slot.acceptedItems()) ? 1 : batches;
            aggregated.merge(slot.acceptedItems(), perSlot, Integer::sum);
        }

        for (var entry : aggregated.entrySet()) {
            if (System.nanoTime() > deadlineNanos) break;

            Set<Item> acceptedSet = entry.getKey();
            int slotNeed = entry.getValue();
            boolean reusable = IngredientHeuristics.isReusableSlot(acceptedSet);

            // Try to satisfy from inventory across all accepted items.
            // Reusable slots are satisfied by ANY one matching item without
            // decrementing - the tool is returned per the planner's
            // remainder accounting.
            if (reusable) {
                boolean toolPresent = false;
                for (Item candidate : acceptedSet) {
                    if (invCopy.getOrDefault(candidate, 0) > 0) {
                        toolPresent = true;
                        break;
                    }
                }
                if (toolPresent) continue;

                // Missing tool: report by tag label (or by representative
                // concrete name if no useful tag is shared).
                MissingIngredient ing = describeMissingIngredient(acceptedSet);
                shortfall.merge(ing, 1, Integer::max);
                continue;
            }

            for (Item candidate : acceptedSet) {
                if (slotNeed == 0) break;
                int avail = invCopy.getOrDefault(candidate, 0);
                if (avail <= 0) continue;
                int useable = Math.min(avail, slotNeed);
                dec(invCopy, candidate, useable);
                slotNeed -= useable;
            }

            if (slotNeed > 0) {
                // Tag-style slot (e.g. #minecraft:planks accepts any plank
                // type): report the shortfall as the tag label itself
                // instead of recursing into one alphabetically-chosen
                // representative. Otherwise a 3-plank shortfall on a
                // tag slot would render as "3x Acacia Planks" - misleading
                // because the player could equally well supply any other
                // plank type.
                if (acceptedSet.size() > 1) {
                    net.minecraft.tags.TagKey<Item> tag =
                        IngredientHeuristics.findBestCommonTag(acceptedSet);
                    if (tag != null
                        && IngredientHeuristics.tagPriority(tag)
                            <= IngredientHeuristics.USEFUL_TAG_PRIORITY_CUTOFF) {
                        shortfall.merge(
                            new MissingIngredient(IngredientHeuristics.labelOf(tag), false),
                            slotNeed,
                            Integer::sum
                        );
                        continue;
                    }
                }

                // Singleton slot, or multi-item slot with no clean shared
                // tag: recurse on the alphabetically-first item so the
                // walker can break it down into base materials.
                Item representative = chooseRepresentativeMissingItem(acceptedSet);
                if (representative == null) continue;

                Map<MissingIngredient, Integer> sub = resolveShortfall(
                    representative,
                    slotNeed,
                    invCopy,
                    graph,
                    visited,
                    depth + 1,
                    deadlineNanos,
                    false
                );
                for (var s : sub.entrySet()) {
                    shortfall.merge(s.getKey(), s.getValue(), Integer::sum);
                }
            }
        }

        return shortfall;
    }

    private static MissingIngredient describeConcrete(Item item) {
        return new MissingIngredient(displayItem(item), false);
    }

    private static Map<MissingIngredient, Integer> singleton(MissingIngredient key, int value) {
        Map<MissingIngredient, Integer> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    private static int totalCount(Map<MissingIngredient, Integer> m) {
        int sum = 0;
        for (int v : m.values()) sum += v;
        return sum;
    }

    /**
     * Describe an ingredient slot using the most useful player-facing label.
     *
     * <p>Tag labels are preferred whenever the shared tag scores well in
     * {@link IngredientHeuristics#tagPriority}:
     * <ul>
     *   <li><b>Reusable tool slots</b> always prefer the tag (e.g.
     *       "Missing: 1x #gtceu:tools/buzz_saws" instead of one specific
     *       buzzsaw tier the player may not even own).</li>
     *   <li><b>Non-reusable material slots</b> prefer a clean material tag
     *       when one is available (e.g. "Missing: 3x #minecraft:planks"
     *       instead of "3x Acacia Planks" - so a player holding birch
     *       planks isn't told they need acacia specifically).</li>
     * </ul>
     *
     * <p>If the best shared tag is junk-looking (vanilla internal markers
     * like "#minecraft:completes_find_tree_tutorial") it scores past the
     * cutoff and we fall back to the representative concrete item name.
     */
    private static MissingIngredient describeMissingIngredient(Set<Item> acceptedItems) {
        boolean reusable = IngredientHeuristics.isReusableSlot(acceptedItems);

        net.minecraft.tags.TagKey<Item> tag = IngredientHeuristics.findBestCommonTag(acceptedItems);
        if (tag != null) {
            int priority = IngredientHeuristics.tagPriority(tag);
            boolean tagIsUseful = reusable
                || priority <= IngredientHeuristics.USEFUL_TAG_PRIORITY_CUTOFF;
            if (tagIsUseful) {
                return new MissingIngredient(IngredientHeuristics.labelOf(tag), reusable);
            }
        }

        Item representative = chooseRepresentativeMissingItem(acceptedItems);
        String label = representative == null ? "unknown ingredient" : displayItem(representative);

        return new MissingIngredient(label, reusable);
    }

    /**
     * Pick the most readable representative item for a missing ingredient slot.
     */
    private static Item chooseRepresentativeMissingItem(Set<Item> acceptedItems) {
        if (acceptedItems == null || acceptedItems.isEmpty()) {
            return null;
        }

        return acceptedItems.stream()
            .min(Comparator.comparing(PlannerService::displayItem, String.CASE_INSENSITIVE_ORDER))
            .orElse(null);
    }

    private static String formatMissingItems(Map<MissingIngredient, Integer> missing) {
        return missing.entrySet().stream()
            .sorted(Map.Entry.<MissingIngredient, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(e -> e.getKey().label(), String.CASE_INSENSITIVE_ORDER))
            .map(e -> e.getValue() + "x " + e.getKey().label())
            .collect(Collectors.joining(", "));
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

    /**
     * The full pool of materials available to {@code player}: their own
     * inventory, plus the contents of any Sophisticated backpack they can reach
     * - carried (open or closed) or worn in a Curios slot - when
     * {@link com.sabbs.fabricate.ModConfig#INCLUDE_BACKPACK_INVENTORY}
     * is enabled. This is the single source of truth for planning, the
     * pre-execute availability check, and failure explanations, so they all
     * agree on what the player can reach.
     */
    private static Map<Item, Integer> buildMaterialMap(ServerPlayer player) {
        Map<Item, Integer> counts = inventoryToMap(player.getInventory());

        if (com.sabbs.fabricate.ModConfig.INCLUDE_BACKPACK_INVENTORY.get()) {
            Map<Item, Integer> storage =
                com.sabbs.fabricate.integration.sophisticated.SophisticatedStorageAccess.readStorage(player);
            for (var e : storage.entrySet()) {
                counts.merge(e.getKey(), e.getValue(), Integer::sum);
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