package com.sabbs.fabricate.mixin;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.integration.emi.CraftIntent;
import com.sabbs.fabricate.network.CraftPacket;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.recipe.CraftabilityCheck;
import com.sabbs.fabricate.recipe.RecipeSelector;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.input.EmiBind;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks EMI's own click dispatcher to intercept craft keybind activations on
 * any hovered item stack. Instead of letting EMI fill the vanilla crafting
 * grid (which triggers Polymorph's alphabetical-conflict resolution), we
 * scan every recipe  vanilla and Fabricate synthetic  that produces
 * the hovered item, pick the first one the player has materials for, and
 * craft it server-side via {@link CraftPacket}.
 *
 * <p>Why here: EMI installs a mixin on {@code MouseHandler.onMouseButton} that
 * dispatches to {@code EmiScreenManager.mouseClicked} BEFORE the vanilla
 * {@code Screen.mouseClicked} path, so Forge's {@code ScreenEvent} hooks can't
 * see or cancel these clicks. Injecting HEAD here and returning false tells
 * EMI nothing was handled  no fill, no recipe-tree navigation.
 *
 * <p>Non-craft clicks and clicks where no recipe can produce the hovered item
 * fall through to EMI unchanged.
 */
@Mixin(value = EmiScreenManager.class, remap = false)
public abstract class EmiScreenManagerMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fabricate$mouseClicked(double mx, double my, int button,
                                                   CallbackInfoReturnable<Boolean> cir) {
        CraftIntent intent = detectIntent(button);
        if (intent == null) return;

        Item hovered = resolveHoveredItem();
        if (hovered == null) return;
        Fabricate.LOGGER.info("[FAB-EMI] click intercepted  button={} intent={} item={}", button, intent, hovered);

        Recipe<?> recipe = findFirstCraftable(hovered);
        if (recipe == null) {
            Fabricate.LOGGER.info("[FAB-EMI] no craftable recipe for {}  passing through", hovered);
            return;
        }
        Fabricate.LOGGER.info("[FAB-EMI] resolved recipe {} for {}", recipe.getId(), hovered);

        int batches = intent.craftAll
                ? CraftabilityCheck.maxBatches(recipe)
                : (CraftabilityCheck.playerCanCraft(recipe) ? 1 : 0);

        if (batches <= 0) {
            Fabricate.LOGGER.info("[FAB-EMI] insufficient materials for {}  aborting", recipe.getId());
            cir.setReturnValue(false);
            return;
        }

        // Only single-batch crafts can meaningfully land on the cursor, and only
        // while a real container screen is open  otherwise the cursor stack
        // has no visible home and vanilla drops it on screen close.
        boolean hasContainerScreen = Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>;
        boolean toCursor = intent.toCursor && !intent.craftAll && batches == 1 && hasContainerScreen;
        Fabricate.LOGGER.info("[FAB-EMI] dispatching {} CraftPacket(s) for {} (toCursor={})", batches, recipe.getId(), toCursor);
        for (int i = 0; i < batches; i++) {
            NetworkHandler.sendToServer(new CraftPacket(recipe.getId(), toCursor));
        }
        cir.setReturnValue(false);
    }

    /** Mirrors EMI's priority: modified binds before plain binds. */
    private static CraftIntent detectIntent(int button) {
        if (matches(EmiConfig.craftAllToInventory, button)) return CraftIntent.CRAFT_ALL_TO_INVENTORY;
        if (matches(EmiConfig.craftOneToInventory, button)) return CraftIntent.CRAFT_ONE_TO_INVENTORY;
        if (matches(EmiConfig.craftOneToCursor, button))    return CraftIntent.CRAFT_ONE_TO_CURSOR;
        if (matches(EmiConfig.craftAll, button))            return CraftIntent.CRAFT_ALL;
        if (matches(EmiConfig.craftOne, button))            return CraftIntent.CRAFT_ONE;
        return null;
    }

    private static boolean matches(EmiBind bind, int button) {
        try { return bind != null && bind.matchesMouse(button); }
        catch (Throwable t) { return false; }
    }

    /** Item under the cursor, or {@code null} if EMI reports nothing hovered. */
    private static Item resolveHoveredItem() {
        try {
            EmiStackInteraction si = EmiApi.getHoveredStack(false);
            if (si == null || si.isEmpty()) return null;
            for (EmiStack es : si.getStack().getEmiStacks()) {
                ItemStack s = es.getItemStack();
                if (s != null && !s.isEmpty()) return s.getItem();
            }
        } catch (Throwable t) {
            Fabricate.LOGGER.debug("[FAB-EMI] resolveHoveredItem failed", t);
        }
        return null;
    }

    /**
     * Picks the best craftable recipe for {@code target}  prefers minimal
     * substitution from vanilla, then lowest total material. See
     * {@link RecipeSelector#pickBest}.
     */
    private static Recipe<?> findFirstCraftable(Item target) {
        return RecipeSelector.pickBest(target);
    }
}
