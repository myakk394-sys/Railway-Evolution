package com.fizzylovely.railwayevolution.ai;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Represents a single track segment reservation within the Virtual Block System (VBS).
 *
 * A reservation locks a track segment (identified by start/end node positions)
 * for exclusive use by a particular train, preventing face-to-face collisions.
 *
 * Reservations are directional: they encode which direction the owning train
 * is traveling through the segment.
 */
public class TrackReservation {

    private final UUID ownerTrainId;
    private final BlockPos segmentStart;
    private final BlockPos segmentEnd;
    private final boolean forwardDirection;
    private final long createdTick;
    private long expirationTick;
    private boolean active;

    public TrackReservation(UUID ownerTrainId, BlockPos segmentStart, BlockPos segmentEnd,
                            boolean forwardDirection, long currentTick, long ttlTicks) {
        this.ownerTrainId = ownerTrainId;
        this.segmentStart = segmentStart;
        this.segmentEnd = segmentEnd;
        this.forwardDirection = forwardDirection;
        this.createdTick = currentTick;
        this.expirationTick = currentTick + ttlTicks;
        this.active = true;
    }

    public UUID getOwnerTrainId() {
        return ownerTrainId;
    }

    public BlockPos getSegmentStart() {
        return segmentStart;
    }

    public BlockPos getSegmentEnd() {
        return segmentEnd;
    }

    public boolean isForwardDirection() {
        return forwardDirection;
    }

    public long getCreatedTick() {
        return createdTick;
    }

    public boolean isActive() {
        return active;
    }

    public void release() {
        this.active = false;
    }

    /**
     * Extend the reservation TTL (used when a train is still occupying the segment).
     */
    public void renew(long currentTick, long ttlTicks) {
        this.expirationTick = currentTick + ttlTicks;
        this.active = true;
    }

    /**
     * Check if the reservation has expired based on the current server tick.
     */
    public boolean isExpired(long currentTick) {
        return currentTick >= expirationTick;
    }

    /**
     * Check if this reservation conflicts with another reservation
     * (same segment, different train, opposing direction = head-on collision risk).
     */
    public boolean conflictsWith(TrackReservation other) {
        if (!this.active || !other.active) return false;
        if (this.ownerTrainId.equals(other.ownerTrainId)) return false;

        boolean sameSegment = (this.segmentStart.equals(other.segmentStart) && this.segmentEnd.equals(other.segmentEnd))
                || (this.segmentStart.equals(other.segmentEnd) && this.segmentEnd.equals(other.segmentStart));

        return sameSegment;
    }

    /**
     * Check if this reservation represents an opposing direction on the same segment.
     * This is the critical head-on collision scenario.
     */
    public boolean isHeadOnConflict(TrackReservation other) {
        if (!conflictsWith(other)) return false;
        return this.forwardDirection != other.forwardDirection
                || (this.segmentStart.equals(other.segmentEnd) && this.segmentEnd.equals(other.segmentStart));
    }

    /**
     * Generate a unique key for the segment this reservation covers,
     * independent of direction. Used for hashmap lookups.
     */
    public String getSegmentKey() {
        // Normalize: always put the "smaller" position first
        int cmp = segmentStart.compareTo(segmentEnd);
        BlockPos first = cmp <= 0 ? segmentStart : segmentEnd;
        BlockPos second = cmp <= 0 ? segmentEnd : segmentStart;
        return first.toShortString() + "|" + second.toShortString();
    }

    @Override
    public String toString() {
        return String.format("Reservation[train=%s, %s->%s, dir=%s, active=%s]",
                ownerTrainId.toString().substring(0, 8),
                segmentStart.toShortString(), segmentEnd.toShortString(),
                forwardDirection ? "FWD" : "REV", active);
    }
}
