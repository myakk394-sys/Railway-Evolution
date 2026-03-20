package com.fizzylovely.railwayevolution.command;

import com.fizzylovely.railwayevolution.ai.TrainAIController;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.ai.VirtualBlockSystem;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Collection;

/**
 * /railwayai — debug command to show AI system status in chat.
 *
 * Usage:
 *   /railwayai         — show all tracked trains, their state, position, speed
 *   /railwayai reload   — force re-scan for trains
 */
public class RailwayAICommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("railwayai")
                .requires(src -> src.hasPermission(0)) // anyone can use it
                .executes(ctx -> showStatus(ctx.getSource()))
                .then(Commands.literal("reload")
                    .requires(src -> src.hasPermission(2)) // ops only
                    .executes(ctx -> forceReload(ctx.getSource())))
        );
    }

    private static int showStatus(CommandSourceStack source) {
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) {
            source.sendFailure(Component.literal("§c[Railway AI] Manager not initialized"));
            return 0;
        }

        Collection<TrainAIController> controllers = manager.getAllControllers();
        int vbsCount = VirtualBlockSystem.getInstance().getActiveReservationCount();

        source.sendSystemMessage(Component.literal(
                "§6§l[Railway AI] §r§fTracking §a" + controllers.size()
                + "§f trains, §b" + vbsCount + "§f VBS reservations"));

        if (controllers.isEmpty()) {
            source.sendSystemMessage(Component.literal(
                    "§7  No trains found. Make sure Create Mod is loaded and trains exist in the world."));
            return 1;
        }

        for (TrainAIController ctrl : controllers) {
            BlockPos pos = ctrl.getCurrentPosition();
            String posStr = pos != null
                    ? String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ())
                    : "§cNULL";

            String stateColor = switch (ctrl.getCurrentState()) {
                case CRUISING -> "§a";
                case ANALYZING_OBSTACLE -> "§e";
                case YIELDING -> "§c";
                case BYPASSING -> "§d";
                case RETURNING -> "§b";
                case REVERSING -> "§6";
                case TRAFFIC_JAM -> "§4";
                case WAIT_FOR_CLEARANCE -> "§5";
            };

            source.sendSystemMessage(Component.literal(String.format(
                    "  §e%s §f| %s%s §f| §7speed=§f%.2f §f| §7pos=%s §f| §7heading=(%.1f, %.1f)",
                    ctrl.getTrainId().toString().substring(0, 8),
                    stateColor, ctrl.getCurrentState().name(),
                    ctrl.getCurrentSpeed(),
                    posStr,
                    ctrl.getHeadingX(), ctrl.getHeadingZ()
            )));
        }

        return 1;
    }

    private static int forceReload(CommandSourceStack source) {
        TrainAIManager.initialize();
        source.sendSuccess(() -> Component.literal("§a[Railway AI] Manager re-initialized. Trains will be re-scanned."), true);
        return 1;
    }
}
