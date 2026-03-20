package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.config.RailwayConfig;

import java.util.UUID;

/**
 * Conflict Resolver — negotiation algorithm for two trains competing
 * for a shared track segment or during a bypass maneuver.
 *
 * Алгоритм разрешения конфликтов / Conflict Resolution Algorithm:
 * ──────────────────────────────────────────────────────────────────
 *
 * When VBS detects that two trains want the same segment:
 *
 * Case 1: HEAD-ON CONFLICT (opposing directions)
 *   ┌─────────────────────────────────────────────────┐
 *   │  Train A ──►        ◄── Train B                 │
 *   │              [segment]                          │
 *   │                                                 │
 *   │  Step 1: Calculate priority(A), priority(B)     │
 *   │  Step 2: Higher priority train keeps reservation│
 *   │  Step 3: Lower priority train → YIELDING state  │
 *   │  Step 4: Yielding train searches for a pocket   │
 *   │  (siding) to pull into and wait                 │
 *   │  Step 5: After passage, yielding train resumes  │
 *   └─────────────────────────────────────────────────┘
 *
 * Case 2: SAME-DIRECTION CONFLICT (following/queueing)
 *   ┌─────────────────────────────────────────────────┐
 *   │  Train A ──►  Train B ──►                       │
 *   │              [segment]                          │
 *   │                                                 │
 *   │  Step 1: Trailing train decelerates             │
 *   │  Step 2: Maintain safe following distance       │
 *   │  Step 3: If gap persists, consider bypass       │
 *   └─────────────────────────────────────────────────┘
 *
 * Case 3: BYPASS vs LANE-OWNER
 *   ┌─────────────────────────────────────────────────┐
 *   │  Bypassing train on oncoming track:             │
 *   │  Oncoming train = lane owner → ALWAYS priority  │
 *   │  Bypassing train → immediate YIELDING           │
 *   │  Find nearest pocket, pull in, wait             │
 *   └─────────────────────────────────────────────────┘
 *
 * Deadlock Detection:
 *   If two trains are both YIELDING and waiting for each other:
 *   - The train with lower priority backs up to the last switch.
 *   - Emergency escalation after timeout.
 */
public class ConflictResolver {

    /**
     * Resolution decision for a conflict between two trains.
     */
    public enum Resolution {
        /** Train A wins (keeps segment, higher priority). */
        TRAIN_A_WINS,
        /** Train B wins (keeps segment, higher priority). */
        TRAIN_B_WINS,
        /** Equal priority — use tiebreaker rules. */
        TIE_BREAKER,
        /** Deadlock detected — requires emergency intervention. */
        DEADLOCK
    }

    /**
     * Resolve a conflict between two trains competing for the same segment.
     *
     * @param trainA         Context for train A
     * @param trainB         Context for train B
     * @param isHeadOn       Whether this is a head-on conflict
     * @param currentTick    Current server tick
     * @return               Resolution decision
     */
    public static Resolution resolve(TrainConflictContext trainA, TrainConflictContext trainB,
                                      boolean isHeadOn, long currentTick) {

        int priorityA = PriorityCalculator.calculatePriority(
                trainA.isOnCorrectLane, trainA.currentSpeed, trainA.maxSpeed,
                trainA.distToDestination, trainA.carriageCount,
                trainA.waitTicks, trainA.isEmergency);

        int priorityB = PriorityCalculator.calculatePriority(
                trainB.isOnCorrectLane, trainB.currentSpeed, trainB.maxSpeed,
                trainB.distToDestination, trainB.carriageCount,
                trainB.waitTicks, trainB.isEmergency);

        CreateRailwayMod.LOGGER.debug("[Conflict] Train {} (priority={}) vs Train {} (priority={}), headOn={}",
                trainA.trainId.toString().substring(0, 8), priorityA,
                trainB.trainId.toString().substring(0, 8), priorityB,
                isHeadOn);

        // Special case: bypass vs lane-owner — lane owner ALWAYS wins
        if (isHeadOn && trainA.isOnCorrectLane && !trainB.isOnCorrectLane) {
            return Resolution.TRAIN_A_WINS;
        }
        if (isHeadOn && trainB.isOnCorrectLane && !trainA.isOnCorrectLane) {
            return Resolution.TRAIN_B_WINS;
        }

        // Deadlock detection: both trains yielding for too long
        long deadlockThreshold = RailwayConfig.deadlockTimeoutTicks.get();
        if (trainA.state == TrainState.YIELDING && trainB.state == TrainState.YIELDING) {
            if (trainA.waitTicks > deadlockThreshold && trainB.waitTicks > deadlockThreshold) {
                CreateRailwayMod.LOGGER.warn("[Conflict] DEADLOCK detected between {} and {}",
                        trainA.trainId.toString().substring(0, 8),
                        trainB.trainId.toString().substring(0, 8));
                return Resolution.DEADLOCK;
            }
        }

        // Standard priority comparison
        int cmp = PriorityCalculator.comparePriority(priorityA, priorityB);
        if (cmp > 0) return Resolution.TRAIN_A_WINS;
        if (cmp < 0) return Resolution.TRAIN_B_WINS;

        // Tiebreaker: use train UUID hash (deterministic, consistent for both trains)
        return trainA.trainId.hashCode() > trainB.trainId.hashCode()
                ? Resolution.TRAIN_A_WINS
                : Resolution.TRAIN_B_WINS;
    }

    /**
     * Handle a deadlock situation. The lower-priority train must back up.
     *
     * @param losingTrain The train that must yield and back up
     * @param currentTick Current server tick
     */
    public static void handleDeadlock(TrainConflictContext losingTrain, long currentTick) {
        CreateRailwayMod.LOGGER.warn("[Deadlock] Train {} forced to back up to last switch",
                losingTrain.trainId.toString().substring(0, 8));

        // Release all reservations for the losing train
        VirtualBlockSystem.getInstance().releaseAll(losingTrain.trainId);

        // The train AI controller will handle the physical backing up
        // by detecting that reservations were force-released
    }

    /**
     * Context data for a train involved in a conflict.
     * Gathered from the Train AI Controller at the time of conflict.
     */
    public static class TrainConflictContext {
        public final UUID trainId;
        public final TrainState state;
        public final boolean isOnCorrectLane;
        public final double currentSpeed;
        public final double maxSpeed;
        public final double distToDestination;
        public final int carriageCount;
        public final long waitTicks;
        public final boolean isEmergency;

        public TrainConflictContext(UUID trainId, TrainState state, boolean isOnCorrectLane,
                                    double currentSpeed, double maxSpeed,
                                    double distToDestination, int carriageCount,
                                    long waitTicks, boolean isEmergency) {
            this.trainId = trainId;
            this.state = state;
            this.isOnCorrectLane = isOnCorrectLane;
            this.currentSpeed = currentSpeed;
            this.maxSpeed = maxSpeed;
            this.distToDestination = distToDestination;
            this.carriageCount = carriageCount;
            this.waitTicks = waitTicks;
            this.isEmergency = isEmergency;
        }
    }
}
