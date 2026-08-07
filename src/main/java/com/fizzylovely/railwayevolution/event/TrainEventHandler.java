package com.fizzylovely.railwayevolution.event;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.command.RailwayAICommand;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge event handler — hooks into the server tick to drive the AI system.
 */
public class TrainEventHandler {

    @SubscribeEvent
    public void onServerTickEnd(ServerTickEvent.Post event) {
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return;

        ServerLevel fallbackLevel = event.getServer().overworld();
        long currentTick = fallbackLevel.getGameTime();

        manager.tick(fallbackLevel, currentTick);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        RailwayAICommand.register(event.getDispatcher());
        CreateRailwayMod.LOGGER.info("[Railway Evolution] Registered /railway and /railwayai commands");
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        CreateRailwayMod.LOGGER.info("[Railway Evolution] Server stopped — cleaning up AI system");
        TrainAIManager.initialize();
    }
}
