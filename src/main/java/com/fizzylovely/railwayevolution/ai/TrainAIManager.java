package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    // Cached reflection for Create Mod classes
    private boolean createModAvailable = true;
    private Object railwayManagerRef;
    private Field trainsField;
    private boolean reflectionReady = false;

    private TrainAIManager() {}

    public static void initialize() {
        instance = new TrainAIManager();
        VirtualBlockSystem.reset();
        CreateRailwayMod.LOGGER.info("[AI Manager] Initialized with fresh VBS");
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

        // Tick all AI controllers
        for (TrainAIController controller : controllers.values()) {
            try {
                controller.tick(level, currentTick);
            } catch (Exception e) {
                CreateRailwayMod.LOGGER.error("[AI Manager] Error ticking train {}: {}",
                        controller.getTrainId().toString().substring(0, 8), e.getMessage());
            }
        }

        // Periodic: VBS cleanup
        int cleanupInterval = RailwayConfig.vbsCleanupIntervalTicks.get();
        if (currentTick - lastVBSCleanupTick >= cleanupInterval) {
            VirtualBlockSystem.getInstance().cleanupExpired(currentTick);
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
     * Tries multiple approaches to find the RAILWAYS field.
     */
    private void initCreateReflection() {
        if (reflectionReady) return;

        try {
            Class<?> createClass = Class.forName("com.simibubi.create.Create");
            CreateRailwayMod.LOGGER.info("[AI Manager] Found Create Mod main class");

            // Try known field names for the railway manager
            for (String fieldName : new String[]{"RAILWAYS", "railways"}) {
                try {
                    Field f = createClass.getField(fieldName);
                    railwayManagerRef = f.get(null);
                    if (railwayManagerRef != null) {
                        CreateRailwayMod.LOGGER.info("[AI Manager] Found railway manager via field '{}'", fieldName);
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
                            CreateRailwayMod.LOGGER.info("[AI Manager] Found railway manager via declared field '{}' (type: {})",
                                    f.getName(), f.getType().getSimpleName());
                            break;
                        }
                    }
                }
            }

            if (railwayManagerRef == null) {
                // Log all static fields for debugging
                CreateRailwayMod.LOGGER.warn("[AI Manager] Could not find railway manager. Create class fields:");
                for (Field f : createClass.getDeclaredFields()) {
                    CreateRailwayMod.LOGGER.warn("  - {} : {} (static={})",
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
                    CreateRailwayMod.LOGGER.info("[AI Manager] Found trains map via field '{}'", fieldName);
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
                            // Check if keys are UUIDs
                            Object firstKey = map.keySet().iterator().next();
                            if (firstKey instanceof UUID) {
                                trainsField = f;
                                CreateRailwayMod.LOGGER.info("[AI Manager] Found trains map via declared field '{}' ({} entries)",
                                        f.getName(), map.size());
                                break;
                            }
                        }
                    }
                }
            }

            if (trainsField == null) {
                CreateRailwayMod.LOGGER.warn("[AI Manager] Could not find trains map. Manager fields:");
                for (Field f : managerClass.getDeclaredFields()) {
                    CreateRailwayMod.LOGGER.warn("  - {} : {}", f.getName(), f.getType().getSimpleName());
                }
                createModAvailable = false;
                return;
            }

            reflectionReady = true;
            CreateRailwayMod.LOGGER.info("[AI Manager] Reflection setup complete");

        } catch (ClassNotFoundException e) {
            CreateRailwayMod.LOGGER.warn("[AI Manager] Create Mod not found — AI system disabled");
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
            // Re-read the railway manager in case it was reloaded
            // (Create may recreate it on world load)
            if (railwayManagerRef == null) {
                reflectionReady = false;
                initCreateReflection();
                if (!reflectionReady) return;
            }

            Map<UUID, ?> createTrains = (Map<UUID, ?>) trainsField.get(railwayManagerRef);
            if (createTrains == null || createTrains.isEmpty()) {
                // No trains exist yet — clean up old controllers
                if (!controllers.isEmpty()) {
                    controllers.keySet().forEach(id -> VirtualBlockSystem.getInstance().releaseAll(id));
                    controllers.clear();
                }
                return;
            }

            // Add controllers for new trains
            Set<UUID> activeIds = new HashSet<>();
            for (Map.Entry<UUID, ?> entry : createTrains.entrySet()) {
                UUID id = entry.getKey();
                activeIds.add(id);

                if (!controllers.containsKey(id)) {
                    TrainAIController controller = new TrainAIController(id);
                    controller.bindCreateTrain(entry.getValue());
                    controllers.put(id, controller);
                    CreateRailwayMod.LOGGER.info("[AI Manager] Attached AI to train {}", id.toString().substring(0, 8));
                } else {
                    // Re-bind in case the Train object was replaced
                    controllers.get(id).bindCreateTrain(entry.getValue());
                }
            }

            // Remove controllers for removed trains
            controllers.keySet().removeIf(id -> {
                if (!activeIds.contains(id)) {
                    VirtualBlockSystem.getInstance().releaseAll(id);
                    CreateRailwayMod.LOGGER.info("[AI Manager] Detached AI from train {}", id.toString().substring(0, 8));
                    return true;
                }
                return false;
            });

        } catch (Exception e) {
            CreateRailwayMod.LOGGER.error("[AI Manager] Train scan error: {}", e.getMessage());
            // Reset reflection in case objects were invalidated
            reflectionReady = false;
        }
    }

    private void logDiagnostics() {
        if (controllers.isEmpty()) return;

        CreateRailwayMod.LOGGER.info("[AI Diagnostics] Tracking {} trains, {} VBS reservations",
                controllers.size(),
                VirtualBlockSystem.getInstance().getActiveReservationCount());

        for (TrainAIController ctrl : controllers.values()) {
            CreateRailwayMod.LOGGER.info("  {}", ctrl);
        }
    }

    // ─── Public API ───

    public Collection<TrainAIController> getAllControllers() {
        return Collections.unmodifiableCollection(controllers.values());
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
        }
    }
}
