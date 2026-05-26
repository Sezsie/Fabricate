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
     * Cutoff for "useful" tag priority. Tags scoring at or below this are
     * considered clear enough to surface as a failure-message label even
     * for non-tool slots (e.g. "#minecraft:planks" for a plank slot, so
     * the player isn't told "Missing 3x Acacia Planks" when they actually
     * have birch planks ready to go).
     */
    public static final int USEFUL_TAG_PRIORITY_CUTOFF = 20;

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
        TagKey<Item> tag = findBestCommonTag(acceptedItems);
        return tag == null ? null : labelOf(tag);
    }

    /**
     * Same as {@link #findBestCommonTagLabel} but returns the raw
     * {@link TagKey} so callers can also inspect its priority via
     * {@link #tagPriority(TagKey)}. The label form is
     * {@code "#namespace:path"} - call {@link #labelOf(TagKey)} to format.
     */
    public static TagKey<Item> findBestCommonTag(Set<Item> acceptedItems) {
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
            .findFirst()
            .orElse(null);
    }

    /** Format a tag as the conventional {@code "#namespace:path"} label. */
    public static String labelOf(TagKey<Item> tag) {
        return "#" + tag.location();
    }

    /**
     * Common-material tag paths whose names are clean nouns players will
     * recognize on sight ("planks", "ingots", "wool"). Used by
     * {@link #tagPriority} to score these above default-namespace tags so
     * they win as failure-message labels.
     */
    private static final Set<String> CLEAN_MATERIAL_PATHS = Set.of(
        "planks",
        "logs",
        "logs_that_burn",
        "leaves",
        "saplings",
        "flowers",
        "small_flowers",
        "tall_flowers",
        "wool",
        "wools",
        "carpets",
        "beds",
        "candles",
        "dyes",
        "ingots",
        "nuggets",
        "raw_materials",
        "gems",
        "ores",
        "stones",
        "sand",
        "concrete",
        "concrete_powder",
        "wooden_slabs",
        "wooden_stairs",
        "wooden_fences",
        "wooden_fence_gates",
        "wooden_buttons",
        "wooden_doors",
        "wooden_trapdoors",
        "wooden_pressure_plates"
    );

    /**
     * Score for picking the most descriptive tag among several common to a
     * slot. Lower is preferred. Tool-class names beat clean material tags,
     * clean material tags beat generic forge/c tags, and obvious junk
     * (vanilla internal markers like {@code completes_find_tree_tutorial})
     * is ranked dead last.
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

        // Clean material tags ("planks", "logs", "ingots", ...) - readable
        // noun labels that work great as "Missing: 1x #minecraft:planks"
        // failure messages.
        if (CLEAN_MATERIAL_PATHS.contains(path)) return 10;

        if (id.startsWith("forge:") || id.startsWith("c:")) return 20;

        // Vanilla internal-state markers (e.g. minecraft:completes_*) are
        // technically common tags but useless as a UI label. Bury them
        // below the generic default so they only win if no real tag
        // exists.
        if (path.startsWith("completes_")
            || path.startsWith("entity_")
            || path.startsWith("creature_")) return 90;

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
