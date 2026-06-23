package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/**
 * YieldingState — уступка дороги: поезд остановлен, ожидает очистки пути.
 *
 * Переходы:
 *   → CRUISING           если путь очистился
 *   → FLOW_FOLLOWER      если появился подходящий попутный поезд
 *   → WAIT_FOR_CLEARANCE если препятствие в критической зоне
 *   → REVERSING          если тайм-аут (maxYieldTicks), есть место сзади
 *   → TRAFFIC_JAM        если зажаты
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class YieldingState implements TrainControlState {

    private long yieldStartTick = 0;
    private static final long MAX_YIELD_TICKS = 300; // 15 сек

    @Override
    public TrainStateId getId() { return TrainStateId.YIELDING; }

    @Override
    public void onEnter(TrainControlContext ctx) {
        yieldStartTick = ctx.currentTick;
        if (ctx.selfHandle != null) ctx.selfHandle.forceStop();
        CreateRailwayMod.aiDebug("[FSM] {} → YIELDING", shortId(ctx));
    }

    @Override
    public void onExit(TrainControlContext ctx) {
        yieldStartTick = 0;
    }

    @Override
    public @Nullable TrainStateId tick(TrainControlContext ctx) {
        // Удерживаем стоп каждый тик (Create может попытаться разогнать)
        if (ctx.selfHandle != null) ctx.selfHandle.forceStop();

        var obs = ctx.obstacleProfile;

        // Путь очистился
        if (obs == null || !obs.hasObstacle()) return TrainStateId.CRUISING;

        if (ctx.sandwiched) return TrainStateId.TRAFFIC_JAM;

        // Лидер стал flow-кандидатом (напр. заехал на другой путь и едет попутно)
        if (obs.isFlowCandidate) return TrainStateId.FLOW_FOLLOWER;

        // Критическая зона
        if (obs.isOverlapping) return TrainStateId.WAIT_FOR_CLEARANCE;

        // Тайм-аут: слишком долго ждём → откат
        long waitedTicks = ctx.currentTick - yieldStartTick;
        if (waitedTicks > MAX_YIELD_TICKS && ctx.spaceAvailableBehind) {
            return TrainStateId.REVERSING;
        }

        return null; // продолжаем ждать
    }

    private String shortId(TrainControlContext ctx) {
        if (ctx.selfHandle == null) return "?";
        return ctx.selfHandle.getId().toString().substring(0, 8);
    }
}
