package com.fizzylovely.railwayevolution.command;

import com.fizzylovely.railwayevolution.ai.TrainAIController;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.ai.TrainState;
import com.fizzylovely.railwayevolution.ai.VirtualBlockSystem;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Collection;

/**
 * /railway — main command tree for Railway Evolution mod.
 *
 * Usage:
 *   /railway                          — show all tracked trains + toggle states
 *   /railway reload                   — force re-scan for trains
 *   /railway log true|false           — toggle AI console logging on/off
 *   /railway safe-mode true|false     — toggle player safety system on/off
 *   /railway infinity true|false      — toggle unlimited rails/trains mode
 *
 * Aliases: /railwayai (backwards compatibility)
 */
@SuppressWarnings({"unused", "null"})
public class RailwayAICommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var railwayCmd = Commands.literal("railway")
                .requires(src -> src.hasPermission(0))
                .executes(ctx -> showStatus(ctx.getSource()))
                .then(Commands.literal("reload")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> forceReload(ctx.getSource())))
                .then(Commands.literal("release")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> releaseAll(ctx.getSource())))
                .then(Commands.literal("log")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> toggleLogging(ctx.getSource(),
                                BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("safe-mode")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> toggleSafeMode(ctx.getSource(),
                                BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("infinity")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> toggleInfinity(ctx.getSource(),
                                BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("braking")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("distance")
                        .then(Commands.argument("blocks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(5, 200))
                            .executes(ctx -> setBrakingDistance(ctx.getSource(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "blocks")))))
                    .then(Commands.literal("sharpness")
                        .then(Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10))
                            .executes(ctx -> setBrakingSharpness(ctx.getSource(),
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "value")))))
                    .executes(ctx -> showBrakingInfo(ctx.getSource())));

        dispatcher.register(railwayCmd);

        // Alias: /railwayai
        dispatcher.register(
            Commands.literal("railwayai")
                .requires(src -> src.hasPermission(0))
                .executes(ctx -> showStatus(ctx.getSource()))
                .then(Commands.literal("reload")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> forceReload(ctx.getSource())))
                .then(Commands.literal("release")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> releaseAll(ctx.getSource())))
                .then(Commands.literal("log")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> toggleLogging(ctx.getSource(),
                                BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("safe-mode")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> toggleSafeMode(ctx.getSource(),
                                BoolArgumentType.getBool(ctx, "enabled")))))
                .then(Commands.literal("infinity")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> toggleInfinity(ctx.getSource(),
                                BoolArgumentType.getBool(ctx, "enabled")))))
        );
    }

    // ─── /railway release ───
    private static int releaseAll(CommandSourceStack source) {
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) {
            source.sendFailure(Component.literal("§c[Railway AI] Manager not initialized"));
            return 0;
        }
        long tick = source.getServer().overworld().getGameTime();
        int count = 0;
        for (TrainAIController ctrl : manager.getAllControllers()) {
            TrainState state = ctrl.getCurrentState();
            if (state == TrainState.WAIT_FOR_CLEARANCE
                    || state == TrainState.TRAFFIC_JAM
                    || state == TrainState.YIELDING) {
                ctrl.forceRelease(tick, "command_release");
                count++;
            }
        }
        final int released = count;
        if (released == 0) {
            source.sendSuccess(() -> Component.literal(
                    "§a[Railway AI] §7No stuck trains found — all clear!"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§a[Railway AI] §f" + released + " train(s) released and resumed!"), true);
        }
        return 1;
    }

    // ─── /railway log true|false ───
    private static int toggleLogging(CommandSourceStack source, boolean enabled) {
        RailwayConfig.loggingEnabled = enabled;
        if (enabled) {
            source.sendSuccess(() -> Component.literal(
                    "§a[Railway AI] §fLogging §aENABLED §7— AI messages will appear in console"), true);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§a[Railway AI] §fLogging §cDISABLED §7— AI messages silenced"), true);
        }
        return 1;
    }

    // ─── /railway safe-mode true|false ───
    private static int toggleSafeMode(CommandSourceStack source, boolean enabled) {
        RailwayConfig.safeModeEnabled = enabled;
        if (enabled) {
            source.sendSuccess(() -> Component.literal(
                    "§a[Railway AI] §fSafe-mode §aENABLED §7— trains will brake for players"), true);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§a[Railway AI] §fSafe-mode §cDISABLED §7— trains will NOT brake for players"), true);
        }
        return 1;
    }

    // ─── /railway infinity true|false ───
    private static int toggleInfinity(CommandSourceStack source, boolean enabled) {
        RailwayConfig.infinityMode = enabled;
        int patched = applyInfinityMode(enabled);
        if (enabled) {
            final int p = patched;
            source.sendSuccess(() -> Component.literal(
                    "§a[Railway AI] §fInfinity mode §aENABLED §7— " + p + " limits patched"), true);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§a[Railway AI] §fInfinity mode §cDISABLED §7— defaults restored"), true);
        }
        return 1;
    }

    /**
     * Apply infinity mode — direct access to Create 6.0.9 config:
     *   AllConfigs.server() → CServer
     *     .trains (CTrains):
     *       .maxTrackPlacementLength (ConfigInt, default 32, max 128)
     *       .maxAssemblyLength       (ConfigInt, default 128, max 512)
     *       .maxBogeyCount           (ConfigInt, default 20, max 200)
     *     .kinetics (CKinetics):
     *       — contraption size limits
     *
     * ConfigInt (catnip) wraps ModConfigSpec.IntValue internally.
     * Must modify IntValue's max range BEFORE calling set() to bypass validation.
     */
    @SuppressWarnings("unchecked")
    private static int applyInfinityMode(boolean enabled) {
        int count = 0;
        try {
            // Get CServer via AllConfigs.server()
            Object cServer = null;
            for (String cn : new String[]{
                    "com.simibubi.create.infrastructure.config.AllConfigs",
                    "com.simibubi.create.AllConfigs"}) {
                try {
                    Class<?> cls = Class.forName(cn);
                    java.lang.reflect.Method m = cls.getDeclaredMethod("server");
                    m.setAccessible(true);
                    cServer = m.invoke(null);
                    if (cServer != null) {
                        com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                                "[Railway AI] infinity: found AllConfigs at {}, CServer={}",
                                cn, cServer.getClass().getName());
                        break;
                    }
                } catch (Exception ignored) {}
            }
            if (cServer == null) {
                com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                        "[Railway AI] infinity: AllConfigs.server() not found!");
                return 0;
            }

            // CServer.trains → CTrains
            java.lang.reflect.Field trainsF = findFieldInHierarchy(cServer.getClass(), "trains");
            if (trainsF != null) {
                trainsF.setAccessible(true);
                Object cTrains = trainsF.get(cServer);
                com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                        "[Railway AI] infinity: CServer.trains={}",
                        cTrains != null ? cTrains.getClass().getName() : "NULL");
                if (cTrains != null) {
                    count += patchConfigInt(cTrains, "maxTrackPlacementLength", enabled ? 10000 : 32);
                    count += patchConfigInt(cTrains, "maxAssemblyLength", enabled ? 10000 : 128);
                    count += patchConfigInt(cTrains, "maxBogeyCount", enabled ? 10000 : 20);
                }
            } else {
                com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                        "[Railway AI] infinity: CServer has no 'trains' field!");
            }

            // CServer.kinetics → CKinetics
            java.lang.reflect.Field kinF = findFieldInHierarchy(cServer.getClass(), "kinetics");
            if (kinF != null) {
                kinF.setAccessible(true);
                Object cKin = kinF.get(cServer);
                if (cKin != null) {
                    count += patchConfigInt(cKin, "maxBlocksMoved", enabled ? 10000 : 2048);
                }
            }

            com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                    "[Railway AI] infinity {} — {} values patched",
                    enabled ? "ON" : "OFF", count);
        } catch (Exception e) {
            com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                    "[Railway AI] infinity failed: {}", e.getMessage());
        }
        return count;
    }

    /**
     * Patch a single ConfigInt field inside a Create config section.
     * ConfigInt (catnip CValue) wraps ModConfigSpec.IntValue.
     * Steps: find inner IntValue → widen its min/max → call set().
     */
    private static int patchConfigInt(Object section, String fieldName, int target) {
        try {
            java.lang.reflect.Field f = findFieldInHierarchy(section.getClass(), fieldName);
            if (f == null) {
                com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                        "[Railway AI] infinity: field '{}' NOT FOUND in {}",
                        fieldName, section.getClass().getName());
                return 0;
            }
            f.setAccessible(true);
            Object configInt = f.get(section);
            if (configInt == null) {
                com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                        "[Railway AI] infinity: field '{}' is NULL", fieldName);
                return 0;
            }

            com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                    "[Railway AI] infinity: field '{}' type={}", fieldName,
                    configInt.getClass().getName());

            // Read current value via get()
            int oldValue = -1;
            try {
                java.lang.reflect.Method getM = configInt.getClass().getMethod("get");
                Object cur = getM.invoke(configInt);
                if (cur instanceof Number n) oldValue = n.intValue();
            } catch (Exception ignored) {}

            // Walk ConfigInt's hierarchy to find inner ModConfigSpec.IntValue
            Object innerValue = null;
            for (Class<?> c = configInt.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field inner : c.getDeclaredFields()) {
                    inner.setAccessible(true);
                    Object candidate = inner.get(configInt);
                    if (candidate == null) continue;
                    String cName = candidate.getClass().getName();
                    // Match ModConfigSpec.IntValue, ForgeConfigSpec.IntValue, or any ConfigValue
                    if (cName.contains("IntValue") || cName.contains("ConfigValue")
                            || cName.contains("ModConfigSpec") || cName.contains("ForgeConfigSpec")) {
                        innerValue = candidate;
                        com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                                "[Railway AI] infinity: found inner value in '{}': field={}, type={}",
                                fieldName, inner.getName(), cName);
                        break;
                    }
                }
                if (innerValue != null) break;
            }

            boolean success = false;

            if (innerValue != null) {
                // Widen min/max range so our value passes validation
                java.lang.reflect.Field maxF = findFieldInHierarchy(innerValue.getClass(), "max");
                if (maxF != null) { maxF.setAccessible(true); maxF.set(innerValue, Integer.MAX_VALUE); }
                java.lang.reflect.Field minF = findFieldInHierarchy(innerValue.getClass(), "min");
                if (minF != null) { minF.setAccessible(true); minF.set(innerValue, 0); }

                // Call set() on IntValue
                try {
                    java.lang.reflect.Method setM = innerValue.getClass().getMethod("set", Object.class);
                    setM.invoke(innerValue, target);
                    success = true;
                } catch (Exception e1) {
                    // Direct cached value as last resort
                    java.lang.reflect.Field cached = findFieldInHierarchy(innerValue.getClass(), "cachedValue");
                    if (cached != null) {
                        cached.setAccessible(true);
                        cached.set(innerValue, target);
                        success = true;
                    }
                }
            } else {
                com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                        "[Railway AI] infinity: NO inner IntValue found for '{}', listing all fields:",
                        fieldName);
                // Dump all fields for diagnostics
                for (Class<?> c = configInt.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field inner : c.getDeclaredFields()) {
                        inner.setAccessible(true);
                        Object val = inner.get(configInt);
                        com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                                "  {} -> {}: {}", c.getSimpleName(), inner.getName(),
                                val != null ? val.getClass().getName() : "null");
                    }
                }
            }

            // Also try set() on ConfigInt wrapper itself
            if (!success) {
                try {
                    java.lang.reflect.Method ws = configInt.getClass().getMethod("set", Object.class);
                    ws.invoke(configInt, target);
                    success = true;
                } catch (Exception ignored) {}
            }

            // Verify by re-reading
            int newValue = -1;
            try {
                java.lang.reflect.Method getM = configInt.getClass().getMethod("get");
                Object cur = getM.invoke(configInt);
                if (cur instanceof Number n) newValue = n.intValue();
            } catch (Exception ignored) {}

            com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                    "[Railway AI] infinity: {} = {} -> {} (target={}, success={})",
                    fieldName, oldValue, newValue, target, success);
            return success ? 1 : 0;
        } catch (Exception e) {
            com.fizzylovely.railwayevolution.CreateRailwayMod.LOGGER.warn(
                    "[Railway AI] infinity: patch {} EXCEPTION: {}", fieldName, e.getMessage());
            return 0;
        }
    }

    /** Walk superclasses to find a declared field. */
    private static java.lang.reflect.Field findFieldInHierarchy(Class<?> cls, String name) {
        while (cls != null && cls != Object.class) {
            try { return cls.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ─── /railway braking ───
    private static int showBrakingInfo(CommandSourceStack source) {
        double range = RailwayConfig.obstacleDetectionRange.get();
        double minStop = RailwayConfig.minimumStopDistance.get();
        source.sendSystemMessage(Component.literal(
                "§6§l[Railway AI] §r§fBraking config:"));
        source.sendSystemMessage(Component.literal(
                "  §7Detection range: §a" + (int) range + "§7 blocks"));
        source.sendSystemMessage(Component.literal(
                "  §7Min stop distance: §a" + String.format("%.1f", minStop) + "§7 blocks"));
        source.sendSystemMessage(Component.literal(
                "  §7Usage: §f/railway braking distance <5-200>"));
        source.sendSystemMessage(Component.literal(
                "  §7Usage: §f/railway braking sharpness <1-10>"));
        return 1;
    }

    private static int setBrakingDistance(CommandSourceStack source, int blocks) {
        RailwayConfig.obstacleDetectionRange.set((double) blocks);
        RailwayConfig.minimumStopDistance.set(Math.max(3.0, blocks * 0.14));
        source.sendSuccess(() -> Component.literal(
                "§a[Railway AI] §fBraking distance: §a" + blocks
                + "§f blocks (stop: §a" + String.format("%.1f", blocks * 0.14) + "§f)"), true);
        return 1;
    }

    private static int setBrakingSharpness(CommandSourceStack source, int sharpness) {
        double minStop = 15.0 - (sharpness - 1) * 1.33;
        RailwayConfig.minimumStopDistance.set(Math.max(3.0, minStop));
        source.sendSuccess(() -> Component.literal(
                "§a[Railway AI] §fBraking sharpness: §a" + sharpness
                + "§f (stop: §a" + String.format("%.1f", Math.max(3.0, minStop)) + "§f blocks)"), true);
        return 1;
    }


    // ─── /railway (status) ───
    private static int showStatus(CommandSourceStack source) {
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) {
            source.sendFailure(Component.literal("§c[Railway AI] Manager not initialized"));
            return 0;
        }

        Collection<TrainAIController> controllers = manager.getAllControllers();
        int vbsCount = VirtualBlockSystem.getInstance().getActiveReservationCount();

        String logState   = RailwayConfig.loggingEnabled ? "§aON" : "§cOFF";
        String safeState  = RailwayConfig.safeModeEnabled ? "§aON" : "§cOFF";
        String infState   = RailwayConfig.infinityMode ? "§aON" : "§cOFF";

        source.sendSystemMessage(Component.literal(
                "§6§l[Railway AI] §r§fTracking §a" + controllers.size()
                + "§f trains, §b" + vbsCount + "§f VBS reservations"));
        source.sendSystemMessage(Component.literal(
                "  §7Logging: " + logState
                + " §7| Safe-mode: " + safeState
                + " §7| Infinity: " + infState));

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

            String playerMark = ctrl.isPlayerControlled() ? " §b[PLAYER]" : "";

            source.sendSystemMessage(Component.literal(String.format(
                    "  §e%s §f| %s%s §f| §7speed=§f%.2f §f| §7pos=%s%s",
                    ctrl.getTrainId().toString().substring(0, 8),
                    stateColor, ctrl.getCurrentState().name(),
                    ctrl.getCurrentSpeed(),
                    posStr, playerMark
            )));
        }

        return 1;
    }

    // ─── /railway reload ───
    private static int forceReload(CommandSourceStack source) {
        TrainAIManager.initialize();
        source.sendSuccess(() -> Component.literal("§a[Railway AI] Manager re-initialized. Trains will be re-scanned."), true);
        return 1;
    }
}
