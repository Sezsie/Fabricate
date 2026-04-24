package com.sabbs.fabricate.integration.polymorph;

import com.illusivesoulworks.polymorph.api.PolymorphApi;
import com.illusivesoulworks.polymorph.api.client.widget.OutputWidget;
import com.illusivesoulworks.polymorph.api.client.widget.SelectionWidget;
import com.illusivesoulworks.polymorph.api.common.capability.IPlayerRecipeData;
import com.illusivesoulworks.polymorph.client.recipe.RecipesWidget;
import com.sabbs.fabricate.ModConfig;
import com.sabbs.fabricate.recipe.FabricateRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Comparator;
import java.util.List;

/**
 * Adds mouse-wheel scrolling to Polymorph's recipe-selection widget row, and
 * repositions the row to be centered above the crafting GUI. Needed because
 * Polymorph renders every candidate recipe in a single fixed-width bar, which
 * overflows the screen once Fabricate injects dozens of synthetics.
 *
 * <p>Registered via {@link PolymorphCompat}  Polymorph is a mandatory
 * dependency, but keeping registration explicit matches the EMI/JEI pattern
 * and lets us kill the feature via config.
 */
public final class PolymorphScrollHandler {

    private PolymorphScrollHandler() {}

    /** Size in pixels of each Polymorph OutputWidget button. */
    private static final int BUTTON_SIZE = 25;

    /** Index of the leftmost visible widget (offset into the full list). */
    private static int scrollOffset = 0;
    /** Tracks last rendered list size so we can reset scroll when it changes. */
    private static int lastWidgetCount = -1;
    /**
     * What kind of recipe we're trying to promote as the selected one. When
     * Fabricate is client-enabled, we fight to put shaped vanilla recipes
     * above synthetics; when it's disabled, we fight to keep the selected
     * recipe non-synthetic so the crafting-output slot doesn't show a
     * Fabricate synthetic that the UI is already pretending isn't there.
     */
    private enum PromotionMode {
        /** Default behavior: prefer an author-shaped recipe over any synthetic. */
        SHAPED_OVER_SYNTHETIC,
        /** Disabled-mod behavior: prefer any non-synthetic over a synthetic. */
        NON_SYNTHETIC_OVER_SYNTHETIC
    }

    /**
     * Fingerprint of the widget-id set last time we decided "auto-select is
     * done for this list". When the grid changes, Polymorph rebuilds the
     * candidate list and this fingerprint changes  that's our cue to start
     * promoting the preferred recipe again for the new grid state.
     */
    private static int lastResolvedFingerprint = 0;
    /** Tracks whether we've confirmed the cap's selected recipe matches the active mode for the current fingerprint. */
    private static boolean promotionConfirmed = false;
    /** Mode used the last time we fingerprinted, so mode flips (config toggle) reset the latch. */
    private static PromotionMode lastMode = PromotionMode.SHAPED_OVER_SYNTHETIC;
    /** System-clock timestamp of the last selectRecipe packet we sent (for retry debounce). */
    private static long lastFireMs = 0L;
    /**
     * Fingerprint state for the per-frame visual sort/hide pass. Distinct from
     * {@link #lastResolvedFingerprint} (promotion latch) because the two run
     * under different conditions: the visual pass also resets on mode flips
     * but doesn't care about the cap's selection state.
     */
    private static int lastVisualFingerprint = 0;
    private static PromotionMode lastVisualMode = PromotionMode.SHAPED_OVER_SYNTHETIC;
    private static boolean visualApplied = false;

    @SubscribeEvent
    public static void onMouseScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!ModConfig.CLIENT_ENABLED.get()) return;
        if (!ModConfig.ENABLE_SCROLL.get()) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?>)) return;

        RecipesWidget.get().ifPresent(recipesWidget -> {
            SelectionWidget selectionWidget = recipesWidget.getSelectionWidget();
            if (selectionWidget == null || !selectionWidget.isActive()) return;

            List<OutputWidget> widgets = selectionWidget.getOutputWidgets();
            if (widgets.isEmpty()) return;

            int maxVisible = ModConfig.MAX_VISIBLE_RECIPES.get();
            if (maxVisible <= 0 || widgets.size() <= maxVisible) return;

            int step = ModConfig.SCROLL_STEP.get();
            int maxOffset = widgets.size() - maxVisible;
            double scrollDelta = event.getScrollDelta();

            if (scrollDelta < 0) {
                scrollOffset = Math.max(0, scrollOffset - step);
            } else if (scrollDelta > 0) {
                scrollOffset = Math.min(maxOffset, scrollOffset + step);
            }

            event.setCanceled(true);
        });
    }

    /**
     * Run after Polymorph renders so our positions win. Hides off-screen
     * widgets by toggling their {@code visible} flag rather than removing
     * them  Polymorph still needs them in the list for click handling.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> containerScreen)) return;

        RecipesWidget.get().ifPresent(recipesWidget -> {
            SelectionWidget selectionWidget = recipesWidget.getSelectionWidget();
            if (selectionWidget == null) return;

            List<OutputWidget> widgets = selectionWidget.getOutputWidgets();
            if (widgets.isEmpty()) return;

            boolean clientEnabled = ModConfig.CLIENT_ENABLED.get();
            boolean scrollEnabled = clientEnabled && ModConfig.ENABLE_SCROLL.get();

            PromotionMode mode = clientEnabled
                ? PromotionMode.SHAPED_OVER_SYNTHETIC
                : PromotionMode.NON_SYNTHETIC_OVER_SYNTHETIC;

            int fingerprint = fingerprint(widgets);

            // Visual housekeeping. Two distinct visuals:
            //   1. Fully disabled (or scroll off): hide synthetics, recenter
            //      the real recipes. Matches the "mod is off" expectation.
            //   2. Enabled: shaped recipes bubble to the front of the row so
            //      they're the visible default among the candidates.
            //
            // Gated behind a fingerprint+mode latch: the sort comparator does
            // a RecipeManager.byKey lookup per compare, and hide/recenter does
            // one per widget. When the candidate list is stable (the common
            // per-frame case) both are pure no-ops to repeat.
            boolean visualDirty = fingerprint != lastVisualFingerprint
                || mode != lastVisualMode
                || !visualApplied;
            if (visualDirty) {
                if (!scrollEnabled) {
                    hideSyntheticsAndRecenter(widgets, containerScreen);
                } else {
                    widgets.sort(Comparator.comparingInt(w ->
                        isAcceptable(w.getResourceLocation(), PromotionMode.SHAPED_OVER_SYNTHETIC) ? 0 : 1));
                }
                lastVisualFingerprint = fingerprint;
                lastVisualMode = mode;
                visualApplied = true;
            }

            // Auto-select runs in BOTH modes:
            //   - enabled: promote the first vanilla shaped recipe so the
            //     output slot reflects the author-intended result.
            //   - disabled: promote any non-synthetic so the output slot
            //     doesn't hand out a synthetic's output while the UI is
            //     pretending the mod is off. Hiding the widget alone isn't
            //     enough  the server's selected recipe drives the slot.
            //
            // We deliberately don't gate on selectionWidget.isActive(); the
            // selected recipe drives the output slot regardless of whether
            // the player has the selection row visibly expanded.
            // Mode flips (config toggled live) and grid-change fingerprint
            // flips both reset the confirmation latch so we re-arbitrate.
            if (fingerprint != lastResolvedFingerprint || mode != lastMode) {
                lastResolvedFingerprint = fingerprint;
                lastMode = mode;
                promotionConfirmed = false;
            }

            if (!promotionConfirmed) {
                // Poll the cap every frame until it reports an acceptable
                // selection  then latch. Self-heals against dropped/out-of-
                // order packets and Polymorph's "preserve previous selection"
                // path on grid changes.
                ResourceLocation currentSelected = getSelectedRecipeId();
                if (currentSelected != null && isAcceptable(currentSelected, mode)) {
                    promotionConfirmed = true;
                } else {
                    // Debounce at 150 ms  the server answers within a tick
                    // or two; anything faster is just wire noise. The grid
                    // scan (findTargetForGrid → RecipeManager.getRecipesFor)
                    // iterates every crafting recipe and re-runs matches(),
                    // so gate it behind the debounce window.
                    long now = System.currentTimeMillis();
                    if (now - lastFireMs >= 150L) {
                        // Use the widget list first  Polymorph has already
                        // filtered it against the current grid via its own
                        // getRecipesFor call, so iterating widgets costs
                        // O(widgets) × O(1) byKey instead of re-matching
                        // every crafting recipe in the manager. On heavy
                        // modpacks (tens of thousands of synthetics) the
                        // full-scan fallback was pinning low-end CPUs for
                        // hundreds of ms per grid edit.
                        ResourceLocation target = findTargetInWidgets(widgets, mode);
                        if (target == null) {
                            target = findTargetForGrid(containerScreen.getMenu(), mode);
                        }
                        if (target != null) {
                            lastFireMs = now;
                            recipesWidget.selectRecipe(target);
                        } else {
                            promotionConfirmed = true;
                        }
                    }
                }
            }

            // --- Scroll repositioning only runs when the row overflows ---
            if (!scrollEnabled) return;
            int maxVisible = ModConfig.MAX_VISIBLE_RECIPES.get();
            if (maxVisible <= 0 || widgets.size() <= maxVisible) return;

            // Reset scroll when the candidate list changes (opening a different
            // recipe would otherwise leave us scrolled past the new end).
            if (widgets.size() != lastWidgetCount) {
                scrollOffset = 0;
                lastWidgetCount = widgets.size();
            }
            int maxOffset = widgets.size() - maxVisible;
            scrollOffset = Math.min(scrollOffset, maxOffset);

            int guiLeft = containerScreen.getGuiLeft();
            int guiTop = containerScreen.getGuiTop();
            int guiWidth = containerScreen.getXSize();

            int visibleCount = Math.min(maxVisible, widgets.size());
            int totalWidth = visibleCount * BUTTON_SIZE;
            int startX = guiLeft + (guiWidth - totalWidth) / 2;
            int buttonY = guiTop - BUTTON_SIZE - 2; // 2px gap above the GUI

            for (int i = 0; i < widgets.size(); i++) {
                OutputWidget widget = widgets.get(i);
                int relativeIndex = i - scrollOffset;
                boolean onScreen = relativeIndex >= 0 && relativeIndex < maxVisible;
                if (onScreen) {
                    widget.setPosition(startX + relativeIndex * BUTTON_SIZE, buttonY);
                }
                widget.visible = onScreen;
            }
        });
    }

    @SubscribeEvent
    public static void onScreenClose(ScreenEvent.Closing event) {
        scrollOffset = 0;
        lastWidgetCount = -1;
        lastResolvedFingerprint = 0;
        promotionConfirmed = false;
        lastFireMs = 0L;
        lastVisualFingerprint = 0;
        visualApplied = false;
    }

    /**
     * Reads the cap's current server-synced selection for the local player.
     * The client cap is updated by {@code SPacketPlayerRecipeSync}, so this
     * reflects what the server-side {@code IRecipeData.setSelectedRecipe}
     * committed on the last round-trip.
     */
    private static ResourceLocation getSelectedRecipeId() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return null;
        return PolymorphApi.common().getRecipeData(player)
            .flatMap(IPlayerRecipeData::getSelectedRecipe)
            .map(Recipe::getId)
            .orElse(null);
    }

    /**
     * Order-independent hash of the widget-id set. Changes whenever Polymorph
     * swaps in a different candidate list, but stays stable across our own
     * in-place sort so we don't re-trigger auto-select every frame.
     */
    private static int fingerprint(List<OutputWidget> widgets) {
        int h = 0;
        for (OutputWidget w : widgets) {
            ResourceLocation id = w.getResourceLocation();
            h += (id == null ? 0 : id.hashCode());
        }
        return h;
    }

    /**
     * Hides every widget backed by a Fabricate synthetic recipe and
     * re-centers the remaining real recipes in a single row above the GUI.
     * Used when the mod is client-disabled (or scroll is turned off) so the
     * injected synthetics don't visually leak into Polymorph's panel.
     */
    private static void hideSyntheticsAndRecenter(List<OutputWidget> widgets,
                                                  AbstractContainerScreen<?> containerScreen) {
        int visibleCount = 0;
        for (OutputWidget w : widgets) {
            boolean synthetic = isSyntheticRecipe(w);
            w.visible = !synthetic;
            if (!synthetic) visibleCount++;
        }
        if (visibleCount == 0) return;

        int guiLeft = containerScreen.getGuiLeft();
        int guiTop = containerScreen.getGuiTop();
        int guiWidth = containerScreen.getXSize();
        int totalWidth = visibleCount * BUTTON_SIZE;
        int startX = guiLeft + (guiWidth - totalWidth) / 2;
        int buttonY = guiTop - BUTTON_SIZE - 2;

        int i = 0;
        for (OutputWidget w : widgets) {
            if (!w.visible) continue;
            w.setPosition(startX + i * BUTTON_SIZE, buttonY);
            i++;
        }
    }

    /** True when the widget's recipe is a {@link FabricateRecipe} synthetic. */
    private static boolean isSyntheticRecipe(OutputWidget widget) {
        ResourceLocation id = widget.getResourceLocation();
        if (id == null) return false;
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return false;
        return connection.getRecipeManager().byKey(id)
            .map(r -> r instanceof FabricateRecipe).orElse(false);
    }

    /**
     * Fast-path: pick the alphabetically-lowest acceptable id directly from
     * Polymorph's widget list. Polymorph populated this list from a
     * {@code RecipeManager.getRecipesFor} call on the last grid update, so
     * every widget is by construction a recipe that matches the grid right
     * now. Iterating widgets + O(1) byKey lookups is dramatically cheaper
     * than re-matching every crafting recipe ourselves.
     *
     * <p>The fallback {@link #findTargetForGrid} is used when this returns
     * {@code null}. It can happen during rapid edits where Polymorph's list
     * lags the true grid state by a tick or two.
     */
    private static ResourceLocation findTargetInWidgets(List<OutputWidget> widgets, PromotionMode mode) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return null;
        RecipeManager rm = connection.getRecipeManager();
        ResourceLocation best = null;
        for (OutputWidget w : widgets) {
            ResourceLocation id = w.getResourceLocation();
            if (id == null) continue;
            Recipe<?> r = rm.byKey(id).orElse(null);
            if (r == null || !acceptsRecipe(r, mode)) continue;
            if (best == null || id.compareTo(best) < 0) best = id;
        }
        return best;
    }

    /**
     * Fallback: asks the client's RecipeManager which recipes actually match
     * the open menu's crafting grid right now, filters by the active
     * {@link PromotionMode}, and returns the alphabetically-lowest id  or
     * {@code null} if nothing acceptable matches.
     *
     * <p>Only called when {@link #findTargetInWidgets} came up empty, which
     * handles the narrow window where Polymorph's widget list hasn't caught
     * up with the live grid yet.
     */
    private static ResourceLocation findTargetForGrid(AbstractContainerMenu menu, PromotionMode mode) {
        CraftingContainer container = null;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof CraftingContainer cc) {
                container = cc;
                break;
            }
        }
        if (container == null) return null;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientPacketListener conn = mc.getConnection();
        if (player == null || conn == null) return null;
        RecipeManager rm = conn.getRecipeManager();
        List<CraftingRecipe> matches = rm.getRecipesFor(RecipeType.CRAFTING, container, player.level());
        ResourceLocation best = null;
        for (CraftingRecipe r : matches) {
            if (!acceptsRecipe(r, mode)) continue;
            ResourceLocation id = r.getId();
            if (best == null || id.compareTo(best) < 0) best = id;
        }
        return best;
    }

    /** True when {@code r} is an acceptable promotion target under {@code mode}. */
    private static boolean acceptsRecipe(Recipe<?> r, PromotionMode mode) {
        return switch (mode) {
            case SHAPED_OVER_SYNTHETIC -> r instanceof ShapedRecipe;
            case NON_SYNTHETIC_OVER_SYNTHETIC -> !(r instanceof FabricateRecipe);
        };
    }

    /** True when the recipe at {@code id} satisfies the latch condition for {@code mode}. */
    private static boolean isAcceptable(ResourceLocation id, PromotionMode mode) {
        if (id == null) return false;
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return false;
        return connection.getRecipeManager().byKey(id)
            .map(r -> acceptsRecipe(r, mode))
            .orElse(false);
    }

}
