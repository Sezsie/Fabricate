package com.sabbs.fabricate.mixin;

import com.sabbs.fabricate.OptOutRegistry;
import com.sabbs.fabricate.recipe.FabricateRecipe;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Optional;

/**
 * Server-side filter that hides Fabricate synthetics from the crafting
 * output slot for players who have opted out client-side (CLIENT_ENABLED=false).
 *
 * <p>Without this, the server's shared RecipeManager would happily match a
 * FabricateRecipe for an opted-out player's grid contents and display the
 * synthetic output. From the player's perspective the "disabled" mod would
 * still be actively transforming their crafting attempts.
 *
 * <p>We use {@link ModifyVariable} on the {@code Optional<CraftingRecipe>} local
 * rather than a {@link org.spongepowered.asm.mixin.injection.Redirect} on the
 * {@code getRecipeFor} call because Polymorph's {@code MixinCraftingMenu}
 * already owns a @Redirect on that INVOKE and Mixin only permits one redirect
 * per call site. @ModifyVariable fires on the STORE after the call, so it
 * composes with Polymorph's arbitration instead of fighting it: Polymorph
 * picks the "winning" recipe among duplicates, then we veto it if it's a
 * synthetic and the player opted out.
 *
 * <p>Happy path (opted-in player or a vanilla match on first try) adds one
 * registry lookup + one instanceof check. The fallback
 * rescan only runs when a synthetic was the first match for an opted-out
 * player, which is rare and already off the hot path.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {

    @ModifyVariable(
        method = "slotChangedCraftingGrid",
        at = @At("STORE"),
        ordinal = 0
    )
    private static Optional<CraftingRecipe> fabricate$filterOptOut(
            Optional<CraftingRecipe> match,
            AbstractContainerMenu menu,
            Level level,
            Player player,
            CraftingContainer container,
            ResultContainer resultContainer) {

        if (match.isEmpty()) return match;
        if (!(player instanceof ServerPlayer sp)) return match;
        if (!OptOutRegistry.isOptedOut(sp.getUUID())) return match;
        if (!(match.get() instanceof FabricateRecipe)) return match;

        // First match was a Fabricate synthetic for an opted-out player. Scan
        // all crafting recipes for the first non-synthetic that matches.
        var rm = level.getServer() == null ? null : level.getServer().getRecipeManager();
        if (rm == null) return Optional.empty();
        for (CraftingRecipe r : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (r instanceof FabricateRecipe) continue;
            if (r.matches(container, level)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
