package com.sabbs.fabricate.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.ModConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side gate that decides whether a sidebar click should be claimed by
 * Fabricate or allowed to fall through to the recipe viewer and any other mod.
 *
 * <p>The reported problem was that Fabricate consumed plain left-clicks
 * outright, which broke recipe viewing and any GUI that expects the click for
 * itself (Create's ghost item filters, Create's stock requests). The fix is a
 * held modifier: in the default {@link ModConfig.CraftClickBehavior#HOLD_KEY}
 * mode, a click only becomes a craft while {@link #CRAFT_MODIFIER} is held, so
 * an un-modified click passes through untouched. {@link
 * ModConfig.CraftClickBehavior#INTERCEPT_ALL} restores the old always-claim
 * behavior.
 *
 * <p>The keybind is read via {@link InputConstants#isKeyDown} against its
 * currently-bound key rather than {@link KeyMapping#isDown()}. In a GUI the
 * game routes key events to the open screen, so a keybind's own pressed-state
 * doesn't reliably update; polling the raw key is how mods read a modifier
 * while a screen has focus.
 */
@Mod.EventBusSubscriber(modid = Fabricate.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FabricateActivation {

    private FabricateActivation() {}

    public static final String CATEGORY = "key.categories.fabricate";
    public static final String CRAFT_MODIFIER_KEY = "key.fabricate.craft_modifier";

    /**
     * Held to arm click-to-craft in {@code HOLD_KEY} mode. Defaults to Left Alt
     * and lives in the GUI conflict context, so it never clashes with in-world
     * bindings and is rebindable (or clearable) under Controls.
     */
    public static final KeyMapping CRAFT_MODIFIER = new KeyMapping(
        CRAFT_MODIFIER_KEY,
        KeyConflictContext.GUI,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_LEFT_ALT,
        CATEGORY
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CRAFT_MODIFIER);
        Fabricate.LOGGER.info("[FAB-client] registered craft-modifier keybind (default Left Alt)");
    }

    /**
     * True when a click right now should be claimed by Fabricate and turned
     * into a craft. In {@code INTERCEPT_ALL} mode this is always true; in
     * {@code HOLD_KEY} mode it tracks whether the craft modifier is held.
     *
     * <p>Callers that would otherwise cancel/consume a click must check this
     * first and, when it returns false, leave the click completely alone.
     */
    public static boolean shouldClaimClick() {
        if (ModConfig.CRAFT_CLICK_BEHAVIOR.get() == ModConfig.CraftClickBehavior.INTERCEPT_ALL) {
            return true;
        }
        return isCraftModifierHeld();
    }

    /**
     * Whether the (possibly rebound) craft modifier is currently held. Returns
     * false when the binding is cleared, so an unbound key in {@code HOLD_KEY}
     * mode simply means "never claim clicks" rather than crashing.
     */
    public static boolean isCraftModifierHeld() {
        InputConstants.Key key = CRAFT_MODIFIER.getKey();
        if (key == null || key.equals(InputConstants.UNKNOWN)) {
            return false;
        }

        long window = Minecraft.getInstance().getWindow().getWindow();
        try {
            if (key.getType() == InputConstants.Type.KEYSYM) {
                return InputConstants.isKeyDown(window, key.getValue());
            }
            if (key.getType() == InputConstants.Type.MOUSE) {
                return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
            }
        } catch (Throwable t) {
            Fabricate.LOGGER.debug("[FAB-client] craft-modifier state read failed", t);
        }
        return false;
    }
}
