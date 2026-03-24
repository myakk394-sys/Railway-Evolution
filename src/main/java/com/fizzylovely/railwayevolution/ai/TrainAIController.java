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
 * 
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 *
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
    @SuppressWarnings("unused")
    private boolean derailed;

    // Bypass tracking
    @SuppressWarnings("unused")
    private boolean onOppositeTrack;
    @SuppressWarnings("unused")
    private BlockPos obstaclePosition;
    private UUID obstacleTrainId;

    // Direction tracking (for the directional forward scan "invisible stick")
    private BlockPos previousPosition;
    private Vec3 previousPrecisePosition;
    double headingX;  // normalized unit vector (package-visible for visualizer)
    double headingZ;
    private boolean headingFromMovement; // true if heading was computed from actual movement

    // Bypass mode — when bypassing/reversing, completely ignore the train we're
    // going around so the beam doesn't re-detect it on the parallel track
    private UUID bypassingTrainId;
    private long bypassModeUntilTick;
    private static final int BYPASS_MODE_TICKS = 300; // 15 seconds of ignore mode

    // WFC safety: when entering WAIT_FOR_CLEARANCE, lock the triggering train's UUID.
    // Used to verify the obstacle is still physically far enough away before granting
    // clearance — prevents false clearance_granted during curve edge transitions where
    // graphWalkScan temporarily returns null because myLeadingEdge is null.
    private UUID wfcObstacleTrainId = null;
    private static final double BEAM_LATERAL_TOLERANCE      = 1.1; // straight track beam
    private static final double BEAM_LATERAL_TOLERANCE_CURVE = 1.0; // curve / turn beam — kept tight
    // (parallel Create tracks are always ≥ 2 blocks apart; values above those thresholds
    //  cause false positives on adjacent curved sections of a loop)

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

    // ── Control-panel flags (toggled via F10 GUI) ──
    private boolean chatSilenced  = false; // suppress in-chat state messages
    private boolean aiEnabled     = true;  // false = skip all AI logic, restore throttle
    private long    collisionAtTick = -1;  // last tick we entered buffer_critical zone
    private String  trainDisplayName = ""; // read from Create's Train.name if available

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
    // Last-known edge/node data — persists when Create clears TravellingPoint on stopped trains.
    // isOnSameTrack() uses these as fallback so stopped trains remain detectable to scanners.
    private Object myLastLeadingEdge;
    private Object myLastLeadingNode1;
    private Object myLastLeadingNode2;
    private double myLeadingEdgePos;   // TravellingPoint.position on leading edge
    private double graphDistanceToObstacle = -1; // graph distance (blocks), -1 = n/a
    private boolean graphScanActive;   // true if last detection was via graph walk

    // Cached reflection for graph-walk scanner (lazily initialized)
    private Field tpNode1Field;
    private Field tpNode2Field;
    private Field tpPositionField;
    private Method getLengthMethod;    // TrackEdge.getLength()
    private Method getConnectionsFromMethod; // TrackGraph.getConnectionsFrom(TrackNode)
    private Method nodeGetLocationMethod;    // TrackNode.getLocation() → TrackNodeLocation (extends BlockPos)

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
    private boolean graphHitIsParkingZone  = false; // hit train is at a dead-end (depot/station)
    private boolean graphHitIsDeparting    = false; // hit train is ahead, same direction, faster
    private boolean graphHitIsHeadOn       = false; // hit train is head-on (oncoming via reverseEdgeMap or converging at junction)
    // Set when BFS found a junction where our primary route is blocked BUT an alternative
    // branch edge is FREE. scanForObstacleController will trigger an immediate navigation
    // cancel so Create re-pathfinds via the free branch — proactive detour.
    private boolean graphFoundFreeDetour   = false;
    // True when this train is heading AGAINST the edge's natural (node1→node2) direction.
    // Used as the primary right-of-way decider: wrong-way train yields to the rightful train.
    private boolean isWrongWay = false;
    // Set when BFS found a junction ahead (≥3 connections) whose exit side has insufficient
    // free space for this train's full length. Train stops before entering to avoid gridlock.
    private boolean graphJunctionNotEnoughSpace = false;
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
        if (playerControlled || !aiEnabled) {
            // Make sure we don't leave throttle zeroed from a previous AI stop
            restoreThrottle();
            if (currentState != TrainState.CRUISING) {
                transitionTo(TrainState.CRUISING, currentTick, playerControlled ? "player_control" : "ai_disabled");
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
        // Topology failsafe: if another train's head node is our tail node,
        // they're directly adjacent behind us on the track. This catches the case
        // where the heading-dot filter still misses a train at an extreme curve angle.
        //
        // JUNCTION GUARD: At junctions multiple branches share the same node object.
        // A train on a DIFFERENT branch entering the junction will also have
        // myLeadingNode2 == our myLeadingNode1, causing a false "train behind" hit.
        // Only trigger if the other train is also on the SAME track as us
        // (confirmed by isOnSameTrack), OR if the dot check confirms it is behind
        // (toOther · heading < 0 = other is in our backward direction).
        if (spaceAvailableBehind && myLeadingNode1 != null) {
            TrainAIManager nodeMgr = TrainAIManager.getInstance();
            if (nodeMgr != null) {
                for (TrainAIController other : nodeMgr.getAllControllers()) {
                    if (other.trainId.equals(this.trainId)) continue;
                    if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;
                    // Same "yielding-to-us" exception as in scanBehindForObstacle:
                    // a stopped train whose blocker is us will move the instant we reverse.
                    if ((other.currentState == TrainState.YIELDING
                            || other.currentState == TrainState.WAIT_FOR_CLEARANCE
                            || other.currentState == TrainState.TRAFFIC_JAM)
                            && trainId.equals(other.obstacleTrainId)) continue;
                    if (other.myLeadingNode2 != myLeadingNode1) continue;
                    // Junction convergence guard:
                    // At a Y-merge, a branch train (Train D) has node2 = JUNCTION_NODE.
                    // Our train (B, just-past-junction) has node1 = JUNCTION_NODE.
                    // This causes a false match: D appears to be "behind" B.
                    // Guard: if our edge and their edge are DIFFERENT, and the shared node
                    // is a junction (multiple branches possible), use a strict dot threshold
                    // (-0.5 instead of 0.0) so perpendicular branch trains don't count.
                    boolean differentEdge = myLeadingEdge != null && other.myLeadingEdge != null
                            && myLeadingEdge != other.myLeadingEdge
                            && myTrailingEdge != other.myLeadingEdge; // not directly connected behind us
                    boolean actuallyBehind = !differentEdge && isOnSameTrack(other);
                    if (!actuallyBehind && other.currentPosition != null && currentPosition != null) {
                        double tox = other.currentPosition.getX() - currentPosition.getX();
                        double toz = other.currentPosition.getZ() - currentPosition.getZ();
                        double dot = tox * headingX + toz * headingZ;
                        // Use strict threshold for different-edge (convergence) case to avoid
                        // branch trains at 30-90° angles being counted as "behind".
                        double threshold = differentEdge ? -0.5 : -0.1;
                        actuallyBehind = (dot < threshold);
                    }
                    if (actuallyBehind) {
                        spaceAvailableBehind = false;
                        break;
                    }
                }
            }
        }

        // Distance to forward obstacle (needed for sandwiched check)
        double forwardDist = Double.MAX_VALUE;
        if (obstacleDetected) {
            if (graphScanActive && graphDistanceToObstacle > 0) {
                forwardDist = graphDistanceToObstacle;
            } else {
                forwardDist = distanceBetween(this, obstacleController);
            }
        }

        // SANDWICHED = train close ahead (<= 15 blocks) AND train behind.
        // If the forward obstacle is far (>15 blocks), the train can still approach
        // it normally with graduated braking — no need for full JAM lockout.
        // This prevents false JAM when a distant train on the same route is detected
        // while a close train is behind us.
        //
        // Exception: if the obstacle ahead is already REVERSING (actively moving away),
        // don't enter JAM — it will clear in a moment. Entering JAM here would lock
        // the waiting train forever even though the path is about to open.
        boolean obstacleAlreadyReversing = obstacleController != null
                && obstacleController.currentState == TrainState.REVERSING;
        boolean sandwiched = obstacleDetected && !spaceAvailableBehind && forwardDist <= 15.0
                && !obstacleAlreadyReversing;

        // Step 4: State-specific decision
        if (currentState == TrainState.REVERSING) {
            // Dedicated reverse handler — uses its own heading-based scan for reverse-path
            // safety (graphWalkScan uses schedule-forward edge, wrong during reversal).
            handleReversing(currentTick);
        } else if (!obstacleDetected && graphJunctionNotEnoughSpace
                && currentState != TrainState.WAIT_FOR_CLEARANCE) {
            // ── Junction look-ahead block ──
            // BFS found a real junction ahead with insufficient exit space (occupied or too short
            // for our full train length). Stop before entering to avoid parking inside the junction
            // and causing a gridlock. Behaves like YIELDING: we wait for the exit to clear.
            smoothStop();
            transitionTo(TrainState.YIELDING, currentTick, "junction_exit_blocked");
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
            totalWaitTicks++;
            forceStop();
            long maxYield = RailwayConfig.maxYieldTicks.get();

            // ── Fast priority escalation (120 ticks = 6 s) ──
            // When two jammed trains share the same obstacle (converging junction, head-on
            // via different branches), only the SENIOR train (higher hashCode) tries a
            // smart reroute. The junior waits. Prevents both from spinning trySmartReroute
            // simultaneously and wasting the junction slot.
            if (totalWaitTicks > 120 && obstacleTrainId != null) {
                TrainAIManager mgrJ = TrainAIManager.getInstance();
                TrainAIController oppJ = mgrJ != null ? mgrJ.getController(obstacleTrainId) : null;
                if (oppJ != null && (oppJ.getCurrentState() == TrainState.TRAFFIC_JAM
                        || oppJ.getCurrentState() == TrainState.YIELDING
                        || oppJ.getCurrentState() == TrainState.WAIT_FOR_CLEARANCE)) {
                    int myHash  = trainId.hashCode();
                    int oppHash = obstacleTrainId.hashCode();
                    boolean isSenior = (myHash != oppHash)
                            ? myHash > oppHash
                            : trainId.compareTo(obstacleTrainId) > 0;
                    if (isSenior) {
                        trySmartJunctionReroute(currentTick);
                    }
                }
            }

            if (totalWaitTicks > maxYield) {
                // JAM escalation — after maxYieldTicks the jam is not self-clearing.
                // If space behind has opened, de-escalate to YIELDING so this train
                // can then escalate to REVERSING and break the chain.
                if (spaceAvailableBehind && RailwayConfig.reverseManeuverEnabled.get()) {
                    CreateRailwayMod.LOGGER.info("[AI] Train {} JAM escalating to YIELDING (space behind clear after {}t)",
                            trainId.toString().substring(0, 8), totalWaitTicks);
                    totalWaitTicks = 0;
                    transitionTo(TrainState.YIELDING, currentTick, "jam_deescalate_space_behind");
                } else if (totalWaitTicks > maxYield * 2 && RailwayConfig.reverseManeuverEnabled.get()) {
                    // Forced de-escalation: both sides still blocked after 2× maxYield.
                    // Force into YIELDING regardless of spaceAvailableBehind — the
                    // YIELDING priority logic will decide who reverses. Without this,
                    // a two-train converging JAM at a junction waits forever because
                    // neither train ever gets spaceAvailableBehind=true.
                    CreateRailwayMod.LOGGER.info("[AI] Train {} JAM FORCE de-escalate ({}t > maxYield*2)",
                            trainId.toString().substring(0, 8), totalWaitTicks);
                    totalWaitTicks = 0;
                    transitionTo(TrainState.YIELDING, currentTick, "jam_force_deescalate");
                } else {
                    // Still blocked both sides — try smart reroute
                    trySmartJunctionReroute(currentTick);
                }
            }
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

            // ── Head-on fast resolution (30 ticks = 1.5 sec) ──
            // When the BFS scanner confirmed a genuine head-on encounter (graphHitIsHeadOn),
            // resolve it 4× faster than the generic 120-tick path.
            // Deterministic tie-break: higher hashCode = “senior” (waits); lower = “junior” (reverses).
            // Falls through to 120-tick block for non-head-on mutual jams.
            boolean dirResolved = false;
            if (totalWaitTicks > 30 && reverseEnabled && obstacleTrainId != null && graphHitIsHeadOn) {
                TrainAIManager mgrDir = TrainAIManager.getInstance();
                TrainAIController oppDir = mgrDir != null ? mgrDir.getController(obstacleTrainId) : null;
                if (oppDir != null
                        && (oppDir.getCurrentState() == TrainState.YIELDING
                                || oppDir.getCurrentState() == TrainState.WAIT_FOR_CLEARANCE
                                || oppDir.getCurrentState() == TrainState.TRAFFIC_JAM)) {
                    int myHash  = trainId.hashCode();
                    int oppHash = obstacleTrainId.hashCode();
                    boolean isSenior = (myHash != oppHash)
                            ? myHash > oppHash
                            : trainId.compareTo(obstacleTrainId) > 0;
                    dirResolved = true;
                    if (isSenior) {
                        forceStop(); // senior: hold position, junior will reverse
                    } else if (spaceAvailableBehind) {
                        // Junior: reverse to clear the head-on
                        CreateRailwayMod.LOGGER.info(
                                "[AI] Train {} head-on reverse (junior vs {}, wait={}t)",
                                trainId.toString().substring(0, 8),
                                obstacleTrainId.toString().substring(0, 8), totalWaitTicks);
                        reversingStartPosition = currentPosition;
                        reversingStartTick = currentTick;
                        blockedEdge = myLeadingEdge;
                        blockedByTrainId = obstacleTrainId;
                        avoidanceUntilTick = currentTick + AVOIDANCE_TICKS;
                        transitionTo(TrainState.REVERSING, currentTick, "head_on_reverse");
                        obstaclePosition = null;
                        obstacleTrainId = null;
                        totalWaitTicks = 0;
                        dirResolved = false; // state changed — don’t suppress follow-on blocks
                    } else {
                        forceStop(); // junior but no space yet — wait
                    }
                }
            }

            // ── Fast deadlock resolution (120 ticks = 6 sec) ──
            // When two trains are mutually stuck at a curve/junction (both YIELDING or WFC),
            // use UUID hashCode as a deterministic priority:
            //   Higher hashCode → "senior"  → stays stopped and waits
            //   Lower hashCode  → "junior"  → reverses to clear the junction
            // Tie-break on hashCode equality: use UUID compareTo (always distinct).
            // This fires 2.5× faster than maxYieldTicks (300), resolving most deadlocks
            // in 6 seconds instead of 15.
            if (!dirResolved && totalWaitTicks > 120 && reverseEnabled && obstacleTrainId != null) {
                TrainAIManager mgrFast = TrainAIManager.getInstance();
                TrainAIController opp = mgrFast != null ? mgrFast.getController(obstacleTrainId) : null;
                if (opp != null && (opp.getCurrentState() == TrainState.YIELDING
                        || opp.getCurrentState() == TrainState.WAIT_FOR_CLEARANCE
                        || opp.getCurrentState() == TrainState.TRAFFIC_JAM)) {
                    int myHash  = trainId.hashCode();
                    int oppHash = obstacleTrainId.hashCode();
                    // Determine who is "senior" (waits) and who is "junior" (reverses)
                    boolean isSenior = (myHash != oppHash)
                            ? myHash > oppHash
                            : trainId.compareTo(obstacleTrainId) > 0;
                    if (isSenior) {
                        // Senior — stay stopped, let the junior clear first
                        forceStop();
                    } else if (spaceAvailableBehind) {
                        // Junior — reverse to unblock the junction
                        CreateRailwayMod.LOGGER.info(
                                "[AI] Train {} priority-reverse (junior vs {}, wait={}t)",
                                trainId.toString().substring(0, 8),
                                obstacleTrainId.toString().substring(0, 8), totalWaitTicks);
                        reversingStartPosition = currentPosition;
                        reversingStartTick = currentTick;
                        blockedEdge = myLeadingEdge;
                        blockedByTrainId = obstacleTrainId;
                        avoidanceUntilTick = currentTick + AVOIDANCE_TICKS;
                        transitionTo(TrainState.REVERSING, currentTick, "priority_junction_reverse");
                        obstaclePosition = null;
                        obstacleTrainId = null;
                        totalWaitTicks = 0;
                    }
                    // (junior with no space behind: fall through to maxYield path below)
                }
            }

            // ── Standard reverse escalation (maxYieldTicks, default 300) ──
            // Only reverse if this is the LAST train (nothing behind)
            if (!dirResolved && totalWaitTicks > maxYield && reverseEnabled && spaceAvailableBehind) {
                // Head-on deadlock tie-breaker: when two trains face each other and BOTH
                // want to reverse simultaneously, only the lower-UUID train actually reverses.
                // The higher-UUID train defers (resets partial wait), giving the lower-UUID
                // train time to clear. Without this, both reverse equal distances, return,
                // and re-collide forever.
                if (graphHitIsHeadOn && obstacleTrainId != null) {
                    TrainAIManager mgrTB = TrainAIManager.getInstance();
                    TrainAIController opp = mgrTB != null ? mgrTB.getController(obstacleTrainId) : null;
                    boolean oppStillConflicting = opp != null && (
                            opp.getCurrentState() == TrainState.YIELDING
                            || opp.getCurrentState() == TrainState.WAIT_FOR_CLEARANCE
                            || opp.getCurrentState() == TrainState.REVERSING);
                    if (oppStillConflicting && trainId.compareTo(obstacleTrainId) > 0) {
                        // We are the "higher UUID" — defer and let the other train reverse first
                        totalWaitTicks = maxYield / 2;
                        return;
                    }
                }
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

        // Step 6: Soft-resume ramp — runs after ALL state handling to override any
        // throttle=1.0 that Create might use to re-accelerate between ticks.
        applyResumeRamp(currentTick);
    }

    // ─── Data reading from Create Mod ───

    private void updateTrainData(ServerLevel level) {
        if (createTrainRef == null || !reflectionInitialized) return;

        // Save previous position for directional scanner heading computation
        if (this.currentPosition != null) {
            this.previousPosition = this.currentPosition;
        }
        if (this.precisePosition != null) {
            this.previousPrecisePosition = this.precisePosition;
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

            // Read train display name (lazily, since reflection is expensive)
            if (trainDisplayName.isEmpty()) {
                try {
                    Field nameField = findField(createTrainRef.getClass(), "name");
                    if (nameField != null) {
                        Object nameObj = nameField.get(createTrainRef);
                        if (nameObj != null) {
                            // Create stores train name as MutableComponent or plain String
                            trainDisplayName = nameObj.toString();
                            if (trainDisplayName.length() > 24) trainDisplayName = trainDisplayName.substring(0, 24);
                        }
                    }
                } catch (Exception ignored) {}
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
        // Prefer precise Vec3 positions for sub-block accuracy
        if (previousPrecisePosition != null && precisePosition != null) {
            double dx = precisePosition.x - previousPrecisePosition.x;
            double dz = precisePosition.z - previousPrecisePosition.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01) {
                this.headingX = dx / len;
                this.headingZ = dz / len;
                this.headingFromMovement = true;
            }
        }
        // Fallback to BlockPos if Vec3 not available
        else if (previousPosition != null && currentPosition != null
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

        // ── Curve tangent correction (v1.0.1) ──
        // On curved edges, position-delta heading gives a CHORD direction that diverges
        // from the actual track tangent. This causes the beam scanner to "cut across"
        // the inside of the curve and falsely detect trains on adjacent parallel tracks,
        // or miss trains on the same track that have rounded the bend.
        // Correction: blend the movement heading with the direction toward the forward
        // node (node2), which approximates the track tangent much better on curves.
        if (headingFromMovement && myLeadingEdge != null && precisePosition != null) {
            boolean isCurve = false;
            if (isTurnMethod != null) {
                try { isCurve = Boolean.TRUE.equals(isTurnMethod.invoke(myLeadingEdge)); }
                catch (Exception ignored) {}
            }
            if (isCurve && myLeadingNode2 != null) {
                double[] n2xz = getNodeXZ(myLeadingNode2);
                if (n2xz != null) {
                    double toDx = n2xz[0] + 0.5 - precisePosition.x;
                    double toDz = n2xz[1] + 0.5 - precisePosition.z;
                    double toLen = Math.sqrt(toDx * toDx + toDz * toDz);
                    if (toLen > 1.5) {
                        double trackHX = toDx / toLen;
                        double trackHZ = toDz / toLen;
                        // Blend: 60% track geometry, 40% movement delta.
                        // Movement provides short-term responsiveness; track geometry
                        // prevents chord-vs-tangent divergence on tight bends.
                        this.headingX = headingX * 0.4 + trackHX * 0.6;
                        this.headingZ = headingZ * 0.4 + trackHZ * 0.6;
                        double bLen = Math.sqrt(headingX * headingX + headingZ * headingZ);
                        if (bLen > 0.01) { headingX /= bLen; headingZ /= bLen; }
                    }
                }
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
     * Get the XZ world-position of a Create TrackNode.
     * TrackNode.getLocation() returns a TrackNodeLocation which extends BlockPos (extends Vec3i).
     * Returns null on any reflection failure.
     */
    private double[] getNodeXZ(Object node) {
        if (node == null) return null;
        try {
            if (nodeGetLocationMethod == null)
                nodeGetLocationMethod = findMethod(node.getClass(), "getLocation");
            if (nodeGetLocationMethod == null) return null;
            Object loc = nodeGetLocationMethod.invoke(node);
            if (loc instanceof net.minecraft.core.Vec3i v)
                return new double[]{v.getX(), v.getZ()};
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

        // Persist non-null data so same-track detection still works after Create clears TravellingPoint.
        if (myLeadingEdge != null) myLastLeadingEdge = myLeadingEdge;
        if (myLeadingNode1 != null) myLastLeadingNode1 = myLeadingNode1;
        if (myLeadingNode2 != null) myLastLeadingNode2 = myLeadingNode2;

        // ── Right-of-way: compute whether we travel against the edge's natural direction ──
        // Use currentPosition → node2 direction (NOT node1→node2) so the angle is correct
        // even mid-curve: on tight bends the node1→node2 chord diverges from the tangent.
        // "currentPos → node2" always points where we're headed, stable across all arc positions.
        isWrongWay = false;
        double[] n2xz = getNodeXZ(myLeadingNode2);
        if (n2xz != null && currentPosition != null) {
            double toDx = n2xz[0] - currentPosition.getX();
            double toDz = n2xz[1] - currentPosition.getZ();
            double toLen = Math.sqrt(toDx * toDx + toDz * toDz);
            if (toLen > 1.0) { // only when farther than 1 block — avoids noise at the node itself
                double dot = (toDx / toLen) * headingX + (toDz / toLen) * headingZ;
                isWrongWay = (dot < -0.25); // −0.25: clearly moving AWAY from target node
            }
        }
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
            // SAFETY: only skip braking when the gap is large enough to be truly safe.
            // If the departing train suddenly brakes on a curve, closing speed spikes and
            // we can go from "safe" to collision in 1-2 ticks. So always brake within
            // BUFFER_CLEARANCE (7 blocks) regardless of relative speed.
            if (graphHitIsDeparting && graphDistanceToObstacle > BUFFER_CLEARANCE) {
                CreateRailwayMod.LOGGER.debug(
                        "[AI] Train {} sees departing train {} (dist={}) — skipping brake",
                        trainId.toString().substring(0, 8),
                        graphHit.trainId.toString().substring(0, 8),
                        (int) graphDistanceToObstacle);
                return null;
            }
            // Departing but within BUFFER_CLEARANCE — treat as normal obstacle
            if (graphHitIsDeparting) graphHitIsDeparting = false;
            this.obstaclePosition = graphHit.currentPosition;
            this.obstacleTrainId = graphHit.trainId;

            // ── Proactive detour ──
            // BFS found an obstacle on the primary route BUT also found a free
            // alternative branch at a junction ahead. Immediately cancel Create's
            // navigation so it re-pathfinds through the free longer route — no 5s wait.
            if (graphFoundFreeDetour
                    && (lastRerouteTick < 0
                        || (level.getGameTime() - lastRerouteTick) >= REROUTE_COOLDOWN_TICKS)) {
                lastRerouteTick = level.getGameTime();
                cancelCreateNavigation();
                CreateRailwayMod.LOGGER.info("[AI] Train {} proactive detour reroute"
                        + " (blocked={}, free alt branch exists)",
                        trainId.toString().substring(0, 8),
                        graphHit.trainId.toString().substring(0, 8));
            }

            return graphHit;
        }

        // ── Post-BFS emergency proximity safety scan ──
        // graphWalkScan follows routeNextHop and edge-identity. In two scenarios it
        // can miss a real obstacle:
        //   a) Edge-transition ticks: train slips into range before its edge data updates
        //   b) Stopped trains (JAM/WFC): Create stops updating TravellingPoint when the
        //      train is stationary, so myLeadingEdge becomes null → BFS never finds them.
        //      These trains appear invisible to BFS even when directly ahead.
        //
        // Detection ranges (both require isOnSameTrack or noData AND dot > 0.3):
        //   • Stopped obstacles (JAM/WFC/YIELDING at speed≈0): 15 blocks — gives
        //     enough distance for graduated braking to actually stop us
        //   • Moving obstacles (edge-transition): 5 blocks — tight guard only
        if (graphScanRan) {
            TrainAIManager proxMgr = TrainAIManager.getInstance();
            if (proxMgr != null && currentPosition != null) {
                for (TrainAIController other : proxMgr.getAllControllers()) {
                    if (other.trainId.equals(this.trainId)) continue;
                    if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;
                    if (other.currentPosition == null) continue;
                    int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                    if (dy > 10) continue;
                    double physDist = distanceBetween(this, other);
                    // Determine detection threshold based on whether the obstacle is stopped.
                    // Stopped trains lose their edge data → BFS invisible → need wider range.
                    boolean otherStopped = (other.currentState == TrainState.TRAFFIC_JAM
                            || other.currentState == TrainState.WAIT_FOR_CLEARANCE
                            || other.currentState == TrainState.YIELDING)
                            && other.currentSpeed < 0.02;
                    double proximityThreshold = otherStopped ? 15.0 : BUFFER_CRITICAL;
                    if (physDist > proximityThreshold) continue;
                    // Must be on the same track — key guard against parallel-track false
                    // positives. When both trains have graph data and isOnSameTrack returns
                    // false (disjoint signal blocks / different edges), skip.
                    boolean sameTrackConfirmed = isOnSameTrack(other);
                    boolean noData = !hasSufficientTrackData() || !other.hasSufficientTrackData();
                    if (!sameTrackConfirmed && !noData) continue;
                    // Must be clearly ahead (not behind us).
                    // For confirmed-same-track stopped trains: accept any forward direction
                    // (dot > 0.0) because on tight curves the stopped train can be nearly
                    // perpendicular to our current heading yet still directly in our path.
                    // For unconfirmed tracks (noData path): keep 0.3 to avoid side false-hits.
                    if (headingFromMovement) {
                        double tox = other.currentPosition.getX() - currentPosition.getX();
                        double toz = other.currentPosition.getZ() - currentPosition.getZ();
                        double dot = tox * headingX + toz * headingZ;
                        double minDot = (otherStopped && sameTrackConfirmed) ? 0.0 : 0.3;
                        if (dot < minDot) continue;
                    }
                    graphDistanceToObstacle = physDist;
                    graphScanActive = true;
                    obstaclePosition = other.currentPosition;
                    obstacleTrainId = other.trainId;
                    CreateRailwayMod.LOGGER.info(
                            "[AI] Train {} proximity hit {} (dist={}, stoppedTarget={})",
                            trainId.toString().substring(0, 8),
                            other.trainId.toString().substring(0, 8),
                            String.format("%.1f", physDist), otherStopped);
                    return other;
                }
            }
            return null;
        }

        // ── FALLBACK: Signal blocks + edge identity + beam ──
        // Used ONLY when graphWalkScan had no graph data (train just spawned, no TravellingPoint yet).
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
                // On a curve, the train directly ahead may be at up to 90° to our current
                // heading when it has already rounded the bend. Use -0.3 threshold so we
                // keep detecting trains up to ~107° ahead. For confirmed-same-track trains
                // (edge/node match), accept any non-clearly-behind angle (-0.5 threshold).
                double minDot = sameTrack ? -0.5 : 0.15;
                if (dot < minDot) continue; // train is clearly behind us

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
        // EXCEPTION: if the other train has no edge/node data (stopped, Create cleared
        // TravellingPoint), its signal group may differ even though it is ON THE SAME
        // physical rail (different signal block ahead on the same line). Skip exclusion
        // for stopped trains with no live edge/node data — fall through to last-known check.
        boolean otherHasLivePositionalData = other.myLeadingEdge != null || other.myTrailingEdge != null
                || other.myLeadingNode1 != null || other.myLeadingNode2 != null;
        if (otherHasLivePositionalData
                && !myOccupiedSignalGroups.isEmpty() && !other.myOccupiedSignalGroups.isEmpty()) {
            boolean anyShared = false;
            for (UUID g : myOccupiedSignalGroups) {
                if (other.myOccupiedSignalGroups.contains(g)) { anyShared = true; break; }
            }
            if (!anyShared) return false; // disjoint signal blocks = different tracks
            // Shared group but a block can span parallel tracks — don't auto-confirm.
            // Fall through to the edge check below.
        }

        // Edge identity: same TrackEdge object = same physical rail.
        // Use last-known data as fallback for stopped trains whose live data is null.
        Object myEdgeLead = myLeadingEdge != null ? myLeadingEdge : myLastLeadingEdge;
        Object oEdgeLead  = other.myLeadingEdge  != null ? other.myLeadingEdge  : other.myLastLeadingEdge;
        Object oEdgeTrail = other.myTrailingEdge;
        if (myEdgeLead != null && (myEdgeLead == oEdgeLead || myEdgeLead == oEdgeTrail))
            return true;
        if (myTrailingEdge != null && (myTrailingEdge == oEdgeLead || myTrailingEdge == oEdgeTrail))
            return true;

        // Node adjacency: trains sharing a TrackNode are on connected/same edges.
        // Catches HEAD-ON trains (edge A→B vs B→A share both nodes).
        // Also uses last-known node data for stopped trains.
        Object myN1 = myLeadingNode1 != null ? myLeadingNode1 : myLastLeadingNode1;
        Object myN2 = myLeadingNode2 != null ? myLeadingNode2 : myLastLeadingNode2;
        Object oN1  = other.myLeadingNode1 != null ? other.myLeadingNode1 : other.myLastLeadingNode1;
        Object oN2  = other.myLeadingNode2 != null ? other.myLeadingNode2 : other.myLastLeadingNode2;

        // Determine which node (if any) is shared
        Object sharedNode = null;
        if (myN1 != null && (myN1 == oN1 || myN1 == oN2)) sharedNode = myN1;
        if (sharedNode == null && myN2 != null && (myN2 == oN1 || myN2 == oN2)) sharedNode = myN2;
        // Check for MULTIPLE shared nodes (both N1 and N2 match) — a strong same-track signal
        boolean multipleShared = false;
        if (sharedNode != null) {
            if (sharedNode == myN1 && myN2 != null && (myN2 == oN1 || myN2 == oN2)) multipleShared = true;
            if (sharedNode == myN2 && myN1 != null && (myN1 == oN1 || myN1 == oN2)) multipleShared = true;
        }
        if (multipleShared) return true; // share BOTH nodes → definitely same/reverse rail

        if (sharedNode != null) {
            // ── Junction guard (v1.0.1) ──
            // At crossroads (≥3 connections), perpendicular tracks share the junction
            // node but are NOT on the same physical rail. Use heading angle to
            // distinguish perpendicular crossing from head-on / same-direction trains.
            Map<Object, Object> nodeConns = invokeGetConnectionsFrom(sharedNode);
            if (nodeConns != null && nodeConns.size() >= 3) {
                // Junction — check approach angle
                Object myEdgeEff = myLeadingEdge != null ? myLeadingEdge : myLastLeadingEdge;
                Object oEdgeEff  = other.myLeadingEdge != null ? other.myLeadingEdge : other.myLastLeadingEdge;
                if (myEdgeEff != null && oEdgeEff != null && myEdgeEff != oEdgeEff
                        && headingFromMovement && other.headingFromMovement) {
                    double hdot = headingX * other.headingX + headingZ * other.headingZ;
                    if (Math.abs(hdot) < 0.5) {
                        // Nearly perpendicular (60-120°): crossing paths, not same track
                        return false;
                    }
                }
                // Same/opposite direction at junction, or no heading data → same track
                return true;
            }
            // Simple node (≤2 connections): node sharing = same track
            return true;
        }

        // No edge/node match — let the 1.1-block beam fallback in the scanner decide.
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

    /**
     * Junction look-ahead: check whether the exit segment after a junction has enough
     * free space to fit our full train length.
     *
     * Walks 2 edges forward from the junction node along our route (via routeNextHop),
     * accumulating edge lengths. Returns true if accumulated >= trainLength AND no
     * obstacle (edgeToTrain / reverseEdgeMap) is found in that window.
     *
     * Two-edge depth covers trains up to ~2× typical segment length (~24 blocks). For
     * longer trains the check is conservative (may miss a third edge), but that is safe:
     * a false positive at most causes the train to wait one extra cycle.
     */
    private boolean isJunctionExitClear(Object junctionNode,
                                         Object exitNode,
                                         Map<Object, Object> juncConns,
                                         IdentityHashMap<Object, TrainAIController> edgeToTrain,
                                         IdentityHashMap<Object, TrainAIController> reverseEdgeMap) {
        // Edge from junction to exit node
        Object exitEdge = juncConns.get(exitNode);
        if (exitEdge == null) return true; // no edge data — assume clear
        // Only block if a train is ACTUALLY on the exit edge (or coming head-on via it).
        // Do NOT check length: short-but-empty segments are fine to roll through.
        // Blocking on length alone (without an occupying train) causes every junction
        // approach to stop even on a completely empty road → cascading JAM.
        if (edgeToTrain.containsKey(exitEdge) || reverseEdgeMap.containsKey(exitEdge)) return false;

        // Check one more hop: if the immediate exit is clear but the segment after is
        // occupied AND short (train can't clear the junction), also block.
        double firstLen = getEdgeLength(exitEdge);
        if (firstLen >= trainLength) return true; // exit alone is long enough — no need to peek further

        Map<Object, Object> exitConns = invokeGetConnectionsFrom(exitNode);
        if (exitConns == null) return true; // can't peek further — assume clear
        Object nextNode = routeNextHop.get(exitNode);
        Object nextEdge = (nextNode != null) ? exitConns.get(nextNode) : null;
        if (nextEdge == null && exitConns.size() == 1) {
            nextEdge = exitConns.values().iterator().next();
        }
        if (nextEdge != null && (edgeToTrain.containsKey(nextEdge) || reverseEdgeMap.containsKey(nextEdge))) {
            // Second segment is occupied AND the first segment alone is too short to park
            // the whole train outside the junction — we'd straddle the junction. Block.
            return false;
        }
        return true; // exit clear (with or without length being sufficient)
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
        graphFoundFreeDetour  = false;
        graphJunctionNotEnoughSpace = false;

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

        // Build reverse-edge map for head-on (oncoming) detection.
        // Create uses DIRECTED edges: edge(A→B) ≠ edge(B→A). A train traveling B→A
        // has myLeadingEdge = edge(B→A). Our BFS walks edge(A→B). Without this map,
        // BFS never finds head-on trains → collisions.
        // For each other train on edge(N1→N2), find the reverse edge(N2→N1) via
        // getConnectionsFrom(N2).get(N1). Map that reverse edge to the train.
        IdentityHashMap<Object, TrainAIController> reverseEdgeMap = new IdentityHashMap<>();
        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId)) continue;
            if (other.myLeadingNode1 == null || other.myLeadingNode2 == null) continue;
            if (other.myLeadingEdge == null) continue;
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;
            Map<Object, Object> revConns = invokeGetConnectionsFrom(other.myLeadingNode2);
            if (revConns != null) {
                Object revEdge = revConns.get(other.myLeadingNode1);
                if (revEdge != null) reverseEdgeMap.putIfAbsent(revEdge, other);
            }
        }

        // Build junction-convergence map: node → train approaching from a SIDE branch.
        // When train B is on a branch whose myLeadingNode2 is a junction node on OUR route,
        // B will physically arrive at that junction and merge onto our track — collision risk.
        // Distinct from oval-end nodes: we only flag if B's edge is NOT in edgeToTrain
        // (i.e. not on our own route edges) AND B is not behind us.
        // Map: junctionNode → closest approaching side-branch train (physical dist as tiebreak).
        IdentityHashMap<Object, TrainAIController> junctionApproachMap = new IdentityHashMap<>();
        IdentityHashMap<Object, Double> junctionApproachDist = new IdentityHashMap<>();
        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId)) continue;
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;
            if (other.myLeadingNode2 == null || other.myLeadingEdge == null) continue;
            if (other.currentPosition == null) continue;
            // Skip if this train is already captured in edgeToTrain (on our route edge)
            if (edgeToTrain.containsKey(other.myLeadingEdge)) continue;
            // Y-level filter
            if (currentPosition != null) {
                int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                if (dy > 10) continue;
            }
            double physDist = distanceBetween(this, other);
            Object jNode = other.myLeadingNode2; // node this train is heading toward
            Double existing = junctionApproachDist.get(jNode);
            if (existing == null || physDist < existing) {
                junctionApproachMap.put(jNode, other);
                junctionApproachDist.put(jNode, physDist);
            }
        }

        if (edgeToTrain.isEmpty() && reverseEdgeMap.isEmpty() && junctionApproachMap.isEmpty()) return null;

        // ── Step 1: check if another train is AHEAD on our own leading edge ──
        // (Our own leading edge is always on our route — no side-track filter needed here)
        TrainAIController hit = edgeToTrain.get(myLeadingEdge);
        if (hit != null) {
            double posDiff = hit.myLeadingEdgePos - myLeadingEdgePos;
            // Old threshold: 0.5 blocks. That left a blindspot: trains on the SAME edge
            // within 0.5 blocks were not returned here (edge already in visited set,
            // so BFS step 2 won't find them either) → undetected until physics collision.
            // New: accept any train that is ahead OR overlapping (posDiff > -0.5).
            // Negative means they've just slipped past us on the edge — still report
            // for the emergency stop path in handleObstacleDetected.
            if (posDiff > -0.5) {
                graphDistanceToObstacle = Math.max(0, posDiff);
                graphScanActive = true;
                graphHitIsParkingZone = false; // sharing our current edge — not a dead-end
                // Departing train: same direction and faster → gap is widening.
                // Only flag as departing when there's actually safe distance between us.
                graphHitIsDeparting = (posDiff > BUFFER_CLEARANCE)
                        && (hit.direction == this.direction)
                        && (hit.currentSpeed > this.currentSpeed + 0.05);
                return hit;
            }
        }

        // ── Step 1b: head-on train on the REVERSE of our leading edge ──
        // Our edge is A→B; a head-on train is on B→A. The reverse of B→A is A→B
        // (our own leading edge), which is what reverseEdgeMap maps.
        hit = reverseEdgeMap.get(myLeadingEdge);
        if (hit != null) {
            double edgeLenHere = getEdgeLength(myLeadingEdge);
            // We're at myLeadingEdgePos from A. Hit is at hit.myLeadingEdgePos from B (on B→A).
            // Distance between = edgeLen - ourPos - theirPos.
            // Allow negative (overlap) — trains inside each other need the emergency stop.
            double headOnDist = edgeLenHere - myLeadingEdgePos - hit.myLeadingEdgePos;
            // Threshold was 0.3 — that left a blindspot when trains got within 0.3 blocks
            // (step 1b skipped, BFS doesn't walk backward) → CRUISING until collision.
            // Now fire at any non-absurd distance (> -3 blocks allows up to 3 blocks overlap).
            if (headOnDist > -3.0) {
                graphDistanceToObstacle = Math.max(0, headOnDist);
                graphScanActive = true;
                graphHitIsParkingZone = false;
                graphHitIsDeparting = false; // head-on trains are never “departs”
                return hit;
            }
        }

        // ── Step 2: BFS walk forward from node2 through connected edges ──
        double edgeLen = getEdgeLength(myLeadingEdge);
        double remainingOnCurrent = Math.max(0, edgeLen - myLeadingEdgePos);

        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(myLeadingEdge);
        // Track visited NODES to prevent BFS from walking backward.
        // Create uses directed edges: edge(A→B) ≠ edge(B→A). Without node tracking,
        // BFS at node2 can walk backward through reverse edge(node2→node1), detecting
        // trains BEHIND us as if they were ahead → false TRAFFIC_JAM.
        Set<Object> visitedNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        visitedNodes.add(myLeadingNode1); // block backward walk from start
        // Entry format: {TrackNode node, double distanceSoFar, Object previousEdge}
        queue.add(new Object[]{myLeadingNode2, remainingOnCurrent, myLeadingEdge});

        // NOTE: Converging-junction detection (nodeToConvTrain) was removed.
        // It fired on SHARED JUNCTION NODES between parallel oval-track loops, causing
        // false stops. The stopped train then got hit because the other train (on a
        // separate loop) missed it at close range. reverseEdgeMap catches all real
        // head-on cases; same-direction trains are caught by edgeToTrain.

        TrainAIController closestHit = null;
        double closestDist = Double.MAX_VALUE;
        Object closestHitExitNode = null; // destination node of closest hit's edge (dead-end check)
        boolean closestHitIsReverse = false; // true when closestHit found via reverseEdgeMap (head-on)

        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            Object node = cur[0];
            double dist = (double) cur[1];
            Object fromEdge = cur[2];

            if (dist > maxDist) continue;

            // ── Junction convergence check ──
            // If another train is approaching THIS node from a side branch (not on our
            // route), they will physically arrive at this junction and merge onto our
            // track. We must treat them as an obstacle at physical distance.
            // Guard: only fire if the node is a REAL junction (connectionsFrom has 2+
            // entries meaning multiple branches meet here). This skips simple curve nodes
            // where any two trains on parallel ovals both happen to target the end-node.
            TrainAIController juncHit = junctionApproachMap.get(node);
            if (juncHit != null) {
                Map<Object, Object> juncConns = invokeGetConnectionsFrom(node);
                boolean isRealJunction = juncConns != null && juncConns.size() >= 2;
                if (isRealJunction) {
                    // Cross-junction safety (v1.0.1): at a crossroads, two trains can
                    // enter from perpendicular branches and exit to different branches
                    // without ever being on the same rail segment. Three-layer check:
                    //  1. Route exit comparison (most reliable when both have route data)
                    //  2. Heading angle — perpendicular approach = crossing paths
                    //  3. Entry edge identity — different entry edges at 4+ junction
                    Object myExit    = routeNextHop.isEmpty() ? null : routeNextHop.get(node);
                    Object theirExit = juncHit.routeNextHop.isEmpty() ? null : juncHit.routeNextHop.get(node);
                    // Layer 1: different exit nodes → non-conflicting routes
                    if (myExit != null && theirExit != null && myExit != theirExit) continue;
                    // Layer 2: heading angle check — if nearly perpendicular, trains are
                    // on crossing paths even when route data is incomplete.
                    if (headingFromMovement && juncHit.headingFromMovement) {
                        double hdot = headingX * juncHit.headingX + headingZ * juncHit.headingZ;
                        // |dot| < 0.5 → angle between 60° and 120° → perpendicular crossing
                        if (Math.abs(hdot) < 0.5) {
                            // Layer 3: at 4+ connection junctions with different entry edges,
                            // perpendicular trains never compete for the same rail segment.
                            if (juncConns != null && juncConns.size() >= 4
                                    && myLeadingEdge != null && juncHit.myLeadingEdge != null
                                    && myLeadingEdge != juncHit.myLeadingEdge) {
                                continue; // perpendicular crossing — let both pass
                            }
                            // 3-way junction: perpendicular but might merge at exit.
                            // Only allow if exits are confirmed different (handled by Layer 1).
                            // When exits are unknown, fall through to block (conservative).
                        }
                    }
                    if (dist < closestDist && dist <= maxDist) {
                        closestDist = dist;
                        closestHit = juncHit;
                        closestHitExitNode = node;
                        closestHitIsReverse = false; // converging — treat as same-direction obstacle
                        CreateRailwayMod.LOGGER.debug(
                                "[AI] Train {} junction-converge hit {} at node (dist={})",
                                trainId.toString().substring(0, 8),
                                juncHit.trainId.toString().substring(0, 8), (int) dist);
                    }
                }
            }

            Map<Object, Object> connections = invokeGetConnectionsFrom(node);
            if (connections == null) continue;

            // ── Junction look-ahead ──
            // If this is a real junction (≥3 connections) on our route AND we are within
            // (trainLength + 5) blocks of it, check if there is enough free space on the
            // exit side. A train should not enter if the exit segment is already occupied
            // or too short to fit the whole consist. This prevents gridlocks where a train
            // parks itself IN the junction, blocking everyone else.
            if (!routeNextHop.isEmpty() && connections != null && connections.size() >= 3) {
                Object exitNode = routeNextHop.get(node);
                if (exitNode != null && dist <= trainLength + 5) {
                    boolean enough = isJunctionExitClear(node, exitNode, connections,
                            edgeToTrain, reverseEdgeMap);
                    if (!enough) {
                        graphJunctionNotEnoughSpace = true;
                        CreateRailwayMod.LOGGER.debug(
                                "[AI] Train {} junction look-ahead: not enough exit space (dist={}b, need={}b)",
                                trainId.toString().substring(0, 8), (int) dist, trainLength);
                    }
                }
            }

            for (Map.Entry<Object, Object> entry : connections.entrySet()) {
                Object nextNode = entry.getKey();
                Object nextEdge = entry.getValue();

                // Skip the edge we came from and already-visited edges
                if (nextEdge == fromEdge) continue;
                if (!visited.add(nextEdge)) continue;
                // Skip already-visited nodes — prevents backward walks via reverse edges
                if (!visitedNodes.add(nextNode)) continue;

                // ── Route-path filter (SIDE_TRACK elimination) ──
                // routeNextHop: maps each route node to its expected next node.
                // If we’re at a known route node, ONLY follow the branch toward routeNextHop.get(node).
                // Any other branch (parallel sidings, opposite loop side, depots) is invisible.
                // Degrades safely: if routeNextHop is empty, BFS walks all branches (old behavior).
                if (!routeNextHop.isEmpty()) {
                    Object expectedNext = routeNextHop.get(node);
                    if (expectedNext != null && nextNode != expectedNext) {
                        // ── Proactive detour detection ──
                        // This is an off-route branch at a known junction node.
                        // If this alternative edge has no trains on it, flag it.
                        // When our primary route is also blocked (closestHit will be set),
                        // scanForObstacleController immediately cancels navigation so Create
                        // re-pathfinds via the free alternative — no 5-second wait needed.
                        if (!edgeToTrain.containsKey(nextEdge)
                                && !reverseEdgeMap.containsKey(nextEdge)) {
                            graphFoundFreeDetour = true;
                        }
                        continue; // off-route branch — skip train detection AND walking
                    }
                }

                // Check if any train occupies this edge (same direction)
                hit = edgeToTrain.get(nextEdge);
                if (hit != null) {
                    // Distance = accumulated rail dist + target's position on this edge
                    double totalDist = dist + hit.myLeadingEdgePos;
                    if (totalDist < closestDist && totalDist <= maxDist) {
                        closestDist = totalDist;
                        closestHit = hit;
                        closestHitExitNode = nextNode;
                        closestHitIsReverse = false; // same-direction train
                    }
                    continue; // don't walk past an occupied edge
                }

                // Check for head-on (oncoming) train on the reverse of this edge.
                // BFS edge goes node→nextNode. Oncoming train is on edge(nextNode→node).
                // reverseEdgeMap maps edge(node→nextNode) to that oncoming train.
                hit = reverseEdgeMap.get(nextEdge);
                if (hit != null) {
                    double revEdgeLen = getEdgeLength(nextEdge);
                    // Oncoming: train is hit.myLeadingEdgePos from their start (nextNode side).
                    // Distance from node (our side) = edgeLen - hit.myLeadingEdgePos
                    double totalDist = dist + (revEdgeLen - hit.myLeadingEdgePos);
                    if (totalDist < closestDist && totalDist <= maxDist) {
                        closestDist = totalDist;
                        closestHit = hit;
                        closestHitExitNode = nextNode;
                        closestHitIsReverse = true; // head-on — never a departing train
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
            // NEVER apply this to head-on (oncoming) trains: both trains have direction=true
            // (positive speed), so the direction check would falsely pass → collision.
            graphHitIsHeadOn    = closestHitIsReverse;
            graphHitIsDeparting = !graphHitIsHeadOn
                    && (closestHit.direction == this.direction)
                    && (closestHit.currentSpeed > this.currentSpeed + 0.05);
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
            // Avoidance marker expired — also clear bypass so the forward scan
            // sees the original obstacle again on the very next tick.
            blockedEdge = null;
            blockedByTrainId = null;
            bypassingTrainId = null;
            bypassModeUntilTick = 0;
            return false;
        }
        // Check if the blocking train is still alive and on the same edge
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return false;
        if (blockedByTrainId != null) {
            TrainAIController blocker = manager.getController(blockedByTrainId);
            if (blocker != null && blocker.currentPosition != null) {
                // Only block re-entry if the blocker is STILL on the same exact edge
                // as when we had to reverse. Once it has left that edge, clear immediately.
                // The old distance-fallback (dist <= minimumStopDistance*3 = 15 blocks)
                // caused false positives: a train at the station 10 blocks ahead would
                // keep this returning true, permanently blocking approach after reverse.
                if (blockedEdge != null
                        && (blockedEdge == blocker.myLeadingEdge || blockedEdge == blocker.myTrailingEdge)) {
                    return true;
                }
                // Blocker has left the blocked edge — fall through to clear below.
            }
        }
        // Blocker gone or moved away — clear all markers so forward scan is restored
        blockedEdge = null;
        blockedByTrainId = null;
        bypassingTrainId = null;
        bypassModeUntilTick = 0;
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
                // Graduated approach — avoid the cascade where every train in a long queue
                // immediately stops at 38 blocks from the train ahead of it, freezing
                // the entire line for 15+ seconds before any reversal can happen.
                //
                // Instead: proportional braking from CONTEXTUAL_EARLY_BRAKE_DIST down to
                // range*0.3 (15 blocks). Only full YIELDING stop within 15 blocks.
                if (distance <= range * 0.3) {
                    smoothStop();
                    transitionTo(TrainState.YIELDING, currentTick,
                            "ctx_brake_close_stuck_d=" + (int) distance);
                } else {
                    // Scale from full speed at CONTEXTUAL_EARLY_BRAKE_DIST down to
                    // 15% at range*0.3+1. Let Create's speed matching take over.
                    double fadeSpan = CONTEXTUAL_EARLY_BRAKE_DIST - range * 0.3;
                    double fadeFraction = (distance - range * 0.3) / fadeSpan; // 1.0=far, 0.0=close
                    double targetSpd = maxSpeed * Math.max(0.15, fadeFraction * 0.8);
                    applySpeedControl(targetSpd);
                    transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick,
                            "ctx_approaching_stuck_d=" + (int) distance);
                }
                return;
            }
        }

        // ── Speed-matching brake: follow at the obstacle's speed ──
        // When the obstacle is moving (not stuck) and we're faster, match its speed
        // plus a proportional safety margin. This prevents rear-end collisions when
        // the front train decelerates to approach a station.
        if (obstacle != null && obstacle.currentSpeed > 0.02 && distance > BUFFER_CRITICAL) {
            double speedDiff = this.currentSpeed - obstacle.currentSpeed;
            if (speedDiff > 0.01) {
                // Match obstacle speed + safety factor based on gap distance
                double safetyFactor = Math.max(0.0, Math.min(1.0, (distance - BUFFER_CRITICAL) / (range * 0.4)));
                double targetSpd = obstacle.currentSpeed * safetyFactor;
                if (targetSpd < this.currentSpeed) {
                    applySpeedControl(targetSpd);
                    transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick,
                            "speed_match_d=" + (int) distance + "_ts=" + String.format("%.2f", targetSpd));
                    return;
                }
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
    // Soft-resume: after stopping, limit speed for RESUME_RAMP_TICKS so we don't
    // instantly slam into a train that just barely left detection range.
    private long resumedAtTick = -1;
    private static final int RESUME_RAMP_TICKS = 40; // 2 seconds of gradual acceleration

    private void handleNoObstacle(long currentTick) {
        if (currentState == TrainState.WAIT_FOR_CLEARANCE) {
            // Guard 1 — minimum time in WFC (30 ticks = 1.5 s).
            // Protects against 1-tick scan blind-spots during rapid edge transitions.
            long timeInState = currentTick - stateEnteredTick;
            if (timeInState < 30) {
                forceStop();
                return;
            }

            // Guard 2 (REMOVED) — do NOT require graphScanRan here.
            // A stopped train in Create often has myLeadingEdge=null because the engine
            // only updates TravellingPoint while the train is moving. If we block WFC
            // exit on graphScanRan=false, a stopped train can never exit WFC even with
            // a completely empty track ahead — permanent freeze at station / mid-curve.
            // Guard 3 below (physical distance) is the actual safety gate.

            // Guard 3 — physical proximity check against the train that triggered WFC.
            // Survives ticks where graphScanRan=false (myLeadingEdge null on stopped train).
            // Euclidean distance is always ≤ graph distance on curved rail, so conservative.
            if (wfcObstacleTrainId != null) {
                TrainAIManager mgr = TrainAIManager.getInstance();
                if (mgr != null) {
                    TrainAIController lockedObstacle = mgr.getController(wfcObstacleTrainId);
                    if (lockedObstacle != null && lockedObstacle.currentPosition != null
                            && currentPosition != null) {
                        double physDist = distanceBetween(this, lockedObstacle);
                        if (physDist < BUFFER_CLEARANCE) {
                            forceStop(); // obstacle physically still too close
                            return;
                        }
                    }
                }
            }

            // Guard 4 — do NOT grant clearance while the locked obstacle is still YIELDING.
            // A YIELDING train has not started moving yet; granting clearance now causes a
            // re-collision loop: both trains exit their holding states simultaneously and
            // charge back into each other. Only allow WFC exit once the obstacle has
            // actually begun clearing (REVERSING) or fully cleared (CRUISING, etc.).
            if (wfcObstacleTrainId != null) {
                TrainAIManager mgrG4 = TrainAIManager.getInstance();
                if (mgrG4 != null) {
                    TrainAIController g4obs = mgrG4.getController(wfcObstacleTrainId);
                    if (g4obs != null && g4obs.getCurrentState() == TrainState.YIELDING) {
                        forceStop(); // obstacle still waiting — don't exit WFC yet
                        return;
                    }
                }
            }

            // All guards passed — safe to resume.
            junctionBlockedSinceTick = -1;
            resumedAtTick = currentTick;
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
            resumedAtTick = currentTick;
            restoreThrottle();
            transitionTo(TrainState.CRUISING, currentTick, "path_clear_resuming");
            totalWaitTicks = 0;
        }
    }

    /**
     * Soft-resume ramp: for RESUME_RAMP_TICKS after resuming from a stop,
     * gradually increase speed cap to prevent instant full-throttle launch.
     * Called from tick() AFTER obstacle/no-obstacle handling so it overrides
     * any throttle=1.0 that Create might receive.
     */
    private void applyResumeRamp(long currentTick) {
        if (resumedAtTick <= 0) return;
        long elapsed = currentTick - resumedAtTick;
        if (elapsed >= RESUME_RAMP_TICKS) {
            resumedAtTick = -1; // ramp complete
            return;
        }
        // During the ramp, cap speed to a fraction of max
        double rampFactor = (double)(elapsed + 1) / RESUME_RAMP_TICKS;
        double rampSpeed = maxSpeed * rampFactor;
        // Only limit if we'd otherwise go faster
        if (currentSpeed > rampSpeed || currentState == TrainState.CRUISING) {
            applySpeedControl(rampSpeed);
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
     * Smooth micro-braking stop: reduces speed by 50% per tick instead of
     * instantly zeroing it. Once speed drops below 0.05, delegates to forceStop()
     * for a clean zero. Stops in ~4 ticks (0.2s) from max speed.
     */
    private void smoothStop() {
        if (createTrainRef == null) return;

        if (currentSpeed < 0.05) {
            forceStop();
            return;
        }

        // Reduce to 50% of current speed each tick — steep exponential decay
        // At 1.2 b/t: 0.6 → 0.3 → 0.15 → 0.075 → 0 (stops in ~4 ticks)
        double reduced = currentSpeed * 0.5;
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
     * Sets throttle proportional to desiredSpeed/maxSpeed so Create's Navigation
     * doesn't re-accelerate the train between our ticks.
     */
    private void applySpeedControl(double desiredSpeed) {
        if (createTrainRef == null) return;
        try {
            double sign = direction ? 1.0 : -1.0;

            // Set throttle so Create's Navigation cannot accelerate beyond this fraction of topSpeed.
            if (throttleField != null) {
                double throttle = maxSpeed > 0.01 ? Math.min(1.0, desiredSpeed / maxSpeed) : 0.0;
                throttleField.setDouble(createTrainRef, throttle);
            }

            // Set the target speed so Create knows our desired cruise speed.
            // Do NOT clamp by currentSpeed here — that would freeze a stopped train at 0
            // and prevent it from ever accelerating (e.g., train stopped behind obstacle
            // at 30 blocks would try applySpeedControl(0.3*maxSpeed), but clamp to 0
            // and set targetSpeed=0, so the train can never move even when path is clear).
            if (targetSpeedField != null) {
                targetSpeedField.setDouble(createTrainRef, sign * desiredSpeed);
            }

            // Only force the raw speed field DOWNWARD (braking).
            // If desiredSpeed >= currentSpeed, let Create's physics accelerate naturally.
            if (desiredSpeed < this.currentSpeed && speedField != null) {
                speedField.setDouble(createTrainRef, sign * desiredSpeed);
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

            // If the train behind is YIELDING/WFC/JAM specifically because of US (it set
            // obstacleTrainId = our UUID), it is passively waiting and will back up as soon
            // as we start moving backward. Counting it as "behind obstacle" produces НЗД,
            // which prevents us from ever reversing — a permanent head-on deadlock.
            // Safe to ignore: the moment we reverse, it will detect us approaching and
            // itself enter REVERSING or yield further.
            if ((other.currentState == TrainState.YIELDING
                    || other.currentState == TrainState.WAIT_FOR_CLEARANCE
                    || other.currentState == TrainState.TRAFFIC_JAM)
                    && trainId.equals(other.obstacleTrainId)) continue;

            double dist = distanceBetween(this, other);
            if (dist > range) continue;

            int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
            if (dy > 10) continue;

            // Junction convergence guard: if both trains have different leading edges but
            // share the SAME myLeadingNode2 (both heading toward the same junction from
            // different branches), this is a forward convergence scenario — NOT a behind-train.
            // Without this, branch Train D targeting junction node J
            // would be counted as "behind" our Train B (also targeting J from main track).
            if (myLeadingNode2 != null && myLeadingNode2 == other.myLeadingNode2
                    && myLeadingEdge != null && other.myLeadingEdge != null
                    && myLeadingEdge != other.myLeadingEdge) continue;

            // Same-track check: TrackGraph primary, beam only when data is absent
            boolean sameTrack = isOnSameTrack(other);
            boolean useBeamFallback = !hasSufficientTrackData() || !other.hasSufficientTrackData();
            if (!sameTrack && !useBeamFallback) continue; // authoritative: different tracks
            if (!sameTrack && other.currentState == TrainState.TRAFFIC_JAM) continue;

            double toOtherX = other.currentPosition.getX() - currentPosition.getX();
            double toOtherZ = other.currentPosition.getZ() - currentPosition.getZ();
            double dot = toOtherX * headingX + toOtherZ * headingZ;
            // dot > 0.5 means the train is clearly IN FRONT of us — skip it.
            // We accept trains at the side (dot ≈ 0) and behind (dot < 0).
            // Old threshold -0.3 missed trains at 90° to our heading (curve stops):
            // a train directly behind us on a curve may approach from a perpendicular
            // heading, giving dot ≈ 0 which was incorrectly ignored → spaceAvailableBehind
            // = true → all queued curve trains tried to reverse simultaneously.
            if (dot > 0.5) continue;

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

        // ── Stuck-REVERSING rescue (40 ticks ≈ 2 sec) ──
        // When a train enters REVERSING but does not physically move (myLeadingEdge became
        // null because Create stopped updating TravellingPoint when the train was stopped,
        // leaving applyReverseSpeed() unable to apply any throttle), we rescue it by calling
        // cancelCreateNavigation(). This forces Create to re-initialize the navigation path
        // and TravellingPoint data on the very next schedule cycle, unblocking the train.
        if (backedUp < 0.5 && (currentTick - reversingStartTick) > 40) {
            CreateRailwayMod.LOGGER.warn(
                    "[AI] Train {} stuck in REVERSING (moved {}b in {} ticks) — resetting navigation",
                    trainId.toString().substring(0, 8),
                    String.format("%.2f", backedUp),
                    currentTick - reversingStartTick);
            cancelCreateNavigation();
            forceStop();
            totalWaitTicks = 0;
            transitionTo(TrainState.YIELDING, currentTick, "reversing_stuck_rescue");
            return;
        }
        if (backedUp >= RailwayConfig.reverseBackupDistance.get()) {
            CreateRailwayMod.LOGGER.info("[AI] Train {} reverse complete ({}b), handing to navigation",
                    trainId.toString().substring(0, 8), (int) backedUp);
            // Clear bypass immediately so the forward scan sees the original obstacle on
            // the very next tick. The old code extended bypass for 300 more ticks, which
            // caused the train to charge blindly into the obstacle if it hadn't moved away.
            // isBlockedEdgeStillOccupied() handles the re-yield case; graduated braking in
            // handleObstacleDetected() handles all other distances.
            bypassingTrainId = null;
            bypassModeUntilTick = 0;
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

        // Scan for trains in our ACTUAL movement path during reversal.
        //
        // graphWalkScan cannot be used here: it walks from myLeadingEdge which is the
        // SCHEDULE-FORWARD edge (first carriage's getLeadingPoint), NOT the direction of
        // physical movement. When reversing, the tail end is the actual "front".
        //
        // Instead we use the heading vector directly. After the first tick of movement,
        // updateHeading() sets headingX/Z to the REVERSE direction from the position delta.
        // Trains in our reverse path will have dot > 0.3 with that backward heading.
        // bypassingTrainId excludes the original obstacle (which is in the forward direction
        // and would have a negative dot with the reversed heading anyway).
        //
        // Guard: backedUp > 2.0 ensures we've actually moved enough for the heading to flip.
        if (backedUp > 2.0 && headingFromMovement && currentPosition != null) {
            TrainAIManager mgr = TrainAIManager.getInstance();
            if (mgr != null) {
                double range = RailwayConfig.obstacleDetectionRange.get();
                for (TrainAIController other : mgr.getAllControllers()) {
                    if (other.trainId.equals(this.trainId)) continue;
                    if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;
                    if (other.currentPosition == null) continue;
                    double dist = distanceBetween(this, other);
                    if (dist > range) continue;
                    int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                    if (dy > 10) continue;
                    boolean sameTrack = isOnSameTrack(other);
                    boolean useFallback = !hasSufficientTrackData() || !other.hasSufficientTrackData();
                    if (!sameTrack && !useFallback) continue;
                    if (!sameTrack && other.currentState == TrainState.TRAFFIC_JAM) continue;
                    double toOtherX = other.currentPosition.getX() - currentPosition.getX();
                    double toOtherZ = other.currentPosition.getZ() - currentPosition.getZ();
                    double dot = toOtherX * headingX + toOtherZ * headingZ;
                    // dot > 0.3: train is within ~72° of our current movement direction
                    // (heading now points backward = where we're going during reversal)
                    if (dot < 0.3) continue;
                    if (!sameTrack) {
                        double perp = Math.abs(toOtherX * headingZ - toOtherZ * headingX);
                        if (perp > currentBeamTolerance) continue;
                    }
                    CreateRailwayMod.LOGGER.info("[AI] Train {} aborted reverse — {} in reverse path (heading scan)",
                            trainId.toString().substring(0, 8),
                            other.trainId.toString().substring(0, 8));
                    forceStop();
                    totalWaitTicks = 0;
                    transitionTo(TrainState.YIELDING, currentTick, "blocked_in_reverse_path");
                    return;
                }
            }
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

        // Track near-collision events (buffer_critical = physical contact zone, < 5 blocks)
        if (newState == TrainState.WAIT_FOR_CLEARANCE && reason.startsWith("buffer_critical")) {
            this.collisionAtTick = currentTick;
        }

        // When entering WFC, lock the obstacle train reference for exit validation.
        // This survives ticks where the graph scanner has no data (myLeadingEdge null)
        // and where the fallback scanner clears obstacleTrainId.
        if (newState == TrainState.WAIT_FOR_CLEARANCE) {
            wfcObstacleTrainId = obstacleTrainId;
        } else {
            wfcObstacleTrainId = null; // clear when leaving WFC for any reason
        }

        // When starting bypass or reverse, shrink the scan beam for 5 seconds
        if ((newState == TrainState.BYPASSING || newState == TrainState.REVERSING) && obstacleTrainId != null) {
            bypassingTrainId = obstacleTrainId;
            bypassModeUntilTick = currentTick + BYPASS_MODE_TICKS;
        }

        CreateRailwayMod.LOGGER.info("[AI] Train {} state: {} -> {} ({})",
                trainId.toString().substring(0, 8),
                previousState, currentState, reason);

        // Broadcast state changes to nearby players in chat (if not silenced)
        if (!chatSilenced && currentPosition != null && lastKnownLevel != null) {
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
        // Prefer precise Vec3 positions for sub-block accuracy (BlockPos has up to 1.7b error)
        if (a.precisePosition != null && b.precisePosition != null) {
            return a.precisePosition.distanceTo(b.precisePosition);
        }
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
    @SuppressWarnings("null")
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

    // ── Control-panel API ──
    public boolean isChatSilenced() { return chatSilenced; }
    public void setChatSilenced(boolean v) { this.chatSilenced = v; }
    public boolean isAiEnabled() { return aiEnabled; }
    public void setAiEnabled(boolean v) { this.aiEnabled = v; }
    /** Returns true for 60 s after a buffer-critical near-collision was recorded. */
    public boolean hadRecentCollision(long currentTick) {
        return collisionAtTick > 0 && (currentTick - collisionAtTick) < 1200;
    }
    public void clearCollision() { this.collisionAtTick = -1; }
    public String getTrainDisplayName() { return trainDisplayName; }
    public void setTrainDisplayName(String n) { this.trainDisplayName = n == null ? "" : n; }

    // ─── TrackGraph Getters (for debug visualizer and external tools) ───
    /** Signal block group UUIDs this train currently occupies (from Create's occupiedSignalBlocks). */
    public Set<UUID> getSignalGroups() { return Collections.unmodifiableSet(myOccupiedSignalGroups); }
    /** TravellingPoint.edge of the leading carriage (object identity). Null if not available. */
    public Object getLeadingEdge() { return myLeadingEdge; }
    /** TravellingPoint.edge of the trailing carriage (object identity). Null if not available. */
    public Object getTrailingEdge() { return myTrailingEdge; }
    /** The TrackGraph this train belongs to. Null if not available. */
    public Object getTrackGraph() { return myGraph; }
    /** Current beam lateral tolerance — 1.1 on straight, 1.0 on curve. */
    public double getCurrentBeamTolerance() { return currentBeamTolerance; }
    /** Whether the leading edge is a curve (TrackEdge.isTurn()). */
    public boolean isOnCurvedEdge() { return currentBeamTolerance <= BEAM_LATERAL_TOLERANCE_CURVE; }
    /** Graph distance (blocks along rail) to the last detected obstacle. -1 if unavailable. */
    public double getGraphDistanceToObstacle() { return graphDistanceToObstacle; }
    /** Whether the last obstacle detection was done via graph walk (true) or beam fallback (false). */
    public boolean isGraphScanActive() { return graphScanActive; }
    /** Leading edge hashCode for display — stable identifier for the current rail segment. */
    public int getLeadingEdgeHash() { return myLeadingEdge != null ? System.identityHashCode(myLeadingEdge) : 0; }
    /** UUID of the train currently blocking this train (null if none). */
    public UUID getObstacleTrainId() { return obstacleTrainId; }
    /** True when BFS confirmed the obstacle is head-on (oncoming). */
    public boolean isGraphHitHeadOn() { return graphHitIsHeadOn; }
    /** True when this train is moving against the edge's natural node1→node2 direction. */
    public boolean isWrongWay() { return isWrongWay; }
    /** True when there is no train directly behind (safe to reverse). */
    public boolean isSpaceAvailableBehind() { return spaceAvailableBehind; }
    /** True when BFS found a free alternative branch while the primary route is blocked. */
    public boolean isGraphFoundFreeDetour() { return graphFoundFreeDetour; }
    /** True when a junction ahead has insufficient exit space for the full train. */
    public boolean isGraphJunctionNotEnoughSpace() { return graphJunctionNotEnoughSpace; }

    @Override
    public String toString() {
        return String.format("TrainAI[%s, state=%s, speed=%.2f, pos=%s]",
                trainId.toString().substring(0, 8), currentState, currentSpeed,
                currentPosition != null ? currentPosition.toShortString() : "null");
    }
}
