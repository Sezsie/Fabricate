package com.sabbs.fabricate.planner;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared "what kind of ingredient slot is this?" heuristics used by both
 * the planner (to decide whether to multiply by batches) and the failure-
 * explanation path (to label slots and decide whether to count missing tools
 * once vs. per-batch).
 *
 * <p>None of these answers are authoritative - they're best-effort labels
 * for ingredients whose actual semantics live in mod-specific recipe code
 * we can't easily inspect. False positives produce slightly weird-looking
 * messages or slightly under-counted requirements; false negatives produce
 * stack-craft failures that vanilla would have allowed. We err toward
 * "reusable" because the cost of being wrong is just batched UX, not data
 * loss (the server-side dry-run during execute still catches real misses).
 */
public final class IngredientHeuristics {

    private IngredientHeuristics() {}

    /**
     * True if this ingredient slot looks like a reusable tool (hammer,
     * buzzsaw, wrench, file, etc.) that should NOT be multiplied by the
     * batch count when planning. Checks tag-class names first, falls back
     * to per-item heuristics (damageable, has-crafting-remainder, tool-like
     * name).
     */
    public static boolean isReusableSlot(Set<Item> acceptedItems) {
        if (acceptedItems == null || acceptedItems.isEmpty()) return false;

        String tagLabel = findBestCommonTagLabel(acceptedItems);
        if (tagLabel != null && looksToolLike(tagLabel)) return true;

        for (Item item : acceptedItems) {
            if (isReusableItem(item)) return true;
        }
        return false;
    }

    /** Item-level version of {@link #isReusableSlot}; used during remainder accounting. */
    public static boolean isReusableItem(Item item) {
        if (item == null) return false;
        ItemStack stack = item.getDefaultInstance();
        if (stack.isDamageableItem()) return true;
        if (item.hasCraftingRemainingItem()) return true;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id != null && looksToolLike(id.toString());
    }

    /**
     * Try to find a shared tag that describes this ingredient slot better
     * than a single arbitrary concrete item. Returns null when the slot
     * has one accepted item or accepted items share no common tags.
     *
     * <p>Used both by the failure-message labeller (to print
     * "#gtceu:tools/buzz_saws" instead of "Electric Buzzsaw LV") and by
     * {@link #isReusableSlot} as a stronger reusable signal than any
     * per-item check.
     */
    public static String findBestCommonTagLabel(Set<Item> acceptedItems) {
        if (acceptedItems == null || acceptedItems.size() <= 1) return null;

        Set<TagKey<Item>> commonTags = null;
        for (Item item : acceptedItems) {
            Set<TagKey<Item>> itemTags = item.builtInRegistryHolder()
                .tags()
                .collect(Collectors.toCollection(HashSet::new));
            if (itemTags.isEmpty()) return null;

            if (commonTags == null) {
                commonTags = new HashSet<>(itemTags);
            } else {
                commonTags.retainAll(itemTags);
            }
            if (commonTags.isEmpty()) return null;
        }
        if (commonTags == null || commonTags.isEmpty()) return null;

        return commonTags.stream()
            .sorted(Comparator
                .comparingInt(IngredientHeuristics::tagPriority)
                .thenComparing(t -> t.location().toString()))
            .map(tag -> "#" + tag.location())
            .findFirst()
            .orElse(null);
    }

    /**
     * Score for picking the most descriptive tag among several common to a
     * slot. Lower is preferred. Tool-class names beat generic forge tags.
     */
    public static int tagPriority(TagKey<Item> tag) {
        String id = tag.location().toString().toLowerCase(Locale.ROOT);
        String path = tag.location().getPath().toLowerCase(Locale.ROOT);

        if (path.contains("buzz") || path.contains("buzzsaw") || path.contains("buzz_saw")) return 0;
        if (path.contains("saw")) return 1;
        if (path.contains("hammer")) return 2;
        if (path.contains("wrench")) return 3;
        if (path.contains("file")) return 4;
        if (path.contains("knife") || path.contains("cutter") || path.contains("wire")) return 5;
        if (path.contains("tool")) return 6;
        if (id.startsWith("gtceu:")) return 7;
        if (id.startsWith("forge:tools") || id.startsWith("c:tools")) return 8;
        if (id.startsWith("forge:") || id.startsWith("c:")) return 20;
        return 50;
    }

    /**
     * Does this id/tag/path name look like a tool class? Substring match
     * against a fixed list of tool-related words. Used as a final fallback
     * when nothing more authoritative (damageable item, crafting remainder,
     * tool-tag) is available.
     */
    public static boolean looksToolLike(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("tool")
            || lower.contains("saw")
            || lower.contains("buzz")
            || lower.contains("hammer")
            || lower.contains("wrench")
            || lower.contains("file")
            || lower.contains("knife")
            || lower.contains("cutter")
            || lower.contains("wire_cutter")
            || lower.contains("mortar")
            || lower.contains("screwdriver");
    }
}
