package com.sabbs.fabricate;

import com.sabbs.fabricate.network.NetworkHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Main mod entrypoint. Registers config + networking, then wires the EMI/JEI
 * integrations.
 *
 * <p>EMI and JEI are both listed as optional in {@code mods.toml} because
 * Forge has no native "either A or B" dependency form. The actual requirement
 * (at least one of EMI or JEI on the client) is enforced at construction
 * time below, where a missing-both situation throws a clear error rather
 * than letting the mod load into a non-functional state. When both are
 * present EMI takes priority. Dedicated servers don't need either:
 * synthetic generation and {@code CraftPacket} live server-side and don't
 * touch viewer code.
 */
@Mod(Fabricate.MOD_ID)
public class Fabricate {
    public static final String MOD_ID = "fabricate";
    public static final Logger LOGGER = LogManager.getLogger();

    public Fabricate() {
        String version = ModList.get().getModContainerById(MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        LOGGER.info("==================== Fabricate booting ====================");
        LOGGER.info("[FAB] dist={}, version={}", FMLEnvironment.dist, version);

        LOGGER.info("[FAB] registering client config spec");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, com.sabbs.fabricate.ModConfig.CLIENT_SPEC);

        LOGGER.info("[FAB] registering server config spec");
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, com.sabbs.fabricate.ModConfig.SERVER_SPEC);

        LOGGER.info("[FAB] registering network channel");
        NetworkHandler.register();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            LOGGER.info("[FAB] client dist  wiring recipe-viewer integrations");
            // Re-sync opt-out state when CLIENT_ENABLED is toggled in-game; lives
            // on the mod bus, unlike the rest of ClientEvents which is Forge-bus.
            FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(ClientEvents::onClientConfigReload);
            initClientIntegrations();
        } else {
            LOGGER.info("[FAB] dedicated server  skipping client integrations");
        }

        LOGGER.info("[FAB] constructor complete  waiting for recipe load");
        LOGGER.info("================================================================");
    }

    private static void initClientIntegrations() {
        ModList mods = ModList.get();
        boolean jei = mods.isLoaded("jei");
        boolean emi = mods.isLoaded("emi");
        LOGGER.info("[FAB] detected mods: jei={}, emi={}", jei, emi);

        // Hard requirement: a recipe viewer is the only way users interact
        // with synthetics on the client. Without EMI or JEI there's no
        // sidebar button, no click-to-craft, nothing the player can see.
        // Fail fast with a readable message instead of loading silently.
        if (!emi && !jei) {
            throw new RuntimeException(
                "Fabricate requires either EMI or JEI to be installed on the client. "
                + "Install one of them (or both) and relaunch.");
        }

        if (emi) {
            // EMI wins when both are present. Running both sidebars
            // double-injects buttons and fires duplicate CraftPackets.
            com.sabbs.fabricate.integration.emi.EmiCompat.init();
        } else {
            com.sabbs.fabricate.integration.jei.JeiCompat.init();
        }
    }
}
