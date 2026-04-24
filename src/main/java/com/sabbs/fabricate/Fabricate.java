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
 * Main mod entrypoint. Registers config + networking, then wires integration
 * modules only when their host mod is actually installed  references to EMI
 * or JEI live behind {@code isLoaded} guards so the mod boots cleanly on any
 * combination (EMI alone, JEI alone, both, neither).
 */
@Mod(Fabricate.MOD_ID)
public class Fabricate {
    public static final String MOD_ID = "fabricate";
    public static final Logger LOGGER = LogManager.getLogger();

    public Fabricate() {
        LOGGER.info("==================== Fabricate booting ====================");
        LOGGER.info("[FAB] dist={}, version=1.0.0", FMLEnvironment.dist);

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
        boolean polymorph = mods.isLoaded("polymorph");
        boolean emi = mods.isLoaded("emi");
        boolean jei = mods.isLoaded("jei");
        LOGGER.info("[FAB] detected mods: polymorph={}, emi={}, jei={}", polymorph, emi, jei);

        if (polymorph) com.sabbs.fabricate.integration.polymorph.PolymorphCompat.init();
        if (emi) com.sabbs.fabricate.integration.emi.EmiCompat.init();
        // EMI wins when both are present  running both sidebars double-injects
        // buttons and fires duplicate CraftPackets.
        if (jei && !emi) com.sabbs.fabricate.integration.jei.JeiCompat.init();
    }
}
