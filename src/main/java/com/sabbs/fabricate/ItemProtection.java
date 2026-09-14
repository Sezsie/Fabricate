package com.sabbs.fabricate;

import net.minecraft.world.item.ItemStack;

/**
 * Central rule for which item stacks Fabricate refuses to auto-consume as
 * crafting materials.
 *
 * <p>The planner reasons about {@link net.minecraft.world.item.Item} counts,
 * so without this an enchanted tool and a plain one of the same item look
 * identical. Click-to-craft would happily grab an Efficiency V diamond pickaxe
 * to satisfy a recipe slot instead of crafting a fresh pickaxe from diamonds
 * and sticks. Protecting a stack means it is hidden from the material pool
 * <em>and</em> skipped during consumption, so the planner, the pre-execute
 * affordability check, and execution all agree it simply isn't there as a
 * material.
 *
 * <p><b>Damage is deliberately not a protection signal.</b> Fabricate wears
 * tools down in place as part of normal crafting, so a chipped-but-plain saw
 * is still fair game. Only enchantments mark a stack as hands-off, gated
 * behind {@link ModConfig#PROTECT_ENCHANTED_ITEMS}.
 *
 * <p>This is the single hook for "don't eat this" logic; a future favorites /
 * lock system would extend {@link #isProtected(ItemStack)} rather than
 * scattering checks across the planner and executor.
 */
public final class ItemProtection {

    private ItemProtection() {}

    /**
     * True when {@code stack} must never be consumed or damaged by a
     * click-to-craft.
     *
     * <p>Every site that builds the planner's material pool must exclude these
     * stacks, and every site that consumes materials must skip them, or the
     * two views drift and a plan can reference materials the executor then
     * refuses to touch.
     */
    public static boolean isProtected(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return ModConfig.PROTECT_ENCHANTED_ITEMS.get() && stack.isEnchanted();
    }
}
