package com.fizzylovely.railwayevolution.network;

import com.fizzylovely.railwayevolution.ai.TrainAIController;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → Server: player pressed F10 wearing goggles; server should respond
 * with a TrainListPacket to the requesting player.
 *
 * No payload needed — the server knows the sender from NetworkEvent.Context.getSender().
 */
public class OpenPanelRequestPacket {

    public static void encode(OpenPanelRequestPacket msg, FriendlyByteBuf buf) {}

    public static OpenPanelRequestPacket decode(FriendlyByteBuf buf) {
        return new OpenPanelRequestPacket();
    }

    public static void handle(OpenPanelRequestPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) return;

            // Build train snapshot and send back
            TrainAIManager manager = TrainAIManager.getInstance();
            if (manager == null) return;

            long currentTick = sender.serverLevel().getGameTime();
            java.util.List<TrainInfo> infos = new java.util.ArrayList<>();
            for (TrainAIController ctrl : manager.getAllControllers()) {
                String name = ctrl.getTrainDisplayName();
                if (name.isBlank()) name = ctrl.getTrainId().toString().substring(0, 8);
                infos.add(new TrainInfo(
                        ctrl.getTrainId(),
                        name,
                        ctrl.getCurrentState(),
                        ctrl.getCurrentSpeed(),
                        ctrl.isChatSilenced(),
                        ctrl.isAiEnabled(),
                        ctrl.hadRecentCollision(currentTick)
                ));
            }
            infos.sort(java.util.Comparator.comparing(TrainInfo::label));
            ModNetwork.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sender),
                    new TrainListPacket(infos));
        });
        ctx.get().setPacketHandled(true);
    }
}
