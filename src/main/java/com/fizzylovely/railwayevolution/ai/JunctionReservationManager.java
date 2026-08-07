package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Junction Reservation Manager — proximity-priority mutex for track intersections.
 *
 * v1.0.5 Priority System:
 *   The train CLOSEST to the junction wins the reservation.
 *   If a closer train arrives after an existing reservation, it can STEAL it.
 *   The losing train receives a "ghost pass" duration — it temporarily ignores
 *   the winning train so it can pass through freely without being stopped again.
 *
 * Ghost Pass logic:
 *   When train B (far) is denied because train A (close) holds the reservation:
 *   → B records A's UUID with a ghost-pass expiry tick
 *   → For the next GHOST_PASS_TICKS ticks, B's scanners skip A as an obstacle
 *   → A passes the junction unobstructed
 *   → After expiry, B sees A normally again
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public class JunctionReservationManager {

    private static final JunctionReservationManager INSTANCE = new JunctionReservationManager();

    /** Ticks a reservation stays valid after the last renewal. */
    private static final long EXPIRY_TICKS = 60; // 3 seconds

    /** Distance margin: must be this much closer to steal a reservation. */
    private static final double STEAL_MARGIN = 3.0; // blocks

    public static class Reservation {
        public final UUID  trainId;
        public final long  reservedAtTick;
        public final long  expiresAtTick;
        /** Distance from this train to the junction at reservation time (blocks). */
        public final double distanceToJunction;

        public Reservation(UUID trainId, long tick, double dist) {
            this.trainId           = trainId;
            this.reservedAtTick    = tick;
            this.expiresAtTick     = tick + EXPIRY_TICKS;
            this.distanceToJunction = dist;
        }
    }

    // Dimension-qualified junction key → reservation.
    private final ConcurrentHashMap<JunctionKey, Reservation> reservations = new ConcurrentHashMap<>();

    public static JunctionReservationManager getInstance() { return INSTANCE; }

    private JunctionReservationManager() {}

    // ── Core API ──────────────────────────────────────────────────────────────

    /**
     * v1.0.8 Fix #7: Pack junction coordinates into a long key WITH Y.
     * Bit layout: [10 bits Y | 22 bits X | 32 bits Z]
     * Separates stacked junctions (metro under bridges, multi-level stations).
     */
    public static long packKey(int x, int y, int z) {
        long yBits = ((long) (y & 0x3FF)) << 54;      // Y in top 10 bits
        long xBits = ((long) (x & 0x3FFFFF)) << 32;    // X in middle 22 bits
        long zBits = (z & 0xFFFFFFFFL);                 // Z in bottom 32 bits
        return yBits | xBits | zBits;
    }
    public static long packKey(double x, double y, double z) {
        return packKey((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public static JunctionKey key(String dimensionId, long packedPosition) {
        return new JunctionKey(dimensionId, packedPosition);
    }
    /** @deprecated Use 3-arg packKey(x, y, z) for proper Y-separation. */
    @Deprecated
    public static long packKey(int x, int z) {
        return packKey(x, 0, z);
    }
    /** @deprecated Use 3-arg packKey(x, y, z) for proper Y-separation. */
    @Deprecated
    public static long packKey(double x, double z) {
        return packKey(x, 0, z);
    }

    /**
     * Try to reserve a junction with proximity-based priority.
     *
     * @param junctionKey packed junction coordinates
     * @param trainId     requesting train
     * @param currentTick current game tick
     * @param myDist      distance from this train to the junction (blocks)
     * @return GRANTED if we now hold the reservation, DENIED if another closer train holds it
     */
    public ReserveResult tryReserve(JunctionKey junctionKey, UUID trainId, long currentTick, double myDist) {
        Reservation existing = reservations.get(junctionKey);

        // ── No reservation or expired → grant immediately ──
        if (existing == null || currentTick > existing.expiresAtTick) {
            reservations.put(junctionKey, new Reservation(trainId, currentTick, myDist));
            CreateRailwayMod.aiDebug("[Junction] {} GRANTED (dist={:.1f}b, fresh)",
                    trainId.toString().substring(0, 8), myDist);
            return ReserveResult.granted(null);
        }

        // ── We already hold the reservation → renew ──
        if (existing.trainId.equals(trainId)) {
            reservations.put(junctionKey, new Reservation(trainId, currentTick, myDist));
            return ReserveResult.granted(null);
        }

        // ── Another train holds it: compare distances ──
        double holderDist = existing.distanceToJunction;

        // We are significantly CLOSER → steal the reservation
        if (myDist < holderDist - STEAL_MARGIN) {
            UUID loser = existing.trainId;
            reservations.put(junctionKey, new Reservation(trainId, currentTick, myDist));
            CreateRailwayMod.aiDebug(
                    "[Junction] {} STOLE from {} (myDist={:.1f}b < holderDist={:.1f}b)",
                    trainId.toString().substring(0, 8),
                    loser.toString().substring(0, 8),
                    myDist, holderDist);
            // The loser (old holder) should now ghost-pass us
            return ReserveResult.granted(loser);
        }

        // Holder is closer or equal → we yield
        CreateRailwayMod.aiDebug(
                "[Junction] {} DENIED by {} (myDist={:.1f}b, holderDist={:.1f}b)",
                trainId.toString().substring(0, 8),
                existing.trainId.toString().substring(0, 8),
                myDist, holderDist);
        return ReserveResult.denied(existing.trainId);
    }

    /** Legacy Overworld-compatible overload. Prefer dimension-qualified keys. */
    public ReserveResult tryReserve(long junctionKey, UUID trainId, long currentTick, double myDist) {
        return tryReserve(key("minecraft:overworld", junctionKey), trainId, currentTick, myDist);
    }

    /**
     * Legacy overload (no distance — uses MAX_VALUE so newer trains always steal).
     * Used by old call sites that haven't been updated yet.
     */
    public boolean tryReserve(long junctionKey, UUID trainId, long currentTick) {
        ReserveResult r = tryReserve(junctionKey, trainId, currentTick, Double.MAX_VALUE);
        return r.isGranted;
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public static final class ReserveResult {
        public final boolean isGranted;
        /**
         * If DENIED: the UUID of the train that holds the junction (we should ghost-pass it).
         * If GRANTED via steal: the UUID of the train that was displaced (it should ghost-pass us).
         * null when granted normally (no competing train).
         */
        public final UUID ghostPassTarget;

        private ReserveResult(boolean granted, UUID ghost) {
            this.isGranted     = granted;
            this.ghostPassTarget = ghost;
        }

        /** Cached instance for the most common case: granted with no displacement. */
        private static final ReserveResult GRANTED_NO_GHOST = new ReserveResult(true, null);

        public static ReserveResult granted(@Nullable UUID displacedTrain) {
            return displacedTrain == null ? GRANTED_NO_GHOST : new ReserveResult(true, displacedTrain);
        }
        public static ReserveResult denied(UUID holderTrain) { return new ReserveResult(false, holderTrain); }
    }

    // ── Release ───────────────────────────────────────────────────────────────

    public void release(JunctionKey junctionKey, UUID trainId) {
        Reservation existing = reservations.get(junctionKey);
        if (existing != null && existing.trainId.equals(trainId)) {
            reservations.remove(junctionKey);
            CreateRailwayMod.aiDebug("[Junction] {} released junction {}",
                    trainId.toString().substring(0, 8), junctionKey);
        }
    }

    public void release(long junctionKey, UUID trainId) {
        release(key("minecraft:overworld", junctionKey), trainId);
    }

    public void releaseAll(UUID trainId) {
        reservations.entrySet().removeIf(e -> e.getValue().trainId.equals(trainId));
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public boolean isReservedByOther(JunctionKey junctionKey, UUID myTrainId, long currentTick) {
        Reservation existing = reservations.get(junctionKey);
        if (existing == null) return false;
        if (currentTick > existing.expiresAtTick) return false;
        return !existing.trainId.equals(myTrainId);
    }

    public UUID getHolder(JunctionKey junctionKey, long currentTick) {
        Reservation existing = reservations.get(junctionKey);
        if (existing == null || currentTick > existing.expiresAtTick) return null;
        return existing.trainId;
    }

    public void cleanupExpired(long currentTick) {
        reservations.entrySet().removeIf(e -> currentTick > e.getValue().expiresAtTick);
    }

    public int  size()  { return reservations.size(); }
    public void clear() { reservations.clear(); }

    public record JunctionKey(String dimensionId, long packedPosition) {}
}
