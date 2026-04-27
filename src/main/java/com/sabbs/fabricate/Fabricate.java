package com.sabbs.fabricate;

import com.sabbs.fabricate.network.NetworkHandler;
import com.sabbs.fabricate.recipe.ModRecipes;
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
 * <p>JEI is a mandatory client-side dependency (declared in {@code mods.toml}),
 * so on the client dist it's guaranteed to be loaded. EMI is optional; when
 * present it takes priority over JEI to avoid running both sidebars at once.
 * Dedicated servers don't need either: synthetic generation and
 * {@code CraftPacket} live server-side and don't touch viewer code.
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

        LOGGER.info("[FAB] registering config specs (common + client)");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.sabbs.fabricate.ModConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, com.sabbs.fabricate.ModConfig.CLIENT_SPEC);

        LOGGER.info("[FAB] registering network channel + CraftPacket");
        NetworkHandler.register();

        LOGGER.info("[FAB] registering recipe serializer");
        ModRecipes.register(FMLJavaModLoadingContext.get().getModEventBus());

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
        // JEI is mandatory in mods.toml so it's always loaded on the client.
        // The check stays defensive in case Forge ever lets us through with
        // a missing dep (mismatched dev environment, broken classpath, etc).
        boolean jei = mods.isLoaded("jei");
        boolean emi = mods.isLoaded("emi");
        LOGGER.info("[FAB] detected mods: jei={}, emi={}", jei, emi);

        if (emi) {
            // EMI wins when both are present. Running both sidebars
            // double-injects buttons and fires duplicate CraftPackets.
            com.sabbs.fabricate.integration.emi.EmiCompat.init();
        } else if (jei) {
            com.sabbs.fabricate.integration.jei.JeiCompat.init();
        } else {
            LOGGER.error("[FAB] neither JEI nor EMI is loaded - mods.toml dep on JEI was bypassed somehow");
        }
    }
}
