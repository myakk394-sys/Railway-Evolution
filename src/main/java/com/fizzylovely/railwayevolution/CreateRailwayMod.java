package com.fizzylovely.railwayevolution;

import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import com.fizzylovely.railwayevolution.event.TrainEventHandler;
import com.fizzylovely.railwayevolution.item.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create: Railway Evolution — Smart Train AI addon for Create Mod.
 * Vanilla edition: basic collision avoidance + player safety.
 * NeoForge 1.21.1 edition.
 */
@Mod(CreateRailwayMod.MOD_ID)
public class CreateRailwayMod {

    public static final String MOD_ID = "create_railway";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ─── Conditional AI logging (respects /railway log toggle) ───

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

    public CreateRailwayMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::onCommonSetup);
        modContainer.registerConfig(ModConfig.Type.COMMON, RailwayConfig.SPEC);

        // Register game event listener (server tick, commands, etc.)
        NeoForge.EVENT_BUS.register(new TrainEventHandler());

        // Register items (AI Goggles)
        ModItems.ITEMS.register(modEventBus);
        ModItems.ARMOR_MATERIALS.register(modEventBus);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[Railway Evolution] Common setup — initializing Train AI subsystem");
        event.enqueueWork(() -> {
            TrainAIManager.initialize();
            LOGGER.info("[Railway Evolution] Train AI Manager initialized");
        });
    }
}
