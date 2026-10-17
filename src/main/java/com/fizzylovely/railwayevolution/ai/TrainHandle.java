package com.fizzylovely.railwayevolution.ai;

/**
 * Lightweight marker for a train stored in per-edge occupancy data.
 * Implemented by the existing controller so registering a train does not
 * allocate a wrapper object.
 */
public interface TrainHandle {

    /** Owning controller used by edge-occupancy diagnostics and indexing. */
    TrainAIController controller();
}
