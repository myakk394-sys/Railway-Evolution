package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Virtual Block System (VBS) — менеджер динамической блокировки участков путей.
 */
public class VirtualBlockSystem {

    private static VirtualBlockSystem instance;

    public record SegmentKey(int dimHash, long posA, long posB) {
        /** Create a normalized key (posA <= posB for deterministic ordering). */
        public static SegmentKey of(int dimHash, BlockPos a, BlockPos b) {
            long la = a.asLong(), lb = b.asLong();
            return la <= lb ? new SegmentKey(dimHash, la, lb) : new SegmentKey(dimHash, lb, la);
        }

        public static SegmentKey of(String dimensionId, BlockPos a, BlockPos b) {
            return of(dimensionId.hashCode(), a, b);
        }

        public static SegmentKey of(BlockPos a, BlockPos b) {
            return of(OVERWORLD_DIM_HASH, a, b);
        }
    }

    /** Pre-computed dimension hash for "minecraft:overworld" — avoids hashCode() per call. */
    private static final int OVERWORLD_DIM_HASH = "minecraft:overworld".hashCode();

    /** segment key -> active reservation */
    private final Map<SegmentKey, TrackReservation> reservations = new ConcurrentHashMap<>();

    /** trainId -> list of segment keys that train has reserved */
    private final Map<UUID, List<SegmentKey>> trainReservations = new ConcurrentHashMap<>();

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
        return tryReserve(OVERWORLD_DIM_HASH, trainId, segmentStart, segmentEnd, forward, currentTick);
    }

    public ReservationResult tryReserve(String dimensionId, UUID trainId, BlockPos segmentStart, BlockPos segmentEnd,
                                         boolean forward, long currentTick) {
        return tryReserve(dimensionId.hashCode(), trainId, segmentStart, segmentEnd, forward, currentTick);
    }

    private ReservationResult tryReserve(int dimHash, UUID trainId, BlockPos segmentStart, BlockPos segmentEnd,
                                          boolean forward, long currentTick) {
        long ttl = RailwayConfig.reservationTTLTicks.get();
        SegmentKey key = SegmentKey.of(dimHash, segmentStart, segmentEnd);

        TrackReservation existing = reservations.get(key);

        // Segment is free or reservation expired
        if (existing == null || existing.isExpired(currentTick) || !existing.isActive()) {
            TrackReservation newReservation = new TrackReservation(
                    trainId, segmentStart, segmentEnd, forward, currentTick, ttl);
            commitReservation(key, newReservation, trainId);
            return ReservationResult.GRANTED;
        }

        // Same train renewing its own reservation — most common hot path, no new object
        if (existing.getOwnerTrainId().equals(trainId)) {
            existing.renew(currentTick, ttl);
            return ReservationResult.GRANTED;
        }

        // Conflict with another train — check head-on by comparing directions
        if (existing.isForward() != forward) {
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
        List<SegmentKey> keys = trainReservations.remove(trainId);
        if (keys != null) {
            for (SegmentKey key : keys) {
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
        SegmentKey key = SegmentKey.of(segmentStart, segmentEnd);

        TrackReservation existing = reservations.get(key);
        if (existing != null && existing.getOwnerTrainId().equals(trainId)) {
            existing.release();
            reservations.remove(key);

            List<SegmentKey> keys = trainReservations.get(trainId);
            if (keys != null) {
                keys.remove(key);
            }
        }
    }

    /**
     * Check if a segment is currently reserved by any train.
     */
    public boolean isSegmentReserved(BlockPos segmentStart, BlockPos segmentEnd, long currentTick) {
        SegmentKey key = SegmentKey.of(segmentStart, segmentEnd);
        TrackReservation existing = reservations.get(key);
        return existing != null && existing.isActive() && !existing.isExpired(currentTick);
    }

    /**
     * Get the train ID that currently holds reservation on a segment.
     * Returns null if the segment is free.
     */
    public UUID getSegmentOwner(BlockPos segmentStart, BlockPos segmentEnd, long currentTick) {
        SegmentKey key = SegmentKey.of(segmentStart, segmentEnd);
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
        Iterator<Map.Entry<SegmentKey, TrackReservation>> it = reservations.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SegmentKey, TrackReservation> entry = it.next();
            TrackReservation res = entry.getValue();
            if (res.isExpired(currentTick) || !res.isActive()) {
                UUID owner = res.getOwnerTrainId();
                List<SegmentKey> keys = trainReservations.get(owner);
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
        List<SegmentKey> keys = trainReservations.get(trainId);
        return keys != null ? keys.size() : 0;
    }

    /**
     * Generate a normalized segment key from two BlockPos positions ().
     * : Returns a record-based key instead of String concatenation.
     * Kept as public static for backward compatibility with external callers.
     */
    public static SegmentKey makeSegmentKey(BlockPos a, BlockPos b) {
        return SegmentKey.of(a, b);
    }

    public static SegmentKey makeSegmentKey(String dimensionId, BlockPos a, BlockPos b) {
        return SegmentKey.of(dimensionId, a, b);
    }

    private void commitReservation(SegmentKey key, TrackReservation reservation, UUID trainId) {
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
