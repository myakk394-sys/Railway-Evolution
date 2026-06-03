package com.fizzylovely.railwayevolution.ai.core;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * TrainControlContext — иммутабельный снимок состояния поезда за текущий тик.
 *
 * Создаётся ОДИН экземпляр на контроллер и переиспользуется каждый тик
 * методом {@link #reset()}. Нулевых аллокаций в горячем пути.
 *
 * Заполняется в TrainAIController.tick() из ITrainHandle перед вызовом StateMachine.
 * Читается состояниями State Machine и PerceptionEngine (read-only).
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class TrainControlContext {

    // ─── Временна́я метка ──────────────────────────────────────────────────
    public long        currentTick;
    public ServerLevel level;

    // ─── Ссылка на наш хэндл (только для вызова команд — setSpeed и т.п.) ─
    public ITrainHandle selfHandle;

    // ─── Кинематика ───────────────────────────────────────────────────────
    public double speed;              // текущая скорость (б/тик), + = вперёд
    public double maxSpeed;           // максимально допустимая
    public double acceleration;       // ускорение/торможение (б/тик²)

    // ─── Навигация ────────────────────────────────────────────────────────
    public double  distToDestination; // 0 если нет маршрута
    public boolean hasDestination;
    public boolean createWaitingForSignal;
    public double  createDistToSignal;

    // ─── Граф ─────────────────────────────────────────────────────────────
    public boolean onGraph;
    public boolean derailed;
    public boolean blocked;           // конец пути

    // ─── Управление ───────────────────────────────────────────────────────
    public boolean playerControlled;
    @Nullable public UUID playerUUID; // null если нет игрока

    // ─── Позиция и вектор движения ────────────────────────────────────────
    @Nullable public Vec3 leadingPosition;   // позиция головы поезда
    @Nullable public Vec3 trailingPosition;  // позиция хвоста
    public double headingX;                  // нормализованный вектор движения (XZ)
    public double headingZ;
    public boolean headingValid;             // true если вектор надёжен

    // ─── Результаты PerceptionEngine ─────────────────────────────────────

    /**
     * Обнаруженное препятствие. null = путь свободен.
     * Заполняется PerceptionEngine перед вызовом StateMachine.tick().
     */
    @Nullable public ObstacleProfile obstacleProfile;

    /**
     * Обнаружен ли позади нас поезд (reverse path check).
     * Используется для "sandwiched" определения.
     */
    public boolean spaceAvailableBehind;

    /**
     * Поезд "зажат": препятствие спереди И поезд сзади,
     * и расстояние спереди ≤ JAM_FORWARD_THRESHOLD.
     */
    public boolean sandwiched;

    // ─── Состояние перекрёстка ────────────────────────────────────────────
    public boolean junctionBlocked;    // перекрёсток занят
    public boolean junctionHasSpace;   // достаточно места за перекрёстком
    @Nullable public UUID junctionYieldToId;  // UUID поезда которому уступаем

    // ─── Вспомогательные флаги ────────────────────────────────────────────
    public boolean graphScanRan;       // BFS выполнился с реальными данными
    public boolean isWrongWay;         // едем против нормального направления ребра

    // ─── FLOW_FOLLOWER данные ─────────────────────────────────────────────
    /**
     * true если обнаруженный поезд едет В ТОМ ЖЕ направлении что и мы,
     * и его скорость ≥ нашей → можем "ехать в потоке" вместо полного останова.
     */
    public boolean obstacleIsFlowCandidate;

    /**
     * Целевая скорость для FLOW_FOLLOWER режима (скорость лидера).
     * Устанавливается PerceptionEngine при obstacleIsFlowCandidate=true.
     */
    public double flowLeaderSpeed;

    /**
     * true если лидер потока — поезд под управлением игрока.
     * SafetyManager будет агрессивнее следить за внезапными торможениями.
     */
    public boolean flowLeaderIsPlayer;

    // ─── Константы ────────────────────────────────────────────────────────
    public static final double JAM_FORWARD_THRESHOLD = 15.0; // блоки

    // ──────────────────────────────────────────────────────────────────────

    /**
     * Сброс в начало тика. Вызывается TrainAIController ДО заполнения.
     * Нулевых аллокаций — только присваивания примитивов и null-ов.
     */
    public void reset() {
        currentTick = 0;
        level = null;
        selfHandle = null;

        speed = 0;
        maxSpeed = 1.4;
        acceleration = 0.0375;

        distToDestination = 0;
        hasDestination = false;
        createWaitingForSignal = false;
        createDistToSignal = Double.MAX_VALUE;

        onGraph = false;
        derailed = false;
        blocked = false;

        playerControlled = false;
        playerUUID = null;

        leadingPosition = null;
        trailingPosition = null;
        headingX = 0;
        headingZ = 1;
        headingValid = false;

        obstacleProfile = null;
        spaceAvailableBehind = true;
        sandwiched = false;

        junctionBlocked = false;
        junctionHasSpace = true;
        junctionYieldToId = null;

        graphScanRan = false;
        isWrongWay = false;

        obstacleIsFlowCandidate = false;
        flowLeaderSpeed = 0;
        flowLeaderIsPlayer = false;
    }

    /**
     * Заполнить кинематику из хэндла. Вызывается после reset().
     */
    public void fillFrom(ITrainHandle handle, long tick, ServerLevel lvl) {
        this.currentTick     = tick;
        this.level           = lvl;
        this.selfHandle      = handle;
        this.speed           = handle.getSpeed();
        this.maxSpeed        = handle.getMaxSpeed();
        this.acceleration    = handle.getAcceleration();
        this.distToDestination = handle.getDistanceToDestination();
        this.hasDestination  = handle.hasDestination();
        this.createWaitingForSignal = handle.isWaitingForSignal();
        this.createDistToSignal     = handle.getDistanceToSignal();
        this.onGraph         = handle.isOnGraph();
        this.derailed        = handle.isDerailed();
        this.blocked         = handle.isBlocked();
        this.playerControlled= handle.isPlayerControlled();
        this.playerUUID      = handle.getControllingPlayerUUID();
        this.leadingPosition = handle.getLeadingPosition();
        this.trailingPosition= handle.getTrailingPosition();
    }
}
