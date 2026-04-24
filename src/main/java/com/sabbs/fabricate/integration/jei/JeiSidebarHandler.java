package com.sabbs.fabricate.integration.jei;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.network.CraftPacket;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.recipe.CraftabilityCheck;
import com.sabbs.fabricate.recipe.RecipeSelector;
import com.sabbs.fabricate.recipe.RefundRegistry;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JEI-side Fabricate behavior mirrors EMI's pattern: register once, respond
 * to clicks, keep runtime mutations minimal. Provides two things:
 *
 * <ul>
 *   <li><b>Click-to-craft on the sidebar:</b> left-click on an ingredient that
 *       a Fabricate synthetic can produce dispatches a {@link CraftPacket}.
 *       Shift + left-click crafts up to one stack, routed to inventory.</li>
 *   <li><b>Craftables-mode filter:</b> while a {@link CraftingScreen} is open
 *       and JEI's search box is empty, hide every sidebar ingredient the
 *       player can't currently produce (vanilla or synthetic). Restored the
 *       instant the player types into the filter or leaves the screen. EMI's
 *       aggregate-card trick does the equivalent job at registration time;
 *       JEI doesn't have aggregate cards, so we do it via {@code
 *       removeIngredientsAtRuntime}.</li>
 * </ul>
 *
 * <p>We deliberately do <em>not</em> call JEI's {@code hideRecipes} /
 * {@code unhideRecipes} on screen transitions. Those block the main thread
 * for hundreds of ms on large modpacks (JEI rebuilds its filter tree), which
 * manifested as a visible hang when opening a crafting table.
 */
public final class JeiSidebarHandler {

    private JeiSidebarHandler() {}

    /** Items currently hidden from JEI while craftables mode is on. */
    private static final List<ItemStack> HIDDEN = new ArrayList<>();
    private static boolean craftablesActive = false;

    /** Prevents per-tick thrashing if the ingredient manager isn't ready yet. */
    private static int evalCooldown = 0;

    /**
     * Hash of the inventory + cursor + crafting-grid state used to compute the
     * last craftable set. Skipping refreshes when the player's pool of
     * materials is unchanged keeps the list from flickering when an item is
     * briefly picked up onto the cursor and put right back.
     */
    private static long lastMaterialsHash = Long.MIN_VALUE;
    private static Set<Item> lastCraftableSet = Collections.emptySet();

    /**
     * Set when we consume a left-click to trigger a craft. The corresponding
     * mouse-release must also be swallowed, otherwise vanilla's release
     * handling (e.g. throw-cursor-outside-slot on quick re-clicks) can drop
     * the freshly-crafted item back out of the cursor.
     */
    private static boolean swallowNextRelease = false;

    /**
     * Precomputed per-recipe {@code (needed materials, output item)} tuples.
     * Built lazily on the first {@code computeCraftableItems} call and reused
     * until the underlying {@link RecipeManager} reference changes (which
     * coincides with {@code RecipesUpdatedEvent}). Without this, every
     * craftables refresh walked the RecipeManager and re-called
     * {@code getIngredients().getItems()} + {@code getResultItem(ra)} per
     * recipe. This is expensive enough on a heavy pack to cause a first-open stutter.
     */
    private record RecipeNeeds(Map<Item, Integer> needed, Item output) {}
    private static List<RecipeNeeds> recipeCache = null;
    private static RecipeManager recipeCacheSource = null;

    // ---------------------------------------------------------------- click-to-craft

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) return;
        if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) return;

        IJeiRuntime rt = FabricateJeiPlugin.getRuntime();
        if (rt == null) return;

        ItemStack hovered;
        try {
            hovered = rt.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        } catch (Throwable t) {
            return;
        }
        if (hovered == null || hovered.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        // Pick the recipe whose inputs sit closest to vanilla for this output
        // and that the player can actually afford right now. Matches the EMI
        // path (FabricateCraftingHandler.resolveCraftable) so both viewers
        // dispatch the same variant for the same inventory state.
        Recipe<?> chosen = RecipeSelector.pickBest(hovered.getItem());
        if (chosen == null) return;
        ResourceLocation chosenId = chosen.getId();
        if (chosenId == null) return;

        int maxBatches = CraftabilityCheck.maxBatches(chosen);
        if (maxBatches <= 0) return;

        ItemStack output = chosen.getResultItem(mc.level.registryAccess());
        if (output.isEmpty()) return;

        int batches;
        boolean toCursor;
        if (Screen.hasShiftDown()) {
            int perBatch = Math.max(1, output.getCount());
            int maxStack = output.getMaxStackSize();
            int desired = Math.max(1, maxStack / perBatch);
            batches = Math.min(maxBatches, desired);
            toCursor = false;
        } else {
            batches = 1;
            toCursor = event.getScreen() instanceof AbstractContainerScreen<?>;
        }

        Fabricate.LOGGER.info("[FAB-JEI] dispatching {} CraftPacket(s) for {} (toCursor={})", batches, chosenId, toCursor);
        for (int i = 0; i < batches; i++) {
            NetworkHandler.sendToServer(new CraftPacket(chosenId, toCursor));
        }
        swallowNextRelease = true;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!swallowNextRelease) return;
        if (event.getButton() != 0) return;
        swallowNextRelease = false;
        event.setCanceled(true);
    }

    // ---------------------------------------------------------------- craftables mode

    @SubscribeEvent
    public static void onScreenChange(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof CraftingScreen)) {
            disableCraftables();
        } else {
            // Delay the first scan a few ticks so the new screen gets a clean
            // first render. The scan is cheap with the cache, but
            // removeIngredientsAtRuntime still costs a bit on large packs.
            evalCooldown = 5;
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) {
            if (craftablesActive) disableCraftables();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        boolean craftingOpen = mc.screen instanceof CraftingScreen;
        if (!craftingOpen) {
            if (craftablesActive) disableCraftables();
            return;
        }

        IJeiRuntime rt = FabricateJeiPlugin.getRuntime();
        if (rt == null) return;

        String filter;
        try { filter = rt.getIngredientFilter().getFilterText(); }
        catch (Throwable t) { return; }

        boolean filterEmpty = filter == null || filter.isEmpty();
        if (!filterEmpty) {
            if (craftablesActive) disableCraftables();
            return;
        }

        // Poll at most ~2x per second; skip entirely if materials haven't changed.
        if (evalCooldown-- > 0) return;
        evalCooldown = 10;

        // countMaterials walks the full inventory + cursor + open slots; do it
        // once and feed the same map into both the change-detection hash and
        // the craftable-set computation.
        Map<Item, Integer> materials = countMaterials(mc.player);
        long hash = materialsHash(materials);
        if (hash == lastMaterialsHash) return;
        lastMaterialsHash = hash;

        Set<Item> craftable = computeCraftableItems(mc, materials);
        if (craftable.equals(lastCraftableSet)) return;
        lastCraftableSet = craftable;

        if (craftablesActive) restoreHidden(rt);
        applyCraftables(rt, craftable);
    }

    private static void applyCraftables(IJeiRuntime rt, Set<Item> craftable) {
        if (craftable.isEmpty()) return;

        IIngredientManager im = rt.getIngredientManager();
        List<ItemStack> toHide = new ArrayList<>();
        try {
            for (ItemStack stack : im.getAllIngredients(VanillaTypes.ITEM_STACK)) {
                if (!craftable.contains(stack.getItem())) toHide.add(stack);
            }
        } catch (Throwable t) {
            Fabricate.LOGGER.debug("[FAB-JEI] failed to enumerate ingredients", t);
            return;
        }
        if (toHide.isEmpty()) return;

        try {
            im.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, toHide);
            HIDDEN.clear();
            HIDDEN.addAll(toHide);
            craftablesActive = true;
        } catch (Throwable t) {
            Fabricate.LOGGER.warn("[FAB-JEI] removeIngredientsAtRuntime failed", t);
        }
    }

    private static void disableCraftables() {
        if (!craftablesActive && HIDDEN.isEmpty()) {
            lastMaterialsHash = Long.MIN_VALUE;
            lastCraftableSet = Collections.emptySet();
            return;
        }
        IJeiRuntime rt = FabricateJeiPlugin.getRuntime();
        if (rt != null) restoreHidden(rt);
        HIDDEN.clear();
        craftablesActive = false;
        lastMaterialsHash = Long.MIN_VALUE;
        lastCraftableSet = Collections.emptySet();
    }

    private static void restoreHidden(IJeiRuntime rt) {
        if (HIDDEN.isEmpty()) return;
        try {
            rt.getIngredientManager().addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, new ArrayList<>(HIDDEN));
        } catch (Throwable t) {
            Fabricate.LOGGER.debug("[FAB-JEI] addIngredientsAtRuntime failed on restore", t);
        }
        HIDDEN.clear();
        craftablesActive = false;
    }

    // ---------------------------------------------------------------- recipe cache

    /**
     * Lazily builds a flat list of every crafting recipe's
     * {@code (needed-materials map, output item)} pair, keyed by the current
     * {@link RecipeManager}. Rebuilds automatically when the underlying
     * manager is swapped (datapack reload / server join), which is cheaper to
     * detect than subscribing to {@code RecipesUpdatedEvent} here and keeps
     * the dependency surface small.
     */
    private static List<RecipeNeeds> getRecipeCache(Minecraft mc) {
        if (mc.getConnection() == null || mc.level == null) return Collections.emptyList();
        RecipeManager rm = mc.getConnection().getRecipeManager();
        if (recipeCache != null && recipeCacheSource == rm) return recipeCache;

        RegistryAccess ra = mc.level.registryAccess();
        List<RecipeNeeds> list = new ArrayList<>();
        for (Recipe<?> r : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack out = r.getResultItem(ra);
            if (out.isEmpty()) continue;

            Map<Item, Integer> needed;
            ResourceLocation id = r.getId();
            if (id != null && Fabricate.MOD_ID.equals(id.getNamespace()) && RefundRegistry.has(id)) {
                needed = RefundRegistry.getRequiredItems(id);
            } else {
                needed = aggregateIngredients(r);
            }
            if (needed.isEmpty()) continue;
            list.add(new RecipeNeeds(needed, out.getItem()));
        }
        recipeCache = list;
        recipeCacheSource = rm;
        Fabricate.LOGGER.info("[FAB-JEI] built recipe cache ({} entries)", list.size());
        return list;
    }

    private static Set<Item> computeCraftableItems(Minecraft mc, Map<Item, Integer> inv) {
        List<RecipeNeeds> cache = getRecipeCache(mc);
        if (cache.isEmpty()) return Collections.emptySet();

        Set<Item> craftable = new HashSet<>();
        for (RecipeNeeds rn : cache) {
            if (craftable.contains(rn.output())) continue; // already proven craftable
            if (hasAll(inv, rn.needed())) craftable.add(rn.output());
        }
        return craftable;
    }

    private static Map<Item, Integer> aggregateIngredients(Recipe<?> recipe) {
        Map<Item, Integer> out = new HashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            ItemStack[] items = ing.getItems();
            if (items.length == 0) continue;
            out.merge(items[0].getItem(), 1, Integer::sum);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static Map<Item, Integer> countInventory(Inventory inv) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** Inventory + cursor stack + any items sitting in the open container's slots. */
    private static Map<Item, Integer> countMaterials(Player player) {
        Map<Item, Integer> counts = countInventory(player.getInventory());
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty()) counts.merge(carried.getItem(), carried.getCount(), Integer::sum);
        for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
            if (slot.container == player.getInventory()) continue; // already counted
            ItemStack s = slot.getItem();
            if (!s.isEmpty()) counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return counts;
    }

    /**
     * A cheap hash of the player's material pool. Stable across transient
     * cursor-pickup reshuffles (since we include the cursor and open slots)
     * and changes only when the actual counts change.
     */
    private static long materialsHash(Map<Item, Integer> counts) {
        long h = 0L;
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            long k = System.identityHashCode(e.getKey()) * 1099511628211L ^ e.getValue();
            h ^= k + 0x9E3779B97F4A7C15L;
        }
        return h;
    }

    private static boolean hasAll(Map<Item, Integer> have, Map<Item, Integer> need) {
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            if (have.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

}
