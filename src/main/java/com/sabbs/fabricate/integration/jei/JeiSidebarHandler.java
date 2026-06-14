package com.sabbs.fabricate.integration.jei;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.network.PlannerCraftPacket;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * JEI sidebar click-to-craft, routed through the planner.
 *
 * <p>Left-click sends one of {@code target} for cursor delivery;
 * shift+left-click sends a stack-size batch for inventory delivery.
 * The server-side {@link com.sabbs.fabricate.planner.PlannerService}
 * decides which recipe path actually runs based on the player's inventory.
 *
 * <p>Client-side throttling is intentionally duplicated with server-side
 * request validation. The client throttle prevents ordinary spam-clicking
 * from flooding the server with queued planner requests. The server-side
 * checks still protect against malicious or broken clients.
 */
public final class JeiSidebarHandler {

    private JeiSidebarHandler() {}

    /**
     * Set when we consume a left-click to trigger a craft. The corresponding
     * mouse-release must also be swallowed, otherwise vanilla's release
     * handling, such as throw-cursor-outside-slot on quick re-clicks, can drop
     * the freshly-crafted item back out of the cursor.
     */
    private static boolean swallowNextRelease = false;

    /**
     * Timestamp of the last JEI craft packet sent by this client. Cooldown
     * value comes from {@link com.sabbs.fabricate.FabricateLimits#CLIENT_CLICK_COOLDOWN_MS}.
     */
    private static long lastCraftPacketSentMs = 0L;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) return;
        if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) return;

        IJeiRuntime rt = FabricateJeiPlugin.getRuntime();
        if (rt == null) return;

        ItemStack hovered;
        try {
            hovered = rt.getIngredientListOverlay().getIngredientUnderMouse(VanillaTypes.ITEM_STACK);
        } catch (Throwable t) {
            return;
        }
        if (hovered == null || hovered.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        if (isClientClickOnCooldown()) {
            Fabricate.LOGGER.debug("[FAB-JEI] throttled click on {}",
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(hovered.getItem()));

            swallowNextRelease = true;
            event.setCanceled(true);
            return;
        }

        Item target = hovered.getItem();
        int qty;
        boolean toCursor;
        if (Screen.hasShiftDown()) {
            qty = Math.max(1, hovered.getMaxStackSize());
            toCursor = false;
        } else {
            qty = 1;
            toCursor = event.getScreen() instanceof AbstractContainerScreen<?>;
        }

        boolean upToMode =
            com.sabbs.fabricate.ModConfig.CRAFT_MODE.get() == com.sabbs.fabricate.ModConfig.CraftMode.UP_TO;

        Fabricate.LOGGER.debug("[FAB-JEI] click -> PlannerCraftPacket({}x {}, toCursor={}, upToMode={})",
            qty, net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(target), toCursor, upToMode);

        NetworkHandler.sendToServer(new PlannerCraftPacket(target, qty, toCursor, upToMode));

        markClientClickAccepted();
        swallowNextRelease = true;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!swallowNextRelease) return;
        if (event.getButton() != 0) return;
        swallowNextRelease = false;
        event.setCanceled(true);
    }

    /**
     * True if this client has sent a JEI Fabricate packet too recently.
     */
    private static boolean isClientClickOnCooldown() {
        long now = System.currentTimeMillis();
        return now - lastCraftPacketSentMs < com.sabbs.fabricate.FabricateLimits.CLIENT_CLICK_COOLDOWN_MS;
    }

    /**
     * Mark that this client just sent a JEI Fabricate packet.
     */
    private static void markClientClickAccepted() {
        lastCraftPacketSentMs = System.currentTimeMillis();
    }
}