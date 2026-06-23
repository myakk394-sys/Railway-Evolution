package com.fizzylovely.railwayevolution.ai.core;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * ITrainGraph — абстракция графа путей для BFS в PerceptionEngine.
 *
 * Скрывает внутренние типы Create (TrackGraph, TrackNode, TrackEdge)
 * за версионно-независимым API.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public interface ITrainGraph {

    /**
     * Все рёбра, выходящие из узла {@code fromNode} в направлении,
     * противоположном {@code exceptNode} (не разворачивать назад).
     *
     * @param fromNodeRef   identity-ref на TrackNode
     * @param exceptNodeRef identity-ref на предыдущий узел (null = нет исключений)
     * @return список записей (соседний узел, ребро)
     */
    List<IGraphEdgeEntry> getEdgesFrom(Object fromNodeRef, @Nullable Object exceptNodeRef);

    /**
     * Получить ребро между двумя узлами (directed: n1→n2).
     * {@code null} если ребро не существует.
     */
    @Nullable
    ITrackEdge getEdge(Object node1Ref, Object node2Ref);

    /**
     * Ребро в обратном направлении (n2→n1).
     * Используется для обнаружения встречных поездов.
     */
    @Nullable
    ITrackEdge getReverseEdge(Object node1Ref, Object node2Ref);

    /**
     * Количество рёбер, выходящих из узла (degree).
     * >= 3 → перекрёсток/стрелка.
     */
    int getNodeDegree(Object nodeRef);

    /**
     * Мировая позиция узла.
     */
    Vec3 getNodePosition(Object nodeRef);

    /**
     * Является ли граф тем же объектом что {@code otherGraphRef}.
     * Используется для isOnSameTrack проверки без боксирования.
     */
    boolean isSameGraph(Object otherGraphRef);

    /**
     * Нативная ссылка на объект TrackGraph.
     * Используется только в адаптерном слое — не в ядре.
     */
    Object getNativeRef();
}
