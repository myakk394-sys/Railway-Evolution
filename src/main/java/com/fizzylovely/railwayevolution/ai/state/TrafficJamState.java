package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/**
 * TrafficJamState — пробка: поезд зажат спереди и сзади.
 *
 * Только последний поезд в колонне (spaceAvailableBehind=true) может
 * начать откат для разрешения пробки. Остальные просто ждут.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class TrafficJamState implements TrainControlState {

    private static final int JAM_REVERSE_WAIT_TICKS = 100; // 5 сек до решения откатиться

    private long enteredTick = 0;

    @Override
    public TrainStateId getId() { return TrainStateId.TRAFFIC_JAM; }

    @Override
    public void onEnter(TrainControlContext ctx) {
        enteredTick = ctx.currentTick;
        if (ctx.selfHandle != null) ctx.selfHandle.forceStop();
        CreateRailwayMod.aiDebug("[FSM] {} → TRAFFIC_JAM", shortId(ctx));
    }

    @Override
    public void onExit(TrainControlContext ctx) { }

    @Override
    public @Nullable TrainStateId tick(TrainControlContext ctx) {
        if (ctx.selfHandle != null) ctx.selfHandle.forceStop();

        // Пробка рассосалась
        if (!ctx.sandwiched) {
            if (ctx.obstacleProfile == null || !ctx.obstacleProfile.hasObstacle()) {
                return TrainStateId.CRUISING;
            }
            if (ctx.spaceAvailableBehind) {
                return TrainStateId.REVERSING; // мы последние — начинаем откат
            }
            return TrainStateId.YIELDING;
        }

        // Мы последние в колонне → откатываемся после JAM_REVERSE_WAIT_TICKS
        long waited = ctx.currentTick - enteredTick;
        if (ctx.spaceAvailableBehind && waited > JAM_REVERSE_WAIT_TICKS) {
            return TrainStateId.REVERSING;
        }

        return null;
    }

    private String shortId(TrainControlContext ctx) {
        if (ctx.selfHandle == null) return "?";
        return ctx.selfHandle.getId().toString().substring(0, 8);
    }
}
