package com.fizzylovely.railwayevolution.ai.priority;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of train priority levels.
 *
 * Thread-safe. Priorities persist until changed or the train is removed.
 * Can be changed at runtime without restart.
 *
 * Aging: trains at priority 0–4 that wait longer than AGING_THRESHOLD ticks
 * get a temporary +1 effective priority boost (capped at MAXIMUM).
 * Resets when the train starts moving. Prevents indefinite starvation.
 */
public final class TrainPriorityRegistry {

    private static final TrainPriorityRegistry INSTANCE = new TrainPriorityRegistry();

    private final ConcurrentHashMap<UUID, TrainPriorityLevel> priorities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> agingTicks = new ConcurrentHashMap<>();

    public static final int AGING_THRESHOLD = 600; // ~30 seconds
    private static final TrainPriorityLevel AGING_CEILING = TrainPriorityLevel.MAXIMUM;

    private TrainPriorityRegistry() {}

    public static TrainPriorityRegistry getInstance() { return INSTANCE; }

    // --- Priority management ---

    public void setPriority(UUID trainId, TrainPriorityLevel level) {
        TrainPriorityLevel old = priorities.put(trainId, level);
        if (old != level) {
            agingTicks.remove(trainId);
            CreateRailwayMod.aiLog("[Priority] Train {} {} -> {}",
                trainId.toString().substring(0, 8),
                old != null ? old : "NORMAL", level);
        }
    }

    public void setPriority(UUID trainId, int value) {
        setPriority(trainId, TrainPriorityLevel.fromValue(Math.max(0, Math.min(5, value))));
    }

    public void setPlayerControlled(UUID trainId) {
        priorities.put(trainId, TrainPriorityLevel.PLAYER);
        agingTicks.remove(trainId);
    }

    public void clearPlayerControlled(UUID trainId) {
        if (priorities.get(trainId) == TrainPriorityLevel.PLAYER) {
            priorities.put(trainId, TrainPriorityLevel.NORMAL);
        }
    }

    public TrainPriorityLevel getStaticPriority(UUID trainId) {
        return priorities.getOrDefault(trainId, TrainPriorityLevel.NORMAL);
    }

    /**
     * Effective priority used in decisions — includes aging bonus if applicable.
     */
    public TrainPriorityLevel getEffectivePriority(UUID trainId) {
        TrainPriorityLevel base = priorities.getOrDefault(trainId, TrainPriorityLevel.NORMAL);
        if (base == TrainPriorityLevel.PLAYER || base == AGING_CEILING) return base;

        Integer aging = agingTicks.get(trainId);
        if (aging != null && aging >= AGING_THRESHOLD) {
            return TrainPriorityLevel.fromValue(Math.min(base.value + 1, AGING_CEILING.value));
        }
        return base;
    }

    public boolean shouldYield(UUID trainAId, UUID trainBId) {
        return getEffectivePriority(trainAId).shouldYieldTo(getEffectivePriority(trainBId));
    }

    public int priorityDelta(UUID a, UUID b) {
        return getEffectivePriority(a).value - getEffectivePriority(b).value;
    }

    // --- Aging ---

    public void incrementAging(UUID trainId) {
        TrainPriorityLevel base = priorities.getOrDefault(trainId, TrainPriorityLevel.NORMAL);
        if (base == TrainPriorityLevel.PLAYER || base == AGING_CEILING) return;
        agingTicks.merge(trainId, 1, Integer::sum);
    }

    public void resetAging(UUID trainId) {
        agingTicks.remove(trainId);
    }

    public int getAgingTicks(UUID trainId) {
        return agingTicks.getOrDefault(trainId, 0);
    }

    public boolean hasAgingBoost(UUID trainId) {
        return getAgingTicks(trainId) >= AGING_THRESHOLD;
    }

    // --- Lifecycle ---

    public void remove(UUID trainId) {
        priorities.remove(trainId);
        agingTicks.remove(trainId);
    }

    public void clear() {
        priorities.clear();
        agingTicks.clear();
        CreateRailwayMod.aiLog("[Priority] Registry cleared");
    }

    public int size() { return priorities.size(); }

    public String getDisplayString(UUID trainId) {
        TrainPriorityLevel base = getStaticPriority(trainId);
        TrainPriorityLevel eff  = getEffectivePriority(trainId);
        int aging = getAgingTicks(trainId);
        if (eff != base) return eff + " (base:" + base + ", aging:" + aging + "t)";
        return base + (aging > 0 ? " (aging:" + aging + "t)" : "");
    }

    @Nullable
    public Map<UUID, TrainPriorityLevel> snapshotAll() {
        return new LinkedHashMap<>(priorities);
    }
}
