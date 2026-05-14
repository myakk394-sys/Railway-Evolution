package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Junction Reservation Manager — mutex system for track intersections.
 *
 * Problem: at cross-intersections (перекрёстки) and T-junctions, two trains
 * approaching from perpendicular directions can both enter simultaneously.
 * Neither can proceed because they physically block each other → deadlock.
 *
 * Solution: before entering a junction (node with 3+ connections), a train
 * must RESERVE it. If another train already holds the reservation, this
 * train stops and waits. First-come-first-served with UUID tiebreak.
 *
 * Reservations auto-expire after EXPIRY_TICKS to prevent permanent locks
 * from crashed/removed trains.
 *
 * Thread-safe via ConcurrentHashMap.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public class JunctionReservationManager {

    private static final JunctionReservationManager INSTANCE = new JunctionReservationManager();
    private static final long EXPIRY_TICKS = 200; // 10 seconds — auto-release stuck reservations

    /**
     * Reservation entry for a single junction.
     */
    public static class Reservation {
        public final UUID trainId;
        public final long reservedAtTick;
        public final long expiresAtTick;

        public Reservation(UUID trainId, long reservedAtTick) {
            this.trainId = trainId;
            this.reservedAtTick = reservedAtTick;
            this.expiresAtTick = reservedAtTick + EXPIRY_TICKS;
        }
    }

    // Junction key (packed XZ coordinates) → reservation
    private final ConcurrentHashMap<Long, Reservation> reservations = new ConcurrentHashMap<>();

    public static JunctionReservationManager getInstance() {
        return INSTANCE;
    }

    /**
     * Pack junction coordinates into a single long key.
     * Uses block-level precision (integer XZ).
     */
    public static long packKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static long packKey(double x, double z) {
        return packKey((int) Math.floor(x), (int) Math.floor(z));
    }

    /**
     * Try to reserve a junction for a train.
     *
     * @return true if reservation granted (or train already holds it),
     *         false if another train holds a valid reservation
     */
    public boolean tryReserve(long junctionKey, UUID trainId, long currentTick) {
        Reservation existing = reservations.get(junctionKey);

        // No reservation or expired → grant
        if (existing == null || currentTick > existing.expiresAtTick) {
            reservations.put(junctionKey, new Reservation(trainId, currentTick));
            return true;
        }

        // We already hold the reservation → renew
        if (existing.trainId.equals(trainId)) {
            reservations.put(junctionKey, new Reservation(trainId, currentTick));
            return true;
        }

        // Another train holds a valid reservation → denied
        return false;
    }

    /**
     * Release a junction reservation (called when train passes through or stops needing it).
     * Only releases if the specified train actually holds the reservation.
     */
    public void release(long junctionKey, UUID trainId) {
        Reservation existing = reservations.get(junctionKey);
        if (existing != null && existing.trainId.equals(trainId)) {
            reservations.remove(junctionKey);
            CreateRailwayMod.aiDebug("[Junction] Train {} released junction {}",
                    trainId.toString().substring(0, 8), junctionKey);
        }
    }

    /**
     * Release ALL reservations held by a specific train.
     * Called on train removal/despawn.
     */
    public void releaseAll(UUID trainId) {
        reservations.entrySet().removeIf(e -> e.getValue().trainId.equals(trainId));
    }

    /**
     * Check if a junction is currently reserved by another train.
     */
    public boolean isReservedByOther(long junctionKey, UUID myTrainId, long currentTick) {
        Reservation existing = reservations.get(junctionKey);
        if (existing == null) return false;
        if (currentTick > existing.expiresAtTick) return false;
        return !existing.trainId.equals(myTrainId);
    }

    /**
     * Get the UUID of the train holding a junction reservation (or null).
     */
    public UUID getHolder(long junctionKey, long currentTick) {
        Reservation existing = reservations.get(junctionKey);
        if (existing == null || currentTick > existing.expiresAtTick) return null;
        return existing.trainId;
    }

    /**
     * Clean up expired reservations.
     */
    public void cleanupExpired(long currentTick) {
        reservations.entrySet().removeIf(e -> currentTick > e.getValue().expiresAtTick);
    }

    /**
     * Number of active reservations.
     */
    public int size() {
        return reservations.size();
    }

    /**
     * Clear all reservations (server stop/reload).
     */
    public void clear() {
        reservations.clear();
    }
}
