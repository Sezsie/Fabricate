package com.sabbs.fabricate.integration.emi;

import com.sabbs.fabricate.Fabricate;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * When the player opens the standalone crafting-table UI, automatically focus
 * EMI's "Craftables" sidebar so usable recipes are front-and-center without
 * the player having to switch pages. Registered via {@link EmiCompat} only
 * when EMI is present  so the class never loads on EMI-less setups.
 */
public final class CraftingTableSidebarHandler {

    private CraftingTableSidebarHandler() {}

    @SubscribeEvent
    public static void onScreenOpen(ScreenEvent.Opening event) {
        if (!com.sabbs.fabricate.ModConfig.CLIENT_ENABLED.get()) return;
        if (!(event.getNewScreen() instanceof CraftingScreen)) return;
        try {
            if (EmiScreenManager.hasSidebarAvailable(SidebarType.CRAFTABLES)) {
                EmiScreenManager.focusSidebarType(SidebarType.CRAFTABLES);
            }
        } catch (Throwable t) {
            // EMI internals shift between versions; swallow and log at debug so
            // an API break doesn't spam user logs or block the screen from opening.
            Fabricate.LOGGER.debug("[FAB-EMI] failed to focus craftables sidebar", t);
        }
    }
}
