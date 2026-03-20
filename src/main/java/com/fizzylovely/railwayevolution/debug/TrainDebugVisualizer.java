package com.fizzylovely.railwayevolution.debug;

import com.fizzylovely.railwayevolution.ai.TrainAIController;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.ai.VirtualBlockSystem;
import com.fizzylovely.railwayevolution.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Train Debug Visualizer — shows AI state as in-world particles and action-bar text
 * when a player wears the AI Debug Goggles (ai_goggles item in helmet slot).
 *
 * Visual legend:
 *   HAPPY_VILLAGER (green)  — train has signal block data from TrackGraph
 *   DRIPPING_HONEY (amber)  — train has edge data only (no signal blocks)
 *   CLOUD (grey)            — no TrackGraph data available yet
 *   FLAME particles         — VBS reserved track segments (15-block footprint per train)
 *   ELECTRIC_SPARK          — position of the train itself
 *   WITCH particles         — line connecting two trains that share the same track
 *   Action bar text         — "ID:STATE speed GRP:N" per nearby train
 *                             GRP:N = N signal groups, GRP:E = edge-only, GRP:? = no data
 *
 * Called every server tick; internally throttled to every 4 ticks.
 */
public class TrainDebugVisualizer {

    private static final int TICK_INTERVAL  = 4;      // render every 4 ticks
    private static final double RANGE_SQ   = 20000; // 141-block radius (particles)
    private static final double BAR_RANGE_SQ = 2304; // 48-block radius (action bar)
    private static final int MAX_BAR_TRAINS = 4;     // max trains shown in action bar

    /** Entry point — call from TrainEventHandler once per server tick. */
    public static void tickAll(ServerLevel level, long currentTick) {
        if (currentTick % TICK_INTERVAL != 0) return;

        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return;

        Collection<TrainAIController> controllers = manager.getAllControllers();
        if (controllers.isEmpty()) return;

        // Pre-fetch VBS midpoints once for all players
        Map<UUID, List<BlockPos>> vbsMidpoints =
                VirtualBlockSystem.getInstance().getReservationMidpoints();

        for (ServerPlayer player : level.players()) {
            if (!isWearingGoggles(player)) continue;
            renderForPlayer(player, level, controllers, vbsMidpoints);
        }
    }

    // ─── Per-player render ───

    private static void renderForPlayer(ServerPlayer player,
                                         ServerLevel level,
                                         Collection<TrainAIController> controllers,
                                         Map<UUID, List<BlockPos>> vbsMidpoints) {
        BlockPos pPos = player.blockPosition();
        List<TrainAIController> nearby = new java.util.ArrayList<>();

        for (TrainAIController ctrl : controllers) {
            BlockPos tPos = ctrl.getCurrentPosition();
            if (tPos == null) continue;
            if (pPos.distSqr(tPos) > RANGE_SQ) continue;
            nearby.add(ctrl);

            // ── Train position dot ──
            level.sendParticles(player, ParticleTypes.ELECTRIC_SPARK,
                    true,
                    tPos.getX() + 0.5, tPos.getY() + 2.0, tPos.getZ() + 0.5,
                    3, 0.3, 0.2, 0.3, 0.0);

            // ── TrackGraph status halo ──
            int sigCount = ctrl.getSignalGroups().size();
            boolean hasEdge = ctrl.getLeadingEdge() != null || ctrl.getTrailingEdge() != null;
            net.minecraft.core.particles.ParticleOptions haloParticle;
            if (sigCount > 0) {
                haloParticle = ParticleTypes.HAPPY_VILLAGER;
            } else if (hasEdge) {
                haloParticle = ParticleTypes.DRIPPING_HONEY;
            } else {
                haloParticle = ParticleTypes.CLOUD;
            }
            double[] dx = {1.2, 0, -1.2, 0};
            double[] dz = {0, 1.2, 0, -1.2};
            for (int i = 0; i < 4; i++) {
                level.sendParticles(player, haloParticle,
                        true,
                        tPos.getX() + 0.5 + dx[i], tPos.getY() + 1.5, tPos.getZ() + 0.5 + dz[i],
                        1, 0, 0, 0, 0.01);
            }

            // ── VBS footprint (orange FLAME particles) ──
            List<BlockPos> reserved = vbsMidpoints.get(ctrl.getTrainId());
            if (reserved != null) {
                for (BlockPos rp : reserved) {
                    if (pPos.distSqr(rp) > RANGE_SQ) continue;
                    level.sendParticles(player, ParticleTypes.FLAME,
                            true,
                            rp.getX() + 0.5, rp.getY() + 0.5, rp.getZ() + 0.5,
                            1, 0.15, 0.1, 0.15, 0.01);
                }
            }

            // ── Heading vector (END_ROD particles — 8 blocks ahead) ──
            double hx = ctrl.getHeadingX();
            double hz = ctrl.getHeadingZ();
            if (hx != 0 || hz != 0) {
                for (int step = 2; step <= 8; step += 2) {
                    level.sendParticles(player, ParticleTypes.END_ROD,
                            true,
                            tPos.getX() + 0.5 + hx * step,
                            tPos.getY() + 1.0,
                            tPos.getZ() + 0.5 + hz * step,
                            1, 0, 0, 0, 0.0);
                }
            }

            // ── Danger zone (SOUL_FIRE_FLAME when graph-detected obstacle is close) ──
            if (ctrl.isGraphScanActive() && ctrl.getGraphDistanceToObstacle() > 0
                    && ctrl.getGraphDistanceToObstacle() <= 15) {
                for (int step = 3; step <= 12; step += 3) {
                    level.sendParticles(player, ParticleTypes.SOUL_FIRE_FLAME,
                            true,
                            tPos.getX() + 0.5 + hx * step,
                            tPos.getY() + 0.6,
                            tPos.getZ() + 0.5 + hz * step,
                            2, 0.2, 0.1, 0.2, 0.01);
                }
            }
        }

        // ── Same-track connection lines ──
        for (int i = 0; i < nearby.size(); i++) {
            for (int j = i + 1; j < nearby.size(); j++) {
                TrainAIController a = nearby.get(i);
                TrainAIController b = nearby.get(j);
                if (!a.isOnSameTrack(b)) continue;
                BlockPos aPos = a.getCurrentPosition();
                BlockPos bPos = b.getCurrentPosition();
                if (aPos == null || bPos == null) continue;
                double ax = aPos.getX() + 0.5, ay = aPos.getY() + 1.8, az = aPos.getZ() + 0.5;
                double bx = bPos.getX() + 0.5, by = bPos.getY() + 1.8, bz = bPos.getZ() + 0.5;
                double len = Math.sqrt((bx-ax)*(bx-ax) + (by-ay)*(by-ay) + (bz-az)*(bz-az));
                int steps = Math.max(2, (int)(len / 3.0));
                for (int s = 1; s < steps; s++) {
                    double t = (double) s / steps;
                    level.sendParticles(player, ParticleTypes.WITCH,
                            true,
                            ax + (bx-ax)*t, ay + (by-ay)*t, az + (bz-az)*t,
                            1, 0, 0, 0, 0.01);
                }
            }
        }

        // ── Action bar ──
        // Sort nearby trains by distance to player, take the closest MAX_BAR_TRAINS.
        // Each train gets a compact single-entry: "ID:STATE spd [GRFdist]"
        // This keeps the action-bar line short enough to fit on any screen.
        List<TrainAIController> barTrains = nearby.stream()
                .filter(c -> c.getCurrentPosition() != null
                          && pPos.distSqr(c.getCurrentPosition()) <= BAR_RANGE_SQ)
                .sorted(java.util.Comparator.comparingDouble(
                        c -> pPos.distSqr(c.getCurrentPosition())))
                .limit(MAX_BAR_TRAINS)
                .collect(Collectors.toList());

        if (barTrains.isEmpty()) return;

        int total = (int) nearby.stream()
                .filter(c -> c.getCurrentPosition() != null
                          && pPos.distSqr(c.getCurrentPosition()) <= BAR_RANGE_SQ)
                .count();
        int hidden = total - barTrains.size();

        StringBuilder bar = new StringBuilder("§b[AI]");
        for (TrainAIController ctrl : barTrains) {
            String stateColor = stateColorCode(ctrl.getCurrentState());
            String stateAbbr  = abbreviate(ctrl.getCurrentState().name());
            // Compact format: " ID:STATE spd" + optional graph dist
            bar.append(" §e")
               .append(ctrl.getTrainId().toString(), 0, 4)
               .append("§f:")
               .append(stateColor).append(stateAbbr)
               .append(String.format(" §7%.1f", ctrl.getCurrentSpeed()));
            if (ctrl.isGraphScanActive() && ctrl.getGraphDistanceToObstacle() > 0) {
                bar.append(String.format("§d%.0fb", ctrl.getGraphDistanceToObstacle()));
            }
            if (barTrains.indexOf(ctrl) < barTrains.size() - 1) {
                bar.append("§8|");
            }
        }
        if (hidden > 0) {
            bar.append("§8 +").append(hidden);
        }

        player.displayClientMessage(Component.literal(bar.toString()), true);
    }

    // ─── Helpers ───

    private static boolean isWearingGoggles(ServerPlayer player) {
        // Helmet slot index 3 in the 4-slot armor inventory (boots=0, leggings=1, chest=2, helmet=3)
        ItemStack helmet = player.getInventory().armor.get(3);
        return !helmet.isEmpty() && helmet.getItem() == ModItems.AI_GOGGLES.get();
    }

    private static String stateColorCode(com.fizzylovely.railwayevolution.ai.TrainState state) {
        return switch (state) {
            case CRUISING           -> "§a";
            case ANALYZING_OBSTACLE -> "§e";
            case YIELDING           -> "§c";
            case BYPASSING          -> "§d";
            case RETURNING          -> "§b";
            case REVERSING          -> "§6";
            case TRAFFIC_JAM        -> "§4";
            case WAIT_FOR_CLEARANCE -> "§5";
        };
    }

    private static String abbreviate(String name) {
        // e.g. ANALYZING_OBSTACLE → ANA, CRUISING → CRS, YIELDING → YLD
        return switch (name) {
            case "CRUISING"            -> "CRS";
            case "ANALYZING_OBSTACLE"  -> "ANA";
            case "BYPASSING"           -> "BYP";
            case "YIELDING"            -> "YLD";
            case "RETURNING"           -> "RET";
            case "REVERSING"           -> "REV";
            case "TRAFFIC_JAM"         -> "JAM";
            case "WAIT_FOR_CLEARANCE" -> "WFC";
            default -> name.length() > 3 ? name.substring(0, 3) : name;
        };
    }
}
