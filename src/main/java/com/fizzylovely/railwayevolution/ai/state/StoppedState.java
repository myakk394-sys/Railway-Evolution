package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/** Safe idle state used when Create has no active destination. */
public final class StoppedState implements TrainControlState {

    @Override
    public TrainStateId getId() { return TrainStateId.STOPPED; }

    @Override
    public void onEnter(TrainControlContext ctx) {
        if (ctx.selfHandle != null) ctx.selfHandle.forceStop();
        CreateRailwayMod.aiDebug("[FSM] {} → STOPPED", shortId(ctx));
    }

    @Override
    public void onExit(TrainControlContext ctx) { }

    @Override
    public @Nullable TrainStateId tick(TrainControlContext ctx) {
        if (ctx.selfHandle != null) ctx.selfHandle.forceStop();
        return ctx.hasDestination ? TrainStateId.CRUISING : null;
    }

    private String shortId(TrainControlContext ctx) {
        if (ctx.selfHandle == null) return "?";
        return ctx.selfHandle.getId().toString().substring(0, 8);
    }
}
