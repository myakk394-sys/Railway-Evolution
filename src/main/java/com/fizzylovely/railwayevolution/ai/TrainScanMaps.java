package com.fizzylovely.railwayevolution.ai;

import java.util.IdentityHashMap;

/**
 * Global per-tick scan maps (v1.0.7, updated v1.0.8) — built once by
 * TrainAIManager, consumed by each TrainAIController.graphWalkScan().
 *
 * Replaces per-controller O(n) map-building loops with O(1) lookups.
 * All maps use IdentityHashMap because Create's TrackEdge / TrackNode
 * objects are compared by reference identity, not equals().
 *
 * v1.0.8 Fix #5: edgeToTrain now stores EdgeOccupancy (multi-train)
 * instead of a single TrainAIController. Pool of 128 EdgeOccupancy
 * objects eliminates per-tick allocations.
 */
public final class TrainScanMaps {

    // ─── EdgeOccupancy pool (zero-allocation) ─────────────────────────────
    private static final int POOL_SIZE = 128;
    private final EdgeOccupancy[] pool = new EdgeOccupancy[POOL_SIZE];
    private int poolPtr = 0;

    public TrainScanMaps() {
        for (int i = 0; i < POOL_SIZE; i++) {
            pool[i] = new EdgeOccupancy();
        }
    }

    /**
     * Acquire a cleared EdgeOccupancy from the pool.
     * If pool is exhausted, wraps around (overwrites oldest — extremely unlikely
     * with 128 slots and typical train counts < 64).
     */
    public EdgeOccupancy acquireOccupancy() {
        EdgeOccupancy occ = pool[poolPtr % POOL_SIZE];
        occ.clear();
        poolPtr++;
        return occ;
    }

    // ─── Maps ─────────────────────────────────────────────────────────────

    /**
     * edge (identity) → EdgeOccupancy with all controllers on this edge.
     * v1.0.8: Changed from single TrainAIController to EdgeOccupancy
     * to support multiple trains on the same edge (e.g. leading + trailing).
     */
    public final IdentityHashMap<Object, EdgeOccupancy> edgeToTrain = new IdentityHashMap<>();

    /** reverse edge (identity) → controller traveling in the opposite direction. */
    public final IdentityHashMap<Object, TrainAIController> reverseEdgeMap = new IdentityHashMap<>();

    /** junction node → approaching controller from a side branch. */
    public final IdentityHashMap<Object, TrainAIController> junctionApproachMap = new IdentityHashMap<>();

    /** junction node → physical distance from approaching controller to the junction. */
    public final IdentityHashMap<Object, Double> junctionApproachDist = new IdentityHashMap<>();

    /** Wipe all maps and reset pool pointer for re-population on the next tick. */
    public void clear() {
        edgeToTrain.clear();
        reverseEdgeMap.clear();
        junctionApproachMap.clear();
        junctionApproachDist.clear();
        poolPtr = 0;
    }
}
