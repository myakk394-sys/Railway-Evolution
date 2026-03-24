package com.fizzylovely.railwayevolution.event;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.command.RailwayAICommand;
import com.fizzylovely.railwayevolution.debug.TrainDebugVisualizer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge event handler — hooks into the server tick to drive the AI system.
 */
public class TrainEventHandler {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return;

        ServerLevel overworld = event.getServer().overworld();
        long currentTick = overworld.getGameTime();

        manager.tick(overworld, currentTick);

        // Update debug visualizer for any players wearing AI Goggles
        TrainDebugVisualizer.tickAll(overworld, currentTick);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        RailwayAICommand.register(event.getDispatcher());
        CreateRailwayMod.LOGGER.info("[Railway Evolution] Registered /railwayai command");
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        CreateRailwayMod.LOGGER.info("[Railway Evolution] Server stopped — cleaning up AI system");
        TrainAIManager.initialize();
    }
}
