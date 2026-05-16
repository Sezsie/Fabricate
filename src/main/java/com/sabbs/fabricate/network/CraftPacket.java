package com.sabbs.fabricate.network;

import com.sabbs.fabricate.ModConfig;
import com.sabbs.fabricate.recipe.RefundRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import com.sabbs.fabricate.recipe.FabricateRecipe;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class CraftPacket {
    private final ResourceLocation recipeId;
    private final boolean toCursor;

    private record ConsumptionResult(
        boolean success,
        Map<Item, Map<Item, Integer>> consumedByRequired
    ) {
        static ConsumptionResult fail() {
            return new ConsumptionResult(false, Map.of());
        }

        static ConsumptionResult ok(Map<Item, Map<Item, Integer>> consumedByRequired) {
            return new ConsumptionResult(true, consumedByRequired);
        }
    }

    public CraftPacket(ResourceLocation recipeId) {
        this(recipeId, false);
    }

    public CraftPacket(ResourceLocation recipeId, boolean toCursor) {
        this.recipeId = recipeId;
        this.toCursor = toCursor;
    }

    public static void encode(CraftPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.recipeId);
        buf.writeBoolean(msg.toCursor);
    }

    public static CraftPacket decode(FriendlyByteBuf buf) {
        ResourceLocation id = buf.readResourceLocation();
        boolean toCursor = buf.readBoolean();
        return new CraftPacket(id, toCursor);
    }

    public static void handle(CraftPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                com.sabbs.fabricate.Fabricate.LOGGER.warn("[FAB-packet] received CraftPacket with null sender");
                return;
            }

            com.sabbs.fabricate.Fabricate.LOGGER.info("[FAB-packet] received {} (toCursor={}) from {}",
                msg.recipeId, msg.toCursor, player.getGameProfile().getName());

            if (com.sabbs.fabricate.Fabricate.MOD_ID.equals(msg.recipeId.getNamespace())
                && com.sabbs.fabricate.OptOutRegistry.isOptedOut(player.getUUID())) {
                com.sabbs.fabricate.Fabricate.LOGGER.info("[FAB-packet] {} opted out, rejecting synthetic craft {}",
                    player.getGameProfile().getName(), msg.recipeId);
                return;
            }

            var opt = player.server.getRecipeManager().byKey(msg.recipeId);
            if (opt.isEmpty()) {
                com.sabbs.fabricate.Fabricate.LOGGER.warn("[FAB-packet] server RecipeManager has no recipe {}  aborting", msg.recipeId);
                return;
            }

            Recipe<?> recipe = opt.get();

            if (recipe.getType() != RecipeType.CRAFTING) {
                com.sabbs.fabricate.Fabricate.LOGGER.warn("[FAB-packet] {} is not a crafting recipe (type={})  aborting",
                    msg.recipeId, recipe.getType());
                return;
            }

            Inventory inv = player.getInventory();

            if (player.containerMenu instanceof InventoryMenu) {
                boolean fits = (recipe instanceof FabricateRecipe tp)
                    ? !tp.requiresCraftingTable()
                    : recipe.canCraftInDimensions(2, 2);

                if (!fits) {
                    com.sabbs.fabricate.Fabricate.LOGGER.info("[FAB-packet] {} doesn't fit in 2x2  aborting", msg.recipeId);
                    return;
                }
            }

            boolean isSynthetic = RefundRegistry.has(msg.recipeId);

            ConsumptionResult consumption = consumeMaterials(player, recipe, isSynthetic);
            if (!consumption.success()) return;

            com.sabbs.fabricate.Fabricate.LOGGER.info("[FAB-packet] {} passed gates  crafting", msg.recipeId);

            ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();

            if (msg.toCursor) {
                deliverToCursor(player, output);
            } else if (!inv.add(output)) {
                player.drop(output, false);
            }

            if (isSynthetic && ModConfig.ENABLE_REFUNDS.get()) {
                List<ItemStack> refunds = RefundRegistry.getRefund(msg.recipeId);

                for (ItemStack refund : refunds) {
                    ItemStack copy = remapRefundVariant(player, refund, consumption.consumedByRequired());

                    if (!inv.add(copy)) {
                        player.drop(copy, false);
                    }
                }
            }
        });

        ctx.get().setPacketHandled(true);
    }

    private static void deliverToCursor(ServerPlayer player, ItemStack output) {
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
            if (!player.getInventory().add(output)) {
                player.drop(output, false);
            }
        }

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, player.containerMenu.getCarried()));
    }

    private static ConsumptionResult consumeMaterials(ServerPlayer player, Recipe<?> recipe, boolean isSynthetic) {
        Inventory inv = player.getInventory();
        ResourceLocation id = recipe.getId();

        if (isSynthetic) {
            Map<Item, Integer> required = RefundRegistry.getRequiredItems(id);

            if (required.isEmpty()) {
                com.sabbs.fabricate.Fabricate.LOGGER.warn("[FAB-packet] required-items map empty for {}  aborting", id);
                return ConsumptionResult.fail();
            }

            for (var entry : required.entrySet()) {
                int have = countWithEquivalents(inv, entry.getKey());

                if (have < entry.getValue()) {
                    com.sabbs.fabricate.Fabricate.LOGGER.info("[FAB-packet] insufficient {} for {}  aborting", entry.getKey(), id);
                    return ConsumptionResult.fail();
                }
            }

            List<ItemStack> remainders = new ArrayList<>();
            Map<Item, Map<Item, Integer>> consumedByRequired = new HashMap<>();

            for (var entry : required.entrySet()) {
                removeWithEquivalents(
                    inv,
                    entry.getKey(),
                    entry.getValue(),
                    remainders,
                    consumedByRequired
                );
            }

            giveBack(player, remainders);
            return ConsumptionResult.ok(consumedByRequired);
        }

        List<Ingredient> ings = new ArrayList<>();
        for (Ingredient i : recipe.getIngredients()) {
            if (!i.isEmpty()) {
                ings.add(i);
            }
        }

        if (ings.isEmpty()) {
            com.sabbs.fabricate.Fabricate.LOGGER.warn("[FAB-packet] recipe {} has no ingredients  aborting", id);
            return ConsumptionResult.fail();
        }

        int size = inv.getContainerSize();
        int[] reserved = new int[size];

        for (Ingredient ing : ings) {
            boolean satisfied = false;

            for (int slot = 0; slot < size; slot++) {
                ItemStack s = inv.getItem(slot);

                if (s.isEmpty() || s.getCount() - reserved[slot] <= 0) continue;

                if (ing.test(s)) {
                    reserved[slot]++;
                    satisfied = true;
                    break;
                }
            }

            if (!satisfied) {
                com.sabbs.fabricate.Fabricate.LOGGER.info("[FAB-packet] cannot satisfy ingredient for {}  aborting", id);
                return ConsumptionResult.fail();
            }
        }

        List<ItemStack> remainders = new ArrayList<>();

        for (int slot = 0; slot < size; slot++) {
            if (reserved[slot] <= 0) continue;

            ItemStack stack = inv.getItem(slot);

            if (stack.hasCraftingRemainingItem()) {
                ItemStack base = stack.getCraftingRemainingItem();

                if (!base.isEmpty()) {
                    ItemStack r = base.copy();
                    r.setCount(base.getCount() * reserved[slot]);
                    remainders.add(r);
                }
            }

            stack.shrink(reserved[slot]);
        }

        giveBack(player, remainders);
        return ConsumptionResult.ok(Map.of());
    }

    private static void giveBack(ServerPlayer player, List<ItemStack> remainders) {
        if (remainders.isEmpty()) return;

        Inventory inv = player.getInventory();

        for (ItemStack r : remainders) {
            if (r.isEmpty()) continue;
            if (!inv.add(r)) {
                player.drop(r, false);
            }
        }
    }

    private static int countWithEquivalents(Inventory inv, Item required) {
        java.util.Set<Item> eq = com.sabbs.fabricate.recipe.TagEquivalence.equivalents(required);
        int sum = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);

            if (stack.isEmpty()) continue;
            if (eq.contains(stack.getItem())) {
                sum += stack.getCount();
            }
        }

        return sum;
    }

    private static void removeWithEquivalents(
            Inventory inv,
            Item required,
            int amount,
            List<ItemStack> remainders,
            Map<Item, Map<Item, Integer>> consumedByRequired
    ) {
        int remaining = amount;
        int size = inv.getContainerSize();

        for (int i = 0; i < size && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);

            if (stack.isEmpty()) continue;
            if (stack.getItem() != required) continue;

            int take = Math.min(remaining, stack.getCount());

            accumulateRemainder(stack, take, remainders);
            noteConsumed(consumedByRequired, required, stack.getItem(), take);

            stack.shrink(take);
            remaining -= take;
        }

        if (remaining <= 0) return;

        java.util.Set<Item> eq = com.sabbs.fabricate.recipe.TagEquivalence.equivalents(required);

        for (int i = 0; i < size && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);

            if (stack.isEmpty()) continue;

            Item actual = stack.getItem();

            if (actual == required) continue;
            if (!eq.contains(actual)) continue;

            int take = Math.min(remaining, stack.getCount());

            accumulateRemainder(stack, take, remainders);
            noteConsumed(consumedByRequired, required, actual, take);

            stack.shrink(take);
            remaining -= take;
        }
    }

    private static void noteConsumed(
            Map<Item, Map<Item, Integer>> consumedByRequired,
            Item required,
            Item actual,
            int count
    ) {
        if (count <= 0) return;

        consumedByRequired
            .computeIfAbsent(required, k -> new HashMap<>())
            .merge(actual, count, Integer::sum);
    }

    private static void accumulateRemainder(ItemStack stack, int consumed, List<ItemStack> out) {
        if (consumed <= 0) return;
        if (!stack.hasCraftingRemainingItem()) return;

        ItemStack base = stack.getCraftingRemainingItem();
        if (base.isEmpty()) return;

        ItemStack r = base.copy();
        r.setCount(base.getCount() * consumed);
        out.add(r);
    }

    private static ItemStack remapRefundVariant(
            ServerPlayer player,
            ItemStack refund,
            Map<Item, Map<Item, Integer>> consumedByRequired
    ) {
        if (refund.isEmpty() || consumedByRequired.isEmpty()) {
            return refund.copy();
        }

        Item refundItem = refund.getItem();
        Item remapped = findRefundVariant(player, refundItem, consumedByRequired);

        if (remapped == refundItem) {
            return refund.copy();
        }

        return new ItemStack(remapped, refund.getCount());
    }

    private static Item findRefundVariant(
            ServerPlayer player,
            Item refundItem,
            Map<Item, Map<Item, Integer>> consumedByRequired
    ) {
        Item best = refundItem;
        int bestConsumedCount = -1;

        for (Map<Item, Integer> actualCounts : consumedByRequired.values()) {
            for (var consumedEntry : actualCounts.entrySet()) {
                Item actualConsumed = consumedEntry.getKey();
                int consumedCount = consumedEntry.getValue();

                Item candidate = findDirectOutputVariant(player, actualConsumed, refundItem);
                if (candidate == null) continue;

                if (consumedCount > bestConsumedCount) {
                    best = candidate;
                    bestConsumedCount = consumedCount;
                }
            }
        }

        return best;
    }

    private static Item findDirectOutputVariant(ServerPlayer player, Item actualInput, Item refundItem) {
        ItemStack probe = new ItemStack(actualInput);

        for (Recipe<?> recipe : player.server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack output = recipe.getResultItem(player.level().registryAccess());
            if (output.isEmpty()) continue;

            Item candidate = output.getItem();

            if (candidate != refundItem
                && !com.sabbs.fabricate.recipe.TagEquivalence.canSubstitute(refundItem, candidate)) {
                continue;
            }

            boolean hasIngredient = false;
            boolean allIngredientsAcceptActual = true;

            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;

                hasIngredient = true;

                if (!ingredient.test(probe)) {
                    allIngredientsAcceptActual = false;
                    break;
                }
            }

            if (hasIngredient && allIngredientsAcceptActual) {
                return candidate;
            }
        }

        return null;
    }
}