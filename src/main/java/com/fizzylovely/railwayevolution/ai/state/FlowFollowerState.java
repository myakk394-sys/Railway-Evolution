package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.ObstacleProfile;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/**
 * FlowFollowerState — следование в потоке за попутным поездом.
 *
 * Ключевое состояние экосистемной интеграции:
 * вместо полного останова AI «едет вместе» с лидером (AI или игрок),
 * держа безопасную дистанцию и адаптируя скорость под лидера.
 *
 * Поезда игрока обрабатываются идентично AI-поездам, но SafetyManager
 * агрессивнее реагирует на резкое торможение игрока.
 *
 * Переходы:
 *   → CRUISING           если лидер ушёл далеко (> FLOW_MAX) или исчез
 *   → ANALYZING_OBSTACLE если lидер остановился или риск высокий
 *   → WAIT_FOR_CLEARANCE если дистанция критическая
 *   → TRAFFIC_JAM        если зажаты
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class FlowFollowerState implements TrainControlState {

    // ─── Тайминги ─────────────────────────────────────────────────────────
    private long enteredTick = 0;
    private static final int  MIN_TICKS_BEFORE_EXIT = 10; // не вылетать сразу

    // ─── Плавное следование ───────────────────────────────────────────────
    /**
     * Коэффициент инерции скорости: новая скорость = lerp(текущая, целевая, α).
     * α=0.15 → плавное, без дёрганий.
     */
    private static final double SPEED_LERP_ALPHA = 0.15;

    /**
     * Если дистанция < TARGET_DISTANCE → притормозить.
     * Если дистанция > TARGET_DISTANCE → ускориться до скорости лидера.
     */
    private static final double TARGET_DISTANCE = 12.0; // блоки

    /**
     * Зона «мёртвой зоны» вокруг TARGET_DISTANCE: не реагируем на
     * незначительные отклонения (предотвращает осцилляции).
     */
    private static final double DEAD_ZONE = 2.0;

    // ─── Пороги переходов ─────────────────────────────────────────────────
    private static final double STOP_RISK_THRESHOLD = 0.70; // высокий риск → WFC
    private static final double LEADER_STOPPED_SPEED = 0.05; // лидер стоит

    @Override
    public TrainStateId getId() { return TrainStateId.FLOW_FOLLOWER; }

    @Override
    public void onEnter(TrainControlContext ctx) {
        enteredTick = ctx.currentTick;
        CreateRailwayMod.aiDebug("[FSM] {} → FLOW_FOLLOWER (leader={}, player={})",
            shortId(ctx),
            ctx.obstacleProfile != null && ctx.obstacleProfile.obstacleId != null
                ? ctx.obstacleProfile.obstacleId.toString().substring(0, 8) : "?",
            ctx.obstacleProfile != null && ctx.obstacleProfile.leaderIsPlayer);
    }

    @Override
    public void onExit(TrainControlContext ctx) {
        // Ничего не сбрасываем — плавный выход
    }

    @Override
    public @Nullable TrainStateId tick(TrainControlContext ctx) {
        ObstacleProfile obs = ctx.obstacleProfile;

        // ── Проверка продолжения потока ───────────────────────────────────
        if (obs == null || !obs.hasObstacle()) {
            // Лидер исчез — возвращаемся к обычному движению
            if (ctx.currentTick - enteredTick >= MIN_TICKS_BEFORE_EXIT) {
                return TrainStateId.CRUISING;
            }
            return null;
        }

        if (ctx.sandwiched) return TrainStateId.TRAFFIC_JAM;

        // Экстремальный риск → WFC
        if (obs.isOverlapping || obs.riskScore >= STOP_RISK_THRESHOLD) {
            return TrainStateId.WAIT_FOR_CLEARANCE;
        }

        // Лидер полностью остановился → переходим в анализ
        if (obs.obstacleSpeed < LEADER_STOPPED_SPEED && !obs.isDeparting) {
            return TrainStateId.ANALYZING_OBSTACLE;
        }

        // Лидер вышел за зону потока → возвращаемся в CRUISING
        if (obs.distance > ObstacleProfile.FLOW_MAX_DISTANCE * 1.2) {
            return TrainStateId.CRUISING;
        }

        // Лидер повернул навстречу (стал head-on) → немедленно YIELDING
        if (obs.isHeadOn) {
            return TrainStateId.YIELDING;
        }

        // Поток продолжается → управляем скоростью
        applyFlowSpeed(ctx, obs);
        return null;
    }

    /**
     * Вычислить и применить целевую скорость следования в потоке.
     *
     * Логика дистанционного управления (PD-контроллер без деривативного члена):
     *   - dist < TARGET-DEAD_ZONE → слишком близко: скорость ниже лидера
     *   - dist in DEAD_ZONE → удерживаем скорость лидера
     *   - dist > TARGET+DEAD_ZONE → отстаём: скорость = лидер
     */
    private void applyFlowSpeed(TrainControlContext ctx, ObstacleProfile obs) {
        if (ctx.selfHandle == null) return;

        double leaderSpeed = obs.obstacleSpeed;
        double distance    = obs.distance;
        double currentSpeed = Math.abs(ctx.speed);

        double targetSpeed;

        if (distance < TARGET_DISTANCE - DEAD_ZONE) {
            // Слишком близко → замедляемся
            double ratio = Math.max(0, (distance - ObstacleProfile.FLOW_MIN_DISTANCE)
                                       / (TARGET_DISTANCE - ObstacleProfile.FLOW_MIN_DISTANCE));
            targetSpeed = leaderSpeed * (0.6 + 0.4 * ratio);

        } else if (distance > TARGET_DISTANCE + DEAD_ZONE) {
            // Отстаём → держим скорость лидера (не более maxSpeed)
            targetSpeed = Math.min(leaderSpeed, ctx.maxSpeed);

        } else {
            // Мёртвая зона → точно скорость лидера
            targetSpeed = leaderSpeed;
        }

        // Кинематический предел безопасности (нельзя ехать быстрее чем успеем тормознуть)
        double kinematicLimit = computeKinematicLimit(distance, ctx.acceleration, obs);
        targetSpeed = Math.min(targetSpeed, kinematicLimit);
        targetSpeed = Math.max(0, Math.min(targetSpeed, ctx.maxSpeed));

        // Плавный lerp (нет дёрганий)
        double smoothedSpeed = currentSpeed + (targetSpeed - currentSpeed) * SPEED_LERP_ALPHA;

        ctx.selfHandle.setSpeed(smoothedSpeed);
        ctx.selfHandle.setThrottle(smoothedSpeed / Math.max(0.01, ctx.maxSpeed));
    }

    /**
     * Кинематически безопасная скорость: v = sqrt(2 * a * (d - safeBuffer)).
     * При торможении игрока (leaderIsPlayer) применяем дополнительный запас.
     */
    private double computeKinematicLimit(double distance, double accel, ObstacleProfile obs) {
        // Для поезда игрока — больший буфер безопасности (он может тормознуть резко)
        double safeBuffer = obs.leaderIsPlayer ? 4.0 : 2.5;
        double usableDist = Math.max(0, distance - safeBuffer);
        return Math.sqrt(2 * accel * usableDist);
    }

    private String shortId(TrainControlContext ctx) {
        if (ctx.selfHandle == null) return "?";
        return ctx.selfHandle.getId().toString().substring(0, 8);
    }
}
