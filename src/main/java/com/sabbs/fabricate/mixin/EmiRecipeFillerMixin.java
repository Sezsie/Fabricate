package com.sabbs.fabricate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.integration.emi.EmiCraftThrottle;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.network.PlannerCraftPacket;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.registry.EmiRecipeFiller;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Intercepts EMI's recipe-fill path, the "+" button on a recipe view, and
 * routes it through the planner instead of EMI's grid-fill logic.
 *
 * <p>The recipe's output item is dispatched as a {@link PlannerCraftPacket};
 * the server picks the actual recipe path based on the player's inventory.
 * Fires for every recipe, not just Fabricate ones, so the planner is the
 * single source of truth for "click -> craft" behavior.
 *
 * <p>Client-side throttling is shared with {@link EmiScreenManagerMixin} so
 * EMI's two packet-send paths cannot bypass each other.
 */
@Mixin(value = EmiRecipeFiller.class, remap = false)
public abstract class EmiRecipeFillerMixin {

    @Inject(method = "performFill", at = @At("HEAD"), cancellable = true, remap = false)
    private static <T extends AbstractContainerMenu> void fabricate$intercept(
            EmiRecipe recipe,
            AbstractContainerScreen<T> screen,
            EmiCraftContext.Type type,
            EmiCraftContext.Destination dest,
            int amount,
            CallbackInfoReturnable<Boolean> cir) {

        if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) return;
        if (recipe == null || recipe.getOutputs().isEmpty()) return;

        EmiStack outStack = recipe.getOutputs().get(0);
        ItemStack itemStack = outStack.getItemStack();
        if (itemStack == null || itemStack.isEmpty()) return;

        if (EmiCraftThrottle.isOnCooldown()) {
            Fabricate.LOGGER.debug("[FAB-EMI] throttled performFill on {} ({}ms remaining)",
                itemStack.getItem(),
                EmiCraftThrottle.remainingMs());

            cir.setReturnValue(true);
            return;
        }

        int qty = Math.max(1, amount);
        boolean toCursor = dest == EmiCraftContext.Destination.CURSOR
            && qty == 1
            && screen != null;

        Fabricate.LOGGER.debug("[FAB-EMI] performFill -> PlannerCraftPacket({}x {}, toCursor={})",
            qty, itemStack.getItem(), toCursor);

        NetworkHandler.sendToServer(new PlannerCraftPacket(itemStack.getItem(), qty, toCursor));
        EmiCraftThrottle.markAccepted();

        cir.setReturnValue(true);
    }
}