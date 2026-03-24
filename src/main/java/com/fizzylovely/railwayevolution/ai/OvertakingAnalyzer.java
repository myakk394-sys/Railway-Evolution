package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Overtaking Analyzer — evaluates and executes bypass maneuvers.
 *
 * Логика обгона / Overtaking Logic:
 * ──────────────────────────────────
 * When a train encounters an obstacle on its current track, this analyzer
 * determines whether a bypass via the oncoming track is feasible.
 *
 * Bypass feasibility check (all must pass):
 *   1. SWITCH AVAILABILITY: There must be a switch/turnout within range
 *      that connects the current track to the oncoming track.
 *   2. ONCOMING TRACK FREE: The oncoming track must be free of reserved
 *      segments for the entire bypass distance.
 *   3. RETURN SWITCH: There must be a switch after the obstacle to return
 *      to the original lane.
 *   4. NO ONCOMING TRAFFIC: No trains detected on the oncoming track
 *      within the safety distance (X blocks look-ahead).
 *   5. BYPASS LENGTH: The bypass distance must not exceed the configured
 *      maximum (to prevent excessively long wrong-way travel).
 *
 * Bypass execution:
 *   1. Reserve all segments of the bypass route.
 *   2. Switch the entry turnout.
 *   3. Travel on the oncoming track past the obstacle.
 *   4. Continuously scan for oncoming trains (→ YIELDING if detected).
 *   5. Switch the exit turnout and return to original lane.
 *   6. Release all oncoming-track reservations.
 */
public class OvertakingAnalyzer {

    /**
     * Result of a bypass feasibility analysis.
     */
    public static class BypassAnalysis {
        public final boolean feasible;
        public final BlockPos entrySwitchPos;
        public final BlockPos exitSwitchPos;
        public final List<BlockPos[]> bypassSegments;
        public final double bypassDistance;
        public final String denyReason;

        private BypassAnalysis(boolean feasible, BlockPos entrySwitch, BlockPos exitSwitch,
                               List<BlockPos[]> bypassSegments, double bypassDistance, String denyReason) {
            this.feasible = feasible;
            this.entrySwitchPos = entrySwitch;
            this.exitSwitchPos = exitSwitch;
            this.bypassSegments = bypassSegments != null ? bypassSegments : Collections.emptyList();
            this.bypassDistance = bypassDistance;
            this.denyReason = denyReason;
        }

        public static BypassAnalysis feasible(BlockPos entrySwitch, BlockPos exitSwitch,
                                               List<BlockPos[]> segments, double distance) {
            return new BypassAnalysis(true, entrySwitch, exitSwitch, segments, distance, null);
        }

        public static BypassAnalysis denied(String reason) {
            return new BypassAnalysis(false, null, null, null, 0, reason);
        }
    }

    /**
     * Analyze whether a bypass maneuver is feasible from the current position.
     *
     * This method inspects the track graph around the obstacle to find:
     *   - Nearest entry switch (turnout to oncoming track)
     *   - Nearest exit switch (turnout back to correct lane)
     *   - All segments on the oncoming track between the switches
     *
     * @param trainId          UUID of the train wanting to bypass
     * @param currentPos       Current position of the train
     * @param obstaclePos      Position of the detected obstacle
     * @param trainDirection   Direction the train is facing (true=forward along track)
     * @param nearbySwitches   List of known switch/turnout positions in range
     * @param currentTick      Current server tick
     * @return                 BypassAnalysis with feasibility result
     */
    public static BypassAnalysis analyzeBypass(UUID trainId, BlockPos currentPos,
                                                BlockPos obstaclePos, boolean trainDirection,
                                                List<SwitchInfo> nearbySwitches,
                                                long currentTick) {
        if (nearbySwitches == null || nearbySwitches.isEmpty()) {
            return BypassAnalysis.denied("NO_SWITCHES_IN_RANGE");
        }

        double maxBypassDist = RailwayConfig.maxBypassDistance.get();

        // Step 1: Find the nearest entry switch BEFORE the obstacle
        SwitchInfo entrySwitch = findNearestSwitchBefore(currentPos, obstaclePos, nearbySwitches);
        if (entrySwitch == null) {
            return BypassAnalysis.denied("NO_ENTRY_SWITCH_BEFORE_OBSTACLE");
        }

        // Step 2: Find the nearest exit switch AFTER the obstacle
        SwitchInfo exitSwitch = findNearestSwitchAfter(obstaclePos, nearbySwitches, trainDirection);
        if (exitSwitch == null) {
            return BypassAnalysis.denied("NO_EXIT_SWITCH_AFTER_OBSTACLE");
        }

        // Step 3: Calculate bypass distance
        double bypassDist = distance(entrySwitch.position, exitSwitch.position);
        if (bypassDist > maxBypassDist) {
            return BypassAnalysis.denied("BYPASS_TOO_LONG: " + bypassDist + " > " + maxBypassDist);
        }

        // Step 4: Build the bypass segment path on the oncoming track
        List<BlockPos[]> bypassSegments = buildBypassPath(entrySwitch, exitSwitch);

        // Step 5: Check VBS — are the oncoming segments free?
        VirtualBlockSystem vbs = VirtualBlockSystem.getInstance();
        for (BlockPos[] seg : bypassSegments) {
            if (vbs.isSegmentReserved(seg[0], seg[1], currentTick)) {
                UUID owner = vbs.getSegmentOwner(seg[0], seg[1], currentTick);
                if (owner != null && !owner.equals(trainId)) {
                    return BypassAnalysis.denied("ONCOMING_TRACK_OCCUPIED_BY_" + owner.toString().substring(0, 8));
                }
            }
        }

        CreateRailwayMod.LOGGER.info("[Overtaking] Bypass feasible for train {}: entry={}, exit={}, dist={}",
                trainId.toString().substring(0, 8),
                entrySwitch.position.toShortString(),
                exitSwitch.position.toShortString(),
                bypassDist);

        return BypassAnalysis.feasible(entrySwitch.position, exitSwitch.position,
                bypassSegments, bypassDist);
    }

    /**
     * Find the nearest return switch when a train is on the oncoming track
     * and needs to merge back to its original lane.
     *
     * Called continuously while in RETURNING state.
     *
     * @param currentPos     Current train position on the oncoming track
     * @param trainDirection Travel direction
     * @param switches       Available switches ahead
     * @param currentTick    Current server tick
     * @return               Position of the nearest available return switch, or null
     */
    public static BlockPos findReturnSwitch(BlockPos currentPos, boolean trainDirection,
                                             List<SwitchInfo> switches, long currentTick) {
        VirtualBlockSystem vbs = VirtualBlockSystem.getInstance();

        SwitchInfo best = null;
        double bestDist = Double.MAX_VALUE;

        for (SwitchInfo sw : switches) {
            if (!sw.connectsToOriginalLane) continue;

            double dist = distance(currentPos, sw.position);
            if (dist < bestDist) {
                // Check that the segment back to original lane is free
                if (!vbs.isSegmentReserved(sw.position, sw.originalLaneConnectionPos, currentTick)) {
                    best = sw;
                    bestDist = dist;
                }
            }
        }

        return best != null ? best.position : null;
    }

    /**
     * Reserve all segments of a bypass route in the VBS.
     *
     * @return true if all segments were successfully reserved
     */
    public static boolean reserveBypassRoute(UUID trainId, BypassAnalysis analysis, long currentTick) {
        VirtualBlockSystem vbs = VirtualBlockSystem.getInstance();

        // Try to reserve all, rollback on failure
        List<BlockPos[]> reserved = new ArrayList<>();
        for (BlockPos[] seg : analysis.bypassSegments) {
            VirtualBlockSystem.ReservationResult result = vbs.tryReserve(
                    trainId, seg[0], seg[1], true, currentTick);

            if (result != VirtualBlockSystem.ReservationResult.GRANTED) {
                // Rollback
                for (BlockPos[] prev : reserved) {
                    vbs.releaseSegment(trainId, prev[0], prev[1]);
                }
                return false;
            }
            reserved.add(seg);
        }
        return true;
    }

    // ─── Helper methods ───

    private static SwitchInfo findNearestSwitchBefore(BlockPos trainPos, BlockPos obstaclePos,
                                                       List<SwitchInfo> switches) {
        SwitchInfo best = null;
        double bestDist = Double.MAX_VALUE;

        for (SwitchInfo sw : switches) {
            double distToTrain = distance(trainPos, sw.position);
            double distToObstacle = distance(sw.position, obstaclePos);
            double trainToObstacle = distance(trainPos, obstaclePos);

            // Switch must be between train and obstacle (closer to train)
            if (distToTrain + distToObstacle <= trainToObstacle * 1.2 && distToTrain < bestDist) {
                best = sw;
                bestDist = distToTrain;
            }
        }
        return best;
    }

    private static SwitchInfo findNearestSwitchAfter(BlockPos obstaclePos,
                                                      List<SwitchInfo> switches,
                                                      boolean direction) {
        SwitchInfo best = null;
        double bestDist = Double.MAX_VALUE;

        for (SwitchInfo sw : switches) {
            double dist = distance(obstaclePos, sw.position);
            // Switch must be "after" the obstacle relative to travel direction
            boolean isAfter = direction
                    ? sw.position.getX() > obstaclePos.getX() || sw.position.getZ() > obstaclePos.getZ()
                    : sw.position.getX() < obstaclePos.getX() || sw.position.getZ() < obstaclePos.getZ();

            if (isAfter && dist < bestDist) {
                best = sw;
                bestDist = dist;
            }
        }
        return best;
    }

    private static List<BlockPos[]> buildBypassPath(SwitchInfo entrySwitch, SwitchInfo exitSwitch) {
        // Simplified: create a straight bypass from entry to exit
        // In real implementation, this would query the track graph for actual edges
        List<BlockPos[]> path = new ArrayList<>();
        path.add(new BlockPos[]{entrySwitch.position, exitSwitch.position});
        return path;
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Information about a switch/turnout on the track.
     * Populated by querying the Create Mod TrackGraph API.
     */
    public static class SwitchInfo {
        public final BlockPos position;
        public final boolean connectsToOriginalLane;
        public final BlockPos originalLaneConnectionPos;

        public SwitchInfo(BlockPos position, boolean connectsToOriginalLane,
                          BlockPos originalLaneConnectionPos) {
            this.position = position;
            this.connectsToOriginalLane = connectsToOriginalLane;
            this.originalLaneConnectionPos = originalLaneConnectionPos;
        }
    }
}
