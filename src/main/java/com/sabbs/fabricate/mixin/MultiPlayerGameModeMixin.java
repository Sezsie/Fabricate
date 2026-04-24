package com.sabbs.fabricate.mixin;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.network.CraftPacket;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.recipe.CraftabilityCheck;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catches every grid-fill path for a Fabricate recipe  vanilla recipe book,
 * Polymorph's selector, JEI's transfer handler, EMI's clientFill  and reroutes
 * to our CraftPacket. Without this, second-press of EMI's craft-all keybind
 * leaks through to the vanilla recipe-book pipeline, which fills the crafting
 * grid and lets Polymorph pick alphabetically (e.g. iron_door request → anvil).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
    private void fabricate$intercept(int containerId, Recipe<?> recipe, boolean shiftDown, CallbackInfo ci) {
        ResourceLocation id = recipe == null ? null : recipe.getId();
        if (id == null || !Fabricate.MOD_ID.equals(id.getNamespace())) return;
        if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) return;

        int max = CraftabilityCheck.computeMaxBatches(id);
        int batches = shiftDown ? max : Math.min(1, max);
        if (batches > 0) {
            for (int i = 0; i < batches; i++) {
                NetworkHandler.sendToServer(new CraftPacket(id));
            }
        }
        ci.cancel();
    }
}
