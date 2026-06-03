package com.fizzylovely.railwayevolution.ai.adapter;

import com.fizzylovely.railwayevolution.ai.core.ITrainHandle;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * PlayerTrainHandle — адаптер для поезда под управлением игрока.
 *
 * Делает поезд игрока равноправным участником экосистемы трафика:
 * PerceptionEngine видит его через тот же ITrainHandle интерфейс,
 * что и AI-поезда. ObstacleProfile.leaderIsPlayer=true сигнализирует
 * SafetyManager о повышенной реакции.
 *
 * Ключевые свойства:
 *   - setSpeed/setThrottle/forceStop/cancelNavigation → NO-OP.
 *     Нельзя переопределять волю игрока.
 *   - isPlayerControlled() → всегда true.
 *   - Скорость читается из базового AI-хэндла через делегирование.
 *     Не нужен второй VarHandle — данные уже прочитаны.
 *
 * Жизненный цикл:
 *   Создаётся TrainAIManager.scanForTrains() когда detectPlayerRiding()
 *   возвращает игрока для данного поезда. Сохраняется пока игрок в кабине.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class PlayerTrainHandle implements ITrainHandle {

    /**
     * Базовый хэндл поезда (Create1211TrainHandle).
     * Делегируем все read-only операции чтения данных.
     * Write-операции — NO-OP.
     */
    private final ITrainHandle delegate;

    /** UUID игрока, управляющего поездом. */
    private final UUID playerUUID;

    /** Ссылка на ServerPlayer для оповещений SafetyManager. */
    @Nullable
    private final ServerPlayer player;

    // ──────────────────────────────────────────────────────────────────────

    public PlayerTrainHandle(ITrainHandle delegate, UUID playerUUID,
                             @Nullable ServerPlayer player) {
        this.delegate   = delegate;
        this.playerUUID = playerUUID;
        this.player     = player;
    }

    // ─── Идентификация ────────────────────────────────────────────────────

    @Override public UUID   getId()          { return delegate.getId(); }
    @Override public String getDisplayName() {
        return delegate.getDisplayName() + " [" + getPlayerName() + "]";
    }

    // ─── Кинематика (READ-ONLY делегирование) ─────────────────────────────

    @Override public double getSpeed()        { return delegate.getSpeed(); }
    @Override public double getMaxSpeed()     { return delegate.getMaxSpeed(); }
    @Override public double getAcceleration() { return delegate.getAcceleration(); }
    @Override public double getThrottle()     { return delegate.getThrottle(); }

    // ─── Команды → NO-OP (нельзя управлять поездом игрока) ───────────────

    /**
     * NO-OP. Скорость поезда игрока устанавливает сам игрок.
     * SafetyManager может вызвать этот метод при overlap/derail,
     * но мы намеренно игнорируем — это ответственность самого игрока.
     *
     * Единственное исключение: если нужно ПРЕДУПРЕДИТЬ игрока —
     * это делается через отдельный канал (звук, чат), не через setSpeed.
     */
    @Override public void setSpeed(double speed)       { /* NO-OP */ }
    @Override public void setThrottle(double throttle) { /* NO-OP */ }
    @Override public void forceStop()                  { /* NO-OP */ }
    @Override public void restoreFullThrottle()        { /* NO-OP */ }
    @Override public void resumeNavigation()           { /* NO-OP */ }
    @Override public void cancelNavigation()           { /* NO-OP */ }

    // ─── Статус ───────────────────────────────────────────────────────────

    @Override public boolean isDerailed()        { return delegate.isDerailed(); }
    @Override public boolean isBlocked()         { return false; } // игрок сам видит
    @Override public boolean isPlayerControlled(){ return true; } // всегда true

    @Override
    public @Nullable UUID getControllingPlayerUUID() { return playerUUID; }

    // ─── Навигация ────────────────────────────────────────────────────────

    /**
     * Для поезда игрока возвращаем оставшееся расстояние из базового хэндла
     * (если у поезда есть расписание и автопилот).
     */
    @Override public double  getDistanceToDestination() { return delegate.getDistanceToDestination(); }
    @Override public boolean hasDestination()           { return delegate.hasDestination(); }

    /** Игрок сам видит сигнал — не блокируем его через нашу логику. */
    @Override public boolean isWaitingForSignal()      { return false; }
    @Override public double  getDistanceToSignal()     { return Double.MAX_VALUE; }

    // ─── Граф и позиция ───────────────────────────────────────────────────

    @Override public boolean isOnGraph() { return delegate.isOnGraph(); }

    @Override public @Nullable Vec3 getLeadingPosition()  { return delegate.getLeadingPosition(); }
    @Override public @Nullable Vec3 getTrailingPosition() { return delegate.getTrailingPosition(); }
    @Override public List<Vec3>     getAllBogeyPositions() { return delegate.getAllBogeyPositions(); }
    @Override public double         getTotalLength()       { return delegate.getTotalLength(); }

    @Override public @Nullable com.fizzylovely.railwayevolution.ai.core.ITrainGraph getGraph() {
        return delegate.getGraph();
    }

    @Override public @Nullable Object getLeadingEdgeRef()  { return delegate.getLeadingEdgeRef(); }
    @Override public @Nullable Object getLeadingNode1Ref() { return delegate.getLeadingNode1Ref(); }
    @Override public @Nullable Object getLeadingNode2Ref() { return delegate.getLeadingNode2Ref(); }
    @Override public double           getLeadingEdgePosition() { return delegate.getLeadingEdgePosition(); }

    @Override public List<UUID> getOccupiedSignalGroups() { return delegate.getOccupiedSignalGroups(); }

    // ─── Вспомогательные ──────────────────────────────────────────────────

    /** Ссылка на ServerPlayer для оповещений (null если игрок отключился). */
    @Nullable public ServerPlayer getPlayer() { return player; }

    private String getPlayerName() {
        if (player != null) return player.getGameProfile().getName();
        return playerUUID.toString().substring(0, 8);
    }

    @Override
    public String toString() {
        return "PlayerTrainHandle[train=" + getId().toString().substring(0, 8)
            + ", player=" + getPlayerName()
            + ", speed=" + String.format("%.2f", getSpeed()) + "]";
    }
}
