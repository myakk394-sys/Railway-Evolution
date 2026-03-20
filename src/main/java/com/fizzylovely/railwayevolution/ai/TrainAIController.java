package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.config.RailwayConfig;
import com.fizzylovely.railwayevolution.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Train AI Controller — the "brain" of a single train.
 *
 * Integrates with Create Mod's Train, Navigation, Carriage, CarriageBogey,
 * and TravellingPoint classes via reflection.
 *
 * Key Create Mod fields used:
 *   Train.speed (double) — current speed
 *   Train.targetSpeed (double) — target speed set by Navigation
 *   Train.carriages (List) — list of Carriage objects
 *   Train.navigation (Navigation) — pathfinding controller
 *   Train.derailed (boolean) — derailment flag
 *   Navigation.distanceToDestination (double)
 *   Carriage.bogeys (Couple) — front/rear bogeys
 *   CarriageBogey -> TravellingPoint -> TrackNode -> TrackNodeLocation (Vec3i)
 */
public class TrainAIController {

    private final UUID trainId;
    private TrainState currentState;
    private TrainState previousState;
    private long stateEnteredTick;
    private long totalWaitTicks;
    private boolean isEmergency;

    // Cached environment data
    private BlockPos currentPosition;
    private Vec3 precisePosition;
    private double currentSpeed;
    private double maxSpeed;
    private boolean direction;
    private int trainLength;
    private int carriageCount;
    private double distToDestination;
    private boolean derailed;

    // Bypass tracking
    private boolean onOppositeTrack;
    private BlockPos obstaclePosition;
    private UUID obstacleTrainId;

    // Direction tracking (for the directional forward scan "invisible stick")
    private BlockPos previousPosition;
    double headingX;  // normalized unit vector (package-visible for visualizer)
    double headingZ;
    private boolean headingFromMovement; // true if heading was computed from actual movement

    // Bypass mode — when bypassing/reversing, completely ignore the train we're
    // going around so the beam doesn't re-detect it on the parallel track
    private UUID bypassingTrainId;
    private long bypassModeUntilTick;
    private static final int BYPASS_MODE_TICKS = 300; // 15 seconds of ignore mode
    private static final double BEAM_LATERAL_TOLERANCE      = 1.1; // straight track beam
    private static final double BEAM_LATERAL_TOLERANCE_CURVE = 2.5; // curve / turn beam

    // Dynamic beam tolerance — updated every tick based on current edge type
    private double currentBeamTolerance = BEAM_LATERAL_TOLERANCE;

    // Avoidance marker — after reversing from a jam, remember the blocked edge
    // and the train that was blocking so we immediately yield if still there
    private Object blockedEdge;       // myLeadingEdge at the moment we started reversing
    private UUID   blockedByTrainId;  // obstacle train at reversal start
    private long   avoidanceUntilTick; // ticks until avoidance marker expires
    private static final int AVOIDANCE_TICKS = 250; // ~12 seconds

    // Smart Junction re-routing — after being stuck for REROUTE_WAIT_TICKS, cancel
    // navigation so Create re-pathfinds; hysteresis prevents thrashing at switches
    private long junctionBlockedSinceTick = -1; // tick when we entered blocked-waiting state
    private long lastRerouteTick = -1;           // last tick we triggered a reroute
    private static final int REROUTE_WAIT_TICKS    = 100; // 5 s before attempting reroute
    private static final int REROUTE_COOLDOWN_TICKS = 200; // 10 s minimum between reroutes

    // Cached TrackGraph data for same-track detection
    private Set<UUID> myOccupiedSignalGroups = new HashSet<>();
    private Object myLeadingEdge;   // TravellingPoint.edge of first carriage leading point
    private Object myTrailingEdge;  // TravellingPoint.edge of last carriage trailing point
    private Object myGraph;         // Train.graph

    // Reverse-escape maneuver state
    private BlockPos reversingStartPosition;
    private long reversingStartTick;
    private boolean spaceAvailableBehind;

    // Player control detection
    private boolean playerControlled;

    // Create Mod train reference
    private Object createTrainRef;

    // Level reference for broadcasting chat messages
    private ServerLevel lastKnownLevel;

    // Cached reflection fields (avoid repeated lookups)
    private Field speedField;
    private Field targetSpeedField;
    private Field throttleField;
    private Field carriagesField;
    private Field navigationField;
    private Field derailedField;
    private Field manualTickField;
    private Field runtimeField;
    private Field graphField;
    private Field occupiedSignalBlocksField;
    private java.lang.reflect.Method isTurnMethod; // TrackEdge.isTurn()
    private boolean reflectionInitialized;

    // Graph-walk scanner: TravellingPoint data for leading carriage
    private Object myLeadingNode1;     // TravellingPoint.node1 (TrackNode)
    private Object myLeadingNode2;     // TravellingPoint.node2 (forward direction)
    private double myLeadingEdgePos;   // TravellingPoint.position on leading edge
    private double graphDistanceToObstacle = -1; // graph distance (blocks), -1 = n/a
    private boolean graphScanActive;   // true if last detection was via graph walk

    // Cached reflection for graph-walk scanner (lazily initialized)
    private Field tpNode1Field;
    private Field tpNode2Field;
    private Field tpPositionField;
    private Method getLengthMethod;    // TrackEdge.getLength()
    private Method getConnectionsFromMethod; // TrackGraph.getConnectionsFrom(TrackNode)

    // Route-aware BFS scanner: node-level next-hop map for SIDE_TRACK filtering.
    //
    // Navigation.currentPath = List<Couple<TrackNode>>  (confirmed via javap, Create 6.0.1-41)
    // Each Couple(nodeA, nodeB) describes one directed hop along our route.
    //
    // routeNextHop maps: nodeA → nodeB  (identity-based)
    // In BFS: if routeNextHop contains the current node, ONLY take the branch to routeNextHop.get(node).
    // This constrains BFS to our scheduled route without needing edge-object resolution.
    // Falls back gracefully (map empty → BFS unrestricted) if reflection fails.
    private final IdentityHashMap<Object, Object> routeNextHop = new IdentityHashMap<>();
    private Field   navCurrentPathField = null;
    private boolean navPathReflInit     = false;

    // Per-scan classification flags (set by graphWalkScan, consumed by scanForObstacleController / handleObstacleDetected)
    private boolean graphHitIsParkingZone = false; // hit train is at a dead-end (depot/station)
    private boolean graphHitIsDeparting   = false; // hit train is ahead, same direction, faster
    // Set to true when graphWalkScan fully executed (had valid graph data), regardless of result.
    // scanForObstacleController skips the fallback beam scanner when this is true, because
    // the BFS result is then authoritative (route clear → no obstacle).
    boolean graphScanRan = false;

    public TrainAIController(UUID trainId) {
        this.trainId = trainId;
        this.currentState = TrainState.CRUISING;
        this.previousState = TrainState.CRUISING;
        this.stateEnteredTick = 0;
        this.totalWaitTicks = 0;
        this.isEmergency = false;
        this.onOppositeTrack = false;
        this.reflectionInitialized = false;
    }

    public void bindCreateTrain(Object createTrain) {
        this.createTrainRef = createTrain;
        this.reflectionInitialized = false;
        initReflection();
    }

    /**
     * Cache reflection Field objects once so we don't do lookups every tick.
     */
    private void initReflection() {
        if (reflectionInitialized || createTrainRef == null) return;
        try {
            Class<?> cls = createTrainRef.getClass();
            speedField = findField(cls, "speed");
            targetSpeedField = findField(cls, "targetSpeed");
            throttleField = findField(cls, "throttle");
            carriagesField = findField(cls, "carriages");
            navigationField = findField(cls, "navigation");
            derailedField = findField(cls, "derailed");
            manualTickField = findField(cls, "manualTick");
            runtimeField = findField(cls, "runtime");
            graphField = findField(cls, "graph");
            occupiedSignalBlocksField = findField(cls, "occupiedSignalBlocks");
            reflectionInitialized = true;
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.error("[AI] Reflection init failed for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /**
     * Resolve TrackEdge.isTurn() method lazily from the first available edge.
     * Called once after we get an edge object.
     */
    private void initIsTurnMethod(Object edgeObject) {
        if (isTurnMethod != null || edgeObject == null) return;
        try {
            isTurnMethod = edgeObject.getClass().getMethod("isTurn");
            isTurnMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            CreateRailwayMod.LOGGER.debug("[AI] TrackEdge.isTurn() not found: {}", e.getMessage());
        }
    }

    /**
     * Main tick — called every server tick by TrainAIManager.
     */
    public void tick(ServerLevel level, long currentTick) {
        if (createTrainRef == null) return;
        this.lastKnownLevel = level;

        // Step 1: Read all train data from Create Mod
        updateTrainData(level);

        // If a player is manually controlling this train, skip all AI logic.
        // The train's position is still updated above so OTHER AI trains see it
        // as an obstacle and brake accordingly.
        if (playerControlled) {
            // Make sure we don't leave throttle zeroed from a previous AI stop
            restoreThrottle();
            if (currentState != TrainState.CRUISING) {
                transitionTo(TrainState.CRUISING, currentTick, "player_control");
                totalWaitTicks = 0;
            }
            return;
        }

        // Step 2: Stamp VBS footprint so other trains can see us in segment-conflict checks
        proactiveReserve(currentTick);

        // Auto-clear bypass mode early if the bypassed train is now behind us
        clearBypassIfPassed();

        // Step 3: Directional 50-block forward scan ("invisible stick" ahead of the train)
        TrainAIController obstacleController = scanForObstacleController(level);
        boolean obstacleDetected = obstacleController != null;

        // Step 3b: Scan behind for reverse-path safety
        TrainAIController behindTrain = scanBehindForObstacle();
        spaceAvailableBehind = (behindTrain == null);
        boolean sandwiched = obstacleDetected && !spaceAvailableBehind;

        // Step 4: State-specific decision
        if (currentState == TrainState.REVERSING) {
            // Dedicated reverse handler — do not override with forward obstacle logic
            handleReversing(currentTick);
        } else if (sandwiched) {
            // TRAFFIC JAM — train in front AND behind, just wait
            forceStop();
            transitionTo(TrainState.TRAFFIC_JAM, currentTick,
                    "jam_front_and_behind");
        } else if (obstacleDetected) {
            double dist = distanceBetween(this, obstacleController);
            // Use graph distance when available (more accurate along curved rails)
            if (graphScanActive && graphDistanceToObstacle > 0) {
                dist = graphDistanceToObstacle;
            }
            // Hysteresis: if already in WAIT_FOR_CLEARANCE, stay locked until
            // the obstacle is at least BUFFER_CLEARANCE (7) blocks away.
            if (currentState == TrainState.WAIT_FOR_CLEARANCE && dist < BUFFER_CLEARANCE) {
                smoothStop();
                // Stay in WAIT_FOR_CLEARANCE — don't call handleObstacleDetected
            } else {
                handleObstacleDetected(dist, obstacleController, currentTick);
            }
        } else {
            handleNoObstacle(currentTick);
        }

        // Step 5: Escalation logic for stuck trains
        if (currentState == TrainState.TRAFFIC_JAM) {
            // Sandwiched trains just wait — do nothing, the jam will clear
            // when another train finds a bypass
            totalWaitTicks++;
            forceStop();
        } else if (currentState == TrainState.WAIT_FOR_CLEARANCE) {
            // Buffer lockout — keep the train fully stopped to prevent hitbox collision.
            // No escalation to reverse; the obstacle must move away first.
            totalWaitTicks++;
            forceStop();
            trySmartJunctionReroute(currentTick);
        } else if (currentState == TrainState.YIELDING) {
            totalWaitTicks++;
            // Smart junction reroute: attempt before escalating to reverse.
            // This lets Create re-pathfind via a free adjacent edge at the junction.
            trySmartJunctionReroute(currentTick);
            long maxYield = RailwayConfig.maxYieldTicks.get();
            boolean reverseEnabled = RailwayConfig.reverseManeuverEnabled.get();
            // Only reverse if this is the LAST train (nothing behind)
            if (totalWaitTicks > maxYield && reverseEnabled && spaceAvailableBehind) {
                reversingStartPosition = currentPosition;
                reversingStartTick = currentTick;
                // Save the blocked edge so after reverse we can immediately re-yield
                // if the obstacle is still on that exact rail segment.
                blockedEdge = myLeadingEdge;
                blockedByTrainId = obstacleTrainId;
                avoidanceUntilTick = currentTick + AVOIDANCE_TICKS;
                // transitionTo needs obstacleTrainId to activate bypass-ignore mode,
                // so we must NOT clear it before the transition call
                transitionTo(TrainState.REVERSING, currentTick, "last_in_jam_reversing");
                obstaclePosition = null;
                obstacleTrainId = null;
                totalWaitTicks = 0;
            }
        } else if (currentState != TrainState.REVERSING) {
            totalWaitTicks = 0;
        }
    }

    // ─── Data reading from Create Mod ───

    private void updateTrainData(ServerLevel level) {
        if (createTrainRef == null || !reflectionInitialized) return;

        // Save previous position for directional scanner heading computation
        if (this.currentPosition != null) {
            this.previousPosition = this.currentPosition;
        }

        try {
            // Read speed
            if (speedField != null) {
                double rawSpeed = speedField.getDouble(createTrainRef);
                this.currentSpeed = Math.abs(rawSpeed);
                this.direction = rawSpeed >= 0;
            }

            // Read target speed for max reference
            if (targetSpeedField != null) {
                this.maxSpeed = Math.max(1.2, Math.abs(targetSpeedField.getDouble(createTrainRef)));
            }

            // Read derailed flag
            if (derailedField != null) {
                this.derailed = derailedField.getBoolean(createTrainRef);
            }

            // Detect if a player is manually controlling this train:
            //   manualTick == true means a player is actively driving right now
            //   runtime.schedule == null means no schedule is assigned (pure manual mode)
            //   runtime.paused == true means schedule exists but player took over
            this.playerControlled = false;
            if (manualTickField != null) {
                this.playerControlled = manualTickField.getBoolean(createTrainRef);
            }
            if (!this.playerControlled && runtimeField != null) {
                Object runtime = runtimeField.get(createTrainRef);
                if (runtime != null) {
                    Field schedField = findField(runtime.getClass(), "schedule");
                    Field pausedField = findField(runtime.getClass(), "paused");
                    boolean hasSchedule = schedField != null && schedField.get(runtime) != null;
                    boolean isPaused = pausedField != null && pausedField.getBoolean(runtime);
                    // No schedule at all = player/manual mode
                    // Schedule paused = player took manual control
                    if (!hasSchedule || isPaused) {
                        this.playerControlled = true;
                    }
                }
            }

            // Read navigation distance
            if (navigationField != null) {
                Object nav = navigationField.get(createTrainRef);
                if (nav != null) {
                    Field distField = findField(nav.getClass(), "distanceToDestination");
                    if (distField != null) {
                        this.distToDestination = distField.getDouble(nav);
                    }
                }
            }

            // Read carriages and extract position
            if (carriagesField != null) {
                List<?> carriages = (List<?>) carriagesField.get(createTrainRef);
                this.carriageCount = carriages != null ? carriages.size() : 1;
                this.trainLength = this.carriageCount * 6;

                if (carriages != null && !carriages.isEmpty()) {
                    BlockPos pos = extractPositionFromCarriage(carriages.get(0), level);
                    if (pos != null) {
                        this.currentPosition = pos;
                    } else {
                        CreateRailwayMod.LOGGER.warn("[AI] Train {} position is NULL — all extraction methods failed",
                                trainId.toString().substring(0, 8));
                    }
                }
            } else {
                CreateRailwayMod.LOGGER.warn("[AI] Train {} has no carriagesField — reflection incomplete",
                        trainId.toString().substring(0, 8));
            }

            // Update heading vector from movement
            updateHeading();

            // Read TrackGraph data for same-track detection
            readTrackGraphData();

        } catch (Exception e) {
            CreateRailwayMod.LOGGER.warn("[AI] Data read error for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /** Recompute normalized heading unit vector from position delta. */
    private void updateHeading() {
        if (previousPosition != null && currentPosition != null
                && !previousPosition.equals(currentPosition)) {
            double dx = currentPosition.getX() - previousPosition.getX();
            double dz = currentPosition.getZ() - previousPosition.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.001) {
                this.headingX = dx / len;
                this.headingZ = dz / len;
                this.headingFromMovement = true;
            }
        }
        // If heading is still unknown but train has a direction flag, use ±Z as default
        if (headingX == 0 && headingZ == 0) {
            this.headingZ = direction ? 1.0 : -1.0;
            this.headingFromMovement = false;
        }
        // When stopped, KEEP the last movement-based heading so the directional
        // filter stays active. This prevents false detections of trains on other
        // tracks / curves. Only trains that have NEVER moved remain omnidirectional.
    }

    /**
     * Read track-graph data from Create's Train object:
     *   - Train.graph (TrackGraph) — for graph identity comparison
     *   - Train.occupiedSignalBlocks (Map<UUID, UUID>) — signal block group IDs
     *   - TravellingPoint.edge from leading/trailing points — for direct edge comparison
     */
    @SuppressWarnings("unchecked")
    private void readTrackGraphData() {
        myOccupiedSignalGroups.clear();
        myLeadingEdge = null;
        myTrailingEdge = null;
        myGraph = null;
        myLeadingNode1 = null;
        myLeadingNode2 = null;
        myLeadingEdgePos = 0;

        try {
            // Train.graph
            if (graphField != null) {
                myGraph = graphField.get(createTrainRef);
            }

            // Train.occupiedSignalBlocks (Map<UUID, UUID>)
            if (occupiedSignalBlocksField != null) {
                Object osbObj = occupiedSignalBlocksField.get(createTrainRef);
                if (osbObj instanceof Map<?, ?> map) {
                    for (Object key : map.keySet()) {
                        if (key instanceof UUID uuid) {
                            myOccupiedSignalGroups.add(uuid);
                        }
                    }
                }
            }

            // Read leading edge of first carriage and trailing edge of last carriage
            if (carriagesField != null) {
                List<?> carriages = (List<?>) carriagesField.get(createTrainRef);
                if (carriages != null && !carriages.isEmpty()) {
                    // First carriage → full TravellingPoint data (edge, node1, node2, position)
                    Object firstCarriage = carriages.get(0);
                    extractLeadingPointData(firstCarriage);
                    // Last carriage → getTrailingPoint() → .edge
                    Object lastCarriage = carriages.get(carriages.size() - 1);
                    myTrailingEdge = extractEdge(lastCarriage, "getTrailingPoint");
                }
            }
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.debug("[AI] TrackGraph read failed for {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
        // Update the beam width based on whether our leading edge is a curve or straight
        updateBeamTolerance();
        // Build route next-hop map for BFS side-track filtering
        updateRouteNextHop();
    }

    /**
     * Dynamically set currentBeamTolerance based on TrackEdge.isTurn():
     *   - Straight track → 1.1 blocks (tight, only our rail center)
     *   - Curved track   → 2.5 blocks (wider, parallel tracks can converge on bends)
     */
    private void updateBeamTolerance() {
        if (myLeadingEdge == null) {
            currentBeamTolerance = BEAM_LATERAL_TOLERANCE;
            return;
        }
        // Resolve isTurn() method lazily once
        initIsTurnMethod(myLeadingEdge);
        if (isTurnMethod == null) {
            currentBeamTolerance = BEAM_LATERAL_TOLERANCE;
            return;
        }
        try {
            boolean isCurve = Boolean.TRUE.equals(isTurnMethod.invoke(myLeadingEdge));
            currentBeamTolerance = isCurve ? BEAM_LATERAL_TOLERANCE_CURVE : BEAM_LATERAL_TOLERANCE;
        } catch (Exception e) {
            currentBeamTolerance = BEAM_LATERAL_TOLERANCE;
        }
    }

    /** Extract TravellingPoint.edge from a Carriage via the named method (getLeadingPoint/getTrailingPoint). */
    private Object extractEdge(Object carriage, String pointMethodName) {
        try {
            Method getPoint = findMethod(carriage.getClass(), pointMethodName);
            if (getPoint != null) {
                Object tp = getPoint.invoke(carriage);
                if (tp != null) {
                    Field edgeField = findField(tp.getClass(), "edge");
                    if (edgeField != null) {
                        return edgeField.get(tp);
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Extract full TravellingPoint data from the leading carriage:
     * edge, node1, node2, position. Used for graph-walk scanner.
     */
    private void extractLeadingPointData(Object carriage) {
        myLeadingEdge = null;
        myLeadingNode1 = null;
        myLeadingNode2 = null;
        myLeadingEdgePos = 0;
        try {
            Method getPoint = findMethod(carriage.getClass(), "getLeadingPoint");
            if (getPoint == null) return;
            Object tp = getPoint.invoke(carriage);
            if (tp == null) return;

            // Lazily cache TravellingPoint field references
            if (tpNode1Field == null) {
                tpNode1Field = findField(tp.getClass(), "node1");
                tpNode2Field = findField(tp.getClass(), "node2");
                tpPositionField = findField(tp.getClass(), "position");
            }

            Field edgeField = findField(tp.getClass(), "edge");
            if (edgeField != null) myLeadingEdge = edgeField.get(tp);
            if (tpNode1Field != null) myLeadingNode1 = tpNode1Field.get(tp);
            if (tpNode2Field != null) myLeadingNode2 = tpNode2Field.get(tp);
            if (tpPositionField != null) myLeadingEdgePos = tpPositionField.getDouble(tp);
        } catch (Exception ignored) {}
    }

    /**
     * Extract world position from a Create Carriage object.
     *
     * Verified against Create Mod mc1.20.1/dev source code:
     *   Carriage.anyAvailableEntity() → CarriageContraptionEntity (Entity) → position()
     *   Carriage.entities (Map<ResourceKey, DimensionalCarriageEntity>) → dce.positionAnchor (Vec3)
     *   Carriage.bogeys (Couple) → CarriageBogey.getAnchorPosition() → Vec3
     *
     * Priority order:
     *   0. anyAvailableEntity() → Entity → position()       [BEST — actual entity position]
     *   1. entities map → DimensionalCarriageEntity.positionAnchor  [reliable even when entity unloaded]
     *   2. bogeys → CarriageBogey.getAnchorPosition()       [fallback via bogey anchor]
     */
    private BlockPos extractPositionFromCarriage(Object carriage, ServerLevel level) {
        // ─── Approach 0: Carriage.anyAvailableEntity() → Entity → position() ───
        try {
            Method anyAvailableEntity = findMethod(carriage.getClass(), "anyAvailableEntity");
            if (anyAvailableEntity != null) {
                Object entity = anyAvailableEntity.invoke(carriage);
                if (entity instanceof net.minecraft.world.entity.Entity e && e.isAlive()) {
                    this.precisePosition = e.position();
                    return BlockPos.containing(e.getX(), e.getY(), e.getZ());
                }
            }
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.debug("[AI] anyAvailableEntity() failed for {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }

        // ─── Approach 1: Carriage.entities (Map) → DimensionalCarriageEntity.positionAnchor (Vec3) ───
        try {
            Field entitiesField = findField(carriage.getClass(), "entities");
            if (entitiesField != null) {
                Object entitiesObj = entitiesField.get(carriage);
                if (entitiesObj instanceof Map<?, ?> map) {
                    for (Object dce : map.values()) {
                        Field posField = findField(dce.getClass(), "positionAnchor");
                        if (posField != null) {
                            Object posAnchor = posField.get(dce);
                            if (posAnchor instanceof Vec3 v && (v.x != 0 || v.y != 0 || v.z != 0)) {
                                this.precisePosition = v;
                                return BlockPos.containing(v);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.debug("[AI] entities.positionAnchor failed for {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }

        // ─── Approach 2: bogeys → CarriageBogey.getAnchorPosition() → Vec3 ───
        try {
            Field bogeysField = findField(carriage.getClass(), "bogeys");
            if (bogeysField != null) {
                Object bogeys = bogeysField.get(carriage);
                if (bogeys != null) {
                    // Couple.getFirst() returns the leading bogey
                    Method getFirst = findMethod(bogeys.getClass(), "getFirst");
                    if (getFirst != null) {
                        Object firstBogey = getFirst.invoke(bogeys);
                        if (firstBogey != null) {
                            // CarriageBogey.getAnchorPosition() returns Vec3
                            Method getAnchor = findMethod(firstBogey.getClass(), "getAnchorPosition");
                            if (getAnchor != null) {
                                Object anchor = getAnchor.invoke(firstBogey);
                                if (anchor instanceof Vec3 v && (v.x != 0 || v.y != 0 || v.z != 0)) {
                                    this.precisePosition = v;
                                    return BlockPos.containing(v);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.debug("[AI] bogey.getAnchorPosition() failed for {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }

        return null;
    }

    // ─── Obstacle Detection ───

    /**
     * TrackGraph-based forward scanner.
     *
     * Detection strategy (from Create's own collision approach):
     *   1. PRIMARY: Compare occupiedSignalBlocks group UUIDs between trains.
     *      If two trains share a signal block group, they're on the same track segment.
     *   2. SECONDARY: Compare TravellingPoint.edge (object identity) — trains on
     *      the same TrackEdge are definitely on the same rail.
     *   3. FALLBACK: Very narrow 1.1-block beam along heading for unsignalled track
     *      or when TrackGraph data is unavailable.
     *
     * Trains with TRAFFIC_JAM status on a DIFFERENT track are always ignored.
     */
    private TrainAIController scanForObstacleController(ServerLevel level) {
        if (currentPosition == null) return null;

        long currentTick = level.getGameTime();

        // Expire bypass mode by time
        if (bypassingTrainId != null && currentTick >= bypassModeUntilTick) {
            CreateRailwayMod.LOGGER.info("[AI] Train {} bypass-ignore expired for {}",
                    trainId.toString().substring(0, 8),
                    bypassingTrainId.toString().substring(0, 8));
            bypassingTrainId = null;
        }

        double range = RailwayConfig.obstacleDetectionRange.get();

        // ── PRIMARY: Graph-walk scanner (topology-aware, curve-proof) ──
        // Walks forward along connected TrackEdges instead of casting a 3D beam.
        // Immune to curve geometry — follows actual rail connectivity.
        TrainAIController graphHit = graphWalkScan(range);
        if (graphHit != null) {
            // ── Vector filter: departing train ──
            // The obstacle is ahead and moving away from us faster than we travel.
            // The gap is widening → no braking needed. Ignore until speeds equalize.
            if (graphHitIsDeparting) {
                CreateRailwayMod.LOGGER.debug(
                        "[AI] Train {} sees departing train {} — skipping brake",
                        trainId.toString().substring(0, 8),
                        graphHit.trainId.toString().substring(0, 8));
                return null;
            }
            this.obstaclePosition = graphHit.currentPosition;
            this.obstacleTrainId = graphHit.trainId;
            return graphHit;
        }

        // ── FALLBACK: Signal blocks + edge identity + beam ──
        // Used ONLY when graphWalkScan had no graph data (train just spawned, no TravellingPoint yet).
        // When graphScanRan=true the BFS completed with full graph data and found nothing —
        // this means our route IS clear; skip the beam fallback to prevent parallel-track false positives.
        if (graphScanRan) return null;
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return null;

        TrainAIController closest = null;
        double closestDist = Double.MAX_VALUE;

        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId)) continue;
            if (other.currentPosition == null) continue;

            // During bypass mode, completely ignore the train we're going around
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;

            double dist = distanceBetween(this, other);
            if (dist > range) continue;

            // Ignore trains on completely different vertical levels
            int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
            if (dy > 10) continue;

            // ── Same-track check ──
            boolean sameTrack = isOnSameTrack(other);

            // If BOTH trains have TrackGraph data (signal blocks or edge), the result
            // of isOnSameTrack() is authoritative — skip the beam fallback entirely.
            // The beam fallback only runs when one of the trains has NO graph data yet.
            boolean useBeamFallback = !hasSufficientTrackData() || !other.hasSufficientTrackData();

            // Parallel track (different tracks, data is reliable) — ignore completely.
            if (!sameTrack && !useBeamFallback) continue;

            // Ignore TRAFFIC_JAM trains on a different track — not our concern.
            if (!sameTrack && other.currentState == TrainState.TRAFFIC_JAM) continue;

            // Directional filter: only react to trains AHEAD
            if (headingFromMovement) {
                double toOtherX = other.currentPosition.getX() - currentPosition.getX();
                double toOtherZ = other.currentPosition.getZ() - currentPosition.getZ();
                double dot = toOtherX * headingX + toOtherZ * headingZ;
                if (dot <= 0) continue; // train is behind or beside us

                if (!sameTrack) {
                    // Beam fallback: only when TrackGraph data is absent
                    // Width adapts: 1.1 on straight, 2.5 on curves (TrackEdge.isTurn())
                    double perpDist = Math.abs(toOtherX * headingZ - toOtherZ * headingX);
                    if (perpDist > currentBeamTolerance) continue;
                }
            } else if (!sameTrack) {
                // No heading yet and TrackGraph has no data — skip to be safe
                continue;
            }

            if (dist < closestDist) {
                closest = other;
                closestDist = dist;
            }
        }

        if (closest != null) {
            this.obstaclePosition = closest.currentPosition;
            this.obstacleTrainId = closest.trainId;
        } else {
            this.obstaclePosition = null;
            this.obstacleTrainId = null;
        }

        return closest;
    }

    /**
     * Check if another train is on the SAME track as us using Create's TrackGraph data.
     *
     * Signal blocks are used ONLY for EXCLUSION (disjoint groups = different tracks).
     * A signal block can span a junction or multiple parallel sections, so two trains
     * sharing the same signal UUID does NOT guarantee they're on the same physical rail.
     *
     * Positive same-track detection uses:
     *   a) TrackEdge identity — trains on the exact same rail segment share the edge object.
     *   b) Beam fallback (1.1 blocks in scanner) — catches trains on the same straight run
     *      that are too far apart to share an edge.
     */
    public boolean isOnSameTrack(TrainAIController other) {
        // EXCLUSION: if both have signal data and their groups are disjoint,
        // the trains are in different signal blocks → confirmed different tracks.
        if (!myOccupiedSignalGroups.isEmpty() && !other.myOccupiedSignalGroups.isEmpty()) {
            boolean anyShared = false;
            for (UUID g : myOccupiedSignalGroups) {
                if (other.myOccupiedSignalGroups.contains(g)) { anyShared = true; break; }
            }
            if (!anyShared) return false; // disjoint signal blocks = different tracks
            // Shared group but a block can span parallel tracks — don't auto-confirm.
            // Fall through to the edge check below.
        }

        // Edge identity: the most precise check — same TrackEdge object = same physical rail.
        if (myLeadingEdge != null && (myLeadingEdge == other.myLeadingEdge || myLeadingEdge == other.myTrailingEdge))
            return true;
        if (myTrailingEdge != null && (myTrailingEdge == other.myLeadingEdge || myTrailingEdge == other.myTrailingEdge))
            return true;

        // No edge match — let the 1.1-block beam fallback in the scanner decide.
        return false;
    }

    /**
     * Returns true if this train has any TrackGraph data available (signal groups or edge).
     * When both trains have data, isOnSameTrack()'s result is authoritative and the
     * beam fallback is not needed.
     */
    private boolean hasSufficientTrackData() {
        return !myOccupiedSignalGroups.isEmpty() || myLeadingEdge != null || myTrailingEdge != null;
    }

    // ─── Graph-Walk Scanner ───

    /**
     * Lazily initialize reflection handles for TrackEdge.getLength() and
     * TrackGraph.getConnectionsFrom(TrackNode). Called each tick but only
     * does work while the methods are still null and the objects are available.
     */
    private void initGraphWalkReflection() {
        if (getLengthMethod == null && myLeadingEdge != null) {
            getLengthMethod = findMethod(myLeadingEdge.getClass(), "getLength");
        }
        if (getConnectionsFromMethod == null && myGraph != null) {
            // TrackGraph.getConnectionsFrom(TrackNode) — 1-arg method
            for (Method m : myGraph.getClass().getMethods()) {
                if ("getConnectionsFrom".equals(m.getName()) && m.getParameterCount() == 1) {
                    getConnectionsFromMethod = m;
                    break;
                }
            }
        }
    }

    /**
     * Build routeNextHop from Navigation.currentPath.
     *
     * Navigation.currentPath = List<Couple<TrackNode>> (javap-confirmed, Create 6.0.1-41).
     * Each Couple(nodeA, nodeB) is one directed hop on our scheduled route.
     *
     * routeNextHop: nodeA → nodeB (IdentityHashMap, object identity).
     * In graphWalkScan BFS: when we’re at nodeA and routeNextHop has an entry,
     * only the branch leading to nodeB is followed; all other junctions are ignored.
     *
     * Does NOT call getConnectionsFrom — avoids the chicken-and-egg with initGraphWalkReflection.
     */
    @SuppressWarnings("unchecked")
    private void updateRouteNextHop() {
        routeNextHop.clear();
        if (createTrainRef == null || navigationField == null) return;

        try {
            Object nav = navigationField.get(createTrainRef);
            if (nav == null) return;

            // Lazily discover Navigation.currentPath (confirmed field name via javap)
            if (!navPathReflInit) {
                navPathReflInit = true;
                navCurrentPathField = findField(nav.getClass(), "currentPath");
                if (navCurrentPathField == null)
                    navCurrentPathField = findField(nav.getClass(), "path");
            }
            if (navCurrentPathField == null) return;

            Object pathObj = navCurrentPathField.get(nav);
            if (!(pathObj instanceof List<?> pathList) || pathList.isEmpty()) return;

            for (Object couple : pathList) {
                if (couple == null) continue;

                // --- Extract nodeA (first) and nodeB (second) from Couple<TrackNode> ---
                // Primary: Couple.getFirst() / Couple.getSecond()  (public API in catnip)
                Object nodeA = null, nodeB = null;
                Method mFirst  = findMethod(couple.getClass(), "getFirst");
                Method mSecond = findMethod(couple.getClass(), "getSecond");
                if (mFirst  != null) try { nodeA = mFirst.invoke(couple);  } catch (Exception ignored) {}
                if (mSecond != null) try { nodeB = mSecond.invoke(couple); } catch (Exception ignored) {}

                // Fallback: scan every non-static field up the class hierarchy
                if (nodeA == null || nodeB == null) {
                    List<Object> vals = new ArrayList<>(2);
                    Class<?> c = couple.getClass();
                    while (c != null && c != Object.class && vals.size() < 2) {
                        for (Field f : c.getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                            try {
                                f.setAccessible(true);
                                Object v = f.get(couple);
                                if (v != null && !vals.contains(v)) vals.add(v);
                            } catch (Exception ignored) {}
                            if (vals.size() >= 2) break;
                        }
                        c = c.getSuperclass();
                    }
                    if (vals.size() >= 2) { nodeA = vals.get(0); nodeB = vals.get(1); }
                }

                if (nodeA != null && nodeB != null) {
                    routeNextHop.put(nodeA, nodeB);
                }
            }
        } catch (Exception ignored) {
            // Reflection failure — routeNextHop stays empty (BFS runs unrestricted, safe)
        }
    }

    /** Invoke TrackEdge.getLength() via cached reflection. */
    private double getEdgeLength(Object edge) {
        if (edge == null || getLengthMethod == null) return 0;
        try {
            return (double) getLengthMethod.invoke(edge);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Invoke TrackGraph.getConnectionsFrom(node) via cached reflection. */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> invokeGetConnectionsFrom(Object node) {
        if (node == null || myGraph == null || getConnectionsFromMethod == null) return null;
        try {
            Object result = getConnectionsFromMethod.invoke(myGraph, node);
            if (result instanceof Map) return (Map<Object, Object>) result;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Graph-walk forward scanner: walk edges from our leading TravellingPoint
     * along the TrackGraph topology and check for other AI-controlled trains
     * within maxDist blocks of rail distance.
     *
     * This is immune to curve-related detection failures — it follows the actual
     * rail connectivity graph instead of casting a beam through 3D space.
     *
     * Detection approach:
     *   1. Check our own leading edge for another train ahead of us
     *   2. BFS walk from our forward node (node2) through connected edges
     *   3. For each edge, check if any other train occupies it (edge identity)
     *   4. Sum edge lengths = accurate graph distance along rails
     *
     * Returns the controller of the nearest obstacle found, or null.
     * Sets graphDistanceToObstacle (for display) and graphScanActive.
     */
    private TrainAIController graphWalkScan(double maxDist) {
        graphDistanceToObstacle = -1;
        graphScanActive = false;
        graphScanRan    = false;
        graphHitIsParkingZone = false;
        graphHitIsDeparting   = false;

        if (myGraph == null || myLeadingEdge == null || myLeadingNode2 == null) return null;

        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return null;

        initGraphWalkReflection();
        if (getLengthMethod == null || getConnectionsFromMethod == null) return null;

        graphScanRan = true; // BFS has valid data — its result is authoritative

        // Build edge → train lookup (identity-based map: same Java object = same directed edge)
        IdentityHashMap<Object, TrainAIController> edgeToTrain = new IdentityHashMap<>();
        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId)) continue;
            if (other.currentPosition == null) continue;
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;
            // Y-level filter
            if (currentPosition != null) {
                int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                if (dy > 10) continue;
            }
            if (other.myLeadingEdge != null) edgeToTrain.putIfAbsent(other.myLeadingEdge, other);
            if (other.myTrailingEdge != null) edgeToTrain.putIfAbsent(other.myTrailingEdge, other);
        }
        if (edgeToTrain.isEmpty()) return null;

        // ── Step 1: check if another train is AHEAD on our own leading edge ──
        // (Our own leading edge is always on our route — no side-track filter needed here)
        TrainAIController hit = edgeToTrain.get(myLeadingEdge);
        if (hit != null) {
            double posDiff = hit.myLeadingEdgePos - myLeadingEdgePos;
            if (posDiff > 0.5) { // ahead of us on this edge
                graphDistanceToObstacle = posDiff;
                graphScanActive = true;
                graphHitIsParkingZone = false; // sharing our current edge — not a dead-end
                // Departing train: same direction and faster → gap is widening, no braking
                graphHitIsDeparting = (hit.direction == this.direction
                        && hit.currentSpeed > this.currentSpeed + 0.05);
                return hit;
            }
        }

        // ── Step 2: BFS walk forward from node2 through connected edges ──
        double edgeLen = getEdgeLength(myLeadingEdge);
        double remainingOnCurrent = Math.max(0, edgeLen - myLeadingEdgePos);

        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(myLeadingEdge);
        // Entry format: {TrackNode node, double distanceSoFar, Object previousEdge}
        queue.add(new Object[]{myLeadingNode2, remainingOnCurrent, myLeadingEdge});

        TrainAIController closestHit = null;
        double closestDist = Double.MAX_VALUE;
        Object closestHitExitNode = null; // destination node of closest hit's edge (dead-end check)

        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            Object node = cur[0];
            double dist = (double) cur[1];
            Object fromEdge = cur[2];

            if (dist > maxDist) continue;

            Map<Object, Object> connections = invokeGetConnectionsFrom(node);
            if (connections == null) continue;

            for (Map.Entry<Object, Object> entry : connections.entrySet()) {
                Object nextNode = entry.getKey();
                Object nextEdge = entry.getValue();

                // Skip the edge we came from and already-visited edges
                if (nextEdge == fromEdge) continue;
                if (!visited.add(nextEdge)) continue;

                // ── Route-path filter (SIDE_TRACK elimination) ──
                // routeNextHop: maps each route node to its expected next node.
                // If we’re at a known route node, ONLY follow the branch toward routeNextHop.get(node).
                // Any other branch (parallel sidings, opposite loop side, depots) is invisible.
                // Degrades safely: if routeNextHop is empty, BFS walks all branches (old behavior).
                if (!routeNextHop.isEmpty()) {
                    Object expectedNext = routeNextHop.get(node);
                    if (expectedNext != null && nextNode != expectedNext) {
                        continue; // off-route branch — skip train detection AND walking
                    }
                }

                // Check if any train occupies this edge
                hit = edgeToTrain.get(nextEdge);
                if (hit != null) {
                    // Distance = accumulated rail dist + target's position on this edge
                    double totalDist = dist + hit.myLeadingEdgePos;
                    if (totalDist < closestDist && totalDist <= maxDist) {
                        closestDist = totalDist;
                        closestHit = hit;
                        closestHitExitNode = nextNode; // save for dead-end / parking check
                    }
                    continue; // don't walk past an occupied edge
                }

                // Continue walking forward
                double nextLen = getEdgeLength(nextEdge);
                if (dist + nextLen <= maxDist) {
                    queue.add(new Object[]{nextNode, dist + nextLen, nextEdge});
                }
            }
        }

        if (closestHit != null) {
            graphDistanceToObstacle = closestDist;
            graphScanActive = true;

            // ── Dead-end / Parking Zone detection ──
            // If the exit node of the hit edge has no further rail connections (or only
            // the back-edge we came from), the train is sitting at a depot / station siding.
            // We apply a soft approach profile instead of hard YIELDING/WFC braking.
            if (closestHitExitNode != null) {
                Map<Object, Object> beyond = invokeGetConnectionsFrom(closestHitExitNode);
                graphHitIsParkingZone = (beyond == null || beyond.isEmpty() || beyond.size() <= 1);
            }

            // ── Departing train detection ──
            // If the obstacle is moving in the same direction as us AND faster, the gap is
            // increasing. No braking is needed while the train is pulling away from us.
            graphHitIsDeparting = (closestHit.direction == this.direction
                    && closestHit.currentSpeed > this.currentSpeed + 0.05);
        }
        return closestHit;
    }

    /**
     * Avoidance marker check — called after reverse completes.
     *
     * Returns true if:
     *  a) The avoidance marker is still active (hasn't expired), AND
     *  b) The train that was blocking us is still in front on the same edge.
     *
     * This prevents the train from immediately charging back onto the blocked segment
     * right after it backs up.
     */
    private boolean isBlockedEdgeStillOccupied(long currentTick) {
        if (blockedEdge == null && blockedByTrainId == null) return false;
        if (currentTick > avoidanceUntilTick) {
            // Marker expired — clear it
            blockedEdge = null;
            blockedByTrainId = null;
            return false;
        }
        // Check if the blocking train is still alive and on the same edge
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return false;
        if (blockedByTrainId != null) {
            TrainAIController blocker = manager.getController(blockedByTrainId);
            if (blocker != null && blocker.currentPosition != null) {
                // If it's still on the same edge as when we had to reverse — blocked
                if (blockedEdge != null
                        && (blockedEdge == blocker.myLeadingEdge || blockedEdge == blocker.myTrailingEdge)) {
                    return true;
                }
                // Fallback: is it still close ahead of us?
                if (currentPosition != null) {
                    double dist = distanceBetween(this, blocker);
                    if (dist <= RailwayConfig.minimumStopDistance.get() * 3) {
                        return true;
                    }
                }
            }
        }
        // Blocker gone or moved away — clear marker
        blockedEdge = null;
        blockedByTrainId = null;
        return false;
    }

    // ─── Decision Handling ───

    /**
     * Handle a train detected ahead. Uses a 4-stage graduated braking curve:
     *   Stage 0 (≤ BUFFER_CRITICAL = 5 blocks): WAIT_FOR_CLEARANCE — complete lockout
     *   Stage 1 (≤ minimumStopDistance = 7 blocks): hard stop — never go closer
     *   Stage 2 (≤ 30% of range = 15 blocks):        full stop and yield
     *   Stage 3 (≤ 50% of range = 25 blocks):        crawl at 20% max speed
     *   Stage 4 (≤ 75% of range = 37 blocks):        half max speed
     *   Stage 5 (≤      range = 50 blocks):        proportional braking
     *
     * Micro-braking: instead of instantly zeroing speed, stages 1-2 reduce at
     * max 30% per tick to avoid the "nose dip" effect from abrupt stops.
     */
    private static final double BUFFER_CRITICAL  = 5.0;  // blocks — lockout zone
    private static final double BUFFER_CLEARANCE = 7.0;  // blocks — hysteresis resume threshold
    private static final double CONTEXTUAL_EARLY_BRAKE_DIST = 38.0; // blocks — early smoothStop when obstacle is stuck

    private void handleObstacleDetected(double distance, TrainAIController obstacle, long currentTick) {
        double minStop = RailwayConfig.minimumStopDistance.get();   // default 7
        double range   = RailwayConfig.obstacleDetectionRange.get(); // default 50

        // ── Parking Zone: stationary train at a dead-end (depot / station siding) ──
        // Use a gentle approach profile so the train can pass alongside at reduced speed
        // rather than triggering a full WAIT_FOR_CLEARANCE lockout.
        if (graphHitIsParkingZone && obstacle != null && obstacle.currentSpeed < 0.02) {
            if (distance <= BUFFER_CRITICAL) {
                // Still prevent collision even for parked trains
                smoothStop();
                transitionTo(TrainState.WAIT_FOR_CLEARANCE, currentTick,
                        "parking_critical_d=" + (int) distance);
                return;
            }
            if (distance <= minStop * 1.5) {
                smoothStop();
                transitionTo(TrainState.YIELDING, currentTick,
                        "parking_stop_d=" + (int) distance);
                return;
            }
            // Soft approach: scale speed proportionally with remaining distance
            double approachFactor = Math.max(0.12, (distance - minStop) / (range * 0.5));
            applySpeedControl(maxSpeed * Math.min(1.0, approachFactor));
            transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick,
                    "parking_approach_d=" + (int) distance);
            return;
        }

        // ── Contextual braking: obstacle is already stuck in traffic ──
        // Begin smooth deceleration from CONTEXTUAL_EARLY_BRAKE_DIST (38 blocks) instead
        // of the standard 15-block zone. Creates an "intelligent platoon" effect — trains
        // queue gracefully behind a jam rather than hard-stopping at the last moment.
        if (obstacle != null) {
            TrainState ts = obstacle.currentState;
            boolean obstacleStuck = ts == TrainState.WAIT_FOR_CLEARANCE
                    || ts == TrainState.YIELDING
                    || ts == TrainState.TRAFFIC_JAM;
            if (obstacleStuck && distance <= CONTEXTUAL_EARLY_BRAKE_DIST && distance > BUFFER_CRITICAL) {
                smoothStop();
                transitionTo(TrainState.YIELDING, currentTick,
                        "ctx_brake_obstacle_stuck_d=" + (int) distance);
                return;
            }
        }

        if (distance <= BUFFER_CRITICAL) {
            // ── CRITICAL BUFFER ZONE ──
            // Within 5 blocks — Create's hitbox collision is imminent.
            // Full lockout until the obstacle is >= BUFFER_CLEARANCE away (hysteresis).
            smoothStop();
            transitionTo(TrainState.WAIT_FOR_CLEARANCE, currentTick,
                    "buffer_critical_dist=" + String.format("%.1f", distance));
        } else if (distance <= minStop) {
            // Hard stop — never get closer than minimumStopDistance
            smoothStop();
            transitionTo(TrainState.YIELDING, currentTick, "hard_stop_dist=" + (int) distance);
        } else if (distance <= range * 0.3) {
            // Close approach zone — smooth stop and wait
            smoothStop();
            transitionTo(TrainState.YIELDING, currentTick, "full_stop_dist=" + (int) distance);
        } else if (distance <= range * 0.5) {
            // Inner braking zone — crawl at 20%
            applySpeedControl(this.maxSpeed * 0.2);
            transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick, "crawling_dist=" + (int) distance);
        } else if (distance <= range * 0.75) {
            // Mid braking zone — half speed
            applySpeedControl(this.maxSpeed * 0.5);
            transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick, "slowing_dist=" + (int) distance);
        } else {
            // Outer braking zone — proportional deceleration
            double brakeFactor = distance / range;
            applySpeedControl(this.maxSpeed * brakeFactor * 0.5);
            transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick, "decelerating_dist=" + (int) distance);
        }
    }

    /**
     * Handle the case when no obstacle is detected — resume normal speed.
     *
     * WAIT_FOR_CLEARANCE uses hysteresis: entered at < 5 blocks, only exits
     * when the scanner reports NO obstacle at all (meaning the forward train is
     * now beyond the full scan range or has moved to a different track).
     * The BUFFER_CLEARANCE (7 blocks) threshold is enforced in the obstacle-detected
     * path — if an obstacle IS detected but farther than 7 blocks, the standard
     * graduated braking handles it instead of re-entering WAIT_FOR_CLEARANCE.
     */
    private void handleNoObstacle(long currentTick) {
        if (currentState == TrainState.WAIT_FOR_CLEARANCE) {
            // No obstacle detected at all — safe to resume.
            junctionBlockedSinceTick = -1;
            restoreThrottle();
            transitionTo(TrainState.CRUISING, currentTick, "clearance_granted");
            totalWaitTicks = 0;
            return;
        }

        if (currentState == TrainState.YIELDING || currentState == TrainState.ANALYZING_OBSTACLE
                || currentState == TrainState.TRAFFIC_JAM) {

            // Avoidance marker: if we just reversed from a jam and the blocking train
            // is still on the same edge, don't resume — yield again immediately.
            if (isBlockedEdgeStillOccupied(currentTick)) {
                forceStop();
                // Don't log the "resuming" transition spam — stay in current state
                return;
            }

            // Path is clear, resume — restore throttle so Create's Navigation
            // can accelerate the train normally again.
            junctionBlockedSinceTick = -1;
            restoreThrottle();
            transitionTo(TrainState.CRUISING, currentTick, "path_clear_resuming");
            totalWaitTicks = 0;
        }
    }

    /** Restore Create's throttle multiplier to 1.0 so Navigation can drive the train. */
    private void restoreThrottle() {
        if (createTrainRef == null || throttleField == null) return;
        try {
            throttleField.setDouble(createTrainRef, 1.0);
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.debug("[AI] Failed to restore throttle for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    // ─── Speed Control ───

    /**
     * Force the train to stop completely by setting both speed and targetSpeed to 0.
     * This overrides Create's Navigation each tick.
     */
    private void forceStop() {
        if (createTrainRef == null) return;
        try {
            // Set throttle=0 first so Navigation.tick() computes topSpeed=0
            // and approachTargetSpeed() becomes a no-op — prevents the creep
            // that occurs when Navigation runs its tick before Phase.END.
            if (throttleField != null) {
                throttleField.setDouble(createTrainRef, 0.0);
            }
            if (speedField != null) {
                speedField.setDouble(createTrainRef, 0.0);
            }
            if (targetSpeedField != null) {
                targetSpeedField.setDouble(createTrainRef, 0.0);
            }
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.debug("[AI] Failed to force stop train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /**
     * Smooth micro-braking stop: reduces speed by at most 30% per tick instead of
     * instantly zeroing it. Once speed drops below 0.05, delegates to forceStop()
     * for a clean zero. This prevents the visual "nose-dip" lurch.
     */
    private void smoothStop() {
        if (createTrainRef == null) return;

        if (currentSpeed < 0.05) {
            forceStop();
            return;
        }

        // Reduce to 70% of current speed each tick — exponential decay
        double reduced = currentSpeed * 0.7;
        if (reduced < 0.05) {
            forceStop();
        } else {
            applySpeedControl(reduced);
            // Also zero the throttle so Navigation doesn't fight the deceleration
            try {
                if (throttleField != null) {
                    throttleField.setDouble(createTrainRef, 0.0);
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Apply a specific speed to the train (for gradual deceleration).
     * Clamps to the target so the train slows rather than accelerates.
     */
    private void applySpeedControl(double desiredSpeed) {
        if (createTrainRef == null) return;
        try {
            // Restore throttle so Navigation can compute proper speeds for
            // the gradual braking zones (we only zero throttle in forceStop).
            if (throttleField != null) {
                throttleField.setDouble(createTrainRef, 1.0);
            }
            double sign = direction ? 1.0 : -1.0;
            double clampedSpeed = Math.min(desiredSpeed, this.currentSpeed);

            if (speedField != null) {
                double currentRaw = speedField.getDouble(createTrainRef);
                double newSpeed = sign * Math.min(Math.abs(currentRaw), clampedSpeed);
                speedField.setDouble(createTrainRef, newSpeed);
            }
            if (targetSpeedField != null) {
                targetSpeedField.setDouble(createTrainRef, sign * clampedSpeed);
            }
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.debug("[AI] Failed to set speed for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    // ─── Reverse Maneuver ───

    /**
     * Apply reverse (negative) speed to the train via reflection.
     */
    private void applyReverseSpeed() {
        if (createTrainRef == null) return;
        try {
            // Restore throttle so Create's physics uses our negative speed target.
            if (throttleField != null) {
                throttleField.setDouble(createTrainRef, 1.0);
            }
            double reverseSpd = RailwayConfig.reverseSpeed.get();
            if (speedField != null) {
                speedField.setDouble(createTrainRef, -reverseSpd);
            }
            if (targetSpeedField != null) {
                targetSpeedField.setDouble(createTrainRef, -reverseSpd);
            }
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.debug("[AI] Failed to apply reverse speed for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /**
     * Smart Junction Re-routing.
     *
     * Called every tick while the train is stuck in YIELDING or WAIT_FOR_CLEARANCE.
     * Two-phase approach:
     *
     *   Phase 1 — Deadlock detection (0 → REROUTE_WAIT_TICKS ticks):
     *     Just wait. The obstacle may move on its own.
     *
     *   Phase 2 — Reroute attempt (> REROUTE_WAIT_TICKS ticks, cooldown respected):
     *     a) Look at forward connections from our leading node (myLeadingNode2).
     *     b) Classify each edge as BLOCKED (another train is on it) or FREE.
     *     c) If at least one FREE edge exists alongside a BLOCKED one  → junction
     *        is partially available → cancel navigation so Create re-pathfinds.
     *        Create's BFS will prefer the free edge because occupied edges carry
     *        a higher cost via Train.Penalties (and our own graph-walk avoidance).
     *     d) If ALL forward edges are blocked (full deadlock) or there's only one
     *        edge (no junction) → skip; the existing YIELDING → REVERSING escalation
     *        will handle it eventually.
     *
     * Hysteresis: REROUTE_COOLDOWN_TICKS (200 ticks / 10 s) between reroute attempts
     * prevents the train from thrashing back and forth at a switch.
     */
    private void trySmartJunctionReroute(long currentTick) {
        // Phase 1: wait REROUTE_WAIT_TICKS before attempting anything
        if (junctionBlockedSinceTick < 0) {
            junctionBlockedSinceTick = currentTick;
            return;
        }
        long waitedTicks = currentTick - junctionBlockedSinceTick;
        if (waitedTicks < REROUTE_WAIT_TICKS) return;

        // Hysteresis: respect cooldown between reroute attempts
        if (lastRerouteTick >= 0 && (currentTick - lastRerouteTick) < REROUTE_COOLDOWN_TICKS) return;

        // Need graph data to inspect junction topology
        if (myGraph == null || myLeadingNode2 == null) {
            // No graph data yet — fall back to blind navigation cancel after long wait
            if (waitedTicks >= REROUTE_WAIT_TICKS * 2) {
                lastRerouteTick = currentTick;
                cancelCreateNavigation();
                CreateRailwayMod.LOGGER.info("[AI] Train {} no graph data, blind reroute after {}t",
                        trainId.toString().substring(0, 8), waitedTicks);
            }
            return;
        }

        initGraphWalkReflection();
        if (getConnectionsFromMethod == null) return;

        // Build set of edges occupied by OTHER trains (identity-based)
        Set<Object> occupiedEdges = Collections.newSetFromMap(new IdentityHashMap<>());
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager != null) {
            for (TrainAIController other : manager.getAllControllers()) {
                if (other.trainId.equals(this.trainId)) continue;
                if (other.myLeadingEdge  != null) occupiedEdges.add(other.myLeadingEdge);
                if (other.myTrailingEdge != null) occupiedEdges.add(other.myTrailingEdge);
            }
        }

        // Get all forward edges from our leading node
        Map<Object, Object> connections = invokeGetConnectionsFrom(myLeadingNode2);
        if (connections == null || connections.isEmpty()) return;

        // Skip the edge we're currently on (myLeadingEdge) to avoid counting backwards
        int freeCount   = 0;
        int blockedCount = 0;
        for (Map.Entry<Object, Object> entry : connections.entrySet()) {
            Object edge = entry.getValue();
            if (edge == myLeadingEdge) continue; // skip reverse direction
            if (occupiedEdges.contains(edge)) {
                blockedCount++;
            } else {
                freeCount++;
            }
        }

        // Only reroute when there genuinely IS a junction (≥2 forward edges) and at
        // least one is free. If everything is blocked, rerouting just creates churn.
        boolean hasJunction = (freeCount + blockedCount) >= 2;
        boolean hasFreeAlternative = freeCount > 0 && blockedCount > 0;

        if (hasJunction && hasFreeAlternative) {
            lastRerouteTick = currentTick;
            junctionBlockedSinceTick = currentTick; // reset wait timer
            cancelCreateNavigation();
            CreateRailwayMod.LOGGER.info(
                    "[AI] Train {} smart-junction reroute: {}f/{}b fwd-edges, waited {}t",
                    trainId.toString().substring(0, 8), freeCount, blockedCount, waitedTicks);
        } else if (!hasJunction && waitedTicks >= REROUTE_WAIT_TICKS * 3) {
            // Single-track section, no junction — try a plain reroute anyway after 15 s
            // in case navigation is stuck in a bad state
            lastRerouteTick = currentTick;
            cancelCreateNavigation();
            CreateRailwayMod.LOGGER.info(
                    "[AI] Train {} single-track reroute after {}t (no junction detected)",
                    trainId.toString().substring(0, 8), waitedTicks);
        }
    }

    /**
     * Cancel Create Mod's current navigation so it recalculates the route.
     *
     * From Create source (Navigation.java):
     *   cancelNavigation() clears the path and calls runtime.transitInterrupted()
     *   On the next tick, ScheduleRuntime.tick() calls startCurrentInstruction()
     *   which re-pathfinds using Navigation.findPathTo()
     *   Create's penalty system (Train.Penalties) adds cost for occupied tracks,
     *   so the new route will naturally avoid the stuck train's track.
     */
    private void cancelCreateNavigation() {
        if (createTrainRef == null || navigationField == null) return;
        try {
            Object nav = navigationField.get(createTrainRef);
            if (nav == null) return;

            // Check if navigation has a destination before cancelling
            Field destField = findField(nav.getClass(), "destination");
            if (destField != null) {
                Object dest = destField.get(nav);
                if (dest == null) return; // not navigating — nothing to cancel
            }

            Method cancelMethod = findMethod(nav.getClass(), "cancelNavigation");
            if (cancelMethod != null) {
                cancelMethod.invoke(nav);
                CreateRailwayMod.LOGGER.info("[AI] Train {} navigation cancelled for reroute",
                        trainId.toString().substring(0, 8));
            }
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.warn("[AI] Failed to cancel navigation for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /**
     * Clear bypass-ignore when the bypassed train is far enough away that
     * our scanner wouldn't detect it anyway. Uses pure distance check —
     * NOT heading-based, because heading can be reversed/wrong during and
     * right after the reverse maneuver.
     */
    private void clearBypassIfPassed() {
        if (bypassingTrainId == null || currentPosition == null) return;

        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return;
        TrainAIController bypassed = manager.getController(bypassingTrainId);
        if (bypassed == null || bypassed.currentPosition == null) {
            CreateRailwayMod.LOGGER.info("[AI] Train {} bypassed train disappeared, clearing bypass",
                    trainId.toString().substring(0, 8));
            bypassingTrainId = null;
            return;
        }

        double dist = distanceBetween(this, bypassed);
        double range = RailwayConfig.obstacleDetectionRange.get();

        // Only clear when the bypassed train is well outside scanner range
        if (dist > range + 15) {
            CreateRailwayMod.LOGGER.info("[AI] Train {} far from bypassed train {} ({}b), clearing bypass",
                    trainId.toString().substring(0, 8),
                    bypassingTrainId.toString().substring(0, 8), (int) dist);
            bypassingTrainId = null;
        }
    }

    /**
     * Scan behind the train (opposite of stored heading) for other trains.
     */
    private TrainAIController scanBehindForObstacle() {
        if (currentPosition == null) return null;
        if (headingX == 0 && headingZ == 0) return null; // no heading yet — assume clear

        double range = RailwayConfig.obstacleDetectionRange.get();
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return null;

        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId)) continue;
            if (other.currentPosition == null) continue;

            // During bypass mode, ignore the train we're going around
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;

            double dist = distanceBetween(this, other);
            if (dist > range) continue;

            int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
            if (dy > 10) continue;

            // Same-track check: TrackGraph primary, beam only when data is absent
            boolean sameTrack = isOnSameTrack(other);
            boolean useBeamFallback = !hasSufficientTrackData() || !other.hasSufficientTrackData();
            if (!sameTrack && !useBeamFallback) continue; // authoritative: different tracks
            if (!sameTrack && other.currentState == TrainState.TRAFFIC_JAM) continue;

            double toOtherX = other.currentPosition.getX() - currentPosition.getX();
            double toOtherZ = other.currentPosition.getZ() - currentPosition.getZ();
            double dot = toOtherX * headingX + toOtherZ * headingZ;
            // dot < -0.3: train is more than ~107° behind our heading
            if (dot >= -0.3) continue;

            if (!sameTrack) {
                // Beam fallback only when one train has no TrackGraph data
                // Width adapts: 1.1 on straight, 2.5 on curves (TrackEdge.isTurn())
                double perpDist = Math.abs(toOtherX * headingZ - toOtherZ * headingX);
                if (perpDist > currentBeamTolerance) continue;
            }

            return other;
        }
        return null;
    }

    /**
     * Handle the REVERSING state — the train is backing up to escape a deadlock.
     *
     * Continues reversing until:
     *   a) Backed up >= reverseBackupDistance blocks → CRUISING (nav reroutes)
     *   b) Another train detected in the reverse path (after initial movement) → YIELDING
     *
     * The "backedUp > 2.0" guard prevents a false positive on the first few ticks
     * before the heading vector has shifted to the new reverse direction.
     */
    private void handleReversing(long currentTick) {
        double backedUp = 0;
        if (reversingStartPosition != null && currentPosition != null) {
            backedUp = distanceBetweenPos(currentPosition, reversingStartPosition);
        }

        // Check if we have backed up far enough — hand control back to navigation
        if (backedUp >= RailwayConfig.reverseBackupDistance.get()) {
            CreateRailwayMod.LOGGER.info("[AI] Train {} reverse complete ({}b), handing to navigation",
                    trainId.toString().substring(0, 8), (int) backedUp);
            // Reset bypass timer so the train ignores the stuck train while on the bypass route
            if (bypassingTrainId != null) {
                bypassModeUntilTick = currentTick + BYPASS_MODE_TICKS;
            }
            cancelCreateNavigation();

            // Distance check: if the obstacle that caused the reversal is STILL on the
            // same blocked edge in front of us, don't go forward — yield immediately.
            // This prevents the "reverse → crash → reverse → crash" loop.
            if (isBlockedEdgeStillOccupied(currentTick)) {
                CreateRailwayMod.LOGGER.info("[AI] Train {} blocked edge still occupied after reverse, yielding",
                        trainId.toString().substring(0, 8));
                forceStop();
                totalWaitTicks = 0;
                transitionTo(TrainState.YIELDING, currentTick, "post_reverse_blocked_edge");
                return;
            }

            transitionTo(TrainState.CRUISING, currentTick,
                    "reverse_complete_rerouting_dist=" + (int) backedUp);
            return;
        }

        // After initial movement, check if a DIFFERENT train is blocking the reverse path
        // (ignore the bypassed train — it's the one we're escaping from)
        if (backedUp > 2.0 && obstacleTrainId != null
                && (bypassingTrainId == null || !obstacleTrainId.equals(bypassingTrainId))) {
            forceStop();
            transitionTo(TrainState.YIELDING, currentTick, "blocked_in_reverse_path");
            return;
        }

        // Continue reversing
        applyReverseSpeed();
    }

    /**
     * Proactive VBS reservation — every tick stamp a 15-block "virtual footprint"
     * ahead of the train so other trains can see it via segment conflict checks.
     */
    private void proactiveReserve(long currentTick) {
        if (currentPosition == null) return;
        if (headingX == 0 && headingZ == 0) return;
        // Reserve segment from current pos to 15 blocks ahead
        BlockPos aheadPos = new BlockPos(
                (int)(currentPosition.getX() + headingX * 15),
                currentPosition.getY(),
                (int)(currentPosition.getZ() + headingZ * 15));
        VirtualBlockSystem.getInstance().tryReserve(
                trainId, currentPosition, aheadPos, direction, currentTick);
    }

    // ─── State Machine ───

    private void transitionTo(TrainState newState, long currentTick, String reason) {
        if (newState == currentState) return;
        this.previousState = this.currentState;
        this.currentState = newState;
        this.stateEnteredTick = currentTick;

        // When starting bypass or reverse, shrink the scan beam for 5 seconds
        if ((newState == TrainState.BYPASSING || newState == TrainState.REVERSING) && obstacleTrainId != null) {
            bypassingTrainId = obstacleTrainId;
            bypassModeUntilTick = currentTick + BYPASS_MODE_TICKS;
        }

        CreateRailwayMod.LOGGER.info("[AI] Train {} state: {} -> {} ({})",
                trainId.toString().substring(0, 8),
                previousState, currentState, reason);

        // Broadcast state changes to nearby players in chat
        if (currentPosition != null && lastKnownLevel != null) {
            String msg = String.format("§6[AI] §eTrain %s §f%s→%s §7(%s)",
                    trainId.toString().substring(0, 8),
                    previousState, currentState, reason);
            broadcastToNearbyPlayers(lastKnownLevel, msg, 100);
        }
    }

    // ─── Conflict participation ───

    public ConflictResolver.TrainConflictContext buildConflictContext() {
        return new ConflictResolver.TrainConflictContext(
                trainId, currentState, !onOppositeTrack,
                currentSpeed, maxSpeed, distToDestination,
                carriageCount, totalWaitTicks, isEmergency);
    }

    // ─── Reflection helpers ───

    private static Field findField(Class<?> cls, String name) {
        // Check public fields first
        try {
            return cls.getField(name);
        } catch (NoSuchFieldException ignored) {}

        // Walk the class hierarchy checking declared fields
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        try {
            return cls.getMethod(name, paramTypes);
        } catch (NoSuchMethodException ignored) {}

        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try {
                Method m = current.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    // ─── Utility ───

    private static double distanceBetween(TrainAIController a, TrainAIController b) {
        if (a.currentPosition == null || b.currentPosition == null) return Double.MAX_VALUE;
        double dx = a.currentPosition.getX() - b.currentPosition.getX();
        double dy = a.currentPosition.getY() - b.currentPosition.getY();
        double dz = a.currentPosition.getZ() - b.currentPosition.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double distanceBetweenPos(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Send a chat message only to players wearing AI Goggles within range. */
    private void broadcastToNearbyPlayers(ServerLevel level, String message, double range) {
        if (currentPosition == null || level == null) return;
        double rangeSq = range * range;
        Component comp = Component.literal(message);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(currentPosition) <= rangeSq) {
                net.minecraft.world.item.ItemStack helmet = player.getInventory().armor.get(3);
                if (!helmet.isEmpty() && helmet.getItem() == ModItems.AI_GOGGLES.get()) {
                    player.sendSystemMessage(comp);
                }
            }
        }
    }

    // ─── Getters ───

    public UUID getTrainId() { return trainId; }
    public TrainState getCurrentState() { return currentState; }
    public BlockPos getCurrentPosition() { return currentPosition; }
    public Vec3 getPrecisePosition() { return precisePosition; }
    public double getCurrentSpeed() { return currentSpeed; }
    public double getHeadingX() { return headingX; }
    public double getHeadingZ() { return headingZ; }
    public boolean isOnOppositeTrack() { return onOppositeTrack; }
    public boolean isEmergency() { return isEmergency; }
    public boolean isPlayerControlled() { return playerControlled; }
    public void setEmergency(boolean emergency) { this.isEmergency = emergency; }
    public void setPosition(BlockPos pos) { this.currentPosition = pos; }

    // ─── TrackGraph Getters (for debug visualizer and external tools) ───
    /** Signal block group UUIDs this train currently occupies (from Create's occupiedSignalBlocks). */
    public Set<UUID> getSignalGroups() { return Collections.unmodifiableSet(myOccupiedSignalGroups); }
    /** TravellingPoint.edge of the leading carriage (object identity). Null if not available. */
    public Object getLeadingEdge() { return myLeadingEdge; }
    /** TravellingPoint.edge of the trailing carriage (object identity). Null if not available. */
    public Object getTrailingEdge() { return myTrailingEdge; }
    /** The TrackGraph this train belongs to. Null if not available. */
    public Object getTrackGraph() { return myGraph; }
    /** Current beam lateral tolerance — 1.1 on straight, 2.5 on curve. */
    public double getCurrentBeamTolerance() { return currentBeamTolerance; }
    /** Whether the leading edge is a curve (TrackEdge.isTurn()). */
    public boolean isOnCurvedEdge() { return currentBeamTolerance >= BEAM_LATERAL_TOLERANCE_CURVE; }
    /** Graph distance (blocks along rail) to the last detected obstacle. -1 if unavailable. */
    public double getGraphDistanceToObstacle() { return graphDistanceToObstacle; }
    /** Whether the last obstacle detection was done via graph walk (true) or beam fallback (false). */
    public boolean isGraphScanActive() { return graphScanActive; }
    /** Leading edge hashCode for display — stable identifier for the current rail segment. */
    public int getLeadingEdgeHash() { return myLeadingEdge != null ? System.identityHashCode(myLeadingEdge) : 0; }

    @Override
    public String toString() {
        return String.format("TrainAI[%s, state=%s, speed=%.2f, pos=%s]",
                trainId.toString().substring(0, 8), currentState, currentSpeed,
                currentPosition != null ? currentPosition.toShortString() : "null");
    }
}
