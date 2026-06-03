package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/**
 * WaitForClearanceState — буферный стоп в критической зоне.
 *
 * Удерживает полный стоп с гистерезисом: не отпускает до тех пор,
 * пока препятствие не уйдёт за CLEARANCE_THRESHOLD блоков.
 *
 * Гистерезис (wfcDistHistory) предотвращает мерцание между WFC и CRUISING
 * при краевых условиях (поезд на изгибе, дистанция нестабильна).
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class WaitForClearanceState implements TrainControlState {

    private static final double CLEARANCE_THRESHOLD  = 12.0; // блоки до выхода
    private static final int    HISTORY_SIZE          = 3;    // проверяем 3 подряд
    private static final int    MIN_TICKS_IN_STATE    = 30;   // мин. нахождение
    private static final int    AUTO_TIMEOUT_TICKS    = 400;  // 20 сек принудительный выход

    /** Rolling-history расстояний до препятствия. -1 = нет данных. */
    private final double[] distHistory = new double[HISTORY_SIZE];
    private int    histIdx    = 0;
    private long   enteredTick = 0;

    @Override
    public TrainStateId getId() { return TrainStateId.WAIT_FOR_CLEARANCE; }

    @Override
    public void onEnter(TrainControlContext ctx) {
        enteredTick = ctx.currentTick;
        java.util.Arrays.fill(distHistory, -1);
        histIdx = 0;
        if (ctx.selfHandle != null) ctx.selfHandle.forceStop();
        CreateRailwayMod.aiDebug("[FSM] {} → WFC", shortId(ctx));
    }

    @Override
    public void onExit(TrainControlContext ctx) {
        java.util.Arrays.fill(distHistory, -1);
        histIdx = 0;
    }

    /**
     * При физическом перекрытии — не отпускаем управление даже игроку.
     */
    @Override
    public boolean allowsPlayerOverride(TrainControlContext ctx) {
        var obs = ctx.obstacleProfile;
        return obs == null || !obs.isOverlapping;
    }

    @Override
    public @Nullable TrainStateId tick(TrainControlContext ctx) {
        if (ctx.selfHandle != null) ctx.selfHandle.forceStop(); // каждый тик

        long timeInState = ctx.currentTick - enteredTick;

        // Принудительный выход при тайм-ауте
        if (timeInState > AUTO_TIMEOUT_TICKS) {
            CreateRailwayMod.aiDebug("[FSM] {} WFC timeout — force release", shortId(ctx));
            return TrainStateId.CRUISING;
        }

        var obs = ctx.obstacleProfile;

        // Нет препятствия → выход (после минимального ожидания)
        if ((obs == null || !obs.hasObstacle()) && timeInState >= MIN_TICKS_IN_STATE) {
            if (allHistoryClear()) return TrainStateId.CRUISING;
        }

        // Обновляем историю расстояний
        double dist = (obs != null && obs.hasObstacle()) ? obs.distance : Double.MAX_VALUE;
        distHistory[histIdx % HISTORY_SIZE] = dist;
        histIdx++;

        // Гистерезисный выход: все три последних замера > CLEARANCE_THRESHOLD
        if (timeInState >= MIN_TICKS_IN_STATE && allHistoryCleared()) {
            CreateRailwayMod.aiDebug("[FSM] {} WFC clearance granted (dist={})",
                shortId(ctx), String.format("%.1f", dist));
            return TrainStateId.CRUISING;
        }

        if (ctx.sandwiched) return TrainStateId.TRAFFIC_JAM;

        return null;
    }

    private boolean allHistoryClear() {
        for (double d : distHistory) if (d >= 0 && d < CLEARANCE_THRESHOLD) return false;
        return true;
    }

    private boolean allHistoryCleared() {
        int filled = Math.min(histIdx, HISTORY_SIZE);
        if (filled < HISTORY_SIZE) return false;
        for (double d : distHistory) if (d < CLEARANCE_THRESHOLD) return false;
        return true;
    }

    private String shortId(TrainControlContext ctx) {
        if (ctx.selfHandle == null) return "?";
        return ctx.selfHandle.getId().toString().substring(0, 8);
    }
}
