package com.fizzylovely.railwayevolution;

import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import com.fizzylovely.railwayevolution.event.TrainEventHandler;
import com.fizzylovely.railwayevolution.item.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Create: Railway Evolution — Smart Train AI addon for Create Mod.
 * Provides autonomous decision-making, dynamic overtaking, conflict resolution,
 * and a Virtual Block System (VBS) for trains on the Create railway network.
 */
@Mod(CreateRailwayMod.MOD_ID)
public class CreateRailwayMod {

    public static final String MOD_ID = "create_railway";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public CreateRailwayMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, RailwayConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(new TrainEventHandler());

        // Register items
        ModItems.ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[Railway Evolution] Common setup — initializing Train AI subsystem");
        event.enqueueWork(() -> {
            TrainAIManager.initialize();
            LOGGER.info("[Railway Evolution] Train AI Manager initialized");
        });
    }
}
