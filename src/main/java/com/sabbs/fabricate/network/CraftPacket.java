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
import com.sabbs.fabricate.recipe.FabricateRecipe;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class CraftPacket {
    private final ResourceLocation recipeId;
    private final boolean toCursor;

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

            // Refuse synthetic crafts for opted-out players regardless of what
            // their client UI does. This is a defense in depth against a modified or
            // out-of-sync client.
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
            // Reject anything that isn't a crafting recipe. A stale or modified
            // client could otherwise submit a smelting/blasting id (e.g.
            // {@code iron_nugget_from_blasting} which accepts any iron tool)
            // and get the server to honor it through the crafting grid.
            if (recipe.getType() != net.minecraft.world.item.crafting.RecipeType.CRAFTING) {
                com.sabbs.fabricate.Fabricate.LOGGER.warn("[FAB-packet] {} is not a crafting recipe (type={})  aborting",
                    msg.recipeId, recipe.getType());
                return;
            }
            Inventory inv = player.getInventory();

            // 2x2 player crafting can only execute recipes that fit. FAB synthetics
            // carry an explicit flag; vanilla recipes answer via canCraftInDimensions.
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
            if (!consumeMaterials(player, recipe, isSynthetic)) return;

            com.sabbs.fabricate.Fabricate.LOGGER.info("[FAB-packet] {} passed gates  crafting", msg.recipeId);
            ItemStack output = recipe.getResultItem(player.level().registryAccess()).copy();

            // Give output onto the cursor if requested, otherwise into the inventory.
            if (msg.toCursor) {
                deliverToCursor(player, output);
            } else if (!inv.add(output)) {
                player.drop(output, false);
            }

            // Refunds (FAB-synthetic side products) always go to inventory, never the cursor.
            if (isSynthetic && ModConfig.ENABLE_REFUNDS.get()) {
                List<ItemStack> refunds = RefundRegistry.getRefund(msg.recipeId);
                for (ItemStack refund : refunds) {
                    ItemStack copy = refund.copy();
                    if (!inv.add(copy)) {
                        player.drop(copy, false);
                    }
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * Places {@code output} onto the player's held-cursor stack, respecting the
     * item's max stack size. Merges with a compatible existing cursor stack up
     * to the limit, takes a fresh cursor stack (capped at the limit) when empty,
     * and routes any overflow back into the inventory  or, only as a last
     * resort, drops it. Syncs the final cursor state to the client.
     */
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
        // else: cursor holds something incompatible  leave it, route output to inventory below.

        if (!output.isEmpty()) {
            if (!player.getInventory().add(output)) player.drop(output, false);
        }

        int stateId = player.containerMenu.incrementStateId();
        player.connection.send(new ClientboundContainerSetSlotPacket(
            -1, stateId, -1, player.containerMenu.getCarried()));
    }

    /**
     * Deducts exactly one batch's worth of materials from the player's inventory.
     * FAB synthetics use the pre-computed {@link RefundRegistry} requirement map
     * (item → count); vanilla recipes are consumed by walking their Ingredients
     * and reserving one matching item per Ingredient from the live inventory,
     * rolling back on any failure so partial consumption can't occur.
     *
     * <p>Both paths honor the per-item "crafting remainder" rule: items like
     * {@code water_bucket}, {@code milk_bucket}, {@code honey_bottle}, and
     * {@code dragon_breath} return their container ({@code bucket},
     * {@code glass_bottle}) on craft. Vanilla's {@code Recipe.getRemainingItems}
     * does this slot by slot from a {@code CraftingContainer}; we don't have
     * one, so we read the per-Item remainder directly off each consumed stack.
     * Covers ~all real-world cases (Forge's stack-aware
     * {@code hasCraftingRemainingItem}/{@code getCraftingRemainingItem}). The
     * narrow exception is recipes that override {@code getRemainingItems} with
     * NBT-aware logic dependent on the grid layout, which can't be modeled
     * without a real grid.
     */
    private static boolean consumeMaterials(ServerPlayer player, Recipe<?> recipe, boolean isSynthetic) {
        Inventory inv = player.getInventory();
        ResourceLocation id = recipe.getId();

        if (isSynthetic) {
            Map<Item, Integer> required = RefundRegistry.getRequiredItems(id);
            if (required.isEmpty()) {
                com.sabbs.fabricate.Fabricate.LOGGER.warn("[FAB-packet] required-items map empty for {}  aborting", id);
                return false;
            }
            for (var entry : required.entrySet()) {
                if (countItem(inv, entry.getKey()) < entry.getValue()) {
                    com.sabbs.fabricate.Fabricate.LOGGER.info("[FAB-packet] insufficient {} for {}  aborting", entry.getKey(), id);
                    return false;
                }
            }
            List<ItemStack> remainders = new ArrayList<>();
            for (var entry : required.entrySet()) {
                removeItem(inv, entry.getKey(), entry.getValue());
                addRemainderFor(entry.getKey(), entry.getValue(), remainders);
            }
            giveBack(player, remainders);
            return true;
        }

        // Vanilla: greedy slot reservation, one item per non-empty Ingredient.
        List<Ingredient> ings = new ArrayList<>();
        for (Ingredient i : recipe.getIngredients()) if (!i.isEmpty()) ings.add(i);
        if (ings.isEmpty()) {
            com.sabbs.fabricate.Fabricate.LOGGER.warn("[FAB-packet] recipe {} has no ingredients  aborting", id);
            return false;
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
                return false;
            }
        }
        // Capture remainders BEFORE shrinking - the stack is the source of
        // truth for hasCraftingRemainingItem (handles NBT-aware items like
        // potions where the remainder depends on the input stack).
        List<ItemStack> remainders = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            if (reserved[slot] <= 0) continue;
            ItemStack stack = inv.getItem(slot);
            if (stack.hasCraftingRemainingItem()) {
                ItemStack base = stack.getCraftingRemainingItem();
                if (!base.isEmpty()) {
                    // Multiply remainder by the reservation count - one
                    // empty bucket per water_bucket consumed, etc.
                    ItemStack r = base.copy();
                    r.setCount(base.getCount() * reserved[slot]);
                    remainders.add(r);
                }
            }
            stack.shrink(reserved[slot]);
        }
        giveBack(player, remainders);
        return true;
    }

    /**
     * Looks up the per-Item crafting remainder (if any) and appends
     * {@code count} copies to {@code out}. Used by the synthetic path where
     * we consume by item type rather than per-stack, so we can't ask a stack
     * for its remainder. Falls back to {@code Item.hasCraftingRemainingItem}
     * which is item-type-level and ignores NBT - synthetics consume base
     * materials (logs, ingots) that don't have NBT-sensitive remainders
     * anyway, so this matches the use case.
     */
    private static void addRemainderFor(Item item, int count, List<ItemStack> out) {
        if (item == null || count <= 0) return;
        if (!item.hasCraftingRemainingItem()) return;
        Item r = item.getCraftingRemainingItem();
        if (r == null) return;
        out.add(new ItemStack(r, count));
    }

    /** Drops each remainder into the player's inventory, falling back to a world drop. */
    private static void giveBack(ServerPlayer player, List<ItemStack> remainders) {
        if (remainders.isEmpty()) return;
        Inventory inv = player.getInventory();
        for (ItemStack r : remainders) {
            if (r.isEmpty()) continue;
            if (!inv.add(r)) player.drop(r, false);
        }
    }

    private static int countItem(Inventory inv, Item item) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private static void removeItem(Inventory inv, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
    }

}
