package com.fizzylovely.railwayevolution.ai;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Represents a single track segment reservation in the Virtual Block System (VBS).
 *
 * A reservation locks a directed segment (segmentStart → segmentEnd) for one train.
 * Reservations expire after TTL ticks as a safety net against stale locks.
 */
public class TrackReservation {

    private final UUID ownerTrainId;
    private final BlockPos segmentStart;
    private final BlockPos segmentEnd;
    private final boolean forward;      // direction of travel
    private final String segmentKey;    // normalized key for map lookup
    private long expiryTick;            // tick at which this reservation expires
    private boolean active;             // false = released

    public TrackReservation(UUID ownerTrainId, BlockPos segmentStart, BlockPos segmentEnd,
                            boolean forward, long currentTick, long ttlTicks) {
        this.ownerTrainId = ownerTrainId;
        this.segmentStart = segmentStart;
        this.segmentEnd   = segmentEnd;
        this.forward      = forward;
        this.active       = true;
        this.expiryTick   = (ttlTicks > 0) ? currentTick + ttlTicks : Long.MAX_VALUE;
        this.segmentKey   = buildKey(segmentStart, segmentEnd);
    }

    /** Normalized, direction-agnostic key so A→B and B→A map to the same slot. */
    private static String buildKey(BlockPos a, BlockPos b) {
        int cmp = a.compareTo(b);
        BlockPos first  = cmp <= 0 ? a : b;
        BlockPos second = cmp <= 0 ? b : a;
        return first.toShortString() + "|" + second.toShortString();
    }

    /** Returns true if this reservation conflicts head-on with another (opposite direction). */
    public boolean isHeadOnConflict(TrackReservation other) {
        return this.segmentKey.equals(other.segmentKey)
                && this.forward != other.forward;
    }

    /** Renew the TTL so the reservation doesn't expire while the train is still here. */
    public void renew(long currentTick, long ttlTicks) {
        this.expiryTick = currentTick + ttlTicks;
    }

    /** Mark the reservation as released (train left the segment). */
    public void release() {
        this.active = false;
    }

    // ─── Getters ───

    public UUID    getOwnerTrainId()  { return ownerTrainId; }
    public BlockPos getSegmentStart() { return segmentStart; }
    public BlockPos getSegmentEnd()   { return segmentEnd;   }
    public boolean  isForward()       { return forward;       }
    public String   getSegmentKey()   { return segmentKey;    }
    public boolean  isActive()        { return active;        }

    public boolean isExpired(long currentTick) {
        return currentTick > expiryTick;
    }

    @Override
    public String toString() {
        return String.format("Reservation[owner=%s, seg=%s, dir=%s, active=%s]",
                ownerTrainId.toString().substring(0, 8),
                segmentKey,
                forward ? "FWD" : "REV",
                active);
    }
}
