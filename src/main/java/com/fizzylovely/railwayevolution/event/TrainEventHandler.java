package com.fizzylovely.railwayevolution.event;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.command.RailwayAICommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge 1.20.1 event handler — hooks into the server tick to drive the AI system.
 */
public class TrainEventHandler {

    @SubscribeEvent
    public void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return;

        ServerLevel fallbackLevel = event.getServer().overworld();
        if (fallbackLevel == null) return;
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
