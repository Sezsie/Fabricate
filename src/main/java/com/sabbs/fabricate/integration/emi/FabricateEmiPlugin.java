package com.sabbs.fabricate.integration.emi;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.network.CraftPacket;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.recipe.RecipeSelector;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiCraftingRecipe;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMI integration. Registered by EMI itself via {@code @EmiEntrypoint}, so it
 * only loads when EMI is present.
 *
 * <p>Declares an {@link EmiRecipeHandler} for the vanilla {@link CraftingMenu}
 * so EMI treats our synthetics as first-class craftable recipes. The actual
 * click interception  including the fill-to-grid path that would otherwise
 * hand control back to Polymorph's alphabetical conflict resolver  lives in
 * {@link com.sabbs.fabricate.mixin.EmiScreenManagerMixin} and
 * {@link com.sabbs.fabricate.mixin.EmiRecipeFillerMixin}.
 */
@EmiEntrypoint
public class FabricateEmiPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addRecipeHandler(MenuType.CRAFTING, new FabricateCraftingHandler());

        // Legacy cleanup: always nuke any {@code truepolymorph:} entries that
        // might have leaked in from a stale datapack or a mid-migration server.
        // No-op on a clean install.
        registry.removeRecipes(r -> {
            ResourceLocation id = r.getId();
            return id != null && com.sabbs.fabricate.ClientEvents.LEGACY_NAMESPACE.equals(id.getNamespace());
        });

        // Opt-out: nuke every Fabricate entry (raw synthetics AND our aggregate
        // cards) from EMI's view and stop  no aggregates to build, no harvest
        // to do. Strip is unconditional on namespace so stale entries from a
        // prior session where the player was opted in can't linger.
        boolean optedOut = !com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get();
        if (optedOut) {
            Fabricate.LOGGER.info("[FAB-EMI] opt-out  removing all fabricate recipes from EMI");
            registry.removeRecipes(r -> {
                ResourceLocation id = r.getId();
                return id != null && Fabricate.MOD_ID.equals(id.getNamespace());
            });
            return;
        }

        // Collapse the full set of FAB synthetic cards into exactly one card per
        // output item, where the sole displayed ingredient is an OR-list of
        // every material any of the underlying synthetics would accept. EMI's
        // craftables filter checks each recipe's inputs against the player
        // inventory; with this aggregate shape, the iron_helmet card lights up
        // the moment the player has any iron_block / iron_ingot / iron_nugget /
        // raw_iron / …, regardless of which specific variant would actually run.
        //
        // The real recipe dispatched on click is still picked by
        // {@link com.sabbs.fabricate.mixin.EmiScreenManagerMixin}, which
        // scans the full RecipeManager  all the removed-from-EMI synthetics
        // included  and picks whichever path the inventory can truly satisfy.
        //
        // Strip every non-aggregate FAB recipe from EMI's view. The predicate
        // runs at bake time (EMI just stashes it now), so we can't use it to
        // also harvest inputs  by the time it fires, we've already finished
        // register() and our addRecipe calls are done. Hence the separate
        // RecipeManager walk below.
        //
        // The agg_* spare clause is critical: EMI applies invalidators to the
        // entire final recipe list including our own addRecipe entries, so
        // without the spare the aggregates delete themselves.
        registry.removeRecipes(r -> {
            ResourceLocation id = r.getId();
            if (id == null || !Fabricate.MOD_ID.equals(id.getNamespace())) return false;
            return !id.getPath().startsWith("/agg_");
        });

        // Harvest directly from the vanilla RecipeManager  it's already
        // populated with FAB synthetics by the time EMI reload fires
        // (RecipesUpdatedEvent → EmiReloadManager.reloadRecipes()).
        Map<Item, Set<Item>> inputsByOutput = new HashMap<>();
        var mc = Minecraft.getInstance();
        if (mc.getConnection() != null && mc.level != null) {
            var rm = mc.getConnection().getRecipeManager();
            var ra = mc.level.registryAccess();
            for (var r : rm.getRecipes()) {
                ResourceLocation id = r.getId();
                if (id == null || !Fabricate.MOD_ID.equals(id.getNamespace())) continue;
                ItemStack out = r.getResultItem(ra);
                if (out.isEmpty()) continue;
                Set<Item> alts = inputsByOutput.computeIfAbsent(out.getItem(), k -> new HashSet<>());
                for (var ing : r.getIngredients()) {
                    for (ItemStack s : ing.getItems()) {
                        if (!s.isEmpty()) alts.add(s.getItem());
                    }
                }
            }
        }

        for (var e : inputsByOutput.entrySet()) {
            Item outItem = e.getKey();
            Set<Item> altItems = e.getValue();
            if (altItems.isEmpty()) continue;

            List<EmiIngredient> alternatives = new ArrayList<>(altItems.size());
            for (Item alt : altItems) alternatives.add(EmiStack.of(alt));
            EmiIngredient aggregate = EmiIngredient.of(alternatives);

            ResourceLocation outKey = BuiltInRegistries.ITEM.getKey(outItem);
            // Namespace the aggregate id under FAB so EmiScreenManagerMixin's
            // namespace check still applies, and flatten the output's own
            // namespace into the path since ResourceLocation paths don't
            // accept colons.
            // EMI flags any non-`/`-prefixed path as "should be data-driven but
            // isn't in the RecipeManager" and warns. The leading `/` marks the
            // id as synthetic per EMI convention.
            ResourceLocation aggId = new ResourceLocation(Fabricate.MOD_ID,
                "/agg_" + outKey.getNamespace() + "_" + outKey.getPath());

            registry.addRecipe(new EmiCraftingRecipe(
                List.of(aggregate),
                EmiStack.of(outItem),
                aggId,
                true /* shapeless */
            ));
        }
    }

    /**
     * EMI recipe handler for the vanilla crafting table. Aggregate FAB cards
     * don't correspond to a single recipe in the RecipeManager  they're a UI
     * summary of every synthetic producing their output  so {@link #canCraft}
     * and {@link #craft} walk the RecipeManager for that output and pick the
     * first craftable path (vanilla or synthetic), bypassing Polymorph's
     * selection logic via {@link CraftPacket}.
     */
    private static class FabricateCraftingHandler implements EmiRecipeHandler<CraftingMenu> {

        @Override
        public EmiPlayerInventory getInventory(AbstractContainerScreen<CraftingMenu> screen) {
            var player = Minecraft.getInstance().player;
            return player != null ? EmiPlayerInventory.of(player) : new EmiPlayerInventory(List.of());
        }

        @Override
        public boolean supportsRecipe(EmiRecipe recipe) {
            return recipe.getId() != null
                && Fabricate.MOD_ID.equals(recipe.getId().getNamespace());
        }

        @Override
        public boolean canCraft(EmiRecipe recipe, EmiCraftContext<CraftingMenu> context) {
            if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) return false;
            return resolveCraftable(recipe) != null;
        }

        @Override
        public boolean craft(EmiRecipe recipe, EmiCraftContext<CraftingMenu> context) {
            if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) return false;
            var chosen = resolveCraftable(recipe);
            if (chosen == null) return false;
            boolean toCursor = context != null
                && context.getDestination() == EmiCraftContext.Destination.CURSOR;
            NetworkHandler.sendToServer(new CraftPacket(chosen.getId(), toCursor));
            return true;
        }

        /** Best craftable RecipeManager entry producing the EMI card's output, or null. */
        private static net.minecraft.world.item.crafting.Recipe<?> resolveCraftable(EmiRecipe recipe) {
            if (recipe.getOutputs().isEmpty()) return null;
            ItemStack out = recipe.getOutputs().get(0).getItemStack();
            if (out.isEmpty()) return null;
            return RecipeSelector.pickBest(out.getItem());
        }
    }
}
