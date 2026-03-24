package com.fizzylovely.railwayevolution.network;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

@SuppressWarnings("removal")
public class ModNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CreateRailwayMod.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int id = 0;

    public static void register() {
        // C → S: open panel request
        CHANNEL.registerMessage(id++,
                OpenPanelRequestPacket.class,
                OpenPanelRequestPacket::encode,
                OpenPanelRequestPacket::decode,
                OpenPanelRequestPacket::handle);

        // S → C: full train list for the panel
        CHANNEL.registerMessage(id++,
                TrainListPacket.class,
                TrainListPacket::encode,
                TrainListPacket::decode,
                TrainListPacket::handle);

        // C → S: toggle command for one train
        CHANNEL.registerMessage(id++,
                TrainCommandPacket.class,
                TrainCommandPacket::encode,
                TrainCommandPacket::decode,
                TrainCommandPacket::handle);
    }
}
