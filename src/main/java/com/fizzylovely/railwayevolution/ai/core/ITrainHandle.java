package com.fizzylovely.railwayevolution.ai.core;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * ITrainHandle — единственная абстракция поезда для AI-ядра.
 *
 * Ядро (PerceptionEngine, StateMachine, SafetyManager) НИКОГДА не импортирует
 * классы Create напрямую. Всё взаимодействие — через этот интерфейс.
 *
 * Реализации:
 *   - {@link com.fizzylovely.railwayevolution.ai.adapter.Create1211TrainHandle}
 *     для обычных поездов Create (AI + безнадзорные).
 *   - {@link com.fizzylovely.railwayevolution.ai.adapter.PlayerTrainHandle}
 *     для поездов под управлением игрока — равноправный участник трафика.
 *
 * Все методы вызываются только с серверного потока.
 * Реализации должны быть потокобезопасны для read-only доступа.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public interface ITrainHandle {

    // ─────────────────────────────────────────────────────────────────────────
    // Идентификация
    // ─────────────────────────────────────────────────────────────────────────

    /** UUID поезда (совпадает с Create's Train.id). */
    UUID getId();

    /** Отображаемое имя поезда (для отладки/UI). */
    String getDisplayName();

    // ─────────────────────────────────────────────────────────────────────────
    // Кинематика
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Текущая скорость в блоках/тик. Знак: + = вперёд по оси навигации.
     * Читается через VarHandle — нулевое боксирование.
     */
    double getSpeed();

    /**
     * Установить скорость. Только для AI-контролируемых поездов.
     * Для {@code PlayerTrainHandle} — no-op (нельзя переопределять волю игрока).
     */
    void setSpeed(double speed);

    /** Максимальная скорость (блоков/тик) при текущем конфиге. */
    double getMaxSpeed();

    /** Ускорение/замедление (блоков/тик²) из конфига Create. */
    double getAcceleration();

    /**
     * Текущий дроссель (0.0 – 1.0).
     * Изменяется AI для плавного управления скоростью.
     */
    double getThrottle();

    /** Установить дроссель. No-op для PlayerTrainHandle. */
    void setThrottle(double throttle);

    // ─────────────────────────────────────────────────────────────────────────
    // Статус
    // ─────────────────────────────────────────────────────────────────────────

    /** Поезд сошёл с рельс (derailed). */
    boolean isDerailed();

    /**
     * Поезд заблокирован (конец пути, тупик).
     * Соответствует Create's TravellingPoint.blocked.
     */
    boolean isBlocked();

    /**
     * Поезд управляется игроком прямо сейчас.
     * Для {@code PlayerTrainHandle} всегда возвращает {@code true}.
     * Для AI-поезда — читает Train.manualTick через VarHandle.
     */
    boolean isPlayerControlled();

    /**
     * UUID игрока, управляющего поездом. {@code null} если никто не управляет.
     * Используется SafetyManager для отправки предупреждений.
     */
    @Nullable
    UUID getControllingPlayerUUID();

    // ─────────────────────────────────────────────────────────────────────────
    // Навигация
    // ─────────────────────────────────────────────────────────────────────────

    /** Расстояние до конечной точки маршрута (блоки). 0 если нет маршрута. */
    double getDistanceToDestination();

    /** Поезд имеет активный маршрут к станции. */
    boolean hasDestination();

    /**
     * Create ожидает зелёного сигнала.
     * Для PlayerTrainHandle — всегда false (игрок сам видит сигнал).
     */
    boolean isWaitingForSignal();

    /** Расстояние до блокирующего сигнала (блоки). Double.MAX_VALUE если нет. */
    double getDistanceToSignal();

    // ─────────────────────────────────────────────────────────────────────────
    // Граф и позиция
    // ─────────────────────────────────────────────────────────────────────────

    /** Поезд находится на TrackGraph (не в процессе миграции). */
    boolean isOnGraph();

    /**
     * Позиция ведущей тележки в мировых координатах.
     * {@code null} если поезд не имеет сущностей (выгружен).
     */
    @Nullable
    Vec3 getLeadingPosition();

    /**
     * Позиция хвостовой тележки в мировых координатах.
     * {@code null} если выгружен.
     */
    @Nullable
    Vec3 getTrailingPosition();

    /**
     * Все якорные точки тележек (для подсчёта длины состава).
     * Порядок: от головы к хвосту.
     */
    List<Vec3> getAllBogeyPositions();

    /**
     * Суммарная длина состава в блоках (от головы до хвоста).
     * Вычисляется из bogeyPositions + carriageSpacing.
     */
    double getTotalLength();

    /**
     * Граф путей, к которому привязан поезд.
     * {@code null} если поезд не на графе.
     */
    @Nullable
    ITrainGraph getGraph();

    /**
     * Идентификатор ведущего ребра (object identity из TrackGraph).
     * Используется PerceptionEngine для BFS без reflection на горячем пути.
     * {@code null} если нет данных.
     */
    @Nullable
    Object getLeadingEdgeRef();

    /**
     * Узел node1 ведущей TravellingPoint.
     * {@code null} если нет данных.
     */
    @Nullable
    Object getLeadingNode1Ref();

    /**
     * Узел node2 ведущей TravellingPoint (направление движения).
     * {@code null} если нет данных.
     */
    @Nullable
    Object getLeadingNode2Ref();

    /**
     * Позиция на ведущем ребре (0..edgeLength).
     */
    double getLeadingEdgePosition();

    /**
     * UUID занятых signal groups (для isOnSameTrack проверки).
     * Возвращает пустой список если нет данных.
     */
    List<UUID> getOccupiedSignalGroups();

    // ─────────────────────────────────────────────────────────────────────────
    // Управляющие команды (только для AI-поездов)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Пробудить навигацию Create (снять паузу ScheduleRuntime).
     * No-op для PlayerTrainHandle.
     */
    void resumeNavigation();

    /**
     * Отменить активный маршрут (cancelNavigation).
     * No-op для PlayerTrainHandle.
     */
    void cancelNavigation();

    /**
     * Полная аварийная остановка:
     * throttle=0, speed=0, targetSpeed=0.
     * No-op для PlayerTrainHandle.
     */
    void forceStop();

    /**
     * Восстановить полный дроссель (1.0).
     * No-op для PlayerTrainHandle.
     */
    void restoreFullThrottle();
}
