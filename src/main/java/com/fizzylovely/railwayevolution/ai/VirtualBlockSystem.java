package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Virtual Block System (VBS) — Dynamic Track Segment Reservation Manager.
 *
 * Принцип работы / How it works:
 * ─────────────────────────────
 * The VBS divides the railway into virtual "blocks" (segments between nodes/switches).
 * Each train reserves N segments ahead of itself (look-ahead distance).
 * A segment can only be reserved by ONE train at a time (exclusive lock).
 *
 * Reservation rules:
 *   1. A train requests reservation of segments along its planned path.
 *   2. If a segment is FREE → grant reservation.
 *   3. If a segment is OCCUPIED by another train → deny (conflict).
 *   4. Head-on conflicts (opposing direction) are CRITICAL — triggers yielding.
 *   5. Same-direction conflicts are SOFT — triggers slowdown/queueing.
 *   6. Reservations auto-expire after TTL ticks (safety net).
 *   7. Trains release segments behind them as they advance.
 *
 * Collision prevention guarantee:
 *   Two trains CANNOT hold active reservations on the same segment
 *   in opposing directions simultaneously.
 */
public class VirtualBlockSystem {

    private static VirtualBlockSystem instance;

    /** segmentKey -> active reservation */
    private final Map<String, TrackReservation> reservations = new ConcurrentHashMap<>();

    /** trainId -> list of segment keys that train has reserved */
    private final Map<UUID, List<String>> trainReservations = new ConcurrentHashMap<>();

    private VirtualBlockSystem() {}

    public static VirtualBlockSystem getInstance() {
        if (instance == null) {
            instance = new VirtualBlockSystem();
        }
        return instance;
    }

    public static void reset() {
        instance = new VirtualBlockSystem();
    }

    /**
     * Attempt to reserve a track segment for a train.
     *
     * @param trainId       UUID of the requesting train
     * @param segmentStart  Start node position of the segment
     * @param segmentEnd    End node position of the segment
     * @param forward       Direction of travel through the segment
     * @param currentTick   Current server tick
     * @return              ReservationResult indicating success or type of conflict
     */
    public ReservationResult tryReserve(UUID trainId, BlockPos segmentStart, BlockPos segmentEnd,
                                         boolean forward, long currentTick) {
        long ttl = RailwayConfig.reservationTTLTicks.get();

        TrackReservation newReservation = new TrackReservation(
                trainId, segmentStart, segmentEnd, forward, currentTick, ttl);
        String key = newReservation.getSegmentKey();

        TrackReservation existing = reservations.get(key);

        // Segment is free or reservation expired
        if (existing == null || existing.isExpired(currentTick) || !existing.isActive()) {
            commitReservation(key, newReservation, trainId);
            return ReservationResult.GRANTED;
        }

        // Same train renewing its own reservation
        if (existing.getOwnerTrainId().equals(trainId)) {
            existing.renew(currentTick, ttl);
            return ReservationResult.GRANTED;
        }

        // Conflict with another train
        if (newReservation.isHeadOnConflict(existing)) {
            CreateRailwayMod.LOGGER.debug("[VBS] HEAD-ON conflict: train {} vs {} on segment {}",
                    trainId.toString().substring(0, 8),
                    existing.getOwnerTrainId().toString().substring(0, 8), key);
            return ReservationResult.HEAD_ON_CONFLICT;
        }

        CreateRailwayMod.LOGGER.debug("[VBS] SAME-DIR conflict: train {} vs {} on segment {}",
                trainId.toString().substring(0, 8),
                existing.getOwnerTrainId().toString().substring(0, 8), key);
        return ReservationResult.SAME_DIRECTION_CONFLICT;
    }

    /**
     * Reserve multiple segments ahead (look-ahead reservation).
     * Stops at the first conflict and returns the result for that segment.
     *
     * @param trainId    UUID of the requesting train
     * @param path       Ordered list of (start, end) node pairs along the path
     * @param forward    Direction of travel
     * @param currentTick Current server tick
     * @return           List of results, one per segment attempted
     */
    public List<ReservationResult> tryReservePath(UUID trainId, List<BlockPos[]> path,
                                                   boolean forward, long currentTick) {
        List<ReservationResult> results = new ArrayList<>();
        int maxLookahead = RailwayConfig.maxLookaheadSegments.get();

        int count = 0;
        for (BlockPos[] segment : path) {
            if (count >= maxLookahead) break;

            ReservationResult result = tryReserve(trainId, segment[0], segment[1], forward, currentTick);
            results.add(result);

            if (result != ReservationResult.GRANTED) {
                break; // Stop reserving at first conflict
            }
            count++;
        }
        return results;
    }

    /**
     * Release all reservations held by a specific train.
     */
    public void releaseAll(UUID trainId) {
        List<String> keys = trainReservations.remove(trainId);
        if (keys != null) {
            for (String key : keys) {
                TrackReservation res = reservations.get(key);
                if (res != null && res.getOwnerTrainId().equals(trainId)) {
                    res.release();
                    reservations.remove(key);
                }
            }
        }
    }

    /**
     * Release a specific segment reservation if owned by the given train.
     */
    public void releaseSegment(UUID trainId, BlockPos segmentStart, BlockPos segmentEnd) {
        TrackReservation temp = new TrackReservation(trainId, segmentStart, segmentEnd, true, 0, 0);
        String key = temp.getSegmentKey();

        TrackReservation existing = reservations.get(key);
        if (existing != null && existing.getOwnerTrainId().equals(trainId)) {
            existing.release();
            reservations.remove(key);

            List<String> keys = trainReservations.get(trainId);
            if (keys != null) {
                keys.remove(key);
            }
        }
    }

    /**
     * Check if a segment is currently reserved by any train.
     */
    public boolean isSegmentReserved(BlockPos segmentStart, BlockPos segmentEnd, long currentTick) {
        TrackReservation temp = new TrackReservation(UUID.randomUUID(), segmentStart, segmentEnd, true, 0, 0);
        String key = temp.getSegmentKey();
        TrackReservation existing = reservations.get(key);
        return existing != null && existing.isActive() && !existing.isExpired(currentTick);
    }

    /**
     * Get the train ID that currently holds reservation on a segment.
     * Returns null if the segment is free.
     */
    public UUID getSegmentOwner(BlockPos segmentStart, BlockPos segmentEnd, long currentTick) {
        TrackReservation temp = new TrackReservation(UUID.randomUUID(), segmentStart, segmentEnd, true, 0, 0);
        String key = temp.getSegmentKey();
        TrackReservation existing = reservations.get(key);
        if (existing != null && existing.isActive() && !existing.isExpired(currentTick)) {
            return existing.getOwnerTrainId();
        }
        return null;
    }

    /**
     * Periodic cleanup: remove expired reservations.
     * Called every N ticks from the event handler.
     */
    public void cleanupExpired(long currentTick) {
        Iterator<Map.Entry<String, TrackReservation>> it = reservations.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TrackReservation> entry = it.next();
            TrackReservation res = entry.getValue();
            if (res.isExpired(currentTick) || !res.isActive()) {
                UUID owner = res.getOwnerTrainId();
                List<String> keys = trainReservations.get(owner);
                if (keys != null) {
                    keys.remove(entry.getKey());
                    if (keys.isEmpty()) {
                        trainReservations.remove(owner);
                    }
                }
                it.remove();
            }
        }
    }

    /**
     * Get the total number of active reservations (for diagnostics).
     */
    public int getActiveReservationCount() {
        return reservations.size();
    }

    /**
     * For debug visualizer: returns a snapshot of reserved segment midpoints grouped by train.
     */
    public Map<UUID, List<BlockPos>> getReservationMidpoints() {
        Map<UUID, List<BlockPos>> result = new HashMap<>();
        for (TrackReservation res : reservations.values()) {
            if (!res.isActive()) continue;
            BlockPos mid = new BlockPos(
                    (res.getSegmentStart().getX() + res.getSegmentEnd().getX()) / 2,
                    (res.getSegmentStart().getY() + res.getSegmentEnd().getY()) / 2,
                    (res.getSegmentStart().getZ() + res.getSegmentEnd().getZ()) / 2
            );
            result.computeIfAbsent(res.getOwnerTrainId(), k -> new ArrayList<>()).add(mid);
        }
        return result;
    }

    /**
     * Get the number of segments reserved by a specific train.
     */
    public int getTrainReservationCount(UUID trainId) {
        List<String> keys = trainReservations.get(trainId);
        return keys != null ? keys.size() : 0;
    }

    private void commitReservation(String key, TrackReservation reservation, UUID trainId) {
        reservations.put(key, reservation);
        trainReservations.computeIfAbsent(trainId, k -> new ArrayList<>()).add(key);
    }

    /**
     * Result of a reservation attempt.
     */
    public enum ReservationResult {
        /** Segment successfully reserved for the requesting train. */
        GRANTED,
        /** Segment is held by an oncoming train — critical head-on conflict. */
        HEAD_ON_CONFLICT,
        /** Segment is held by a train going the same direction — queue behind it. */
        SAME_DIRECTION_CONFLICT
    }
}
