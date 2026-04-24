package com.sabbs.fabricate.mixin;

import com.illusivesoulworks.polymorph.api.common.capability.IRecipeData;
import com.illusivesoulworks.polymorph.common.crafting.RecipeSelection;
import com.sabbs.fabricate.Fabricate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

/**
 * Filters fabricate: synthetics out of Polymorph's candidate list when
 * any non-FAB recipe also matches the same grid. Our shapeless N-of-X synthetics
 * often co-match grids that vanilla shaped recipes already handle (e.g. 6
 * iron_ingots in iron_door pattern triggers our iron_ingotx31_to_anvil because
 * 192 ≥ 31); Polymorph's item-ID comparator would then pick anvil over
 * iron_door alphabetically. Deferring to non-FAB whenever present is the right
 * default  the player can still craft our synthetics directly via EMI, where
 * our EmiRecipeFillerMixin intercepts by recipe id and bypasses this path.
 */
@Mixin(value = RecipeSelection.class, remap = false)
public abstract class PolymorphSelectionMixin {

    @Inject(
        method = "getRecipe(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/Container;Lnet/minecraft/world/level/Level;Ljava/util/Optional;Ljava/util/List;)Ljava/util/Optional;",
        at = @At("HEAD"),
        remap = false
    )
    private static <T extends Recipe<C>, C extends Container> void fabricate$filter(
            RecipeType<T> type,
            C container,
            Level level,
            Optional<? extends IRecipeData<?>> recipeData,
            List<T> recipes,
            CallbackInfoReturnable<Optional<T>> cir) {
        if (recipes == null || recipes.size() < 2) return;
        boolean hasNonTp = false;
        boolean hasTp = false;
        for (T r : recipes) {
            ResourceLocation id = r.getId();
            if (id == null) continue;
            if (Fabricate.MOD_ID.equals(id.getNamespace())) hasTp = true;
            else hasNonTp = true;
            if (hasTp && hasNonTp) break;
        }
        if (hasTp && hasNonTp) {
            try {
                recipes.removeIf(r -> {
                    ResourceLocation id = r.getId();
                    return id != null && Fabricate.MOD_ID.equals(id.getNamespace());
                });
            } catch (UnsupportedOperationException ignored) {
                // Immutable list  nothing we can do; Polymorph will fall back to its comparator.
            }
        }
    }
}
