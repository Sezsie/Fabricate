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
 * Server-side filter that demotes Fabricate synthetics from the crafting
 * output slot whenever any non-synthetic crafting recipe matches the same
 * grid. The synthetic is still craftable via EMI/JEI click-to-craft (which
 * dispatches by recipe id and bypasses grid resolution); manual grid layouts
 * always resolve to the vanilla/datapack recipe when one exists.
 *
 * <p>Without this, the server's RecipeManager returns the first match it
 * finds in registry order. Synthetics in the {@code fabricate:} namespace
 * frequently match grids that vanilla shaped recipes also satisfy (a
 * shapeless N-of-X synthetic can co-match an iron-door pattern, for
 * example), and the player would see the synthetic's output pop up in the
 * slot instead of the author-intended vanilla result.
 *
 * <p>Opted-out players get a stricter rule: any synthetic match is replaced
 * with {@code Optional.empty()} if no non-synthetic exists, so the slot stays
 * empty rather than handing them a synthetic they've explicitly disabled.
 *
 * <p>Hot path (vanilla first hit, or empty grid) is one instanceof check.
 * The full-scan fallback only runs when the first match was a synthetic,
 * which is rare and already off the per-tick path.
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {

    @ModifyVariable(
        method = "slotChangedCraftingGrid",
        at = @At("STORE"),
        ordinal = 0
    )
    private static Optional<CraftingRecipe> fabricate$demoteSynthetics(
            Optional<CraftingRecipe> match,
            AbstractContainerMenu menu,
            Level level,
            Player player,
            CraftingContainer container,
            ResultContainer resultContainer) {

        if (match.isEmpty()) return match;
        if (!(match.get() instanceof FabricateRecipe)) return match;

        boolean optedOut = (player instanceof ServerPlayer sp)
            && OptOutRegistry.isOptedOut(sp.getUUID());

        // First match was a synthetic. Scan for a non-synthetic that also
        // matches and prefer it. If none exists and the player is opted in,
        // keep the synthetic; if opted out, blank the slot.
        var rm = level.getServer() == null ? null : level.getServer().getRecipeManager();
        if (rm == null) return optedOut ? Optional.empty() : match;
        for (CraftingRecipe r : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
            if (r instanceof FabricateRecipe) continue;
            if (r.matches(container, level)) {
                return Optional.of(r);
            }
        }
        return optedOut ? Optional.empty() : match;
    }
}
