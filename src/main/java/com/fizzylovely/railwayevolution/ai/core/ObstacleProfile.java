package com.fizzylovely.railwayevolution.ai.core;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * ObstacleProfile — непрерывный профиль обнаруженного препятствия.
 *
 * Один экземпляр на PerceptionEngine, переиспользуется каждый тик
 * методом {@link #clear()}. Нулевых аллокаций.
 *
 * Ключевое улучшение над старым «bool obstacle»: вместо дискретного
 * «нашли/не нашли» — непрерывный {@link #riskScore} [0..1], позволяющий
 * StateMachine плавно управлять скоростью.
 *
 * Экосистемная интеграция:
 *   Поезда под управлением игрока обрабатываются ИДЕНТИЧНО AI-поездам,
 *   но {@link #leaderIsPlayer} сигнализирует SafetyManager о повышенной
 *   реакции на резкое торможение игрока.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class ObstacleProfile {

    // ─── Идентификация ────────────────────────────────────────────────────
    /** UUID обнаруженного поезда-препятствия. null = чисто. */
    @Nullable public UUID obstacleId;

    /** Является ли лидер поездом под управлением игрока. */
    public boolean leaderIsPlayer;

    // ─── Геометрия ────────────────────────────────────────────────────────
    /**
     * Расстояние от головы нашего поезда до хвоста препятствия (блоки).
     * Отрицательное → физическое перекрытие (overlap emergency).
     * Double.MAX_VALUE → нет препятствия.
     */
    public double distance;

    /** Расстояние найдено через BFS-граф (более точно на кривых). */
    public boolean distanceIsGraphBased;

    // ─── Кинематика препятствия ───────────────────────────────────────────
    /**
     * Скорость сближения (б/тик):
     *   > 0 → мы приближаемся к препятствию (опасно).
     *   < 0 → препятствие удаляется быстрее нас.
     *   = 0 → параллельное движение.
     */
    public double closingSpeed;

    /** Абсолютная скорость препятствия (б/тик). */
    public double obstacleSpeed;

    // ─── Классификация ────────────────────────────────────────────────────
    /**
     * Препятствие движется навстречу нам (лоб в лоб).
     * Наивысший приоритет — немедленная остановка или разъезд.
     */
    public boolean isHeadOn;

    /**
     * Препятствие едет В ТОМ ЖЕ направлении и быстрее нас.
     * Мы можем «влиться в поток» вместо полной остановки.
     */
    public boolean isDeparting;

    /**
     * Препятствие стоит на станции/в депо (тупик, parking zone).
     * AI не пытается объехать — это конечная точка маршрута.
     */
    public boolean isParkingZone;

    /**
     * FLOW_FOLLOWER кандидат:
     * isDeparting=true AND скорости сопоставимы AND дистанция в [FLOW_MIN..FLOW_MAX].
     * При true StateMachine переходит в FLOW_FOLLOWER вместо YIELDING.
     */
    public boolean isFlowCandidate;

    /**
     * Целевая скорость для следования в потоке:
     * min(leaderSpeed, кинематически безопасная скорость).
     * Устанавливается только при isFlowCandidate=true.
     */
    public double flowTargetSpeed;

    // ─── Риск ─────────────────────────────────────────────────────────────
    /**
     * Непрерывный score риска [0.0 .. 1.0]:
     *   0.0 = препятствие далеко, безопасно
     *   1.0 = немедленная опасность столкновения
     *
     * Формула: max(distanceRisk, headOnBonus, ttcRisk)
     * Используется computeTargetSpeed() для плавного управления скоростью.
     */
    public double riskScore;

    // ─── Перекрёсток ──────────────────────────────────────────────────────
    public boolean junctionBlocked;
    public boolean junctionHasSpace;
    @Nullable public UUID junctionYieldToId;

    // ─── Авариное перекрытие ──────────────────────────────────────────────
    /**
     * true если distance ≤ OVERLAP_THRESHOLD (≈ 0 блоков).
     * Требует немедленной остановки независимо от riskScore.
     */
    public boolean isOverlapping;

    // ─── Константы ────────────────────────────────────────────────────────
    public static final double FLOW_MIN_DISTANCE     = 6.0;   // ближе → YIELDING, не FLOW
    public static final double FLOW_MAX_DISTANCE     = 40.0;  // дальше → обычный CRUISING
    public static final double FLOW_SPEED_TOLERANCE  = 0.15;  // б/тик — разница скоростей
    public static final double OVERLAP_THRESHOLD     = 1.0;   // блоки — считать перекрытием

    // v1.0.8 Fix #11: Per-category tracking (zero-alloc)
    public double nearestHeadOnDist          = Double.MAX_VALUE;
    @Nullable public UUID nearestHeadOnId;
    public double nearestSameDirectionDist   = Double.MAX_VALUE;
    @Nullable public UUID nearestSameDirectionId;
    public double nearestStoppedDist         = Double.MAX_VALUE;
    @Nullable public UUID nearestStoppedId;
    public double nearestPhysicalThreatDist  = Double.MAX_VALUE;
    @Nullable public UUID nearestPhysicalThreatId;

    // Primary candidate is selected after every scan candidate has been collected.
    private double primarySelectionScore     = Double.NEGATIVE_INFINITY;

    // ──────────────────────────────────────────────────────────────────────

    /** Очистка перед новым сканом. Нулевых аллокаций. */
    public void clear() {
        obstacleId          = null;
        leaderIsPlayer      = false;
        distance            = Double.MAX_VALUE;
        distanceIsGraphBased = false;
        closingSpeed        = 0;
        obstacleSpeed       = 0;
        isHeadOn            = false;
        isDeparting         = false;
        isParkingZone       = false;
        isFlowCandidate     = false;
        flowTargetSpeed     = 0;
        riskScore           = 0;
        junctionBlocked     = false;
        junctionHasSpace    = true;
        junctionYieldToId   = null;
        isOverlapping       = false;
        // v1.0.8 Fix #11: Reset categories
        nearestHeadOnDist        = Double.MAX_VALUE;
        nearestHeadOnId          = null;
        nearestSameDirectionDist = Double.MAX_VALUE;
        nearestSameDirectionId   = null;
        nearestStoppedDist       = Double.MAX_VALUE;
        nearestStoppedId         = null;
        nearestPhysicalThreatDist = Double.MAX_VALUE;
        nearestPhysicalThreatId   = null;
        primarySelectionScore     = Double.NEGATIVE_INFINITY;
    }

    /** Есть ли обнаруженное препятствие. */
    public boolean hasObstacle() {
        return obstacleId != null;
    }

    /**
     * Вычислить непрерывный riskScore на основе всех факторов.
     *
     * @param maxRange максимальная дальность скана (для нормализации distanceRisk)
     */
    public void computeRiskScore(double maxRange) {
        if (!hasObstacle()) {
            riskScore = 0;
            return;
        }

        // 1. Дистанционный риск: линейное убывание с расстоянием
        double distRisk = (distance >= maxRange)
            ? 0
            : Math.max(0, 1.0 - distance / maxRange);

        // 2. TTC (Time To Collision) риск
        double ttcRisk = 0;
        if (closingSpeed > 0.005 && distance > 0) {
            double ttcTicks = distance / closingSpeed;
            // TTC < 30 тиков (1.5 сек) → риск 1.0; > 100 тиков → 0
            ttcRisk = Math.max(0, 1.0 - ttcTicks / 100.0);
        }

        // 3. Бонус за встречное движение
        double headOnBonus = isHeadOn ? 0.35 : 0;

        // 4. Штраф за поезд игрока (реагируем быстрее — он может резко тормозить)
        double playerBonus = leaderIsPlayer ? 0.10 : 0;

        // 5. Overlap → максимальный риск
        if (isOverlapping) {
            riskScore = 1.0;
            return;
        }

        riskScore = Math.min(1.0, distRisk + ttcRisk + headOnBonus + playerBonus);
    }

    /**
     * Обновить профиль данными другого кандидата, если он опаснее.
     * Используется PerceptionEngine при многослойном сканировании.
     *
     * @param candidate кандидат (временный, не аллоцируется — поля передаются напрямую)
     */
    public void updateIfCloser(
            UUID candidateId, boolean candidateIsPlayer,
            double candidateDist, double candidateObstacleSpeed,
            double candidateClosingSpeed,
            boolean candidateIsHeadOn, boolean candidateIsDeparting,
            boolean candidateIsParkingZone, boolean candidateDistGraphBased) {

        // Keep category data independent from the final primary selection.
        if (candidateIsHeadOn && candidateDist < nearestHeadOnDist) {
            nearestHeadOnDist = candidateDist;
            nearestHeadOnId = candidateId;
        }
        if (!candidateIsHeadOn && candidateObstacleSpeed > 0.05 && candidateDist < nearestSameDirectionDist) {
            nearestSameDirectionDist = candidateDist;
            nearestSameDirectionId = candidateId;
        }
        if (candidateObstacleSpeed < 0.05 && candidateDist < nearestStoppedDist) {
            nearestStoppedDist = candidateDist;
            nearestStoppedId = candidateId;
        }
        if (!candidateDistGraphBased && candidateDist < nearestPhysicalThreatDist) {
            nearestPhysicalThreatDist = candidateDist;
            nearestPhysicalThreatId = candidateId;
        }

        double candidateRisk = selectionRisk(candidateDist, candidateClosingSpeed,
                candidateIsHeadOn, candidateObstacleSpeed, candidateDistGraphBased);
        if (candidateRisk <= primarySelectionScore) return;

        primarySelectionScore     = candidateRisk;
        this.obstacleId           = candidateId;
        this.leaderIsPlayer       = candidateIsPlayer;
        this.distance             = candidateDist;
        this.distanceIsGraphBased = candidateDistGraphBased;
        this.obstacleSpeed        = candidateObstacleSpeed;
        this.closingSpeed         = candidateClosingSpeed;
        this.isHeadOn             = candidateIsHeadOn;
        this.isDeparting          = candidateIsDeparting;
        this.isParkingZone        = candidateIsParkingZone;
        this.isOverlapping        = candidateDist <= OVERLAP_THRESHOLD;
    }

    /**
     * Relative speed and direction take precedence over raw proximity. This keeps a
     * closing or head-on train selected over a nearby train that is moving away.
     */
    private static double selectionRisk(double candidateDist, double candidateClosingSpeed,
                                        boolean candidateIsHeadOn, double candidateSpeed,
                                        boolean candidateDistGraphBased) {
        double safeDistance = Math.max(0.0, candidateDist);
        double distanceRisk = 1.0 / (1.0 + safeDistance / 12.0);
        double closingRisk = Math.max(0.0, candidateClosingSpeed) * 2.0;
        double directionRisk = candidateIsHeadOn ? 1.0 : 0.0;
        double stoppedRisk = candidateSpeed < 0.05 ? 0.30 : 0.0;
        double physicalRisk = candidateDistGraphBased ? 0.0 : 0.10;
        return distanceRisk + closingRisk + directionRisk + stoppedRisk + physicalRisk;
    }

    /**
     * После заполнения всех полей — определить FLOW_FOLLOWER кандидатуру.
     *
     * @param mySpeed текущая скорость нашего поезда
     */
    public void evaluateFlowCandidate(double mySpeed) {
        if (!hasObstacle()) {
            isFlowCandidate = false;
            return;
        }

        // Условия для FLOW_FOLLOWER:
        // 1. Препятствие движется в том же направлении (не навстречу, не стоит)
        // 2. Дистанция в безопасном диапазоне
        // 3. Скорость лидера >= нашей (он не тормозит перед нами)
        // 4. НЕ перекрытие
        boolean directionOk  = isDeparting && !isHeadOn;
        boolean distanceOk   = distance >= FLOW_MIN_DISTANCE && distance <= FLOW_MAX_DISTANCE;
        boolean speedOk      = obstacleSpeed >= mySpeed - FLOW_SPEED_TOLERANCE;
        boolean notOverlap   = !isOverlapping;
        boolean notParking   = !isParkingZone;

        isFlowCandidate = directionOk && distanceOk && speedOk && notOverlap && notParking;

        if (isFlowCandidate) {
            // Целевая скорость = скорость лидера с небольшой скидкой безопасности
            // Чем ближе лидер — тем медленнее мы держимся
            double distanceFactor = Math.min(1.0, (distance - FLOW_MIN_DISTANCE)
                                                  / (FLOW_MAX_DISTANCE - FLOW_MIN_DISTANCE));
            // При distance==FLOW_MIN → 85% скорости лидера
            // При distance==FLOW_MAX → 100% скорости лидера
            double speedFactor = 0.85 + 0.15 * distanceFactor;
            flowTargetSpeed = obstacleSpeed * speedFactor;
        } else {
            flowTargetSpeed = 0;
        }
    }

    @Override
    public String toString() {
        if (!hasObstacle()) return "ObstacleProfile[clear]";
        return String.format(
            "ObstacleProfile[id=%s, player=%b, dist=%.1f, closing=%.3f, risk=%.2f, flow=%b, headOn=%b]",
            obstacleId.toString().substring(0, 8), leaderIsPlayer,
            distance, closingSpeed, riskScore, isFlowCandidate, isHeadOn
        );
    }
}
