package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.config.RailwayConfig;

/**
 * Priority Calculator — determines right-of-way ranking between trains.
 *
 * Алгоритм приоритета / Priority Algorithm:
 * ──────────────────────────────────────────
 * When two trains compete for a shared segment or during a bypass maneuver,
 * a priority score is computed for each train. The train with the HIGHER score
 * gets right-of-way.
 *
 * Priority Factors (weighted):
 *   1. LANE OWNERSHIP (weight: 100)
 *      - Train on its correct (right) lane: +100 points
 *      - Train on the oncoming (bypass) lane: +0 points
 *      → The "legal" owner always has priority over a bypassing train.
 *
 *   2. SPEED FACTOR (weight: 10)
 *      - Faster trains get slight priority to avoid cascading delays.
 *      - Score: (currentSpeed / maxSpeed) * 10
 *
 *   3. DISTANCE TO DESTINATION (weight: 20)
 *      - Trains closer to their destination get priority (they'll clear sooner).
 *      - Score: max(0, 20 - (distanceToDestination / 100))
 *
 *   4. TRAIN LOAD (weight: 15)
 *      - Heavier/loaded trains get priority (harder to stop).
 *      - Score: (carriages * 3), capped at 15
 *
 *   5. WAIT TIME BONUS (weight: 30)
 *      - Trains that have been waiting longer get escalating priority.
 *      - Prevents starvation: after N ticks of waiting, priority increases.
 *      - Score: min(30, waitTicks / 20)
 *
 *   6. EMERGENCY BONUS (weight: 50)
 *      - Reserved for designated emergency/express trains.
 *      - Score: 50 if emergency flag is set, else 0.
 *
 * Total: 0..225 points max.
 */
public class PriorityCalculator {

    private PriorityCalculator() {}

    /**
     * Calculate the priority score for a train in the current context.
     *
     * @param isOnCorrectLane     Whether the train is on its correct (right) lane
     * @param currentSpeed        Current speed of the train (blocks/tick)
     * @param maxSpeed            Maximum speed of the train
     * @param distToDestination   Distance to the next scheduled destination (blocks)
     * @param carriageCount       Number of carriages in the train
     * @param waitTicks           How many ticks the train has been waiting/yielding
     * @param isEmergency         Whether the train has an emergency designation
     * @return                    Priority score (higher = more priority)
     */
    public static int calculatePriority(boolean isOnCorrectLane, double currentSpeed,
                                         double maxSpeed, double distToDestination,
                                         int carriageCount, long waitTicks,
                                         boolean isEmergency) {
        int score = 0;

        // Factor 1: Lane Ownership — the most important factor
        if (isOnCorrectLane) {
            score += RailwayConfig.priorityWeightLane.get();
        }

        // Factor 2: Speed
        double speedRatio = maxSpeed > 0 ? (currentSpeed / maxSpeed) : 0;
        score += (int) (speedRatio * RailwayConfig.priorityWeightSpeed.get());

        // Factor 3: Distance to destination (closer = higher priority)
        int distScore = Math.max(0, RailwayConfig.priorityWeightDistance.get()
                - (int) (distToDestination / 100.0));
        score += distScore;

        // Factor 4: Train load/size
        int loadScore = Math.min(RailwayConfig.priorityWeightLoad.get(), carriageCount * 3);
        score += loadScore;

        // Factor 5: Wait time anti-starvation
        int waitScore = (int) Math.min(RailwayConfig.priorityWeightWait.get(), waitTicks / 20);
        score += waitScore;

        // Factor 6: Emergency bonus
        if (isEmergency) {
            score += RailwayConfig.priorityWeightEmergency.get();
        }

        return score;
    }

    /**
     * Compare two trains and determine which one has right-of-way.
     *
     * @return positive if trainA has priority, negative if trainB, zero if equal
     */
    public static int comparePriority(int priorityA, int priorityB) {
        return Integer.compare(priorityA, priorityB);
    }

    /**
     * Determine if a bypassing train should yield to an oncoming train.
     * The bypassing train should yield if:
     *   - The oncoming train has higher priority
     *   - OR the oncoming train is within the critical distance threshold
     *
     * @param bypassingPriority   Priority of the bypassing train
     * @param oncomingPriority    Priority of the oncoming (lane-owner) train
     * @param distanceBetween     Distance between the two trains (blocks)
     * @return                    true if the bypassing train must yield
     */
    public static boolean shouldBypassingYield(int bypassingPriority, int oncomingPriority,
                                                double distanceBetween) {
        // Within critical distance — bypassing always yields to lane owner
        double critDist = RailwayConfig.criticalYieldDistance.get();
        if (distanceBetween <= critDist) {
            return true;
        }

        // Beyond critical distance — compare priorities
        return oncomingPriority >= bypassingPriority;
    }
}
