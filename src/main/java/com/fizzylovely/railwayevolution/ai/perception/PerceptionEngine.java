package com.fizzylovely.railwayevolution.ai.perception;

import com.fizzylovely.railwayevolution.ai.core.*;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.UUID;

/**
 * PerceptionEngine — сканирует траекторию поезда без GC-мусора.
 *
 * Алгоритм:
 *   1. Multi-layer scan: сначала BFS-граф (точно), затем физический proximity.
 *   2. BFS использует пул объектов (NodeEntry[BFS_QUEUE_SIZE]).
 *   3. Visited-множество через version-array: O(1) сброс без Arrays.fill.
 *   4. Swept Bezier: промежуточные точки кривых проверяются (не только концы).
 *   5. Поезда игрока сканируются идентично AI — через ITrainHandle.
 *
 * Экосистемная интеграция:
 *   ObstacleProfile.isFlowCandidate указывает StateMachine перейти в
 *   FLOW_FOLLOWER вместо торможения к нулю.
 *
 * Один экземпляр на TrainAIController (не переиспользуется между поездами).
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class PerceptionEngine {

    // ─── BFS пул ──────────────────────────────────────────────────────────
    private static final int BFS_QUEUE_SIZE = 512;
    private final BfsNodeEntry[] bfsQueue   = new BfsNodeEntry[BFS_QUEUE_SIZE];
    private int bfsHead, bfsTail;

    // ─── Visited через version (O(1) reset) ───────────────────────────────
    // Индекс = System.identityHashCode(nodeRef) & (VISITED_SIZE-1)
    // Значение = currentScanVersion → visited в этом скане
    private static final int VISITED_SIZE   = 1024; // степень двойки
    private final int[]  visitedVersion     = new int[VISITED_SIZE];
    private int          scanVersion        = 0;

    // ─── Переиспользуемый профиль результата ──────────────────────────────
    private final ObstacleProfile result   = new ObstacleProfile();

    // ─── Переиспользуемый proximity-буфер ────────────────────────────────
    // При proximity-скане не создаём список — итерируем напрямую
    private ITrainHandle[] allHandles      = new ITrainHandle[64];
    private int            allHandlesCount = 0;

    // ─── Константы ────────────────────────────────────────────────────────
    private static final int   BEZIER_SAMPLES     = 5;    // точек на кривую
    private static final double SAME_TRACK_Y_TOL  = 5.0;  // блоки по Y
    private static final double PROXIMITY_RANGE   = 20.0; // блоки физсканирования
    private static final double PROXIMITY_DOT     = 0.3;  // cos угла (72° конус)
    private static final double PROXIMITY_LATERAL = 4.0;  // блоки бокового допуска

    // ──────────────────────────────────────────────────────────────────────

    public PerceptionEngine() {
        for (int i = 0; i < BFS_QUEUE_SIZE; i++) {
            bfsQueue[i] = new BfsNodeEntry();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Главный метод сканирования
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Выполнить скан траектории.
     *
     * @param selfHandle   наш поезд (для исключения себя из результатов)
     * @param ctx          контекст текущего тика (для позиции, вектора, скорости)
     * @param candidates   все поезда в мире (AI + игроки), включая нас
     * @param routeNextHop карта маршрута: nodeRef → следующий nodeRef (пустая = без ограничений)
     * @param maxRange     максимальная дальность (блоки)
     * @return ObstacleProfile или null если путь чист
     */
    @Nullable
    public ObstacleProfile scan(
            ITrainHandle selfHandle,
            TrainControlContext ctx,
            Iterable<ITrainHandle> candidates,
            IdentityHashMap<Object, Object> routeNextHop,
            double maxRange) {

        result.clear();
        scanVersion++;

        // ── Layer 1: BFS-граф (только если есть граф) ────────────────────
        ITrainGraph graph = selfHandle.getGraph();
        Object node1 = selfHandle.getLeadingNode1Ref();
        Object node2 = selfHandle.getLeadingNode2Ref();
        double edgePos = selfHandle.getLeadingEdgePosition();

        if (graph != null && node1 != null && node2 != null) {
            bfsScan(selfHandle, ctx, candidates, graph, node1, node2,
                    edgePos, routeNextHop, maxRange);
            ctx.graphScanRan = true;
        }

        // ── Layer 2: Физический proximity-скан (всегда) ──────────────────
        // Перебираем candidates — нет аллокаций (итерируем Iterable напрямую)
        proximityScan(selfHandle, ctx, candidates, maxRange);

        // ── После заполнения: финальные вычисления ───────────────────────
        if (result.hasObstacle()) {
            result.computeRiskScore(maxRange);
            result.evaluateFlowCandidate(Math.abs(ctx.speed));
            // Обновить ctx flow-флаги
            ctx.obstacleIsFlowCandidate = result.isFlowCandidate;
            ctx.flowLeaderSpeed         = result.flowTargetSpeed;
            ctx.flowLeaderIsPlayer      = result.leaderIsPlayer;
        }

        return result.hasObstacle() ? result : null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // BFS по графу
    // ──────────────────────────────────────────────────────────────────────

    private void bfsScan(
            ITrainHandle self, TrainControlContext ctx,
            Iterable<ITrainHandle> candidates,
            ITrainGraph graph,
            Object startNode1, Object startNode2, double startEdgePos,
            IdentityHashMap<Object, Object> routeNextHop,
            double maxRange) {

        // Построить edgeRef → handle для всех кандидатов (O(N))
        // Используем allHandles как временный пул — нет аллокаций
        buildHandleIndex(self, candidates);

        bfsHead = bfsTail = 0;

        // Начало BFS от позиции ведущей тележки
        ITrackEdge startEdge = graph.getEdge(startNode1, startNode2);
        if (startEdge == null) return;

        double distFromUs = startEdge.getLength() - startEdgePos;

        BfsNodeEntry start = bfsQueue[bfsTail & (BFS_QUEUE_SIZE - 1)];
        start.nodeRef    = startNode2;
        start.prevRef    = startNode1;
        start.distSoFar  = distFromUs;
        start.edgeRef    = startEdge.getNativeRef();
        bfsTail++;

        markVisited(startNode2);

        // Проверяем само начальное ребро на наличие поездов
        checkEdgeForTrains(self, startEdge, startNode1, startNode2,
                distFromUs, false, graph, ctx);

        // Проверяем встречное ребро (lob-v-lob)
        ITrackEdge reverseEdge = graph.getReverseEdge(startNode1, startNode2);
        if (reverseEdge != null) {
            checkEdgeForTrains(self, reverseEdge, startNode2, startNode1,
                    startEdgePos, true, graph, ctx);
        }

        // ── BFS ──────────────────────────────────────────────────────────
        while (bfsHead != bfsTail) {
            BfsNodeEntry entry = bfsQueue[bfsHead & (BFS_QUEUE_SIZE - 1)];
            bfsHead++;

            if (entry.distSoFar > maxRange) continue;

            Object curNode = entry.nodeRef;
            Object prevNode = entry.prevRef;

            // Route-aware фильтр: если карта маршрута содержит curNode,
            // идём ТОЛЬКО по маршрутному ребру
            Object forcedNext = routeNextHop.get(curNode);

            List<IGraphEdgeEntry> edges = graph.getEdgesFrom(curNode, prevNode);
            for (IGraphEdgeEntry edgeEntry : edges) {
                Object nextNode = edgeEntry.getNeighborNodeRef();
                ITrackEdge edge = edgeEntry.getEdge();

                // Route filter: пропускаем не-маршрутные ребра если маршрут известен
                if (forcedNext != null && nextNode != forcedNext) continue;

                if (isVisited(nextNode)) continue;
                markVisited(nextNode);

                double newDist = entry.distSoFar + edge.getLength();

                // Swept Bezier: проверяем точки по кривой
                if (edge.isCurve()) {
                    checkCurvePoints(self, edge, curNode, nextNode,
                            entry.distSoFar, newDist, graph, ctx);
                }

                // Проверяем ребро на поезда
                checkEdgeForTrains(self, edge, curNode, nextNode,
                        newDist, false, graph, ctx);

                // Проверяем встречное ребро (могут быть встречные поезда)
                ITrackEdge rev = graph.getReverseEdge(curNode, nextNode);
                if (rev != null) {
                    checkEdgeForTrains(self, rev, nextNode, curNode,
                            newDist, true, graph, ctx);
                }

                // Добавляем в очередь BFS
                if (newDist < maxRange) {
                    BfsNodeEntry next = bfsQueue[bfsTail & (BFS_QUEUE_SIZE - 1)];
                    next.nodeRef   = nextNode;
                    next.prevRef   = curNode;
                    next.distSoFar = newDist;
                    next.edgeRef   = edge.getNativeRef();
                    bfsTail++;
                }
            }
        }
    }

    /**
     * Проверить ребро графа на наличие поезда-кандидата.
     * Если нашли — обновляем result через updateIfCloser (нет аллокаций).
     */
    private void checkEdgeForTrains(
            ITrainHandle self,
            ITrackEdge edge, Object fromNode, Object toNode,
            double distAtEndOfEdge, boolean isReverseCheck,
            ITrainGraph graph, TrainControlContext ctx) {

        Object edgeNative = edge.getNativeRef();

        for (int i = 0; i < allHandlesCount; i++) {
            ITrainHandle candidate = allHandles[i];
            if (candidate == self) continue;

            Object candidateEdge = candidate.getLeadingEdgeRef();
            if (candidateEdge == null || candidateEdge != edgeNative) continue;

            // Нашли поезд на этом ребре
            double candidateDist  = distAtEdgeWithPosition(edge, candidate, distAtEndOfEdge);
            double mySpeed        = Math.abs(ctx.speed);
            double candidateSpeed = Math.abs(candidate.getSpeed());
            double closingSpeed   = isReverseCheck
                ? mySpeed + candidateSpeed  // лоб-в-лоб
                : mySpeed - candidateSpeed; // попутный

            boolean isDeparting = !isReverseCheck && candidateSpeed >= mySpeed - 0.01;

            result.updateIfCloser(
                candidate.getId(),
                candidate.isPlayerControlled(),
                Math.max(0, candidateDist),
                candidateSpeed,
                closingSpeed,
                isReverseCheck,
                isDeparting,
                isParkingZone(candidate, graph),
                true // distanceIsGraphBased
            );
        }
    }

    /**
     * Swept Bezier: проверяем промежуточные точки кривой.
     * Каждая точка проверяется на близость к кандидатам.
     */
    private void checkCurvePoints(
            ITrainHandle self, ITrackEdge edge,
            Object fromNode, Object toNode,
            double distStart, double distEnd,
            ITrainGraph graph, TrainControlContext ctx) {

        List<net.minecraft.world.phys.Vec3> points = edge.sampleCurve(BEZIER_SAMPLES);
        if (points == null || points.size() < 2) return;

        for (int p = 1; p < points.size() - 1; p++) {
            net.minecraft.world.phys.Vec3 pt = points.get(p);
            double tParam = (double) p / (points.size() - 1);
            double distAtPoint = distStart + (distEnd - distStart) * tParam;

            // Проверяем каждый кандидат на близость к точке кривой
            for (int i = 0; i < allHandlesCount; i++) {
                ITrainHandle candidate = allHandles[i];
                if (candidate == self) continue;

                net.minecraft.world.phys.Vec3 candPos = candidate.getLeadingPosition();
                if (candPos == null) continue;

                double dy = Math.abs(pt.y - candPos.y);
                if (dy > SAME_TRACK_Y_TOL) continue;

                double dx = pt.x - candPos.x;
                double dz = pt.z - candPos.z;
                double dist2D = Math.sqrt(dx * dx + dz * dz);

                // Если поезд ближе 2 блоков к точке кривой — считаем обнаружением
                if (dist2D < 2.0) {
                    double mySpeed   = Math.abs(ctx.speed);
                    double cSpeed    = Math.abs(candidate.getSpeed());
                    result.updateIfCloser(
                        candidate.getId(), candidate.isPlayerControlled(),
                        Math.max(0, distAtPoint - 1.0),
                        cSpeed, mySpeed - cSpeed,
                        false, cSpeed >= mySpeed - 0.01,
                        isParkingZone(candidate, graph), true
                    );
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Физический proximity-скан (последний рубеж обороны)
    // ──────────────────────────────────────────────────────────────────────

    private void proximityScan(
            ITrainHandle self, TrainControlContext ctx,
            Iterable<ITrainHandle> candidates,
            double maxRange) {

        net.minecraft.world.phys.Vec3 myPos = ctx.leadingPosition;
        if (myPos == null || !ctx.headingValid || Math.abs(ctx.speed) < 0.02) return;

        double hx = ctx.headingX;
        double hz = ctx.headingZ;

        for (ITrainHandle candidate : candidates) {
            if (candidate.getId().equals(self.getId())) continue;

            net.minecraft.world.phys.Vec3 cPos = candidate.getLeadingPosition();
            if (cPos == null) continue;

            double dy = Math.abs(myPos.y - cPos.y);
            if (dy > SAME_TRACK_Y_TOL) continue;

            double dx = cPos.x - myPos.x;
            double dz = cPos.z - myPos.z;
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist > PROXIMITY_RANGE || dist < 0.5) continue;

            // Конус: поезд должен быть ВПЕРЕДИ нас
            double invDist = 1.0 / dist;
            double dirX = dx * invDist, dirZ = dz * invDist;
            double dot = dirX * hx + dirZ * hz;
            if (dot < PROXIMITY_DOT) continue;

            // Боковой фильтр: не слишком сбоку (поезд на параллельном пути)
            double lateral = Math.abs(dx * hz - dz * hx);
            if (lateral > PROXIMITY_LATERAL) continue;

            // Физическая дистанция (приблизительно, без графа)
            double physDist = dist - (self.getTotalLength() * 0.5 + candidate.getTotalLength() * 0.5);

            double mySpeed   = Math.abs(ctx.speed);
            double cSpeed    = Math.abs(candidate.getSpeed());
            double closing   = mySpeed - cSpeed * dot; // проекция скорости кандидата
            boolean isDep    = cSpeed * dot >= mySpeed - 0.01;

            // Только обновляем если ближе чем текущий BFS-результат
            // (BFS точнее — не перебиваем его физическим без весомой причины)
            if (!ctx.graphScanRan || physDist < result.distance * 0.8) {
                result.updateIfCloser(
                    candidate.getId(), candidate.isPlayerControlled(),
                    Math.max(0, physDist),
                    cSpeed, closing,
                    false, isDep,
                    false, false // не graph-based
                );
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Вспомогательные методы
    // ──────────────────────────────────────────────────────────────────────

    /** Построить массив хэндлов кандидатов (нет аллокаций — переиспользуем поле). */
    private void buildHandleIndex(ITrainHandle self, Iterable<ITrainHandle> candidates) {
        allHandlesCount = 0;
        for (ITrainHandle h : candidates) {
            if (allHandlesCount >= allHandles.length) {
                // Расширяем массив если нужно (редко, < 1 раза в сессию)
                ITrainHandle[] bigger = new ITrainHandle[allHandles.length * 2];
                System.arraycopy(allHandles, 0, bigger, 0, allHandles.length);
                allHandles = bigger;
            }
            allHandles[allHandlesCount++] = h;
        }
    }

    /** Вычислить дистанцию от нашей позиции до поезда на данном ребре. */
    private double distAtEdgeWithPosition(ITrackEdge edge, ITrainHandle candidate,
                                          double distAtEndOfEdge) {
        double candEdgePos = candidate.getLeadingEdgePosition();
        // Поезд находится на расстоянии candEdgePos от node1 ребра.
        // distAtEndOfEdge = дистанция от нас до конца ребра (node2).
        // Дистанция до поезда = distAtEndOfEdge - (edgeLength - candEdgePos)
        double distInEdge = edge.getLength() - candEdgePos;
        return distAtEndOfEdge - distInEdge;
    }

    /** Является ли поезд в тупике (parking zone / депо). */
    private boolean isParkingZone(ITrainHandle candidate, ITrainGraph graph) {
        if (!candidate.hasDestination() && Math.abs(candidate.getSpeed()) < 0.05) {
            // Стоит без маршрута — вероятно, в депо
            Object node2 = candidate.getLeadingNode2Ref();
            if (node2 != null && graph.getNodeDegree(node2) <= 1) {
                return true; // тупик
            }
        }
        return false;
    }

    // ─── Version-based visited set ────────────────────────────────────────

    private void markVisited(Object nodeRef) {
        int idx = System.identityHashCode(nodeRef) & (VISITED_SIZE - 1);
        visitedVersion[idx] = scanVersion;
    }

    private boolean isVisited(Object nodeRef) {
        int idx = System.identityHashCode(nodeRef) & (VISITED_SIZE - 1);
        return visitedVersion[idx] == scanVersion;
    }

    // ─── BFS node entry (пул объектов) ────────────────────────────────────

    private static final class BfsNodeEntry {
        Object nodeRef;
        Object prevRef;
        double distSoFar;
        Object edgeRef; // для отладки
    }
}
