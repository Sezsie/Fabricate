package com.sabbs.fabricate.integration.jei;

import com.sabbs.fabricate.Fabricate;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.CraftingRecipe;

import java.util.ArrayList;
import java.util.List;

/**
 * JEI plugin. With the on-demand planner architecture there's no
 * recipe-handler / transfer-handler work to do here - sidebar clicks are
 * intercepted by {@link JeiSidebarHandler} and dispatched directly as
 * {@link com.sabbs.fabricate.network.PlannerCraftPacket}s.
 *
 * <p>The only runtime work is sweeping up legacy Fabricate / TruePolymorph
 * synthetic recipes that may linger in a save from a pre-rewrite version of
 * the mod. Suppresses itself entirely when EMI is present.
 */
@JeiPlugin
public class FabricateJeiPlugin implements IModPlugin {

    private static volatile IJeiRuntime runtime;

    public static IJeiRuntime getRuntime() {
        return runtime;
    }

    private static boolean suppressed() {
        return net.minecraftforge.fml.ModList.get().isLoaded("emi");
    }

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(Fabricate.MOD_ID, "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        if (suppressed()) return;
        runtime = jeiRuntime;
        applyRuntimeFilters(jeiRuntime);
    }

    @Override
    public void onRuntimeUnavailable() {
        if (suppressed()) return;
        runtime = null;
    }

    private static void applyRuntimeFilters(IJeiRuntime jeiRuntime) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        var rm = mc.getConnection().getRecipeManager();

        List<CraftingRecipe> toHide = new ArrayList<>();
        for (CraftingRecipe r : rm.getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING)) {
            ResourceLocation id = r.getId();
            if (id == null) continue;
            String ns = id.getNamespace();
            if (com.sabbs.fabricate.ClientEvents.LEGACY_NAMESPACE.equals(ns)
                || Fabricate.MOD_ID.equals(ns)) {
                toHide.add(r);
            }
        }
        if (toHide.isEmpty()) return;
        try {
            jeiRuntime.getRecipeManager().hideRecipes(RecipeTypes.CRAFTING, toHide);
            Fabricate.LOGGER.info("[FAB-JEI] hid {} legacy synthetic recipes", toHide.size());
        } catch (Throwable t) {
            Fabricate.LOGGER.warn("[FAB-JEI] hideRecipes failed: {}", t.toString());
        }
    }
}
