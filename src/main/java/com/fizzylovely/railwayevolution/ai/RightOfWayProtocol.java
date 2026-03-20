package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * Right-of-Way Protocol — manages yielding behavior and pocket/siding logic.
 *
 * Протокол преимущества движения / Right-of-Way Protocol:
 * ────────────────────────────────────────────────────────
 *
 * Core principle: The LANE OWNER (train on its correct track) always has
 * right-of-way over a BYPASSING train (on the oncoming track).
 *
 * Yielding mechanics:
 *   1. When a bypassing train detects an oncoming lane-owner within X blocks:
 *      → The bypassing train enters YIELDING state.
 *      → It searches for the nearest "pocket" (siding/passing loop).
 *      → It pulls into the pocket and stops.
 *      → It waits until the lane-owner passes.
 *      → It resumes its bypass or returns to original lane.
 *
 *   2. A "pocket" is a short siding track connected via switch:
 *      → Must be long enough to fit the train + safety margin.
 *      → Must have a clear entry and exit.
 *      → The train fully clears the main line before stopping.
 *
 *   3. If no pocket is available:
 *      → The bypassing train stops at the nearest switch.
 *      → It backs up if needed to clear the segment for the oncoming train.
 *
 *   4. Maximum yield time: if a train yields for > N ticks,
 *      its priority escalates (anti-starvation via PriorityCalculator).
 */
public class RightOfWayProtocol {

    /**
     * Result of a yield evaluation — what the bypassing train should do.
     */
    public enum YieldAction {
        /** No yielding needed — oncoming track is clear. */
        CONTINUE,
        /** Pull into the nearest pocket and wait. */
        PULL_INTO_POCKET,
        /** Stop at the nearest switch (no pocket available). */
        STOP_AT_SWITCH,
        /** Emergency stop — oncoming train too close, no time to reach pocket. */
        EMERGENCY_STOP,
        /** Back up to clear the segment. */
        BACK_UP
    }

    /**
     * Evaluate what a bypassing train should do given oncoming traffic.
     *
     * @param trainId           The bypassing train's UUID
     * @param trainPos          Current position of the bypassing train
     * @param trainLength       Length of the train in blocks
     * @param oncomingPos       Position of the detected oncoming train
     * @param oncomingSpeed     Speed of the oncoming train (blocks/tick)
     * @param nearbyPockets     List of pocket/siding positions nearby
     * @param nearbySwitches    List of switch positions nearby
     * @return                  YieldAction indicating what to do
     */
    public static YieldAction evaluateYield(UUID trainId, BlockPos trainPos, int trainLength,
                                             BlockPos oncomingPos, double oncomingSpeed,
                                             List<BlockPos> nearbyPockets,
                                             List<OvertakingAnalyzer.SwitchInfo> nearbySwitches) {

        double distToOncoming = distance(trainPos, oncomingPos);
        double emergencyDist = RailwayConfig.emergencyStopDistance.get();
        double critDist = RailwayConfig.criticalYieldDistance.get();

        // Emergency: oncoming train is dangerously close
        if (distToOncoming <= emergencyDist) {
            CreateRailwayMod.LOGGER.warn("[RightOfWay] EMERGENCY STOP for train {} — oncoming at {} blocks",
                    trainId.toString().substring(0, 8), distToOncoming);
            return YieldAction.EMERGENCY_STOP;
        }

        // Check if we can reach a pocket in time
        if (nearbyPockets != null && !nearbyPockets.isEmpty()) {
            BlockPos nearestPocket = findNearestReachablePocket(trainPos, trainLength,
                    oncomingPos, oncomingSpeed, nearbyPockets);

            if (nearestPocket != null) {
                CreateRailwayMod.LOGGER.debug("[RightOfWay] Train {} pulling into pocket at {}",
                        trainId.toString().substring(0, 8), nearestPocket.toShortString());
                return YieldAction.PULL_INTO_POCKET;
            }
        }

        // No pocket — can we reach a switch?
        if (nearbySwitches != null && !nearbySwitches.isEmpty()) {
            for (OvertakingAnalyzer.SwitchInfo sw : nearbySwitches) {
                double distToSwitch = distance(trainPos, sw.position);
                if (distToSwitch < distToOncoming * 0.5) {
                    return YieldAction.STOP_AT_SWITCH;
                }
            }
        }

        // Nothing nearby — need to back up or emergency stop
        if (distToOncoming <= critDist) {
            return YieldAction.BACK_UP;
        }

        return YieldAction.CONTINUE;
    }

    /**
     * Check if a bypassing train should resume after yielding.
     *
     * @param trainId       The yielding train
     * @param trainPos      Current position
     * @param scanDistance   How far ahead to scan on the oncoming track
     * @param currentTick   Current server tick
     * @return              true if it's safe to resume
     */
    public static boolean canResumeAfterYield(UUID trainId, BlockPos trainPos,
                                               double scanDistance, long currentTick) {
        // Check VBS: are the segments ahead on the oncoming track free?
        VirtualBlockSystem vbs = VirtualBlockSystem.getInstance();

        // Simplified: check the immediate segment ahead
        // In full implementation, this scans multiple segments along the oncoming path
        BlockPos ahead = trainPos.offset((int) scanDistance, 0, 0);
        return !vbs.isSegmentReserved(trainPos, ahead, currentTick);
    }

    /**
     * Calculate the required minimum pocket length for a train.
     * Pocket must fit the entire train + safety buffers on both sides.
     *
     * @param trainLengthBlocks Length of the train in blocks
     * @return                  Required pocket length in blocks
     */
    public static int requiredPocketLength(int trainLengthBlocks) {
        int safetyBuffer = RailwayConfig.pocketSafetyBuffer.get();
        return trainLengthBlocks + safetyBuffer * 2;
    }

    // ─── Helpers ───

    private static BlockPos findNearestReachablePocket(BlockPos trainPos, int trainLength,
                                                        BlockPos oncomingPos, double oncomingSpeed,
                                                        List<BlockPos> pockets) {
        double distToOncoming = distance(trainPos, oncomingPos);
        // Estimate time until oncoming train reaches us (in ticks)
        double ticksUntilCollision = oncomingSpeed > 0 ? distToOncoming / oncomingSpeed : Double.MAX_VALUE;

        int requiredLen = requiredPocketLength(trainLength);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos pocket : pockets) {
            double distToPocket = distance(trainPos, pocket);

            // Rough check: can we reach the pocket before the oncoming train reaches us?
            // Assume our speed ~= 0.5 blocks/tick (will be replaced with actual speed)
            double ticksToReach = distToPocket / 0.5;

            if (ticksToReach < ticksUntilCollision * 0.7 && distToPocket < bestDist) {
                best = pocket;
                bestDist = distToPocket;
            }
        }
        return best;
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
