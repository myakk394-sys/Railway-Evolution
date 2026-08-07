package com.fizzylovely.railwayevolution.ai.core;

import java.util.UUID;

/**
 * SafetyManager — единственный хранитель права на вето команды скорости.
 *
 * Вызывается для ЛЮБОГО изменения скорости — как от AI-состояний,
 * так и от команды игрока. Только в экстремальных ситуациях (физическое
 * перекрытие, сход с рельс) блокирует даже игрока.
 *
 * Экосистемная интеграция:
 *   - Поезд игрока = «авторитетный участник». Его скорость не изменяется.
 *   - Но SafetyManager оценивает риск ДЛЯ НАШЕГО поезда когда мы едем
 *     за игроком, и ограничивает нашу скорость если игрок резко тормозит.
 *
 * Потокобезопасен (только чтение полей конфига, нет общего состояния).
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class SafetyManager {

    // ─── Вердикты ─────────────────────────────────────────────────────────

    public enum Verdict {
        /** Команда разрешена. */
        ALLOW,
        /** Блокируем: неминуемое столкновение (overlap). Блокирует даже игрока. */
        VETO_COLLISION,
        /** Блокируем: сход с рельс. Блокирует даже игрока. */
        VETO_DERAIL,
        /** Разрешаем, но с ограничением максимальной скорости. */
        CLAMP_SPEED,
        /**
         * Экстренное торможение лидера потока (игрок резко тормознул).
         * Наш поезд должен немедленно снизить скорость до {@code maxAllowedSpeed}.
         */
        EMERGENCY_FOLLOW_BRAKE
    }

    public static final class SafetyResult {
        public final Verdict verdict;
        /** Максимально допустимая скорость. Double.MAX_VALUE = без ограничений. */
        public final double maxAllowedSpeed;

        public SafetyResult(Verdict verdict, double maxAllowedSpeed) {
            this.verdict = verdict;
            this.maxAllowedSpeed = maxAllowedSpeed;
        }

        public static final SafetyResult ALLOW_FULL =
            new SafetyResult(Verdict.ALLOW, Double.MAX_VALUE);

        public boolean blocks() {
            return verdict == Verdict.VETO_COLLISION || verdict == Verdict.VETO_DERAIL;
        }

        /** Удобный accessor для switch-выражений в tickEcosystemV2. */
        public Verdict verdict() { return verdict; }
    }

    // ─── Параметры ────────────────────────────────────────────────────────

    /** Блоки — зона перекрытия (требует немедленной блокировки). */
    private static final double OVERLAP_VETO_THRESHOLD = 2.0;

    /** Блоки — критическая зона (ограничиваем скорость). */
    private static final double CRITICAL_CLAMP_THRESHOLD = 8.0;

    /**
     * При экстренном торможении лидера: если его скорость упала более
     * чем на LEADER_DECEL_THRESHOLD б/тик за тик — наш ответ немедленный.
     */
    private static final double LEADER_DECEL_THRESHOLD = 0.05;

    /**
     * Доля скорости лидера: наш максимум при EMERGENCY_FOLLOW_BRAKE.
     * 0.7 = снижаем до 70% от текущей скорости лидера.
     */
    private static final double EMERGENCY_FOLLOW_SPEED_FACTOR = 0.7;

    // Tracked для детектирования резкого торможения лидера
    private double prevLeaderSpeed = Double.NaN;
    private UUID prevLeaderId;

    // ──────────────────────────────────────────────────────────────────────

    /**
     * Оценить допустимость команды скорости {@code requestedSpeed}
     * для нашего поезда в текущем контексте.
     *
     * @param ctx            контекст нашего поезда
     * @param requestedSpeed желаемая скорость (+ = вперёд)
     * @return SafetyResult с вердиктом и максимальной допустимой скоростью
     */
    public SafetyResult evaluate(TrainControlContext ctx, double requestedSpeed) {

        // ── VETO 1: Сход с рельс — полная блокировка, включая игрока ──────
        if (ctx.derailed) {
            resetLeaderTracking();
            return new SafetyResult(Verdict.VETO_DERAIL, 0);
        }

        ObstacleProfile obs = ctx.obstacleProfile;

        // ── VETO 2: Физическое перекрытие — блокируем даже игрока ──────────
        if (obs != null && obs.hasObstacle() && obs.isOverlapping
                && requestedSpeed > 0) {
            resetLeaderTracking();
            return new SafetyResult(Verdict.VETO_COLLISION, 0);
        }

        // ── Красный сигнал — только для AI (игрок решает сам) ──────────────
        if (!ctx.playerControlled
                && ctx.createWaitingForSignal
                && ctx.createDistToSignal < 3.0
                && requestedSpeed > 0) {
            return new SafetyResult(Verdict.CLAMP_SPEED, 0);
        }

        // ── Нет препятствия → разрешаем ────────────────────────────────────
        if (obs == null || !obs.hasObstacle()) {
            resetLeaderTracking();
            return SafetyResult.ALLOW_FULL;
        }

        double dist = obs.distance;

        // ── CLAMP: критическая зона ─────────────────────────────────────────
        if (dist <= CRITICAL_CLAMP_THRESHOLD && requestedSpeed > 0) {
            // Кинематически безопасная скорость: v = sqrt(2 * a * (d - buffer))
            double buffer     = Math.min(2.0, dist * 0.25);
            double kinematic  = Math.sqrt(2 * ctx.acceleration * Math.max(0, dist - buffer));
            double clampSpeed = Math.max(0, Math.min(Math.abs(requestedSpeed), kinematic));
            return new SafetyResult(Verdict.CLAMP_SPEED, clampSpeed);
        }

        // ── FLOW_FOLLOWER: детектирование резкого торможения лидера ────────
        if (obs.isFlowCandidate || obs.isDeparting) {
            double leaderSpeed = obs.obstacleSpeed;

            if (obs.obstacleId != null && obs.obstacleId.equals(prevLeaderId)
                    && !Double.isNaN(prevLeaderSpeed)) {
                double leaderDecel = prevLeaderSpeed - leaderSpeed; // > 0 = тормозит

                if (leaderDecel > LEADER_DECEL_THRESHOLD) {
                    // Лидер резко затормозил — экстренная реакция
                    double emergencyTarget = leaderSpeed * EMERGENCY_FOLLOW_SPEED_FACTOR;
                    prevLeaderSpeed = leaderSpeed;
                    prevLeaderId = obs.obstacleId;
                    return new SafetyResult(Verdict.EMERGENCY_FOLLOW_BRAKE, emergencyTarget);
                }
            }
            prevLeaderSpeed = leaderSpeed;
            prevLeaderId = obs.obstacleId;
        } else {
            resetLeaderTracking();
        }

        return SafetyResult.ALLOW_FULL;
    }

    /**
     * Применить вердикт к контексту: корректирует скорость нашего поезда.
     * Вызывается только для AI-поездов — не для Player-поездов.
     *
     * @param result  вердикт от {@link #evaluate}
     * @param ctx     контекст нашего поезда
     */
    public void applyVerdict(SafetyResult result, TrainControlContext ctx) {
        if (ctx.selfHandle == null) return;

        switch (result.verdict) {
            case VETO_COLLISION, VETO_DERAIL -> {
                ctx.selfHandle.forceStop();
            }
            case CLAMP_SPEED -> {
                double current = Math.abs(ctx.speed);
                if (current > result.maxAllowedSpeed) {
                    double sign = ctx.speed >= 0 ? 1 : -1;
                    ctx.selfHandle.setSpeed(result.maxAllowedSpeed * sign);
                    ctx.selfHandle.setThrottle(
                        result.maxAllowedSpeed / Math.max(0.01, ctx.maxSpeed));
                }
            }
            case EMERGENCY_FOLLOW_BRAKE -> {
                double current = Math.abs(ctx.speed);
                if (current > result.maxAllowedSpeed) {
                    double sign = ctx.speed >= 0 ? 1 : -1;
                    ctx.selfHandle.setSpeed(result.maxAllowedSpeed * sign);
                    ctx.selfHandle.setThrottle(
                        result.maxAllowedSpeed / Math.max(0.01, ctx.maxSpeed));
                }
            }
            case ALLOW -> { /* ничего не делаем */ }
        }
    }

    /** Сброс истории скорости лидера при смене состояния. */
    public void resetLeaderTracking() {
        prevLeaderSpeed = Double.NaN;
        prevLeaderId = null;
    }
}
