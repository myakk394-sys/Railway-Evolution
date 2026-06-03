package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/**
 * ReversingState — реверсный манёвр: откат назад для разблокировки тупика.
 *
 * Стратегия:
 *   1. Откатываемся на reverseDistance блоков назад.
 *   2. После отката — отменяем навигацию (Create перепроложит маршрут).
 *   3. Если за спиной появился поезд — прерываем откат, возврат в YIELDING.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class ReversingState implements TrainControlState {

    private static final double REVERSE_SPEED    = 0.4;  // блоков/тик
    private static final double REVERSE_DISTANCE = 8.0;  // блоки отката
    private static final int    STUCK_TIMEOUT    = 60;   // тики до принудительного выхода

    private long   enteredTick      = 0;
    private double distanceBacked   = 0;
    // Позиция при входе (для подсчёта дистанции отката)
    private double startX = 0, startZ = 0;
    private boolean startSet = false;

    @Override
    public TrainStateId getId() { return TrainStateId.REVERSING; }

    @Override
    public void onEnter(TrainControlContext ctx) {
        enteredTick    = ctx.currentTick;
        distanceBacked = 0;
        startSet       = false;

        if (ctx.leadingPosition != null) {
            startX   = ctx.leadingPosition.x;
            startZ   = ctx.leadingPosition.z;
            startSet = true;
        }

        CreateRailwayMod.aiDebug("[FSM] {} → REVERSING", shortId(ctx));
    }

    @Override
    public void onExit(TrainControlContext ctx) {
        if (ctx.selfHandle != null) {
            ctx.selfHandle.setSpeed(0);
            ctx.selfHandle.setThrottle(1.0);
        }
    }

    @Override
    public @Nullable TrainStateId tick(TrainControlContext ctx) {

        // Нет места сзади → прерываем откат
        if (!ctx.spaceAvailableBehind) {
            CreateRailwayMod.aiDebug("[FSM] {} REVERSING aborted — no space behind", shortId(ctx));
            return TrainStateId.YIELDING;
        }

        // Обновляем пройденную дистанцию
        if (startSet && ctx.leadingPosition != null) {
            double dx = ctx.leadingPosition.x - startX;
            double dz = ctx.leadingPosition.z - startZ;
            distanceBacked = Math.sqrt(dx * dx + dz * dz);
        }

        // Застрял (нет движения за STUCK_TIMEOUT)
        if (ctx.currentTick - enteredTick > STUCK_TIMEOUT && distanceBacked < 0.5) {
            CreateRailwayMod.aiDebug("[FSM] {} REVERSING stuck — aborting", shortId(ctx));
            if (ctx.selfHandle != null) ctx.selfHandle.cancelNavigation();
            return TrainStateId.YIELDING;
        }

        // Откатились достаточно
        if (distanceBacked >= REVERSE_DISTANCE) {
            CreateRailwayMod.aiDebug("[FSM] {} REVERSING complete ({}b)", shortId(ctx),
                String.format("%.1f", distanceBacked));
            if (ctx.selfHandle != null) ctx.selfHandle.cancelNavigation();
            return TrainStateId.CRUISING;
        }

        // Применяем реверсный ход
        if (ctx.selfHandle != null) {
            ctx.selfHandle.setSpeed(-REVERSE_SPEED);
            ctx.selfHandle.setThrottle(1.0);
        }

        return null;
    }

    private String shortId(TrainControlContext ctx) {
        if (ctx.selfHandle == null) return "?";
        return ctx.selfHandle.getId().toString().substring(0, 8);
    }
}
