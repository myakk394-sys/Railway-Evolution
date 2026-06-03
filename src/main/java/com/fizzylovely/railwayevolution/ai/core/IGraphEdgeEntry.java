package com.fizzylovely.railwayevolution.ai.core;

/**
 * IGraphEdgeEntry — пара (соседний узел, ребро) из ITrainGraph.getEdgesFrom().
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public interface IGraphEdgeEntry {

    /** Identity-ref на соседний TrackNode. */
    Object getNeighborNodeRef();

    /** Ребро к соседнему узлу. */
    ITrackEdge getEdge();
}
