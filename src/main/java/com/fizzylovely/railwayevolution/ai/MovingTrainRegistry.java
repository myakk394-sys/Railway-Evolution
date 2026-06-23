package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of all MOVING trains — complements StoppedTrainRegistry.
 *
 * Together they cover ALL trains:
 * - StoppedTrainRegistry: speed < 0.02 or derailed
 * - MovingTrainRegistry:  speed >= 0.02 and not derailed
 *
 * Why needed:
 * - BFS graph walk misses trains during edge transitions (TravellingPoint updates lag)
 * - Beam scan misses trains on curves (heading doesn't follow track geometry)
 * - This registry provides a pure position+velocity fallback that works regardless
 *   of track topology, heading alignment, or Create's internal state.
 *
 * Does NOT replace BFS/beam — used as an additional safety layer in scanForObstacleController.
 *
 * Thread-safe via ConcurrentHashMap.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public class MovingTrainRegistry {

    private static final MovingTrainRegistry INSTANCE = new MovingTrainRegistry();

    /**
     * Snapshot of a moving train's state, updated every tick.
     */
    public static class MovingTrain {
        public final UUID trainId;
        public final Vec3 precisePosition;
        public final BlockPos blockPosition;
        public final int trainLength;
        public final double headingX;        // normalized heading
        public final double headingZ;
        public final double speed;           // blocks/tick
        public final Object graphRef;        // Train.graph for same-network filtering
        public final long lastUpdateTick;    // tick when this entry was last updated

        public MovingTrain(UUID trainId, Vec3 precisePosition, BlockPos blockPosition,
                           int trainLength, double headingX, double headingZ,
                           double speed, Object graphRef, long lastUpdateTick) {
            this.trainId = trainId;
            this.precisePosition = precisePosition;
            this.blockPosition = blockPosition;
            this.trainLength = trainLength;
            this.headingX = headingX;
            this.headingZ = headingZ;
            this.speed = speed;
            this.graphRef = graphRef;
            this.lastUpdateTick = lastUpdateTick;
        }

        /**
         * Predict where this train will be in `ticks` ticks from now.
         * Simple linear extrapolation along heading vector.
         */
        public Vec3 predictPosition(int ticks) {
            if (precisePosition == null) return null;
            return precisePosition.add(
                    headingX * speed * ticks,
                    0,
                    headingZ * speed * ticks);
        }
    }

    private final ConcurrentHashMap<UUID, MovingTrain> registry = new ConcurrentHashMap<>();

    public static MovingTrainRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Called every tick by each TrainAIController after updateTrainData().
     * Moving trains (speed >= 0.02, not derailed) are registered here.
     * Stopped/derailed trains are removed (they go to StoppedTrainRegistry).
     */
    public void update(UUID trainId, double speed, Vec3 precisePosition,
                       BlockPos blockPosition, int trainLength,
                       double headingX, double headingZ,
                       boolean derailed, long currentTick, Object graphRef) {
        if (precisePosition == null && blockPosition == null) {
            return; // no position data
        }

        if (speed >= 0.02 && !derailed) {
            // Train is moving → register/update
            registry.put(trainId, new MovingTrain(
                    trainId, precisePosition, blockPosition,
                    trainLength, headingX, headingZ,
                    speed, graphRef, currentTick));
        } else {
            // Train stopped or crashed → remove from moving registry
            registry.remove(trainId);
        }
    }

    /**
     * Remove a train (despawn/destroy).
     */
    public void remove(UUID trainId) {
        registry.remove(trainId);
    }

    /**
     * Find all moving trains within `range` blocks (center-to-center).
     * Sorted by distance (nearest first).
     *
     * @param position  center position to search from
     * @param range     max center-to-center distance
     * @param excludeId UUID to exclude (the querying train)
     * @param graphRef  querying train's TrackGraph (null = match all)
     * @return list of moving trains within range
     */
    public List<MovingTrain> getNearby(Vec3 position, double range,
                                       UUID excludeId, Object graphRef) {
        if (position == null) return List.of();

        List<MovingTrain> result = new ArrayList<>();
        double rangeSq = range * range;

        for (MovingTrain mt : registry.values()) {
            if (mt.trainId.equals(excludeId)) continue;

            // Same-graph filter
            if (graphRef != null && mt.graphRef != null && graphRef != mt.graphRef) continue;

            Vec3 mtPos = mt.precisePosition;
            if (mtPos == null && mt.blockPosition != null) {
                mtPos = Vec3.atCenterOf(mt.blockPosition);
            }
            if (mtPos == null) continue;

            double distSq = position.distanceToSqr(mtPos);
            if (distSq <= rangeSq) {
                result.add(mt);
            }
        }

        // Sort nearest first
        result.sort((a, b) -> {
            Vec3 pa = a.precisePosition != null ? a.precisePosition : Vec3.atCenterOf(a.blockPosition);
            Vec3 pb = b.precisePosition != null ? b.precisePosition : Vec3.atCenterOf(b.blockPosition);
            return Double.compare(position.distanceToSqr(pa), position.distanceToSqr(pb));
        });

        return result;
    }

    /**
     * Check if two moving trains are on a COLLISION COURSE.
     * Uses Time-To-Collision (TTC) prediction.
     *
     * @return estimated TTC in ticks, or Integer.MAX_VALUE if no collision predicted
     */
    public static int estimateTTC(MovingTrain a, MovingTrain b) {
        if (a.precisePosition == null || b.precisePosition == null) return Integer.MAX_VALUE;
        if (a.speed < 0.01 && b.speed < 0.01) return Integer.MAX_VALUE;

        // Relative position: B from A's perspective
        double dx = b.precisePosition.x - a.precisePosition.x;
        double dz = b.precisePosition.z - a.precisePosition.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5) return 0; // already overlapping

        // Relative velocity (closing speed)
        double vRelX = (a.headingX * a.speed) - (b.headingX * b.speed);
        double vRelZ = (a.headingZ * a.speed) - (b.headingZ * b.speed);

        // Closing speed along the line connecting them
        double closingSpeed = (vRelX * dx + vRelZ * dz) / dist;
        if (closingSpeed <= 0) return Integer.MAX_VALUE; // diverging

        return (int) (dist / closingSpeed);
    }

    /**
     * Get a specific moving train entry.
     */
    public MovingTrain get(UUID trainId) {
        return registry.get(trainId);
    }

    /**
     * Get all registered moving trains.
     */
    public Collection<MovingTrain> getAll() {
        return registry.values();
    }

    /**
     * Count of moving trains.
     */
    public int size() {
        return registry.size();
    }

    /**
     * Clear all entries (server stop/reload).
     */
    public void clear() {
        registry.clear();
    }
}
