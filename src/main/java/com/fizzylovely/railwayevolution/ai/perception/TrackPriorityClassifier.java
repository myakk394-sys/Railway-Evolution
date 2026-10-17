package com.fizzylovely.railwayevolution.ai.perception;

import com.fizzylovely.railwayevolution.ai.core.TrackPriority;

/** Pure, allocation-free priority heuristics kept separate from graph traversal. */
public final class TrackPriorityClassifier {
    private static final double LENGTH_RATIO = 1.5;

    private TrackPriorityClassifier() {}

    public static TrackPriority classify(boolean waitingAtYieldSignal,
                                         double approachLength,
                                         double longestCompetingLength,
                                         boolean unambiguousTopology) {
        if (waitingAtYieldSignal) return TrackPriority.SECONDARY;
        if (!unambiguousTopology || !isUsable(approachLength)
                || !isUsable(longestCompetingLength)) return TrackPriority.UNKNOWN;
        if (approachLength >= longestCompetingLength * LENGTH_RATIO) return TrackPriority.MAINLINE;
        if (longestCompetingLength >= approachLength * LENGTH_RATIO) return TrackPriority.SECONDARY;
        return TrackPriority.UNKNOWN;
    }

    public static double safetyStoppingDistance(double speed, double deceleration) {
        double v = Math.abs(speed);
        if (!Double.isFinite(v)) return Double.MAX_VALUE;
        double a = Math.abs(deceleration);
        if (!Double.isFinite(a) || a < 1.0e-6) return Double.MAX_VALUE;
        // Physics distance + two ticks reaction + a fixed low-speed clearance margin.
        return v * v / (2.0 * a) + v * 2.0 + 2.0;
    }

    private static boolean isUsable(double value) {
        return Double.isFinite(value) && value > 0.0;
    }
}
