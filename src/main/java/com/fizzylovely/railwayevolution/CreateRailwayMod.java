package com.fizzylovely.railwayevolution;

import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import com.fizzylovely.railwayevolution.event.TrainEventHandler;
import com.fizzylovely.railwayevolution.item.ModItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create: Railway Evolution — Smart Train AI addon for Create Mod.
 * Vanilla edition: basic collision avoidance + player safety.
 * Forge 1.20.1 edition.
 */
@Mod(CreateRailwayMod.MOD_ID)
public class CreateRailwayMod {

    public static final String MOD_ID = "create_railway";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Conditional AI logging (respects /railway log toggle)

    /** Log at INFO level only when AI logging is enabled via /railway log true. */
    public static void aiLog(String format, Object... args) {
        if (RailwayConfig.loggingEnabled) {
            LOGGER.info(format, args);
        }
    }

    /** Log at DEBUG level only when AI logging is enabled. */
    public static void aiDebug(String format, Object... args) {
        if (RailwayConfig.loggingEnabled) {
            LOGGER.debug(format, args);
        }
    }

    /** Log at WARN level only when AI logging is enabled. */
    public static void aiWarn(String format, Object... args) {
        if (RailwayConfig.loggingEnabled) {
            LOGGER.warn(format, args);
        }
    }

    public CreateRailwayMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onCommonSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, RailwayConfig.SPEC);

        // Register game event listener (server tick, commands, etc.)
        MinecraftForge.EVENT_BUS.register(new TrainEventHandler());

        // Register items (AI Goggles)
        ModItems.ITEMS.register(modEventBus);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[Railway Evolution] Common setup — initializing Train AI subsystem");
        event.enqueueWork(() -> {
            TrainAIManager.initialize();
            LOGGER.info("[Railway Evolution] Train AI Manager initialized");
        });
    }
}
