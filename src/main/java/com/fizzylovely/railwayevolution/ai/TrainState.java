package com.fizzylovely.railwayevolution.ai;

/**
 * Finite State Machine states for a train's AI controller.
 *
 * State transitions (Decision Tree):
 *   CRUISING -> ANALYZING_OBSTACLE   (obstacle detected ahead)
 *   ANALYZING_OBSTACLE -> CRUISING   (obstacle cleared or false alarm)
 *   ANALYZING_OBSTACLE -> BYPASSING  (oncoming track is free, switches found)
 *   ANALYZING_OBSTACLE -> YIELDING   (cannot bypass, must wait)
 *   BYPASSING -> YIELDING            (oncoming train detected while on opposite track)
 *   BYPASSING -> RETURNING           (obstacle passed, searching for return switch)
 *   YIELDING -> CRUISING             (path cleared after wait)
 *   YIELDING -> BYPASSING            (pocket freed, continue bypass)
 *   RETURNING -> CRUISING            (returned to original lane successfully)
 *   RETURNING -> YIELDING            (yield while trying to merge back)
 */
public enum TrainState {

    /**
     * Normal operation. Train follows its scheduled route on the correct lane.
     * Continuously scans ahead for obstacles within the detection range.
     */
    CRUISING,

    /**
     * An obstacle has been detected. The train decelerates and evaluates:
     *  - Is the obstacle a stopped/broken train?
     *  - Is there a slower train ahead?
     *  - Is it a deadlock situation?
     *  - Can the oncoming lane be used for a bypass?
     */
    ANALYZING_OBSTACLE,

    /**
     * The train has switched to the oncoming (opposite) track to bypass
     * an obstacle. It must monitor for oncoming traffic and prepare to yield.
     * The train holds reservations on the oncoming track segments it occupies.
     */
    BYPASSING,

    /**
     * The train has stopped and is yielding right-of-way.
     * This happens when:
     *  - An oncoming train is detected while bypassing
     *  - The train is waiting for a higher-priority train to pass
     *  - Waiting in a pocket/siding for clearance
     */
    YIELDING,

    /**
     * The obstacle has been passed. The train is actively searching for
     * the nearest available switch to return to its original (correct) lane.
     * Once returned, transitions to CRUISING.
     */
    RETURNING,

    /**
     * The train has been stuck yielding for too long with no bypass available.
     * It backs up along the track to create distance from the blocker, then
     * returns to CRUISING so navigation can reroute to the destination from
     * the opposite direction. This prevents the "train doesn't know where to go"
     * deadlock.
     */
    REVERSING,

    /**
     * Traffic jam — the train is sandwiched between other trains (obstacle
     * ahead AND behind). It simply waits. Only the LAST train in the jam
     * (no train behind it) can reverse and search for a bypass route.
     * When that train clears the jam, the others resume automatically.
     */
    TRAFFIC_JAM,

    /**
     * Buffer-hold — the train has stopped because a forward obstacle is
     * within the critical buffer zone (< 5 blocks). Movement is completely
     * blocked until the obstacle moves away to at least 7 blocks (hysteresis)
     * to prevent Create's hitbox collision detection from triggering a crash.
     */
    WAIT_FOR_CLEARANCE;

    /**
     * Whether this state means the train is on the wrong (oncoming) track.
     */
    public boolean isOnOppositeTrack() {
        return this == BYPASSING || this == RETURNING;
    }

    /**
     * Whether the train should be decelerating in this state.
     */
    public boolean shouldDecelerate() {
        return this == ANALYZING_OBSTACLE || this == YIELDING || this == TRAFFIC_JAM
                || this == WAIT_FOR_CLEARANCE;
    }

    /**
     * Whether the train is in an active maneuvering state (not just cruising).
     */
    public boolean isManeuvering() {
        return this != CRUISING;
    }
}
