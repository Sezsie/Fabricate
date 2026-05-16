package com.sabbs.fabricate.recipe;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.sabbs.fabricate.Fabricate;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

/**
 * For each item, the set of items considered tag-equivalent for substitution
 * during synthetic crafting. Two items are equivalent iff they share at least
 * one tag that's actually used as an ingredient in some vanilla crafting recipe
 * (the "substitution-relevant" tag set). This keeps gold_ingot and iron_ingot
 * apart (their tags are {@code #forge:ingots/gold} vs {@code #forge:ingots/iron},
 * neither shared) while making oak_log and stripped_oak_log interchangeable
 * (both in {@code #minecraft:logs}, which is an ingredient of the stick recipe).
 *
 * <p>Used by {@link CraftabilityCheck} and {@link com.sabbs.fabricate.network.CraftPacket}
 * to broaden inventory matching beyond exact-item equality. The synthetic itself
 * still says "needs oak_log:1" but at craft-time we accept any tag-equivalent
 * item the player has.
 *
 * <p>Lazy-built on first access (per RecipeManager identity), so a recipe reload
 * invalidates correctly. All maps are concurrent so the cache can serve client
 * UI queries and server craft requests simultaneously.
 */
public final class TagEquivalence {

    private TagEquivalence() {}

    private static volatile RecipeManager cachedManager = null;
    private static final Map<Item, Set<Item>> EQUIVALENTS = new ConcurrentHashMap<>();
    /** Tags that appear as an ingredient in some crafting recipe. Other tags are ignored to avoid over-broad equivalences. */
    private static volatile Set<TagKey<Item>> substitutionTags = Set.of();

    /** Invalidate the cache. Call on RecipesUpdatedEvent. */
    public static void invalidate() {
        cachedManager = null;
        EQUIVALENTS.clear();
        substitutionTags = Set.of();
    }

    /**
     * Returns items considered equivalent to {@code item} for substitution
     * during synthetic crafting. The returned set always includes {@code item}
     * itself; if the item has no relevant tag membership, the set has only
     * that one entry.
     */
    public static Set<Item> equivalents(Item item) {
        ensureCache();
        Set<Item> cached = EQUIVALENTS.get(item);
        if (cached != null) return cached;
        return EQUIVALENTS.computeIfAbsent(item, TagEquivalence::computeEquivalents);
    }

    /**
     * True iff {@code candidate} can substitute for {@code required} in a
     * synthetic's required-items map. Equivalent to
     * {@code equivalents(required).contains(candidate)} but slightly faster
     * by avoiding set iteration for the common identity case.
     */
    public static boolean canSubstitute(Item required, Item candidate) {
        if (required == candidate) return true;
        return equivalents(required).contains(candidate);
    }

    private static void ensureCache() {
        RecipeManager rm = currentRecipeManager();
        if (rm == null) return;
        if (cachedManager == rm) return;
        synchronized (TagEquivalence.class) {
            if (cachedManager == rm) return;
            EQUIVALENTS.clear();
            substitutionTags = collectSubstitutionTags(rm);
            cachedManager = rm;
            Fabricate.LOGGER.info("[FAB-tags] discovered {} substitution-relevant tags from {} crafting recipes",
                substitutionTags.size(), rm.getAllRecipesFor(RecipeType.CRAFTING).size());
        }
    }

        /**
     * Recursively scans an Ingredient JSON element for "tag" properties.
     *
     * Handles all common Ingredient JSON shapes:
     *
     * { "tag": "minecraft:logs" }
     * { "item": "minecraft:stick" }
     * [
     *   { "tag": "minecraft:planks" },
     *   { "item": "minecraft:bamboo_planks" }
     * ]
     *
     * It intentionally ignores "item" entries because this cache only cares about
     * tags that are actually used as crafting ingredients.
     */
    private static void collectTagsFromIngredientJson(JsonElement element, Set<TagKey<Item>> out) {
        if (element == null || element.isJsonNull()) return;

        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            for (JsonElement child : arr) {
                collectTagsFromIngredientJson(child, out);
            }
            return;
        }

        if (!element.isJsonObject()) return;

        JsonObject obj = element.getAsJsonObject();

        JsonElement tagElement = obj.get("tag");
        if (tagElement != null && tagElement.isJsonPrimitive()) {
            String tagName = tagElement.getAsString();

            ResourceLocation id = ResourceLocation.tryParse(tagName);
            if (id != null) {
                out.add(TagKey.create(Registries.ITEM, id));
            }
        }

        // Defensive recursion in case some modded Ingredient serializer nests data.
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement child = entry.getValue();
            if (child != tagElement) {
                collectTagsFromIngredientJson(child, out);
            }
        }
    }

        /**
     * Walks every crafting recipe and collects the set of tag keys that appear
     * as ingredient tags.
     *
     * Uses Ingredient#toJson instead of reflecting into Ingredient internals.
     * Reflection is fragile in production modpacks because mapped dev field names
     * like "values" may not exist at runtime.
     */
    private static Set<TagKey<Item>> collectSubstitutionTags(RecipeManager rm) {
        Set<TagKey<Item>> tags = new HashSet<>();

        try {
            for (Recipe<?> r : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) continue;

                    JsonElement json = ing.toJson();
                    collectTagsFromIngredientJson(json, tags);
                }
            }
        } catch (Throwable t) {
            Fabricate.LOGGER.warn(
                "[FAB-tags] couldn't enumerate Ingredient tags from Ingredient JSON; tag-equivalent matching disabled",
                t
            );
            return Set.of();
        }

        return tags;
    }

    private static Set<Item> computeEquivalents(Item item) {
        Set<Item> result = new HashSet<>();
        result.add(item);
        Set<TagKey<Item>> subTags = substitutionTags;
        if (subTags.isEmpty()) return Set.copyOf(result);
        for (TagKey<Item> tag : subTags) {
            if (!item.builtInRegistryHolder().is(tag)) continue;
            // This tag's iteration. Forge's BuiltInRegistries.ITEM exposes
            // each registered tag's holder set, which contains every item
            // currently bound to the tag. Add all of them.
            BuiltInRegistries.ITEM.getTag(tag).ifPresent(holders -> {
                holders.forEach(h -> result.add(h.value()));
            });
        }
        return Set.copyOf(result);
    }

    /**
     * Canonical form of an item for producer-deduplication purposes.
     * If the item belongs to at least one substitution-relevant tag, returns
     * the alphabetically-first such tag's full name (prefixed with "#"). If
     * it belongs to none, returns the item's full registry name.
     *
     * <p>Used by {@link GraphWalker} to dedupe tag-equivalent producers in
     * {@code productionMap}: all log-based stick producers map to the same
     * canonical "#minecraft:logs", so only one survives the dedup pass.
     * The tag-equivalent matching layer at click time covers the other
     * variants without needing N separate synthetics per tag class.
     */
    public static String canonicalForm(Item item) {
        ensureCache();
        Set<TagKey<Item>> subTags = substitutionTags;
        String best = null;
        for (TagKey<Item> tag : subTags) {
            if (!item.builtInRegistryHolder().is(tag)) continue;
            String tagName = tag.location().toString();
            if (best == null || tagName.compareTo(best) < 0) {
                best = tagName;
            }
        }
        if (best != null) return "#" + best;
        var k = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        return k == null ? "unknown" : k.toString();
    }

    /**
     * Sums {@code stack} counts for any inventory slot whose item is
     * tag-equivalent to {@code required}. Used by the click-time craftability
     * check and by server-side consumption.
     */
    public static int countMatching(Iterable<ItemStack> slots, Item required) {
        Set<Item> eq = equivalents(required);
        int sum = 0;
        for (ItemStack s : slots) {
            if (s.isEmpty()) continue;
            if (eq.contains(s.getItem())) sum += s.getCount();
        }
        return sum;
    }

    /** Best-effort RecipeManager lookup that works on both client and server. */
    private static RecipeManager currentRecipeManager() {
        try {
            // Server context first - server-side consumption needs server's RM.
            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) return server.getRecipeManager();
        } catch (Throwable ignored) {}
        try {
            var mc = Minecraft.getInstance();
            if (mc != null && mc.getConnection() != null) {
                return mc.getConnection().getRecipeManager();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
