package com.sabbs.fabricate.mixin;

import com.sabbs.fabricate.recipe.FabricateRecipe;
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
 * Server-side filter that prevents Fabricate synthetics from ever resolving
 * into the crafting grid's output slot. Synthetics are an EMI/JEI feature -
 * the click-to-craft path dispatches by recipe id directly and never touches
 * {@code slotChangedCraftingGrid} - so the manual grid path should only ever
 * show vanilla/datapack recipes.
 *
 * <p>If the first match is a synthetic, we look for a non-synthetic that
 * also matches the same grid and use that. If no non-synthetic matches, we
 * blank the slot. This keeps synthetics out of the output slot entirely,
 * which:
 * <ul>
 *   <li>fixes the post-craft flicker - vanilla decrements grid slots one at
 *       a time, and each transient state used to match a different
 *       synthetic with a different output item, producing rapid-fire output
 *       updates as the server ran the recipe lookup per slot change;</li>
 *   <li>kills the "iron-door pattern resolves to a synthetic anvil"
 *       category of bugs at the source rather than via Polymorph
 *       arbitration;</li>
 *   <li>removes the per-player branch on opt-out state for this code path -
 *       behavior is now uniform.</li>
 * </ul>
 *
 * <p>Hot path (vanilla first hit, or empty grid) is one instanceof check.
 * The full-scan fallback only runs when the first match was a synthetic.
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

        // First match was a synthetic. Scan for a non-synthetic that also
        // matches and prefer it. If none exists, blank the slot - synthetics
        // are EMI/JEI-only and never appear in the grid output.
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
