package com.fizzylovely.railwayevolution.ai.adapter;

/** Read-only native Create state used by guarded navigation recovery. */
public record NativeRecoveryStatus(
        boolean destinationPresent, boolean finiteDistance,
        Boolean runtimePaused, String runtimeState, boolean schedulePresent, Integer currentEntry,
        boolean derailed, Double speedBeforeStall, boolean anyCarriageStalled,
        boolean waitingForSignal, boolean graphPresent,
        Boolean manualControl, Boolean waitingForChunks,
        double speed, double targetSpeed, double throttle, boolean ownBrakeEngaged) {
}
