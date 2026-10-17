package com.fizzylovely.railwayevolution.ai.adapter;

/** Package-private, Create-independent state machine for transient brake symmetry. */
final class TransientBrakeState {
    private static final double MIGRATION_THROTTLE = 1.0;

    private boolean engaged;
    private double savedThrottle = MIGRATION_THROTTLE;

    void engage(double throttle) {
        if (engaged) return;
        savedThrottle = validRunningThrottle(throttle) ? throttle : MIGRATION_THROTTLE;
        engaged = true;
    }

    double release(double currentThrottle) {
        if (engaged) {
            engaged = false;
            return savedThrottle;
        }
        // Origin is unknown when this instance did not engage the brake.
        // Never migrate persisted zero throttle during an ordinary release.
        return currentThrottle;
    }

    void reset() {
        engaged = false;
        savedThrottle = MIGRATION_THROTTLE;
    }

    boolean isEngaged() {
        return engaged;
    }

    private static boolean validRunningThrottle(double throttle) {
        return Double.isFinite(throttle) && throttle >= 0.05 && throttle <= 1.0;
    }
}
