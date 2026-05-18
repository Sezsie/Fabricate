package com.sabbs.fabricate.mixin;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.integration.emi.CraftIntent;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.network.PlannerCraftPacket;
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

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks EMI's click dispatcher to intercept craft-keybind activations on any
 * hovered item stack and route them through the new planner pipeline instead
 * of EMI's grid-fill logic.
 *
 * <p>The client no longer picks a recipe; it just says "I want X" via
 * {@link PlannerCraftPacket} and the server-side planner decides which
 * recipe path to use based on the player's actual inventory.
 *
 * <p>Why a mixin: EMI's {@code MouseHandler.onMouseButton} dispatches to
 * {@code EmiScreenManager.mouseClicked} BEFORE the vanilla
 * {@code Screen.mouseClicked} path, so Forge's {@code ScreenEvent} hooks
 * can't see or cancel these clicks. Injecting HEAD here lets us claim the
 * click before EMI's grid-fill ever runs.
 *
 * <p>Non-craft clicks and clicks on items the planner can't produce fall
 * through to EMI unchanged (the server silently no-ops on unreachable
 * targets).
 */
@Mixin(value = EmiScreenManager.class, remap = false)
public abstract class EmiScreenManagerMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private static void fabricate$mouseClicked(double mx, double my, int button,
                                                   CallbackInfoReturnable<Boolean> cir) {
        if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) return;

        CraftIntent intent = detectIntent(button);
        if (intent == null) return;

        Item hovered = resolveHoveredItem();
        if (hovered == null) return;

        // Translate EMI intent into (qty, toCursor) for the planner packet.
        // CRAFT_ALL variants → up to a full stack; CRAFT_ONE → 1.
        // toCursor only when a container screen is actually open (otherwise
        // the cursor stack has nowhere to display and gets dropped on close).
        boolean hasContainerScreen = Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>;
        int qty;
        boolean toCursor;
        if (intent.craftAll) {
            qty = Math.max(1, hovered.getDefaultInstance().getMaxStackSize());
            toCursor = false;
        } else {
            qty = 1;
            toCursor = intent.toCursor && hasContainerScreen;
        }

        Fabricate.LOGGER.debug("[FAB-EMI] click {} -> PlannerCraftPacket({}x {}, toCursor={})",
            intent, qty, hovered, toCursor);
        NetworkHandler.sendToServer(new PlannerCraftPacket(hovered, qty, toCursor));
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
}
