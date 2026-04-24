package com.sabbs.fabricate.integration.jei;

import com.mojang.blaze3d.platform.InputConstants;
import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.network.CraftPacket;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.recipe.CraftabilityCheck;
import com.sabbs.fabricate.recipe.FabricateRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@JeiPlugin
public class FabricateJeiPlugin implements IModPlugin {

    private static volatile IJeiRuntime runtime;

    public static IJeiRuntime getRuntime() {
        return runtime;
    }

    /**
     * When EMI is present, EMI owns the recipe-viewer UX (including our
     * click-to-craft path via {@code EmiScreenManagerMixin}). We stay out of
     * JEI entirely  registering anything here would also cause JEMI to
     * mirror our categories back into EMI and compound its tag-type id
     * collision errors.
     */
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

    /**
     * Runtime defense-in-depth against Fabricate recipes leaking into JEI.
     * {@link com.sabbs.fabricate.ClientEvents#onRecipesUpdated} already strips
     * them from the vanilla {@code RecipeManager} at HIGHEST event priority, so
     * in the happy path this pass finds nothing. It's here for (a) legacy
     * {@code truepolymorph:} entries that might outlive a migration and (b)
     * any edge case where JEI cached a recipe reference before our strip. JEI's
     * {@code hideRecipes} operates on its own per-runtime visibility map, so
     * it's effective even if the recipe still sits in the RecipeManager.
     */
    private static void applyRuntimeFilters(IJeiRuntime jeiRuntime) {
        boolean optedOut = !com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get();
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        var rm = mc.getConnection().getRecipeManager();

        List<CraftingRecipe> toHide = new ArrayList<>();
        for (CraftingRecipe r : rm.getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING)) {
            ResourceLocation id = r.getId();
            if (id == null) continue;
            String ns = id.getNamespace();
            boolean legacy = com.sabbs.fabricate.ClientEvents.LEGACY_NAMESPACE.equals(ns);
            boolean fabricate = Fabricate.MOD_ID.equals(ns);
            if (legacy || (optedOut && fabricate)) toHide.add(r);
        }
        if (toHide.isEmpty()) return;
        try {
            jeiRuntime.getRecipeManager().hideRecipes(RecipeTypes.CRAFTING, toHide);
            Fabricate.LOGGER.info("[FAB-JEI] hid {} crafting recipes via JEI runtime (optedOut={})", toHide.size(), optedOut);
        } catch (Throwable t) {
            Fabricate.LOGGER.warn("[FAB-JEI] hideRecipes failed: {}", t.toString());
        }
    }

    @Override
    public void onRuntimeUnavailable() {
        if (suppressed()) return;
        runtime = null;
    }

    @Override
    public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        if (suppressed()) return;
        registration.getCraftingCategory().addCategoryExtension(
            FabricateRecipe.class,
            recipe -> new ClickToCraftExtension(recipe)
        );
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        if (suppressed()) return;
        IRecipeTransferHandlerHelper helper = registration.getTransferHelper();
        registration.addUniversalRecipeTransferHandler(new InventoryTransferHandler(helper));
    }

    /**
     * Crafting category extension that handles clicks on the output item image.
     * When the player clicks the output area, it sends a craft packet.
     */
    private static class ClickToCraftExtension implements ICraftingCategoryExtension {
        private final FabricateRecipe recipe;

        ClickToCraftExtension(FabricateRecipe recipe) {
            this.recipe = recipe;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, ICraftingGridHelper helper, IFocusGroup focus) {
            List<List<ItemStack>> inputs = new ArrayList<>();
            for (Ingredient ingredient : recipe.getIngredients()) {
                inputs.add(List.of(ingredient.getItems()));
            }

            helper.createAndSetInputs(builder, inputs, getWidth(), getHeight());

            var level = Minecraft.getInstance().level;
            if (level != null) {
                helper.createAndSetOutputs(builder, List.of(
                    recipe.getResultItem(level.registryAccess())
                ));
            }

            builder.setShapeless();
        }

        @Override
        public int getWidth() {
            return 0; // auto for shapeless
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public ResourceLocation getRegistryName() {
            return recipe.getId();
        }

        @Override
        public boolean handleInput(double mouseX, double mouseY, InputConstants.Key key) {
            // Left-click inside the output-slot area (~18x18 at roughly 95,19)
            // kicks off a craft via CraftPacket instead of letting JEI fall
            // through to its default "show recipes for this output" behavior.
            if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) return false;
            if (key.getType() != InputConstants.Type.MOUSE || key.getValue() != 0) return false;
            if (mouseX < 88 || mouseX > 116 || mouseY < 12 || mouseY > 40) return false;

            ResourceLocation id = recipe.getId();
            if (id == null || !CraftabilityCheck.playerHasMaterials(id)) return false;

            // If this recipe needs a full 3x3 grid and the player is on their
            // inventory screen (2x2), don't even send the packet  the server
            // would reject it anyway, but bailing here keeps click feedback
            // consistent with the craftability gate.
            var player = Minecraft.getInstance().player;
            if (recipe.requiresCraftingTable()
                    && player != null
                    && player.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu) {
                return false;
            }

            NetworkHandler.sendToServer(new CraftPacket(id));
            return true;
        }
    }

    /**
     * Transfer handler for the "+" button on the inventory screen.
     */
    private static class InventoryTransferHandler implements IUniversalRecipeTransferHandler<InventoryMenu> {
        private final IRecipeTransferHandlerHelper helper;

        InventoryTransferHandler(IRecipeTransferHandlerHelper helper) {
            this.helper = helper;
        }

        @Override
        public Class<InventoryMenu> getContainerClass() {
            return InventoryMenu.class;
        }

        @Override
        public Optional<MenuType<InventoryMenu>> getMenuType() {
            return Optional.empty();
        }

        @Override
        public IRecipeTransferError transferRecipe(InventoryMenu container, Object recipe,
                IRecipeSlotsView slotsView, Player player, boolean maxTransfer, boolean doTransfer) {

            if (!(recipe instanceof FabricateRecipe tpRecipe)) {
                return helper.createInternalError();
            }
            if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) {
                return helper.createInternalError();
            }

            ResourceLocation id = tpRecipe.getId();

            // The "+" transfer button is only available from the 2x2 player
            // inventory grid  block recipes that need a full crafting table.
            if (tpRecipe.requiresCraftingTable()) {
                return helper.createUserErrorWithTooltip(
                    Component.literal("Requires a crafting table"));
            }

            Map<Item, Integer> required = tpRecipe.getRequiredItems();

            Inventory inv = player.getInventory();
            for (var entry : required.entrySet()) {
                int count = 0;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.getItem() == entry.getKey()) count += stack.getCount();
                }
                if (count < entry.getValue()) {
                    return helper.createUserErrorWithTooltip(
                        Component.literal("Not enough materials"));
                }
            }

            if (doTransfer) {
                NetworkHandler.sendToServer(new CraftPacket(id));
            }
            return null;
        }
    }

}
