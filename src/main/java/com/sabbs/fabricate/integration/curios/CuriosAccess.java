package com.sabbs.fabricate.integration.curios;

import com.sabbs.fabricate.Fabricate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Soft, import-free bridge to the Curios API. Exposes the items a player has
 * equipped in Curios slots (rings, belts, the "back" slot, etc.) so other
 * integrations can treat them as part of the player's reachable gear.
 *
 * <p>The motivating case: players often wear a Sophisticated Backpack in a
 * Curios "back" slot rather than carrying it in the main inventory. Fabricate's
 * backpack material sourcing only walked the vanilla inventory, so a worn
 * backpack's contents were invisible to the planner. This bridge surfaces the
 * worn stacks; {@link com.sabbs.fabricate.integration.sophisticated.SophisticatedStorageAccess}
 * then applies the same backpack-wrapper read/consume logic to them.
 *
 * <p>As with the Sophisticated bridge, the only Curios types touched are reached
 * reflectively through <em>public methods on public classes</em>:
 * {@code CuriosApi.getCuriosInventory(LivingEntity)} (returns a Forge
 * {@link LazyOptional}) and {@code ICuriosItemHandler.getEquippedCurios()}
 * (returns a Forge {@link IItemHandler}). No {@code setAccessible} is used, so
 * there is no cross-module {@code InaccessibleObjectException}. If Curios is not
 * installed, the lookup fails once, is cached as unavailable, and
 * {@link #wornItems} becomes a cheap no-op returning an empty list.
 */
public final class CuriosAccess {

    private CuriosAccess() {}

    private static final String CURIOS_API = "top.theillusivec4.curios.api.CuriosApi";
    private static final String CURIOS_HANDLER =
        "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";

    private static Boolean available;
    private static Method getCuriosInventory;
    private static Method getEquippedCurios;

    private static synchronized boolean ensureInit() {
        if (available != null) {
            return available;
        }
        try {
            Class<?> apiClass = Class.forName(CURIOS_API);
            getCuriosInventory = apiClass.getMethod("getCuriosInventory", LivingEntity.class);
            Class<?> handlerClass = Class.forName(CURIOS_HANDLER);
            getEquippedCurios = handlerClass.getMethod("getEquippedCurios");
            available = Boolean.TRUE;
            Fabricate.LOGGER.info("[FAB-curios] Curios bridge initialized");
        } catch (ClassNotFoundException notInstalled) {
            available = Boolean.FALSE;
            Fabricate.LOGGER.debug("[FAB-curios] Curios not installed; worn-slot sourcing disabled");
        } catch (Throwable t) {
            available = Boolean.FALSE;
            Fabricate.LOGGER.warn("[FAB-curios] failed to initialize Curios bridge; "
                + "worn-slot sourcing disabled", t);
        }
        return available;
    }

    /**
     * The (non-empty) item stacks the player has equipped across all Curios
     * slots. These are live references into the Curios inventory, so mutating a
     * stack's NBT (as the backpack wrapper does when extracting) persists.
     * Empty list when Curios is absent or nothing is equipped.
     */
    public static List<ItemStack> wornItems(ServerPlayer player) {
        if (!ensureInit()) {
            return List.of();
        }

        List<ItemStack> worn = new ArrayList<>();
        try {
            Object lazy = getCuriosInventory.invoke(null, player);
            if (!(lazy instanceof LazyOptional<?> lazyOpt)) {
                return List.of();
            }
            Optional<?> resolved = lazyOpt.resolve();
            if (resolved.isEmpty()) {
                return List.of();
            }

            Object equipped = getEquippedCurios.invoke(resolved.get());
            if (!(equipped instanceof IItemHandler handler)) {
                return List.of();
            }

            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    worn.add(stack);
                }
            }
        } catch (Throwable t) {
            Fabricate.LOGGER.debug("[FAB-curios] could not read equipped curios for {}",
                player.getGameProfile().getName(), t);
            return List.of();
        }

        if (Fabricate.LOGGER.isDebugEnabled() && !worn.isEmpty()) {
            Fabricate.LOGGER.debug("[FAB-curios] player {} has {} equipped curio(s)",
                player.getGameProfile().getName(), worn.size());
        }
        return worn;
    }
}
