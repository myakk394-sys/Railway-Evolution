package com.fizzylovely.railwayevolution.ai.core;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * ITrackEdge — абстракция ребра путевого графа.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public interface ITrackEdge {

    /** Длина ребра (блоки). */
    double getLength();

    /**
     * Является ли ребро кривым (BezierConnection с ненулевым поворотом).
     * Соответствует TrackEdge.isTurn().
     */
    boolean isCurve();

    /**
     * Точки Bezier-кривой для swept-сканирования.
     * При {@code isCurve()==false} возвращает только две конечные точки.
     * Точки упорядочены от node1 к node2.
     *
     * @param numSamples количество точек (включая концы). Минимум 2.
     */
    List<Vec3> sampleCurve(int numSamples);

    /**
     * Identity-ref на нативный объект TrackEdge.
     * Используется только в адаптерном слое.
     */
    Object getNativeRef();
}
