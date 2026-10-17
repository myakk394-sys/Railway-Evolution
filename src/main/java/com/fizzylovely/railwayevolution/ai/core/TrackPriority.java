package com.fizzylovely.railwayevolution.ai.core;

/**
 * Conservative priority classification for an approach to a merge/junction.
 * UNKNOWN must be treated as non-priority by decision code.
 */
public enum TrackPriority {
    UNKNOWN,
    MAINLINE,
    SECONDARY;

    public boolean isMainline() {
        return this == MAINLINE;
    }

    public boolean isSecondaryBranch() {
        return this == SECONDARY;
    }
}
