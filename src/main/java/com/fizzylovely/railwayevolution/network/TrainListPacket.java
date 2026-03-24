package com.fizzylovely.railwayevolution.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client: full snapshot of every tracked train.
 * Sent when the client requests the F10 control panel to open.
 */
public class TrainListPacket {

    public final List<TrainInfo> trains;

    public TrainListPacket(List<TrainInfo> trains) {
        this.trains = trains;
    }

    public static void encode(TrainListPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.trains.size());
        for (TrainInfo info : msg.trains) {
            info.encode(buf);
        }
    }

    public static TrainListPacket decode(FriendlyByteBuf buf) {
        int count = buf.readInt();
        List<TrainInfo> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(TrainInfo.decode(buf));
        }
        return new TrainListPacket(list);
    }

    public static void handle(TrainListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Open (or refresh) the control panel screen on the client thread
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof com.fizzylovely.railwayevolution.client.TrainControlPanelScreen panel) {
                panel.refreshData(msg.trains);
            } else {
                mc.setScreen(new com.fizzylovely.railwayevolution.client.TrainControlPanelScreen(msg.trains));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
