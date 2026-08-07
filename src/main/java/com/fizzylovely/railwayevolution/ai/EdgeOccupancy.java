package com.fizzylovely.railwayevolution.ai;

/**
 * v1.0.8 Fix #5: Multi-occupancy container for a single TrackEdge.
 *
 * Replaces the old putIfAbsent(edge → single controller) in TrainScanMaps.
 * Stores up to 2 controllers per edge (first + second). In practice, 3+
 * trains on one edge is extremely rare (would require sub-block spacing).
 *
 * Designed for zero-allocation: instances are pooled and reused via
 * {@link TrainScanMaps#acquireOccupancy()}.
 */
public final class EdgeOccupancy {

    /** First controller registered on this edge. */
    public TrainAIController first;

    /** Second controller (if any). Null when count < 2. */
    public TrainAIController second;

    /** Number of controllers on this edge. */
    public int count;

    /** Reset for reuse from pool. */
    public void clear() {
        first = null;
        second = null;
        count = 0;
    }

    /**
     * Register a controller on this edge.
     * Stores up to 2 entries (first + second). Third+ are counted but not stored
     * — in practice this is 3 trains within one track segment, extremely rare.
     */
    public void add(TrainAIController ctrl) {
        if (count == 0) {
            first = ctrl;
        } else if (count == 1) {
            second = ctrl;
        }
        count++;
    }
}
