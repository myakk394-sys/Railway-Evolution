package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.ObstacleProfile;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/**
 * CruisingState — нормальная работа: движение по маршруту, непрерывный скан.
 *
 * Переходы:
 *   → FLOW_FOLLOWER      если обнаружен попутный поезд в диапазоне потока
 *   → ANALYZING_OBSTACLE если риск > LOW_RISK_THRESHOLD
 *   → WAIT_FOR_CLEARANCE если TTC критический (< 1.5 сек)
 *   → TRAFFIC_JAM        если зажаты
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class CruisingState implements TrainControlState {

    private static final double LOW_RISK_THRESHOLD = 0.25;
    private static final int    CRITICAL_TTC_TICKS = 30;

    @Override
    public TrainStateId getId() { return TrainStateId.CRUISING; }

    @Override
    public void onEnter(TrainControlContext ctx) {
        if (ctx.selfHandle != null) {
            ctx.selfHandle.restoreFullThrottle();
            ctx.selfHandle.resumeNavigation();
        }
        CreateRailwayMod.aiDebug("[FSM] {} → CRUISING", shortId(ctx));
    }

    @Override
    public void onExit(TrainControlContext ctx) { /* ничего */ }

    @Override
    public @Nullable TrainStateId tick(TrainControlContext ctx) {
        ObstacleProfile obs = ctx.obstacleProfile;

        // Путь свободен → оставаться
        if (obs == null || !obs.hasObstacle()) return null;

        // Зажаты → пробка
        if (ctx.sandwiched) return TrainStateId.TRAFFIC_JAM;

        // TTC критический → немедленно WFC
        if (isTTCCritical(obs)) return TrainStateId.WAIT_FOR_CLEARANCE;

        // FLOW_FOLLOWER: попутный поезд, скорости близки, дистанция в норме
        if (obs.isFlowCandidate) return TrainStateId.FLOW_FOLLOWER;

        // Риск высокий → анализ
        if (obs.riskScore > LOW_RISK_THRESHOLD) return TrainStateId.ANALYZING_OBSTACLE;

        // Риск низкий — продолжаем движение (Create сам управляет скоростью)
        return null;
    }

    private boolean isTTCCritical(ObstacleProfile obs) {
        if (obs.closingSpeed <= 0.005 || obs.distance <= 0) return false;
        return (obs.distance / obs.closingSpeed) < CRITICAL_TTC_TICKS;
    }

    private String shortId(TrainControlContext ctx) {
        if (ctx.selfHandle == null) return "?";
        return ctx.selfHandle.getId().toString().substring(0, 8);
    }
}
