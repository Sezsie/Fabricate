package com.sabbs.fabricate.recipe;

import com.sabbs.fabricate.Fabricate;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Forge registry for our recipe serializer. Required so synthetics round-trip
 * over {@code ClientboundUpdateRecipesPacket} on dedicated servers  see
 * {@link FabricateRecipeSerializer}.
 */
public final class ModRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, Fabricate.MOD_ID);

    public static final RegistryObject<FabricateRecipeSerializer> TRUE_POLYMORPH =
        SERIALIZERS.register("true_polymorph", FabricateRecipeSerializer::new);

    private ModRecipes() {}

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }
}
