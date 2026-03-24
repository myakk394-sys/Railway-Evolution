package com.fizzylovely.railwayevolution.client;

import com.fizzylovely.railwayevolution.item.ModItems;
import com.fizzylovely.railwayevolution.network.ModNetwork;
import com.fizzylovely.railwayevolution.network.OpenPanelRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only event subscriber.
 *
 * Handles:
 *   - RegisterKeyMappingsEvent  — registers the F10 key binding
 *   - ClientTickEvent           — polls the key each tick and sends panel-open request
 */
@Mod.EventBusSubscriber(modid = com.fizzylovely.railwayevolution.CreateRailwayMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientModEvents {

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        ModKeyBindings.register(event);
    }
}

/**
 * Separate subscriber on the FORGE bus for per-tick key polling.
 */
@Mod.EventBusSubscriber(modid = com.fizzylovely.railwayevolution.CreateRailwayMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
class ClientForgeEvents {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.level == null) return;

        // Consume all queued presses of the key (can queue up if lag spikes)
        while (ModKeyBindings.OPEN_PANEL.consumeClick()) {
            // Only open when wearing AI Goggles
            ItemStack helmet = player.getInventory().armor.get(3);
            if (!helmet.isEmpty() && helmet.getItem() == ModItems.AI_GOGGLES.get()) {
                // Don't reopen if already open
                if (!(mc.screen instanceof TrainControlPanelScreen)) {
                    ModNetwork.CHANNEL.sendToServer(new OpenPanelRequestPacket());
                }
            }
        }
    }
}
