package com.fizzylovely.railwayevolution.ai;

import java.util.IdentityHashMap;

/**
 * Global per-tick scan maps (v1.0.7) — built once by TrainAIManager,
 * consumed by each TrainAIController.graphWalkScan().
 *
 * Replaces per-controller O(n) map-building loops with O(1) lookups.
 * All maps use IdentityHashMap because Create's TrackEdge / TrackNode
 * objects are compared by reference identity, not equals().
 */
public final class TrainScanMaps {

    /** edge (identity) → controller with leading/trailing edge on this edge. */
    public final IdentityHashMap<Object, TrainAIController> edgeToTrain = new IdentityHashMap<>();

    /** reverse edge (identity) → controller traveling in the opposite direction. */
    public final IdentityHashMap<Object, TrainAIController> reverseEdgeMap = new IdentityHashMap<>();

    /** junction node → approaching controller from a side branch. */
    public final IdentityHashMap<Object, TrainAIController> junctionApproachMap = new IdentityHashMap<>();

    /** junction node → physical distance from approaching controller to the junction. */
    public final IdentityHashMap<Object, Double> junctionApproachDist = new IdentityHashMap<>();

    /** Wipe all maps for re-population on the next tick. */
    public void clear() {
        edgeToTrain.clear();
        reverseEdgeMap.clear();
        junctionApproachMap.clear();
        junctionApproachDist.clear();
    }
}
