package com.fizzylovely.railwayevolution.ai.priority;

/**
 * Priority levels for trains. Higher value = higher right-of-way.
 * PLAYER > MAXIMUM(5) > VERY_HIGH(4) > HIGH(3) > MEDIUM(2) > LOW(1) > NORMAL(0)
 *
 * Safety constraints always override priority — a high-priority train still
 * cannot collide or pass through an occupied segment.
 */
public enum TrainPriorityLevel {

    NORMAL(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    VERY_HIGH(4),
    MAXIMUM(5),
    /** Player-controlled trains. Always beats all AI priorities. */
    PLAYER(6);

    public final int value;

    TrainPriorityLevel(int value) {
        this.value = value;
    }

    /** Returns true if this priority can preempt the other. */
    public boolean dominates(TrainPriorityLevel other) {
        return this.value > other.value;
    }

    /** Returns true if this train should yield to the other. */
    public boolean shouldYieldTo(TrainPriorityLevel other) {
        return other.value > this.value;
    }

    public boolean equalPriorityTo(TrainPriorityLevel other) {
        return this.value == other.value;
    }

    public boolean isPlayer() { return this == PLAYER; }
    public boolean isAI()     { return this != PLAYER; }

    /**
     * Get a level by numeric value 0–5.
     * Out-of-range values return NORMAL. PLAYER cannot be obtained this way.
     */
    public static TrainPriorityLevel fromValue(int v) {
        return switch (v) {
            case 1 -> LOW;
            case 2 -> MEDIUM;
            case 3 -> HIGH;
            case 4 -> VERY_HIGH;
            case 5 -> MAXIMUM;
            default -> NORMAL;
        };
    }

    @Override
    public String toString() {
        return name() + "(" + value + ")";
    }
}
