package com.sabbs.fabricate.integration.sophisticated;

import com.sabbs.fabricate.Fabricate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Soft, import-free bridge to Sophisticated Backpacks. When a player has a
 * backpack within reach - in any main-inventory slot (open or closed) or worn
 * in a Curios slot such as the "back" slot - and the server is configured to
 * include backpack inventory, the backpack's contents are exposed to the
 * planner as available crafting materials and may be consumed.
 *
 * <p><b>Why read the ItemStack capability rather than the open menu.</b> A
 * backpack's contents live on the backpack {@link ItemStack} itself (NBT-backed),
 * surfaced through Sophisticated's
 * {@code CapabilityBackpackWrapper.BACKPACK_WRAPPER_CAPABILITY}. Reading that
 * capability works identically whether the backpack is merely carried or
 * currently open with a crafting upgrade, because the stack is in the player's
 * inventory either way. This replaces an earlier approach that scanned the open
 * container menu's slots, which only worked while a backpack was open and missed
 * carried backpacks entirely.
 *
 * <p><b>Why this doesn't break the "zero compile-time dependency" rule.</b> The
 * only Sophisticated types touched are reached reflectively, and only through
 * <em>public methods on public classes</em>:
 * <ul>
 *   <li>{@code CapabilityBackpackWrapper.getCapabilityInstance()} &rarr; the
 *       {@link Capability} token (a Forge type) for the backpack wrapper;</li>
 *   <li>{@code IStorageWrapper.getInventoryHandler()} &rarr; the backpack's
 *       inventory, whose concrete type extends Forge's
 *       {@code net.minecraftforge.items.ItemStackHandler}, so it is returned to
 *       us as a plain Forge {@link IItemHandler}.</li>
 * </ul>
 * No {@code Field.setAccessible(true)} is used, so there is no
 * {@code InaccessibleObjectException} across Forge's module layer (which is what
 * broke the previous reflection-based attempt). Both Sophisticated jars are
 * automatic modules, so their packages are open and public-member reflection
 * succeeds. Once we hold an {@link IItemHandler}, every read/extract uses only
 * Forge API.
 *
 * <p>If Sophisticated Backpacks is not installed, the reflective lookup fails
 * once, is remembered as unavailable, and every method becomes a cheap no-op.
 */
public final class SophisticatedStorageAccess {

    private SophisticatedStorageAccess() {}

    private static final String CAP_CLASS =
        "net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper";
    private static final String WRAPPER_INTERFACE =
        "net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper";

    /**
     * Tri-state init: null = not yet attempted, TRUE = wired up, FALSE =
     * Sophisticated Backpacks absent or incompatible (permanent no-op).
     */
    private static Boolean available;
    private static Capability<?> backpackCap;
    private static Method getInventoryHandler;
    private static Method getUpgradeHandler;

    /**
     * Resolve the backpack wrapper capability token and the
     * {@code getInventoryHandler} accessor once. Returns false (and stays
     * false) if Sophisticated Backpacks isn't present.
     */
    private static synchronized boolean ensureInit() {
        if (available != null) {
            return available;
        }
        try {
            Class<?> capClass = Class.forName(CAP_CLASS);
            Object cap = capClass.getMethod("getCapabilityInstance").invoke(null);
            Class<?> wrapperInterface = Class.forName(WRAPPER_INTERFACE);
            Method handlerAccessor = wrapperInterface.getMethod("getInventoryHandler");
            Method upgradeAccessor = wrapperInterface.getMethod("getUpgradeHandler");

            backpackCap = (Capability<?>) cap;
            getInventoryHandler = handlerAccessor;
            getUpgradeHandler = upgradeAccessor;
            available = Boolean.TRUE;
            Fabricate.LOGGER.info("[FAB-sophisticated] backpack capability bridge initialized");
        } catch (ClassNotFoundException notInstalled) {
            available = Boolean.FALSE;
            Fabricate.LOGGER.debug("[FAB-sophisticated] Sophisticated Backpacks not installed; backpack sourcing disabled");
        } catch (Throwable t) {
            available = Boolean.FALSE;
            Fabricate.LOGGER.warn("[FAB-sophisticated] failed to initialize backpack capability bridge; "
                + "backpack sourcing disabled", t);
        }
        return available;
    }

    /**
     * Every item stack that could be a backpack: the player's main inventory
     * (hotbar + offhand included) plus anything worn in a Curios slot (e.g. a
     * backpack on the "back" slot). All are live references, so mutating a
     * stack's NBT persists.
     */
    private static List<ItemStack> candidateStacks(ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
        stacks.addAll(com.sabbs.fabricate.integration.curios.CuriosAccess.wornItems(player));
        return stacks;
    }

    /**
     * The inventory handlers of every backpack the player can reach: ones
     * carried in the main inventory and ones worn in a Curios slot. Empty when
     * Sophisticated Backpacks is absent or the player has no backpacks.
     */
    private static List<IItemHandler> backpackHandlers(ServerPlayer player) {
        if (!ensureInit()) {
            return List.of();
        }

        List<IItemHandler> handlers = new ArrayList<>();
        for (ItemStack stack : candidateStacks(player)) {
            IItemHandler handler = handlerFor(stack);
            if (handler != null) {
                handlers.add(handler);
            }
        }

        if (Fabricate.LOGGER.isDebugEnabled() && !handlers.isEmpty()) {
            Fabricate.LOGGER.debug("[FAB-sophisticated] player {} can reach {} backpack(s)",
                player.getGameProfile().getName(), handlers.size());
        }
        return handlers;
    }

    /**
     * The Sophisticated storage wrapper backing {@code stack} (as an opaque
     * {@code Object}, since we never import its type), or null if the stack is
     * not a backpack.
     */
    private static Object wrapperFor(ItemStack stack) {
        try {
            @SuppressWarnings("unchecked")
            Capability<Object> cap = (Capability<Object>) backpackCap;
            return stack.getCapability(cap).resolve().orElse(null);
        } catch (Throwable t) {
            Fabricate.LOGGER.debug("[FAB-sophisticated] could not read backpack wrapper from stack {}",
                stack, t);
            return null;
        }
    }

    /**
     * The backpack inventory handler backing {@code stack}, or null if the
     * stack is not a backpack (or the wrapper can't be resolved).
     */
    private static IItemHandler handlerFor(ItemStack stack) {
        Object wrapper = wrapperFor(stack);
        if (wrapper == null) {
            return null;
        }
        try {
            Object handler = getInventoryHandler.invoke(wrapper);
            if (handler instanceof IItemHandler ih) {
                return ih;
            }
        } catch (Throwable t) {
            Fabricate.LOGGER.debug("[FAB-sophisticated] could not read inventory handler from stack {}",
                stack, t);
        }
        return null;
    }

    /**
     * True if the player has at least one backpack (carried or worn in a Curios
     * slot) with a Crafting Upgrade installed. Used to grant 3x3 crafting
     * access: the upgrade gives the backpack a real crafting table, so treating
     * the reachable backpack as an inventory extension that unlocks 3x3 recipes
     * matches in-game behavior.
     */
    public static boolean hasCraftingUpgrade(ServerPlayer player) {
        if (!ensureInit()) {
            return false;
        }
        for (ItemStack stack : candidateStacks(player)) {
            if (backpackHasCraftingUpgrade(stack)) {
                if (Fabricate.LOGGER.isDebugEnabled()) {
                    Fabricate.LOGGER.debug("[FAB-sophisticated] crafting upgrade found in carried/worn backpack; granting 3x3");
                }
                return true;
            }
        }
        return false;
    }

    /** Scan {@code stack}'s upgrade slots for a {@code crafting_upgrade} item. */
    private static boolean backpackHasCraftingUpgrade(ItemStack stack) {
        Object wrapper = wrapperFor(stack);
        if (wrapper == null) {
            return false;
        }
        try {
            Object upgrades = getUpgradeHandler.invoke(wrapper);
            if (!(upgrades instanceof IItemHandler ih)) {
                return false;
            }
            for (int slot = 0; slot < ih.getSlots(); slot++) {
                ItemStack upgrade = ih.getStackInSlot(slot);
                if (upgrade.isEmpty()) {
                    continue;
                }
                var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(upgrade.getItem());
                if (id.getPath().contains("crafting_upgrade")) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Fabricate.LOGGER.debug("[FAB-sophisticated] could not read upgrade handler from stack {}",
                stack, t);
        }
        return false;
    }

    /**
     * Item counts across all carried backpacks, to be merged into the planner's
     * material pool. Empty when no backpacks are carried or the mod is absent.
     */
    public static Map<Item, Integer> readStorage(ServerPlayer player) {
        Map<Item, Integer> counts = new HashMap<>();
        for (IItemHandler handler : backpackHandlers(player)) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    /**
     * Consume up to {@code amount} of {@code item} from carried backpacks,
     * draining slot by slot across every backpack until satisfied.
     *
     * @return the number actually removed (0..amount).
     */
    public static int consume(ServerPlayer player, Item item, int amount) {
        int remaining = amount;
        for (IItemHandler handler : backpackHandlers(player)) {
            for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty() || stack.getItem() != item) {
                    continue;
                }
                ItemStack extracted = handler.extractItem(slot, remaining, false);
                remaining -= extracted.getCount();
            }
            if (remaining <= 0) {
                break;
            }
        }
        return amount - remaining;
    }
}
