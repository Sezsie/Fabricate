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
 */
public final class JeiSidebarHandler {

    private JeiSidebarHandler() {}

    /**
     * Set when we consume a left-click to trigger a craft. The corresponding
     * mouse-release must also be swallowed, otherwise vanilla's release
     * handling (e.g. throw-cursor-outside-slot on quick re-clicks) can drop
     * the freshly-crafted item back out of the cursor.
     */
    private static boolean swallowNextRelease = false;

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

        Fabricate.LOGGER.debug("[FAB-JEI] click -> PlannerCraftPacket({}x {}, toCursor={})",
            qty, net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(target), toCursor);
        NetworkHandler.sendToServer(new PlannerCraftPacket(target, qty, toCursor));
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
}
