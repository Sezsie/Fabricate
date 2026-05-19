package com.sabbs.fabricate.planner;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.sabbs.fabricate.Fabricate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Player-facing commands. Every command routes through {@link PlannerService}
 * so the EMI/JEI click path and these commands hit identical code.
 *
 * <pre>
 *   /fabricate-plan minecraft:comparator [qty]   -- show what the planner would do, no execution
 *   /fabricate-reach [sampleSize]                -- list items currently craftable from inventory
 *   /fabricate-craft minecraft:comparator [qty]  -- plan and execute (consume + give)
 * </pre>
 */
@Mod.EventBusSubscriber(modid = Fabricate.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FabricateCommands {

    private FabricateCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher(), event.getBuildContext());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext ctx) {
        dispatcher.register(
            Commands.literal("fabricate-plan")
                .requires(src -> src.hasPermission(0))
                .then(Commands.argument("item", ItemArgument.item(ctx))
                    .executes(c -> runPlan(c.getSource(),
                        ItemArgument.getItem(c, "item").getItem(), 1))
                    .then(Commands.argument("qty", IntegerArgumentType.integer(1, 64))
                        .executes(c -> runPlan(c.getSource(),
                            ItemArgument.getItem(c, "item").getItem(),
                            IntegerArgumentType.getInteger(c, "qty"))))));

        dispatcher.register(
            Commands.literal("fabricate-reach")
                .requires(src -> src.hasPermission(0))
                .executes(c -> runReach(c.getSource(), 20))
                .then(Commands.argument("sampleSize", IntegerArgumentType.integer(0, 200))
                    .executes(c -> runReach(c.getSource(),
                        IntegerArgumentType.getInteger(c, "sampleSize")))));

        dispatcher.register(
            Commands.literal("fabricate-craft")
                .requires(src -> src.hasPermission(0))
                .then(Commands.argument("item", ItemArgument.item(ctx))
                    .executes(c -> runCraft(c.getSource(),
                        ItemArgument.getItem(c, "item").getItem(), 1))
                    .then(Commands.argument("qty", IntegerArgumentType.integer(1, 64))
                        .executes(c -> runCraft(c.getSource(),
                            ItemArgument.getItem(c, "item").getItem(),
                            IntegerArgumentType.getInteger(c, "qty"))))));
    }

    private static int runPlan(CommandSourceStack source, Item target, int qty) {
        ServerPlayer player = playerOrFail(source);
        if (player == null) return 0;

        long t = System.nanoTime();
        Optional<CraftPlan> result = PlannerService.plan(player, target, qty);
        long planMs = (System.nanoTime() - t) / 1_000_000;

        ResourceLocation targetId = ForgeRegistries.ITEMS.getKey(target);
        if (result.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No plan for " + qty + "x " + targetId
                + " (" + planMs + "ms)").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        CraftPlan plan = result.get();
        source.sendSuccess(() -> header("Plan for " + qty + "x " + targetId + " (" + planMs + "ms)"), false);
        source.sendSuccess(() -> labeled("Base cost", formatCounts(plan.baseCost())), false);
        source.sendSuccess(() -> labeled("Byproducts",
            plan.byproducts().isEmpty() ? "(none)" : formatCounts(plan.byproducts())), false);
        source.sendSuccess(() -> labeled("Tool damage",
            plan.toolDamage().isEmpty() ? "(none)" : formatCounts(plan.toolDamage())), false);
        source.sendSuccess(() -> labeled("Steps", String.valueOf(plan.steps().size())), false);
        int i = 1;
        for (CraftPlan.Step step : plan.steps()) {
            int idx = i++;
            String line = "  " + idx + ". " + step.batches() + "x "
                + step.edge().id()
                + "  consumes " + formatCounts(step.consumed())
                + " -> " + step.producedCount() + " " + ForgeRegistries.ITEMS.getKey(step.producedItem());
            source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int runReach(CommandSourceStack source, int sampleSize) {
        ServerPlayer player = playerOrFail(source);
        if (player == null) return 0;

        long t = System.nanoTime();
        Set<Item> reachable = PlannerService.reachable(player);
        long ms = (System.nanoTime() - t) / 1_000_000;

        Map<Item, Integer> inv = inventoryToMap(player.getInventory());
        int inventoryItems = inv.size();
        int totalReachable = reachable.size();
        int newlyReachable = totalReachable - inventoryItems;

        source.sendSuccess(() -> header("Reachability (" + ms + "ms)"), false);
        source.sendSuccess(() -> labeled("Inventory items", String.valueOf(inventoryItems)), false);
        source.sendSuccess(() -> labeled("Total reachable", String.valueOf(totalReachable)), false);
        source.sendSuccess(() -> labeled("Newly reachable (via crafting)",
            String.valueOf(newlyReachable)), false);

        if (sampleSize > 0 && newlyReachable > 0) {
            List<ResourceLocation> sample = new ArrayList<>();
            for (Item item : reachable) {
                if (inv.containsKey(item)) continue;
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null) sample.add(id);
                if (sample.size() >= sampleSize) break;
            }
            sample.sort(Comparator.comparing(ResourceLocation::toString));
            source.sendSuccess(() -> labeled("Sample (first " + sample.size() + ")",
                String.join(", ", sample.stream().map(ResourceLocation::toString).toList())), false);
        }
        return totalReachable;
    }

    private static int runCraft(CommandSourceStack source, Item target, int qty) {
        ServerPlayer player = playerOrFail(source);
        if (player == null) return 0;

        ResourceLocation targetId = ForgeRegistries.ITEMS.getKey(target);
        PlannerService.ExecuteResult result = PlannerService.planAndExecute(player, target, qty);

        if (!result.ok()) {
            source.sendFailure(Component.literal("Failed: " + result.reason() + " (" + qty + "x " + targetId + ")"));
            return 0;
        }

        CraftPlan plan = result.plan();
        source.sendSuccess(() -> Component.literal(
            "Crafted " + qty + "x " + targetId
                + "  (consumed " + formatCounts(plan.baseCost()) + ")"
                + (plan.byproducts().isEmpty() ? "" : "  (refund " + formatCounts(plan.byproducts()) + ")"))
            .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static ServerPlayer playerOrFail(CommandSourceStack source) {
        try { return source.getPlayerOrException(); }
        catch (Exception e) {
            source.sendFailure(Component.literal("Must be run as a player"));
            return null;
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

    private static String formatCounts(Map<Item, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : counts.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getValue()).append("x ").append(ForgeRegistries.ITEMS.getKey(e.getKey()));
        }
        return sb.toString();
    }

    private static MutableComponent header(String text) {
        return Component.literal("=== " + text + " ===").withStyle(ChatFormatting.GOLD);
    }

    private static MutableComponent labeled(String label, String value) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }
}
