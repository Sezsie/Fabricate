package com.sabbs.fabricate.mixin;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.network.CraftPacket;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.recipe.CraftabilityCheck;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.registry.EmiRecipeFiller;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts EMI's recipe-fill path so any Fabricate recipe  regardless
 * of which menu is open  bypasses EMI's grid-fill logic (which would let
 * Polymorph pick the alphabetically-first matching recipe) and crafts via our
 * CraftPacket instead.
 *
 * <p>This is the only entry point that catches EMI's craft-to-cursor keybind
 * on the standalone RecipeScreen when no {@code MenuType.CRAFTING} container
 * is open (e.g. clicking from the inventory screen).
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
        ResourceLocation id = recipe == null ? null : recipe.getId();
        if (id == null || !Fabricate.MOD_ID.equals(id.getNamespace())) return;
        Fabricate.LOGGER.info("[FAB-EMI] performFill intercept  id={} type={} dest={} amount={}", id, type, dest, amount);
        if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) {
            Fabricate.LOGGER.info("[FAB-EMI] CLIENT_ENABLED=false  returning handled(false)");
            cir.setReturnValue(false);
            return;
        }

        if (!CraftabilityCheck.playerHasMaterials(id)) {
            Fabricate.LOGGER.info("[FAB-EMI] performFill  missing materials for {}", id);
            cir.setReturnValue(false);
            return;
        }

        int maxByMaterials = CraftabilityCheck.computeMaxBatches(id);
        int requested = Math.max(1, amount);
        int batches = Math.min(requested, maxByMaterials);
        if (batches <= 0) {
            cir.setReturnValue(false);
            return;
        }

        // Cursor delivery only makes sense when a real container screen hosts
        // the click; otherwise the resulting cursor stack has nowhere to
        // display and vanilla eventually drops it on screen close.
        boolean hasContainerScreen = screen != null;
        boolean toCursor = dest == EmiCraftContext.Destination.CURSOR && batches == 1 && hasContainerScreen;
        Fabricate.LOGGER.info("[FAB-EMI] performFill dispatching {} CraftPacket(s) for {} (toCursor={})", batches, id, toCursor);
        for (int i = 0; i < batches; i++) {
            NetworkHandler.sendToServer(new CraftPacket(id, toCursor));
        }
        cir.setReturnValue(true);
    }
}
