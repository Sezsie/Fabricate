package com.sabbs.fabricate.recipe;

import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Network-capable serializer for {@link FabricateRecipe}. Exists so synthetics
 * survive the trip through {@code ClientboundUpdateRecipesPacket} on dedicated
 * servers  without it, vanilla deserializes our recipes as plain
 * {@code ShapelessRecipe} and the client loses {@code requiredItems} +
 * {@code requiresCraftingTable}, which breaks craftability checks and the
 * EMI/JEI click-to-craft path.
 *
 * <p>JSON loading is unused  synthetics are injected directly via reflection in
 * {@link RecipeInjector}  but {@link #fromJson} is implemented for parity in
 * case a user ever ships a datapack file with our type.
 */
public class FabricateRecipeSerializer implements RecipeSerializer<FabricateRecipe> {

    @Override
    public FabricateRecipe fromJson(ResourceLocation id, JsonObject json) {
        String group = net.minecraft.util.GsonHelper.getAsString(json, "group", "");
        CraftingBookCategory category = CraftingBookCategory.CODEC.byName(
            net.minecraft.util.GsonHelper.getAsString(json, "category", null), CraftingBookCategory.MISC);

        NonNullList<Ingredient> ingredients = NonNullList.create();
        for (var el : net.minecraft.util.GsonHelper.getAsJsonArray(json, "ingredients")) {
            Ingredient ing = Ingredient.fromJson(el, false);
            if (!ing.isEmpty()) ingredients.add(ing);
        }

        ItemStack result = net.minecraft.world.item.crafting.ShapedRecipe.itemStackFromJson(
            net.minecraft.util.GsonHelper.getAsJsonObject(json, "result"));

        Map<Item, Integer> required = new LinkedHashMap<>();
        JsonObject req = net.minecraft.util.GsonHelper.getAsJsonObject(json, "requiredItems", new JsonObject());
        for (var entry : req.entrySet()) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.getKey()));
            if (item == null) continue;
            required.put(item, entry.getValue().getAsInt());
        }

        boolean requiresTable = net.minecraft.util.GsonHelper.getAsBoolean(json, "requiresCraftingTable", false);

        return new FabricateRecipe(id, group, category, result, ingredients, required, requiresTable);
    }

    @Override
    public FabricateRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
        String group = buf.readUtf();
        CraftingBookCategory category = buf.readEnum(CraftingBookCategory.class);

        int ingCount = buf.readVarInt();
        NonNullList<Ingredient> ingredients = NonNullList.withSize(ingCount, Ingredient.EMPTY);
        for (int i = 0; i < ingCount; i++) ingredients.set(i, Ingredient.fromNetwork(buf));

        ItemStack result = buf.readItem();

        int reqCount = buf.readVarInt();
        Map<Item, Integer> required = new LinkedHashMap<>(reqCount);
        for (int i = 0; i < reqCount; i++) {
            Item item = buf.readRegistryIdUnsafe(ForgeRegistries.ITEMS);
            int amount = buf.readVarInt();
            required.put(item, amount);
        }

        boolean requiresTable = buf.readBoolean();

        return new FabricateRecipe(id, group, category, result, ingredients, required, requiresTable);
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf, FabricateRecipe recipe) {
        buf.writeUtf(recipe.getGroup());
        buf.writeEnum(recipe.category());

        buf.writeVarInt(recipe.getIngredients().size());
        for (Ingredient ing : recipe.getIngredients()) ing.toNetwork(buf);

        buf.writeItem(recipe.getResultItem(net.minecraft.core.RegistryAccess.EMPTY));

        Map<Item, Integer> required = recipe.getRequiredItems();
        buf.writeVarInt(required.size());
        for (var entry : required.entrySet()) {
            buf.writeRegistryIdUnsafe(ForgeRegistries.ITEMS, entry.getKey());
            buf.writeVarInt(entry.getValue());
        }

        buf.writeBoolean(recipe.requiresCraftingTable());
    }
}
