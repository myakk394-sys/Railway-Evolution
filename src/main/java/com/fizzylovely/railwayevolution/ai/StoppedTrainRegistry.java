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
 * Global registry of all stopped and crashed trains.
 *
 * Problem: Create stops updating TravellingPoint data when a train is stationary,
 * so the graph-walk BFS scanner loses track of stopped trains. The beam scanner
 * misses them on curves because heading doesn't align with track geometry.
 *
 * Solution: every tick each TrainAIController registers itself here if speed ≈ 0
 * or derailed. When the train starts moving, it's removed. All other controllers
 * query this registry as a guaranteed fallback — if a stopped train is physically
 * within range, it WILL be found regardless of track geometry or heading.
 *
 * Thread-safe via ConcurrentHashMap (server tick is single-threaded, but
 * commands and packet handlers may read concurrently).
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public class StoppedTrainRegistry {

    private static final StoppedTrainRegistry INSTANCE = new StoppedTrainRegistry();

    /**
     * Immutable snapshot of a stopped/crashed train's state.
     * Recreated every tick the train remains stopped (positions may drift slightly).
     */
    public static class StoppedTrain {
        public final UUID trainId;
        public final Vec3 precisePosition;   // sub-block accurate position
        public final BlockPos blockPosition; // block-level position
        public final int trainLength;        // total length in blocks
        public final double headingX;        // normalized heading vector
        public final double headingZ;
        public final boolean derailed;       // true = crashed/derailed train
        public final long stoppedSinceTick;  // game tick when the train first stopped
        public final Object graphRef;        // Train.graph reference for same-network filtering

        public StoppedTrain(UUID trainId, Vec3 precisePosition, BlockPos blockPosition,
                            int trainLength, double headingX, double headingZ,
                            boolean derailed, long stoppedSinceTick, Object graphRef) {
            this.trainId = trainId;
            this.precisePosition = precisePosition;
            this.blockPosition = blockPosition;
            this.trainLength = trainLength;
            this.headingX = headingX;
            this.headingZ = headingZ;
            this.derailed = derailed;
            this.stoppedSinceTick = stoppedSinceTick;
            this.graphRef = graphRef;
        }
    }

    private final ConcurrentHashMap<UUID, StoppedTrain> registry = new ConcurrentHashMap<>();

    public static StoppedTrainRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Called every tick by each TrainAIController after updateTrainData().
     *
     * If speed < 0.02 OR derailed → register (or update position).
     * If speed >= 0.02 AND not derailed → remove from registry.
     */
    public void update(UUID trainId, double speed, Vec3 precisePosition,
                       BlockPos blockPosition, int trainLength,
                       double headingX, double headingZ,
                       boolean derailed, long currentTick, Object graphRef) {
        if (precisePosition == null && blockPosition == null) {
            // No position data yet — can't register
            return;
        }

        if (speed < 0.02 || derailed) {
            // Train is stopped or crashed → register/update
            StoppedTrain existing = registry.get(trainId);
            long stoppedSince = (existing != null) ? existing.stoppedSinceTick : currentTick;
            registry.put(trainId, new StoppedTrain(
                    trainId, precisePosition, blockPosition,
                    trainLength, headingX, headingZ,
                    derailed, stoppedSince, graphRef));
        } else {
            // Train is moving → remove from registry
            if (registry.remove(trainId) != null) {
                CreateRailwayMod.aiDebug(
                        "[Registry] Train {} started moving — removed from stopped DB",
                        trainId.toString().substring(0, 8));
            }
        }
    }

    /**
     * Remove a train from the registry (called on train destruction/despawn).
     */
    public void remove(UUID trainId) {
        registry.remove(trainId);
    }

    /**
     * Find all stopped/crashed trains within `range` blocks of `position`.
     *
     * Uses CENTER-TO-CENTER distance for the range check (not edge-to-edge),
     * because the caller will apply edge-to-edge math when computing braking distance.
     *
     * @param position  center position to search from
     * @param range     maximum center-to-center distance
     * @param excludeId UUID of the querying train (excluded from results)
     * @param graphRef  the querying train's TrackGraph (null = match all)
     * @return list of stopped trains within range, sorted by distance (nearest first)
     */
    public List<StoppedTrain> getNearby(Vec3 position, double range,
                                        UUID excludeId, Object graphRef) {
        if (position == null) return List.of();

        List<StoppedTrain> result = new ArrayList<>();
        double rangeSq = range * range;

        for (StoppedTrain st : registry.values()) {
            if (st.trainId.equals(excludeId)) continue;

            // Same-graph filter: different railway networks can't block each other
            if (graphRef != null && st.graphRef != null && graphRef != st.graphRef) continue;

            Vec3 stPos = st.precisePosition;
            if (stPos == null && st.blockPosition != null) {
                stPos = Vec3.atCenterOf(st.blockPosition);
            }
            if (stPos == null) continue;

            double distSq = position.distanceToSqr(stPos);
            if (distSq <= rangeSq) {
                result.add(st);
            }
        }

        // Sort by distance (nearest first) — static comparator, no lambda allocation
        result.sort((a, b) -> {
            Vec3 pa = a.precisePosition != null ? a.precisePosition : Vec3.atCenterOf(a.blockPosition);
            Vec3 pb = b.precisePosition != null ? b.precisePosition : Vec3.atCenterOf(b.blockPosition);
            return Double.compare(position.distanceToSqr(pa), position.distanceToSqr(pb));
        });

        return result;
    }

    /**
     * Check if a specific train is registered as stopped/crashed.
     */
    public boolean isStopped(UUID trainId) {
        return registry.containsKey(trainId);
    }

    /**
     * Get a specific stopped train entry (or null if not registered).
     */
    public StoppedTrain get(UUID trainId) {
        return registry.get(trainId);
    }

    /**
     * Get all registered stopped/crashed trains.
     */
    public Collection<StoppedTrain> getAll() {
        return registry.values();
    }

    /**
     * Get count of registered stopped trains.
     */
    public int size() {
        return registry.size();
    }

    /**
     * Clear all entries (called on server stop / world unload).
     */
    public void clear() {
        registry.clear();
    }
}
