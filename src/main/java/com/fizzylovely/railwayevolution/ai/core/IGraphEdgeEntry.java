package com.fizzylovely.railwayevolution.ai.core;

/**
 * IGraphEdgeEntry — пара (соседний узел, ребро) из ITrainGraph.getEdgesFrom().
 *
 */
public interface IGraphEdgeEntry {

    /** Identity-ref на соседний TrackNode. */
    Object getNeighborNodeRef();

    /** Ребро к соседнему узлу. */
    ITrackEdge getEdge();
}
