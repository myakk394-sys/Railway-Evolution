package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import net.minecraft.core.BlockPos;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AccidentZoneMemory — remembers where near-collisions (WAIT_FOR_CLEARANCE events) occurred.
 *
 * When a train enters the critical buffer zone (near-collision), it registers that
 * position as an "accident zone". Other trains approaching that area slow down
 * aggressively BEFORE getting close enough to cause their own cascade crash.
 *
 * This prevents the "accordion" effect:
 *   Train A stops (crash zone registered).
 *   Train B sees zone at 25+ blocks and decelerates gradually → stops 15 blocks back.
 *   Train C sees Train B at 50 blocks, brakes → stops 15 blocks behind B.
 *   = Trains queue with safe gaps instead of piling up.
 *
 * Zones expire automatically after ZONE_TTL_TICKS (60 seconds).
 */
public class AccidentZoneMemory {

    private static AccidentZoneMemory instance;

    /** How many ticks an accident zone stays active (default 60 seconds = 1200 ticks). */
    private static final long ZONE_TTL_TICKS = 1200L;

    /**
     * Radius (in blocks) within which a train position is considered "in the zone".
     * If an approaching train detects an obstacle within this radius of a zone,
     * it uses the extended braking profile.
     */
    public static final double ZONE_RADIUS = 20.0;

    /** Minimum forced stop distance when approaching an accident zone (blocks). */
    public static final double ZONE_STOP_DISTANCE = 15.0;

    private static final class Zone {
        final BlockPos pos;
        final UUID registeredBy;
        long expiryTick;

        Zone(BlockPos pos, UUID registeredBy, long currentTick) {
            this.pos = pos;
            this.registeredBy = registeredBy;
            this.expiryTick = currentTick + ZONE_TTL_TICKS;
        }

        void refresh(long currentTick) {
            this.expiryTick = currentTick + ZONE_TTL_TICKS;
        }
    }

    /** UUID → Zone for currently active accident zones. */
    private final Map<UUID, Zone> zones = new ConcurrentHashMap<>();

    private AccidentZoneMemory() {}

    public static AccidentZoneMemory getInstance() {
        if (instance == null) instance = new AccidentZoneMemory();
        return instance;
    }

    public static void reset() {
        instance = new AccidentZoneMemory();
    }

    /**
     * Register an accident zone at the given position.
     * Called when a train enters WAIT_FOR_CLEARANCE (critical collision zone).
     *
     * @param trainId    UUID of the train that triggered the near-collision
     * @param pos        World position of the near-collision
     * @param currentTick Current server tick
     */
    public void register(UUID trainId, BlockPos pos, long currentTick) {
        Zone existing = zones.get(trainId);
        if (existing != null) {
            existing.refresh(currentTick);
            return;
        }
        zones.put(trainId, new Zone(pos, trainId, currentTick));
        CreateRailwayMod.LOGGER.info("[AccidentZone] Zone registered by train {} at {} (active {}s)",
                trainId.toString().substring(0, 8),
                pos.toShortString(),
                ZONE_TTL_TICKS / 20);
    }

    /**
     * Clear the accident zone registered by this train (it has moved away / resolved).
     */
    public void clear(UUID trainId) {
        if (zones.remove(trainId) != null) {
            CreateRailwayMod.LOGGER.debug("[AccidentZone] Zone cleared for train {}",
                    trainId.toString().substring(0, 8));
        }
    }

    /**
     * Check if the given position is near any active accident zone (excluding the
     * zone registered by ignoredTrainId — usually the calling train itself).
     *
     * @param pos           Position to check
     * @param ignoredTrainId Train whose own zone to ignore (can be null)
     * @param currentTick   Current server tick
     * @return Distance to the nearest active zone, or -1 if none found
     */
    public double distanceToNearestZone(BlockPos pos, UUID ignoredTrainId, long currentTick) {
        double closest = -1;
        for (Zone zone : zones.values()) {
            if (zone.expiryTick < currentTick) continue;
            if (ignoredTrainId != null && ignoredTrainId.equals(zone.registeredBy)) continue;

            double dx = pos.getX() - zone.pos.getX();
            double dy = pos.getY() - zone.pos.getY();
            double dz = pos.getZ() - zone.pos.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist <= ZONE_RADIUS) {
                if (closest < 0 || dist < closest) closest = dist;
            }
        }
        return closest;
    }

    /**
     * Purge expired zones. Call periodically from server tick.
     */
    public void cleanupExpired(long currentTick) {
        Iterator<Map.Entry<UUID, Zone>> it = zones.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Zone> entry = it.next();
            if (entry.getValue().expiryTick < currentTick) {
                CreateRailwayMod.LOGGER.debug("[AccidentZone] Zone expired for train {}",
                        entry.getKey().toString().substring(0, 8));
                it.remove();
            }
        }
    }

    public int getActiveZoneCount() {
        return zones.size();
    }
}
