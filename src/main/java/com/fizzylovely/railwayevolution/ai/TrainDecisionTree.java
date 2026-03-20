package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * Train Decision Tree — the core flowchart logic that drives state transitions.
 *
 * Дерево принятия решений / Decision Tree:
 * ─────────────────────────────────────────
 *
 *                         ┌──────────┐
 *                         │ CRUISING │
 *                         └────┬─────┘
 *                              │
 *                    ┌─────────▼─────────┐
 *                    │ Obstacle detected? │
 *                    └───┬───────────┬───┘
 *                       YES          NO
 *                        │            └──→ stay CRUISING
 *               ┌────────▼────────┐
 *               │ ANALYZING       │
 *               │ OBSTACLE        │
 *               └──┬──────────┬───┘
 *                  │          │
 *        ┌─────────▼───┐  ┌──▼──────────────┐
 *        │Can bypass?  │  │Obstacle cleared? │
 *        └──┬──────┬───┘  └──┬───────────────┘
 *          YES     NO       YES → CRUISING
 *           │      │
 *   ┌───────▼──┐  ┌▼────────┐
 *   │ Reserve  │  │ YIELDING│ (wait behind obstacle)
 *   │ bypass   │  └─────────┘
 *   │ route    │
 *   └──┬───┬──┘
 *     OK  FAIL → YIELDING
 *      │
 *  ┌───▼──────┐
 *  │BYPASSING │
 *  └──┬───┬───┘
 *     │   │
 *  ┌──▼───▼────────────────┐
 *  │Oncoming train detected│
 *  │within X blocks?       │
 *  └──┬────────────────┬───┘
 *    YES               NO
 *     │                 │
 *  ┌──▼──────┐   ┌─────▼──────────┐
 *  │YIELDING │   │Obstacle passed?│
 *  │(pocket) │   └──┬─────────┬───┘
 *  └─────────┘     YES        NO → continue BYPASSING
 *                   │
 *              ┌────▼─────┐
 *              │RETURNING │
 *              └──┬───┬───┘
 *                 │   │
 *     ┌───────────▼┐ ┌▼──────────────────┐
 *     │Return      │ │Oncoming detected? │
 *     │switch found│ └──┬────────────────┘
 *     └──┬─────────┘   YES → YIELDING
 *       YES
 *        │
 *  ┌─────▼───┐
 *  │CRUISING │ (returned to original lane)
 *  └─────────┘
 */
public class TrainDecisionTree {

    /**
     * Decision output from one tick of the decision tree.
     */
    public static class Decision {
        public final TrainState newState;
        public final double targetSpeed;      // desired speed (-1 = maintain current)
        public final BlockPos targetSwitchPos; // switch to aim for (nullable)
        public final String reason;            // human-readable reason for the decision

        public Decision(TrainState newState, double targetSpeed, BlockPos targetSwitchPos, String reason) {
            this.newState = newState;
            this.targetSpeed = targetSpeed;
            this.targetSwitchPos = targetSwitchPos;
            this.reason = reason;
        }

        public static Decision maintain(TrainState state) {
            return new Decision(state, -1, null, "maintain");
        }

        public static Decision transition(TrainState newState, String reason) {
            return new Decision(newState, -1, null, reason);
        }

        public static Decision transitionWithSpeed(TrainState newState, double speed, String reason) {
            return new Decision(newState, speed, null, reason);
        }

        public static Decision transitionToSwitch(TrainState newState, BlockPos switchPos, String reason) {
            return new Decision(newState, -1, switchPos, reason);
        }
    }

    /**
     * Run one tick of the decision tree for a train.
     *
     * @param ctx Current context of the train (position, speed, state, surroundings)
     * @return    Decision indicating state transition (or maintain)
     */
    public static Decision evaluate(TrainContext ctx) {
        switch (ctx.currentState) {
            case CRUISING:
                return evaluateCruising(ctx);
            case ANALYZING_OBSTACLE:
                return evaluateAnalyzing(ctx);
            case BYPASSING:
                return evaluateBypassing(ctx);
            case YIELDING:
                return evaluateYielding(ctx);
            case RETURNING:
                return evaluateReturning(ctx);
            case REVERSING:
                return evaluateReversing(ctx);
            case TRAFFIC_JAM:
                return Decision.maintain(TrainState.TRAFFIC_JAM);
            case WAIT_FOR_CLEARANCE:
                return Decision.maintain(TrainState.WAIT_FOR_CLEARANCE);
            default:
                return Decision.maintain(ctx.currentState);
        }
    }

    // ─── CRUISING state logic ───

    private static Decision evaluateCruising(TrainContext ctx) {
        double detectionRange = RailwayConfig.obstacleDetectionRange.get();

        // Scan ahead for obstacles
        if (ctx.obstacleDetected && ctx.distToObstacle <= detectionRange) {
            CreateRailwayMod.LOGGER.info("[DecisionTree] Train {} detected obstacle at {} blocks",
                    ctx.trainId.toString().substring(0, 8), ctx.distToObstacle);
            return Decision.transitionWithSpeed(TrainState.ANALYZING_OBSTACLE,
                    ctx.currentSpeed * 0.5, "obstacle_detected_decelerating");
        }

        // Scan VBS for upcoming segment conflicts
        if (ctx.upcomingConflict != null) {
            CreateRailwayMod.LOGGER.debug("[DecisionTree] Train {} upcoming segment conflict",
                    ctx.trainId.toString().substring(0, 8));
            return Decision.transitionWithSpeed(TrainState.ANALYZING_OBSTACLE,
                    ctx.currentSpeed * 0.7, "segment_conflict_ahead");
        }

        return Decision.maintain(TrainState.CRUISING);
    }

    // ─── ANALYZING_OBSTACLE state logic ───

    private static Decision evaluateAnalyzing(TrainContext ctx) {
        // Check if obstacle cleared (false alarm or obstacle moved)
        if (!ctx.obstacleDetected) {
            return Decision.transition(TrainState.CRUISING, "obstacle_cleared");
        }

        // Analyze bypass feasibility
        OvertakingAnalyzer.BypassAnalysis bypass = OvertakingAnalyzer.analyzeBypass(
                ctx.trainId, ctx.position, ctx.obstaclePosition,
                ctx.trainDirection, ctx.nearbySwitches, ctx.currentTick);

        if (bypass.feasible) {
            // Try to reserve the bypass route in VBS
            boolean reserved = OvertakingAnalyzer.reserveBypassRoute(
                    ctx.trainId, bypass, ctx.currentTick);

            if (reserved) {
                CreateRailwayMod.LOGGER.info("[DecisionTree] Train {} initiating bypass via switch at {}",
                        ctx.trainId.toString().substring(0, 8),
                        bypass.entrySwitchPos.toShortString());
                return Decision.transitionToSwitch(TrainState.BYPASSING,
                        bypass.entrySwitchPos, "bypass_route_reserved");
            } else {
                CreateRailwayMod.LOGGER.debug("[DecisionTree] Train {} bypass reservation failed",
                        ctx.trainId.toString().substring(0, 8));
            }
        }

        // Cannot bypass — yield (wait behind obstacle)
        return Decision.transitionWithSpeed(TrainState.YIELDING, 0,
                "cannot_bypass_" + (bypass.denyReason != null ? bypass.denyReason : "reservation_failed"));
    }

    // ─── BYPASSING state logic ───

    private static Decision evaluateBypassing(TrainContext ctx) {
        // Priority check: detect oncoming trains
        if (ctx.oncomingTrainDetected) {
            RightOfWayProtocol.YieldAction yieldAction = RightOfWayProtocol.evaluateYield(
                    ctx.trainId, ctx.position, ctx.trainLength,
                    ctx.oncomingTrainPos, ctx.oncomingTrainSpeed,
                    ctx.nearbyPockets, ctx.nearbySwitches);

            switch (yieldAction) {
                case EMERGENCY_STOP:
                    return Decision.transitionWithSpeed(TrainState.YIELDING, 0,
                            "emergency_stop_oncoming_too_close");
                case PULL_INTO_POCKET:
                    return Decision.transitionWithSpeed(TrainState.YIELDING, ctx.currentSpeed * 0.3,
                            "pulling_into_pocket");
                case STOP_AT_SWITCH:
                    return Decision.transitionWithSpeed(TrainState.YIELDING, 0,
                            "stopping_at_switch_for_oncoming");
                case BACK_UP:
                    return Decision.transitionWithSpeed(TrainState.YIELDING, -0.3,
                            "backing_up_for_oncoming");
                case CONTINUE:
                    break; // oncoming is far enough, continue bypass
            }
        }

        // Check if obstacle has been passed
        if (ctx.obstaclePosition != null && ctx.position != null) {
            boolean passed = hasPassedObstacle(ctx.position, ctx.obstaclePosition, ctx.trainDirection);
            if (passed) {
                CreateRailwayMod.LOGGER.info("[DecisionTree] Train {} passed obstacle, searching for return switch",
                        ctx.trainId.toString().substring(0, 8));
                return Decision.transition(TrainState.RETURNING, "obstacle_passed_searching_return");
            }
        }

        return Decision.maintain(TrainState.BYPASSING);
    }

    // ─── YIELDING state logic ───

    private static Decision evaluateYielding(TrainContext ctx) {
        // Check if path has cleared
        boolean pathClear = !ctx.obstacleDetected && !ctx.oncomingTrainDetected;

        if (pathClear) {
            // If we were on the oncoming track, decide whether to continue bypass or return
            if (ctx.wasOnOppositeTrack) {
                boolean canResume = RightOfWayProtocol.canResumeAfterYield(
                        ctx.trainId, ctx.position,
                        RailwayConfig.oncomingSafetyDistance.get(),
                        ctx.currentTick);
                if (canResume) {
                    // Check if obstacle is still ahead
                    if (ctx.obstacleDetected) {
                        return Decision.transition(TrainState.BYPASSING, "resume_bypass_after_yield");
                    } else {
                        return Decision.transition(TrainState.RETURNING, "yield_complete_returning");
                    }
                }
            } else {
                return Decision.transition(TrainState.CRUISING, "path_clear_resume_cruising");
            }
        }

        // Anti-starvation: if yielding too long, escape via reverse maneuver
        long yieldTimeout = RailwayConfig.maxYieldTicks.get();
        if (ctx.ticksInCurrentState > yieldTimeout) {
            boolean reverseEnabled = RailwayConfig.reverseManeuverEnabled.get();
            if (reverseEnabled && ctx.spaceAvailableBehind) {
                CreateRailwayMod.LOGGER.warn("[DecisionTree] Train {} yield timeout \u2192 REVERSING to reroute",
                        ctx.trainId.toString().substring(0, 8));
                return Decision.transitionWithSpeed(TrainState.REVERSING, 0, "yield_timeout_reversing");
            }
            CreateRailwayMod.LOGGER.warn("[DecisionTree] Train {} yield timeout exceeded, cannot reverse (blocked behind)",
                    ctx.trainId.toString().substring(0, 8));
        }

        return Decision.maintain(TrainState.YIELDING);
    }

    // ─── RETURNING state logic ───

    private static Decision evaluateReturning(TrainContext ctx) {
        // Search for the nearest switch to return to original lane
        BlockPos returnSwitch = OvertakingAnalyzer.findReturnSwitch(
                ctx.position, ctx.trainDirection,
                ctx.nearbySwitches, ctx.currentTick);

        if (returnSwitch != null) {
            double distToSwitch = distance(ctx.position, returnSwitch);
            if (distToSwitch <= 3.0) {
                // We're at the switch — execute return
                CreateRailwayMod.LOGGER.info("[DecisionTree] Train {} returning to original lane at {}",
                        ctx.trainId.toString().substring(0, 8), returnSwitch.toShortString());
                // Release oncoming track reservations
                VirtualBlockSystem.getInstance().releaseAll(ctx.trainId);
                return Decision.transitionToSwitch(TrainState.CRUISING,
                        returnSwitch, "returned_to_original_lane");
            } else {
                // Head toward the return switch
                return Decision.transitionToSwitch(TrainState.RETURNING,
                        returnSwitch, "heading_to_return_switch");
            }
        }

        // Still on oncoming track — check for oncoming trains
        if (ctx.oncomingTrainDetected) {
            RightOfWayProtocol.YieldAction yieldAction = RightOfWayProtocol.evaluateYield(
                    ctx.trainId, ctx.position, ctx.trainLength,
                    ctx.oncomingTrainPos, ctx.oncomingTrainSpeed,
                    ctx.nearbyPockets, ctx.nearbySwitches);

            if (yieldAction != RightOfWayProtocol.YieldAction.CONTINUE) {
                return Decision.transitionWithSpeed(TrainState.YIELDING, 0,
                        "yield_while_returning_for_oncoming");
            }
        }

        return Decision.maintain(TrainState.RETURNING);
    }

    // ─── REVERSING state logic ───

    private static Decision evaluateReversing(TrainContext ctx) {
        double backedUp = ctx.reverseBackedDistance;

        // Handed back to navigation once we've backed up enough
        if (backedUp >= RailwayConfig.reverseBackupDistance.get()) {
            return Decision.transition(TrainState.CRUISING,
                    "reverse_complete_rerouting_dist=" + (int) backedUp);
        }

        // After initial movement, yield if something is now in the reverse path
        if (backedUp > 2.0 && ctx.obstacleDetected) {
            return Decision.transitionWithSpeed(TrainState.YIELDING, 0,
                    "blocked_in_reverse_path");
        }

        // Continue reversing at configured speed (handled by TrainAIController)
        return Decision.maintain(TrainState.REVERSING);
    }

    // ─── Helpers ───

    private static boolean hasPassedObstacle(BlockPos trainPos, BlockPos obstaclePos, boolean forward) {
        if (forward) {
            return trainPos.getX() > obstaclePos.getX() + 5
                    || trainPos.getZ() > obstaclePos.getZ() + 5;
        } else {
            return trainPos.getX() < obstaclePos.getX() - 5
                    || trainPos.getZ() < obstaclePos.getZ() - 5;
        }
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Context snapshot for a train at the moment of decision.
     * Populated by TrainAIController each tick.
     */
    public static class TrainContext {
        public UUID trainId;
        public TrainState currentState;
        public BlockPos position;
        public double currentSpeed;
        public double maxSpeed;
        public boolean trainDirection; // true = forward
        public int trainLength;        // carriages length in blocks

        // Obstacle data
        public boolean obstacleDetected;
        public BlockPos obstaclePosition;
        public double distToObstacle;

        // Oncoming train data
        public boolean oncomingTrainDetected;
        public BlockPos oncomingTrainPos;
        public double oncomingTrainSpeed;

        // Surroundings
        public List<OvertakingAnalyzer.SwitchInfo> nearbySwitches;
        public List<BlockPos> nearbyPockets;

        // VBS conflict data
        public VirtualBlockSystem.ReservationResult upcomingConflict;

        // State tracking
        public long ticksInCurrentState;
        public boolean wasOnOppositeTrack;
        public long currentTick;

        // Reverse-escape maneuver state
        public boolean spaceAvailableBehind;
        public double reverseBackedDistance;
    }
}
