package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.adapter.EcosystemRegistry;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.IdentityHashMap;

/**
 * Train AI Manager — global singleton managing all train AI controllers.
 *
 * Discovers Create Mod trains via reflection:
 *   Create.RAILWAYS (GlobalRailwayManager) -> trains (Map<UUID, Train>)
 *
 * Each discovered train gets a TrainAIController that reads position/speed
 * and stops the train when another train is detected ahead.
 */
public class TrainAIManager {

    private static TrainAIManager instance;

    private final Map<UUID, TrainAIController> controllers = new ConcurrentHashMap<>();
    private long lastVBSCleanupTick = 0;
    private long lastTrainScanTick = 0;
    private long lastDiagnosticTick = 0;

    // v1.0.7: Global per-tick scan maps — built once in buildScanMaps(), consumed by all controllers
    private final TrainScanMaps scanMaps = new TrainScanMaps();

    // ── Global Priority Registry ──
    // When a train has been starved (yielded too many times consecutively) it
    // is granted a priority token here. Other trains check this registry before
    // deciding whether to yield — if the approaching train has a token, they
    // yield IMMEDIATELY instead of running their normal distance checks.
    // Token maps trainId → expiry tick (automatically cleaned up each VBS cycle).
    private final ConcurrentHashMap<UUID, Long> priorityTokens = new ConcurrentHashMap<>();

    /**
     * Grant a priority token to a train for DURATION ticks.
     * Called by TrainAIController when it detects starvation.
     */
    public void grantPriority(UUID trainId, long expiryTick) {
        priorityTokens.put(trainId, expiryTick);
    }

    /**
     * Return true if trainId currently holds a valid priority token.
     * Automatically removes expired tokens.
     */
    public boolean hasPriority(UUID trainId, long currentTick) {
        Long expiry = priorityTokens.get(trainId);
        if (expiry == null) return false;
        if (currentTick > expiry) {
            priorityTokens.remove(trainId);
            return false;
        }
        return true;
    }

    /** Revoke a train's priority token (called when train starts moving again). */
    public void revokePriority(UUID trainId) {
        priorityTokens.remove(trainId);
    }

    /** Number of trains currently holding a priority token. */
    public int getPriorityTokenCount() { return priorityTokens.size(); }

    // Cached reflection for Create Mod classes
    private boolean createModAvailable = true;
    private Object railwayManagerRef;
    private Field trainsField;
    private boolean reflectionReady = false;

    private TrainAIManager() {}

    public static void initialize() {
        instance = new TrainAIManager();
        VirtualBlockSystem.reset();
        AccidentZoneMemory.reset();
        StoppedTrainRegistry.getInstance().clear();
        MovingTrainRegistry.getInstance().clear();
        JunctionReservationManager.getInstance().clear();
        // v1.0.5: Reset ecosystem registry (AI + player train handles)
        EcosystemRegistry.reset();
        CreateRailwayMod.aiLog("[AI Manager] Initialized with fresh VBS, accident zones, train registries, junction reservations, and EcosystemRegistry");
    }

    public static TrainAIManager getInstance() {
        return instance;
    }

    /**
     * Main tick — called every server tick from the event handler.
     */
    public void tick(ServerLevel level, long currentTick) {
        if (!createModAvailable) return;

        // Periodic: scan for new/removed trains
        int scanInterval = RailwayConfig.trainScanIntervalTicks.get();
        if (currentTick - lastTrainScanTick >= scanInterval) {
            scanForTrains(level);
            lastTrainScanTick = currentTick;
        }

        // v1.0.5: Refresh EcosystemRegistry VarHandle cache (fast path, 0 boxing)
        // Must run BEFORE controller ticks so PerceptionEngine has fresh data
        EcosystemRegistry eco = EcosystemRegistry.getInstance();
        eco.tickRefreshAll(currentTick);

        // v1.0.5: Scan which trains are player-controlled (every 20 ticks)
        // Creates/removes PlayerTrainHandle wrappers in EcosystemRegistry
        if (currentTick % 20 == 0) {
            eco.scanPlayerControls(level);
        }

        // v1.0.7: Build global scan maps BEFORE controller ticks.
        // One O(n) pass replaces the 3×n per-controller loops that were in graphWalkScan().
        // Controllers read the pre-built maps via getScanMaps() → O(1) lookups.
        buildScanMaps();

        // Tick all AI controllers
        for (TrainAIController controller : controllers.values()) {
            try {
                controller.tick(level, currentTick);
            } catch (Exception e) {
                CreateRailwayMod.LOGGER.error("[AI Manager] Error ticking train {}: {}",
                        controller.getTrainId().toString().substring(0, 8), e.getMessage());
            }
        }

        // Periodic: VBS + accident zone cleanup
        int cleanupInterval = RailwayConfig.vbsCleanupIntervalTicks.get();
        if (currentTick - lastVBSCleanupTick >= cleanupInterval) {
            VirtualBlockSystem.getInstance().cleanupExpired(currentTick);
            AccidentZoneMemory.getInstance().cleanupExpired(currentTick);
            JunctionReservationManager.getInstance().cleanupExpired(currentTick);
            // Also expire stale priority tokens
            priorityTokens.entrySet().removeIf(e -> currentTick > e.getValue());
            lastVBSCleanupTick = currentTick;
        }

        // Periodic diagnostics (every 10 seconds = 200 ticks)
        if (currentTick - lastDiagnosticTick >= 200) {
            logDiagnostics();
            lastDiagnosticTick = currentTick;
        }
    }

    /**
     * Initialize reflection access to Create Mod's GlobalRailwayManager.
     */
    private void initCreateReflection() {
        if (reflectionReady) return;

        try {
            Class<?> createClass = Class.forName("com.simibubi.create.Create");
            CreateRailwayMod.aiLog("[AI Manager] Found Create Mod main class");

            // Try known field names for the railway manager
            for (String fieldName : new String[]{"RAILWAYS", "railways"}) {
                try {
                    Field f = createClass.getField(fieldName);
                    railwayManagerRef = f.get(null);
                    if (railwayManagerRef != null) {
                        CreateRailwayMod.aiLog("[AI Manager] Found railway manager via field '{}'", fieldName);
                        break;
                    }
                } catch (NoSuchFieldException ignored) {}
            }

            // If public field not found, try declared fields
            if (railwayManagerRef == null) {
                for (Field f : createClass.getDeclaredFields()) {
                    if (f.getType().getSimpleName().contains("Railway")
                            || f.getType().getSimpleName().contains("railway")) {
                        f.setAccessible(true);
                        railwayManagerRef = f.get(null);
                        if (railwayManagerRef != null) {
                            CreateRailwayMod.aiLog("[AI Manager] Found railway manager via declared field '{}' (type: {})",
                                    f.getName(), f.getType().getSimpleName());
                            break;
                        }
                    }
                }
            }

            if (railwayManagerRef == null) {
                CreateRailwayMod.aiWarn("[AI Manager] Could not find railway manager. Create class fields:");
                for (Field f : createClass.getDeclaredFields()) {
                    CreateRailwayMod.aiWarn("  - {} : {} (static={})",
                            f.getName(), f.getType().getSimpleName(),
                            java.lang.reflect.Modifier.isStatic(f.getModifiers()));
                }
                createModAvailable = false;
                return;
            }

            // Find the 'trains' field on the railway manager
            Class<?> managerClass = railwayManagerRef.getClass();
            for (String fieldName : new String[]{"trains", "trainMap"}) {
                try {
                    trainsField = managerClass.getField(fieldName);
                    CreateRailwayMod.aiLog("[AI Manager] Found trains map via field '{}'", fieldName);
                    break;
                } catch (NoSuchFieldException ignored) {}
            }

            // If not found as public, scan declared fields for Map type
            if (trainsField == null) {
                for (Field f : managerClass.getDeclaredFields()) {
                    if (Map.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(railwayManagerRef);
                        if (val instanceof Map<?, ?> map && !map.isEmpty()) {
                            Object firstKey = map.keySet().iterator().next();
                            if (firstKey instanceof UUID) {
                                trainsField = f;
                                CreateRailwayMod.aiLog("[AI Manager] Found trains map via declared field '{}' ({} entries)",
                                        f.getName(), map.size());
                                break;
                            }
                        }
                    }
                }
            }

            if (trainsField == null) {
                CreateRailwayMod.aiWarn("[AI Manager] Could not find trains map. Manager fields:");
                for (Field f : managerClass.getDeclaredFields()) {
                    CreateRailwayMod.aiWarn("  - {} : {}", f.getName(), f.getType().getSimpleName());
                }
                createModAvailable = false;
                return;
            }

            reflectionReady = true;
            CreateRailwayMod.aiLog("[AI Manager] Reflection setup complete");

        } catch (ClassNotFoundException e) {
            CreateRailwayMod.aiWarn("[AI Manager] Create Mod not found — AI system disabled");
            createModAvailable = false;
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.error("[AI Manager] Reflection init error: {}", e.getMessage());
            createModAvailable = false;
        }
    }

    /**
     * Scan Create Mod's train registry and attach/detach AI controllers.
     */
    @SuppressWarnings("unchecked")
    private void scanForTrains(ServerLevel level) {
        if (!createModAvailable) return;

        if (!reflectionReady) {
            initCreateReflection();
            if (!reflectionReady) return;
        }

        try {
            if (railwayManagerRef == null) {
                reflectionReady = false;
                initCreateReflection();
                if (!reflectionReady) return;
            }

            Map<UUID, ?> createTrains = (Map<UUID, ?>) trainsField.get(railwayManagerRef);
            if (createTrains == null || createTrains.isEmpty()) {
                if (!controllers.isEmpty()) {
                    controllers.keySet().forEach(id -> {
                        VirtualBlockSystem.getInstance().releaseAll(id);
                        StoppedTrainRegistry.getInstance().remove(id);
                        MovingTrainRegistry.getInstance().remove(id);
                        JunctionReservationManager.getInstance().releaseAll(id);
                    });
                    controllers.clear();
                }
                return;
            }

            // Add controllers for new trains
            EcosystemRegistry eco = EcosystemRegistry.getInstance();
            Set<UUID> activeIds = new HashSet<>();
            for (Map.Entry<UUID, ?> entry : createTrains.entrySet()) {
                UUID id = entry.getKey();
                activeIds.add(id);

                if (!controllers.containsKey(id)) {
                    TrainAIController controller = new TrainAIController(id);
                    controller.bindCreateTrain(entry.getValue());
                    controllers.put(id, controller);
                    CreateRailwayMod.aiLog("[AI Manager] Attached AI to train {}", id.toString().substring(0, 8));
                } else {
                    controllers.get(id).bindCreateTrain(entry.getValue());
                }
                // v1.0.5: Register in EcosystemRegistry for PerceptionEngine + PlayerTrainHandle
                eco.getOrCreateAiHandle(id, entry.getValue());
            }

            // Remove controllers for removed trains
            controllers.keySet().removeIf(id -> {
                if (!activeIds.contains(id)) {
                    VirtualBlockSystem.getInstance().releaseAll(id);
                    StoppedTrainRegistry.getInstance().remove(id);
                    MovingTrainRegistry.getInstance().remove(id);
                    JunctionReservationManager.getInstance().releaseAll(id);
                    // v1.0.5: Remove from EcosystemRegistry
                    eco.removeTrainHandle(id);
                    CreateRailwayMod.aiLog("[AI Manager] Detached AI from train {}", id.toString().substring(0, 8));
                    return true;
                }
                return false;
            });

        } catch (Exception e) {
            CreateRailwayMod.LOGGER.error("[AI Manager] Train scan error: {}", e.getMessage());
            reflectionReady = false;
        }
    }

    private void logDiagnostics() {
        if (controllers.isEmpty()) return;

        CreateRailwayMod.aiLog("[AI Diagnostics] Tracking {} trains, {} VBS, {} accident zones, {} priority tokens, {} stopped + {} moving, {} junction locks",
                controllers.size(),
                VirtualBlockSystem.getInstance().getActiveReservationCount(),
                AccidentZoneMemory.getInstance().getActiveZoneCount(),
                priorityTokens.size(),
                StoppedTrainRegistry.getInstance().size(),
                MovingTrainRegistry.getInstance().size(),
                JunctionReservationManager.getInstance().size());

        for (TrainAIController ctrl : controllers.values()) {
            CreateRailwayMod.aiLog("  {}", ctrl);
        }
    }

    // ─── Public API ───

    public Collection<TrainAIController> getAllControllers() {
        return Collections.unmodifiableCollection(controllers.values());
    }

    /** v1.0.7: Pre-built global scan maps (never null). */
    public TrainScanMaps getScanMaps() {
        return scanMaps;
    }

    public TrainAIController getController(UUID trainId) {
        return controllers.get(trainId);
    }

    public int getTrackedTrainCount() {
        return controllers.size();
    }

    public void removeController(UUID trainId) {
        TrainAIController removed = controllers.remove(trainId);
        if (removed != null) {
            VirtualBlockSystem.getInstance().releaseAll(trainId);
            StoppedTrainRegistry.getInstance().remove(trainId);
            MovingTrainRegistry.getInstance().remove(trainId);
            JunctionReservationManager.getInstance().releaseAll(trainId);
        }
    }

    /**
     * v1.0.7: Build global scan maps in a single O(n) pass.
     * Populates edgeToTrain, reverseEdgeMap, and junctionApproachMap
     * from ALL controllers. Per-train filtering (self, bypass, etc.)
     * is deferred to query time in TrainAIController.filterGlobalHit().
     *
     * Called once per tick BEFORE controller ticks. Uses data from the
     * PREVIOUS tick (controllers haven't called updateTrainData yet),
     * which is acceptable: trains move ≤1.4 blocks/tick and the
     * ultra-close emergency scan runs per-tick regardless.
     */
    private void buildScanMaps() {
        scanMaps.clear();

        for (TrainAIController ctrl : controllers.values()) {
            // ── edgeToTrain: leading + trailing edge → controller ──
            Object leadEdge = ctrl.getLeadingEdge();
            Object trailEdge = ctrl.getTrailingEdge();
            if (leadEdge != null)
                scanMaps.edgeToTrain.putIfAbsent(leadEdge, ctrl);
            if (trailEdge != null)
                scanMaps.edgeToTrain.putIfAbsent(trailEdge, ctrl);

            // ── reverseEdgeMap: pre-computed reverse edge → controller ──
            // Each controller computes myReverseLeadingEdge in readTrackGraphData().
            // This is the edge(node2→node1) — the reverse of their forward direction.
            // When our BFS walks edge(node1→node2), this map detects head-on trains.
            Object revEdge = ctrl.getReverseLeadingEdge();
            if (revEdge != null)
                scanMaps.reverseEdgeMap.putIfAbsent(revEdge, ctrl);

            // ── junctionApproachMap: node2 → controller approaching that junction ──
            // Only include if the controller's leading edge is NOT already in edgeToTrain
            // (avoids double-counting trains already detectable via edge-based BFS).
            Object node2 = ctrl.getLeadingNode2();
            if (node2 != null && leadEdge != null && ctrl.getCurrentPosition() != null) {
                if (!scanMaps.edgeToTrain.containsKey(leadEdge)
                        || scanMaps.edgeToTrain.get(leadEdge) == ctrl) {
                    // Use putIfAbsent — first train registered at this junction wins.
                    // BFS applies distance-based tiebreaking at query time anyway.
                    scanMaps.junctionApproachMap.putIfAbsent(node2, ctrl);
                }
            }
        }
    }
}
