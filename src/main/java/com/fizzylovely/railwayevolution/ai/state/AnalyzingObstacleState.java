package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.ObstacleProfile;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/**
 * AnalyzingObstacleState — анализ препятствия с кинематическим торможением.
 *
 * Переходы:
 *   → CRUISING           если препятствие исчезло
 *   → FLOW_FOLLOWER      если препятствие стало flow-кандидатом
 *   → YIELDING           если скорость ≈ 0 (заторможены до нуля)
 *   → WAIT_FOR_CLEARANCE если дистанция критическая
 *   → TRAFFIC_JAM        если зажаты
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class AnalyzingObstacleState implements TrainControlState {

    private static final double STOPPED_SPEED_THRESHOLD = 0.03;

    @Override
    public TrainStateId getId() { return TrainStateId.ANALYZING_OBSTACLE; }

    @Override
    public void onEnter(TrainControlContext ctx) {
        CreateRailwayMod.aiDebug("[FSM] {} → ANALYZING_OBSTACLE", shortId(ctx));
    }

    @Override
    public void onExit(TrainControlContext ctx) { /* ничего */ }

    @Override
    public @Nullable TrainStateId tick(TrainControlContext ctx) {
        ObstacleProfile obs = ctx.obstacleProfile;

        if (obs == null || !obs.hasObstacle()) return TrainStateId.CRUISING;
        if (ctx.sandwiched) return TrainStateId.TRAFFIC_JAM;
        if (obs.isOverlapping) return TrainStateId.WAIT_FOR_CLEARANCE;

        // Переход в FLOW если условия сложились
        if (obs.isFlowCandidate) return TrainStateId.FLOW_FOLLOWER;

        // Высокий риск → WFC
        if (obs.riskScore >= 0.85) return TrainStateId.WAIT_FOR_CLEARANCE;

        // Кинематическое торможение
        double targetSpeed = computeTargetSpeed(ctx, obs);
        applySpeedControl(ctx, targetSpeed);

        // Остановились — переходим в YIELDING (ожидаем дальнейших событий)
        if (Math.abs(ctx.speed) < STOPPED_SPEED_THRESHOLD && targetSpeed < STOPPED_SPEED_THRESHOLD) {
            return TrainStateId.YIELDING;
        }

        return null;
    }

    /**
     * Вычислить целевую скорость на основе ObstacleProfile.
     * Плавная кривая: v = maxSpeed × (1 - risk^0.7) ограниченная кинематикой.
     */
    private double computeTargetSpeed(TrainControlContext ctx, ObstacleProfile obs) {
        // 1. Риск-зависимая скорость
        double riskFactor  = Math.pow(1.0 - obs.riskScore, 0.7);
        double riskSpeed   = ctx.maxSpeed * riskFactor;

        // 2. Кинематический предел
        double safeBuffer  = 3.0;
        double kinematic   = Math.sqrt(2 * ctx.acceleration
                             * Math.max(0, obs.distance - safeBuffer));

        return Math.max(0, Math.min(riskSpeed, kinematic));
    }

    private void applySpeedControl(TrainControlContext ctx, double targetSpeed) {
        if (ctx.selfHandle == null) return;
        double current = Math.abs(ctx.speed);
        if (current > targetSpeed) {
            ctx.selfHandle.setSpeed(targetSpeed * Math.signum(ctx.speed == 0 ? 1 : ctx.speed));
            ctx.selfHandle.setThrottle(targetSpeed / Math.max(0.01, ctx.maxSpeed));
        }
    }

    private String shortId(TrainControlContext ctx) {
        if (ctx.selfHandle == null) return "?";
        return ctx.selfHandle.getId().toString().substring(0, 8);
    }
}
