package com.fizzylovely.railwayevolution.ai.adapter;

/** Result of a guarded recovery attempt; no field implies physical movement. */
public record NavigationKickstartOutcome(
        Kind kind, boolean released, boolean runtimeInterrupted,
        boolean blockedBySafety, boolean forcedThrottle) {
    public enum Kind {
        RELEASED_OWN_BRAKE,
        RUNTIME_REPAIR_PENDING,
        RUNTIME_INTERRUPTED,
        BLOCKED_ZERO_THROTTLE_UNKNOWN_ORIGIN,
        BLOCKED_BY_SAFETY,
        COOLDOWN,
        NO_OP,
        FORCED_ADMIN_THROTTLE,
        NAVIGATION_RESTARTED,
        PATH_NOT_FOUND,
        START_REJECTED
    }

    public static NavigationKickstartOutcome of(Kind kind) {
        return new NavigationKickstartOutcome(kind,
                kind == Kind.RELEASED_OWN_BRAKE,
                kind == Kind.RUNTIME_INTERRUPTED,
                kind == Kind.BLOCKED_BY_SAFETY,
                kind == Kind.FORCED_ADMIN_THROTTLE);
    }
}
