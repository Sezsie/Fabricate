package com.sabbs.fabricate.integration.emi;

import com.sabbs.fabricate.Fabricate;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import net.minecraft.resources.ResourceLocation;

/**
 * EMI integration. Registered by EMI via {@code @EmiEntrypoint}.
 *
 * <p>With the on-demand planner architecture there are no synthetic recipes
 * to register; EMI sees the unmodified vanilla recipe list. Sidebar clicks
 * are intercepted by {@link com.sabbs.fabricate.mixin.EmiScreenManagerMixin}
 * and recipe-view "+" clicks by
 * {@link com.sabbs.fabricate.mixin.EmiRecipeFillerMixin}, both of which
 * dispatch a {@link com.sabbs.fabricate.network.PlannerCraftPacket} to the
 * server.
 *
 * <p>The only work this plugin still does is sweep up legacy entries that
 * may linger in a save from a pre-rewrite version of the mod.
 */
@EmiEntrypoint
public class FabricateEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        // Legacy cleanup: nuke any leftover Fabricate/TruePolymorph synthetic
        // recipes that may have been injected by a pre-rewrite version of the
        // mod. No-op on a fresh world.
        registry.removeRecipes(r -> {
            ResourceLocation id = r.getId();
            if (id == null) return false;
            return Fabricate.MOD_ID.equals(id.getNamespace())
                || com.sabbs.fabricate.ClientEvents.LEGACY_NAMESPACE.equals(id.getNamespace());
        });
    }
}
