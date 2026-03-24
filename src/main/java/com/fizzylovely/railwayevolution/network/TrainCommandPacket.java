package com.fizzylovely.railwayevolution.network;

import com.fizzylovely.railwayevolution.ai.TrainAIController;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client → Server: player toggled a setting for a specific train via the F10 panel.
 */
public class TrainCommandPacket {

    public enum Command {
        TOGGLE_CHAT,       // flip chatSilenced
        TOGGLE_AI,         // flip aiEnabled
        RESET_COLLISION    // clear recentCollision flag
    }

    public final UUID    trainId;
    public final Command command;

    public TrainCommandPacket(UUID trainId, Command command) {
        this.trainId = trainId;
        this.command = command;
    }

    public static void encode(TrainCommandPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.trainId);
        buf.writeEnum(msg.command);
    }

    public static TrainCommandPacket decode(FriendlyByteBuf buf) {
        UUID trainId     = buf.readUUID();
        Command command  = buf.readEnum(Command.class);
        return new TrainCommandPacket(trainId, command);
    }

    public static void handle(TrainCommandPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            TrainAIManager manager = TrainAIManager.getInstance();
            if (manager == null) return;
            TrainAIController ctrl = manager.getController(msg.trainId);
            if (ctrl == null) return;

            switch (msg.command) {
                case TOGGLE_CHAT       -> ctrl.setChatSilenced(!ctrl.isChatSilenced());
                case TOGGLE_AI         -> ctrl.setAiEnabled(!ctrl.isAiEnabled());
                case RESET_COLLISION   -> ctrl.clearCollision();
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
