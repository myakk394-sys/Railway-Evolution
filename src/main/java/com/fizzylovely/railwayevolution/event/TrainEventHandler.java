package com.fizzylovely.railwayevolution.event;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.command.RailwayAICommand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Forge event handler — hooks into the server tick to drive the AI system.
 */
public class TrainEventHandler {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return;

        // Forge 1.18.2: ServerTickEvent не имеет поля event.server
        // Получаем сервер через ServerLifecycleHooks
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        long currentTick = overworld.getGameTime();
        manager.tick(overworld, currentTick);
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
