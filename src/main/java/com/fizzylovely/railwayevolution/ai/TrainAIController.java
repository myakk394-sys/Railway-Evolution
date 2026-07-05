package com.fizzylovely.railwayevolution.ai;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.adapter.EcosystemRegistry;
import com.fizzylovely.railwayevolution.ai.core.ObstacleProfile;
import com.fizzylovely.railwayevolution.ai.core.SafetyManager;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import com.fizzylovely.railwayevolution.ai.perception.PerceptionEngine;
import com.fizzylovely.railwayevolution.ai.state.TrainControlStateMachine;
import com.fizzylovely.railwayevolution.ai.state.TrainStateId;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * 
 * Copyright (c) 2026 Fizzy. Licensed under the CC-BY-NC-4.0 License.
 * See LICENSE file in the project root for full license information.
 *
 * Train AI Controller — the "brain" of a single train.
 *
 * Integrates with Create Mod's Train, Navigation, Carriage, CarriageBogey,
 * and TravellingPoint classes via reflection.
 *
 * Key Create Mod fields used:
 * Train.speed (double) — current speed
 * Train.targetSpeed (double) — target speed set by Navigation
 * Train.carriages (List) — list of Carriage objects
 * Train.navigation (Navigation) — pathfinding controller
 * Train.derailed (boolean) — derailment flag
 * Navigation.distanceToDestination (double)
 * Carriage.bogeys (Couple) — front/rear bogeys
 * CarriageBogey -> TravellingPoint -> TrackNode -> TrackNodeLocation (Vec3i)
 */
public class TrainAIController {

    // ── Static per-class reflection cache: avoids repeated hierarchy traversal
    // every tick ──
    // Maps className → fieldName → Field / className → methodName → Method
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    private final UUID trainId;
    private TrainState currentState;
    private TrainState previousState;
    private long stateEnteredTick;
    private long totalWaitTicks;
    private boolean isEmergency;

    // ── Convoy / Following mode ──
    // When we are trailing a train at similar speed with a safe following gap,
    // enter FOLLOWING instead of YIELDING so we continue at matched speed.
    private UUID convoyLeaderId = null; // UUID of the train we are following
    private double convoyTargetSpeed = 0; // matched speed (m/t)
    private int convoyFollowTicks = 0; // ticks continuously in following mode
    private static final int CONVOY_CONFIRM_TICKS = 20; // ticks before entering FOLLOWING
    private static final double CONVOY_SPEED_MATCH = 0.05; // speed delta threshold (m/t)
    private static final double CONVOY_MIN_GAP = 12.0; // min safe gap to follow at (blocks)
    private static final double CONVOY_MAX_GAP = 40.0; // max gap to still follow (blocks)

    // ── Kinematic braking ──
    // Deceleration constant: 0.06 b/t² ≈ comfortable passenger deceleration.
    // Used to compute required braking distance: d = v²/(2a)
    private static final double DECEL_RATE = 0.0375; // blocks/tick² — Create's default (trainAcceleration=15/400)
    private static final double DECEL_RATE_EMERG = 0.12; // emergency hard-stop deceleration

    // ── Predictive TTC (Time To Collision) ──
    // If the closing speed and gap give TTC < CRITICAL_TTC_TICKS, perform hard
    // stop.
    private static final int CRITICAL_TTC_TICKS = 30; // < 1.5 s TTC → hard stop

    // ── WFC distance history for exit hysteresis ──
    // Track the last 3 physical distances to the locked WFC obstacle.
    // Only exit WFC when ALL recent samples are >= BUFFER_CLEARANCE (8 blocks).
    private final double[] wfcDistHistory = { -1, -1, -1 };
    private int wfcDistIdx = 0;

    // Cached environment data
    private BlockPos currentPosition;
    private Vec3 precisePosition;
    private double currentSpeed;
    private double maxSpeed;
    private boolean direction;
    private int trainLength;      // total length in blocks (edge-to-edge)
    private int carriageCount;
    private double distToDestination;
    @SuppressWarnings("unused")
    private boolean derailed;

    // ── v5: Create API integration fields ──
    private double maxSpeedWatermark = 1.4;    // Create default: trainTopSpeed=28 → 28/20=1.4 b/t
    private boolean createWaitingForSignal;     // Navigation.waitingForSignal != null
    private double createDistToSignal;          // Navigation.distanceToSignal
    private double createAcceleration = 0.0375; // Create default: trainAcceleration=15 → 15/400
    private double[] createStress;              // Train.stress[] — carriage coupling stress
    private boolean createBlocked;              // TravellingPoint.blocked — end of track
    private double createThrottle = 1.0;        // Train.throttle (0..1)

    // Bypass tracking
    @SuppressWarnings("unused")
    private boolean onOppositeTrack;
    @SuppressWarnings("unused")
    private BlockPos obstaclePosition;
    private UUID obstacleTrainId;

    // Direction tracking (for the directional forward scan "invisible stick")
    private BlockPos previousPosition;
    private Vec3 previousPrecisePosition;
    double headingX; // normalized unit vector (package-visible for visualizer)
    double headingZ;
    private boolean headingFromMovement; // true if heading was computed from actual movement

    // Bypass mode — when bypassing/reversing, completely ignore the train we're
    // going around so the beam doesn't re-detect it on the parallel track
    private UUID bypassingTrainId;
    private long bypassModeUntilTick;
    private static final int BYPASS_MODE_TICKS = 300; // 15 seconds of ignore mode

    // WFC safety: when entering WAIT_FOR_CLEARANCE, lock the triggering train's
    // UUID.
    // Used to verify the obstacle is still physically far enough away before
    // granting
    // clearance — prevents false clearance_granted during curve edge transitions
    // where
    // graphWalkScan temporarily returns null because myLeadingEdge is null.
    private UUID wfcObstacleTrainId = null;
    private static final double BEAM_LATERAL_TOLERANCE = 1.5; // straight track beam

    // ── Junction entry blocking ──
    // When BFS says the junction exit is not clear, we stop BEFORE entering.
    // This flag prevents the normal YIELDING → REVERSING escalation: junction
    // congestion is temporary and should NOT trigger a reverse maneuver.
    private boolean junctionEntryBlocked = false;  // true while stopped at junction approach
    private long    junctionEntryBlockedSince = -1; // tick when we first stopped for junction

    // ── v5.3: Junction Yield Invisibility ──
    // When we yield at a junction, we store the UUID of the train we're yielding TO.
    // That train's junction checks will see us as "invisible" (skip us), so it can
    // pass through freely without also trying to yield to us → breaks mutual deadlock.
    // Reset when junction clears or timeout fires.
    private UUID junctionYieldingToId = null;
    private long junctionYieldingSince = -1;
    private static final double BEAM_LATERAL_TOLERANCE_CURVE = 3.5; // curve / turn beam — wider to catch trains around bends
    // (v1.0.5: was 1.0 which was TIGHTER than straight — completely wrong!
    // On curves, trains are laterally offset from a straight-line scan ray,
    // so we need a WIDER beam to detect them)

    // ── Priority / Pass-Signal system ──
    // When consecutiveYields >= STARVATION_THRESHOLD, this train earns a
    // priority token: approaching trains yield to IT instead of vice versa.
    // Token is revoked when the train successfully resumes CRUISING.
    private int  consecutiveYields = 0;   // trains yielded to consecutively
    private long yieldSessionStart = -1;  // tick when current yield session started
    private static final int  STARVATION_THRESHOLD    = 4;    // yield to 4 trains → earn token
    private static final long PRIORITY_DURATION_TICKS = 200L; // 10 s of right-of-way

    // ── Curve-exit lane merge ──
    // After leaving a curve/turn edge, reduce speed briefly so main-line
    // trains have time to detect and smoothly brake for the merging train.
    private boolean wasOnCurvedEdge = false; // edge type from last tick
    private long    curveExitTick   = -1;    // when we exited the curve
    private static final int    CURVE_MERGE_TICKS        = 50;  // 2.5 s merge window
    private static final double CURVE_MERGE_SPEED_FACTOR = 0.70; // v5: raised from 0.55 — Create already slows via maxTurnSpeed()

    // Dynamic beam tolerance — updated every tick based on current edge type
    private double currentBeamTolerance = BEAM_LATERAL_TOLERANCE;

    // Avoidance marker — after reversing from a jam, remember the blocked edge
    // and the train that was blocking so we immediately yield if still there
    private Object blockedEdge; // myLeadingEdge at the moment we started reversing
    private UUID blockedByTrainId; // obstacle train at reversal start
    private long avoidanceUntilTick; // ticks until avoidance marker expires
    private static final int AVOIDANCE_TICKS = 250; // ~12 seconds

    // Smart Junction re-routing — after being stuck for REROUTE_WAIT_TICKS, cancel
    // navigation so Create re-pathfinds; hysteresis prevents thrashing at switches
    private long junctionBlockedSinceTick = -1; // tick when we entered blocked-waiting state
    private long lastRerouteTick = -1; // last tick we triggered a reroute
    private static final int REROUTE_WAIT_TICKS = 40; // 2 s before attempting reroute
    private static final int REROUTE_COOLDOWN_TICKS = 200; // 10 s minimum between reroutes

    // Proactive detour backoff (v1.0.3) — prevents nav-cancel spam when the
    // alternate
    // branch turns out to be a dead-end/siding that Create can't route through.
    // Cooldown doubles each failed attempt: 200 → 400 → 800 ticks. After 3 retries
    // the detour is abandoned and the train falls back to normal graduated braking.
    private long proactiveDetourTick = -1;
    private int proactiveDetourRetries = 0;
    private BlockPos proactiveDetourPos = null;

    // Stuck-CRUISING backoff (v1.0.3) — same exponential cooldown when Create
    // repeatedly fails to re-path after navigation cancel.
    private int stuckCruisingRetries = 0;
    private BlockPos lastStuckCruisingPos = null;

    // Stuck CRUISING recovery — if speed≈0 in CRUISING with active navigation for N
    // ticks,
    // cancel navigation so Create re-pathfinds (handles broken-rail dead-ends).
    private static final int STUCK_CRUISING_REROUTE_TICKS = 120; // 6 s

    // Cached TrackGraph data for same-track detection
    private Set<UUID> myOccupiedSignalGroups = new HashSet<>();
    private Object myLeadingEdge; // TravellingPoint.edge of first carriage leading point
    private Object myTrailingEdge; // TravellingPoint.edge of last carriage trailing point
    private Object myGraph; // Train.graph

    // Reverse-escape maneuver state
    private BlockPos reversingStartPosition;
    private long reversingStartTick;
    private boolean spaceAvailableBehind;

    // Player control detection
    private boolean playerControlled;

    // ── v1.0.5: Junction Ghost Pass ──
    // When we yield at a junction because another train is closer, we add that
    // train's UUID here with an expiry tick. During the ghost period we skip it
    // in ALL obstacle scans so it can pass through without us stopping again.
    // Key = UUID of the passing train, Value = tick when ghost expires.
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> junctionGhostPassMap
            = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long GHOST_PASS_TICKS = 60L; // 3 seconds


    // ── Control-panel flags (toggled via F10 GUI) ──
    private boolean chatSilenced = false; // suppress in-chat state messages
    private boolean aiEnabled = true; // false = skip all AI logic, restore throttle
    private long collisionAtTick = -1; // last tick we entered buffer_critical zone
    private String trainDisplayName = ""; // read from Create's Train.name if available

    // ── Player safety system (v1.0.3) ──
    private long playerWhistleStartTick = -1; // tick when whistle warning started
    private UUID warningPlayerUUID = null; // player currently being warned
    private boolean hasWhistleBlock = false; // cached: does this train have a whistle/horn/bell?
    private boolean whistleCached = false; // has whistle detection been run?
    private long whistleCacheExpireTick = 0; // re-scan whistle every 5 seconds

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
    private java.lang.reflect.Method accelerationMethod; // Train.acceleration() → double
    private Field stressField;          // Train.stress (double[])
    private Field navWaitingForSignalField; // Navigation.waitingForSignal
    private Field navDistToSignalField;    // Navigation.distanceToSignal
    private boolean reflectionInitialized;

    // Graph-walk scanner: TravellingPoint data for leading carriage
    private Object myLeadingNode1; // TravellingPoint.node1 (TrackNode)
    private Object myLeadingNode2; // TravellingPoint.node2 (forward direction)
    // Last-known edge/node data — persists when Create clears TravellingPoint on
    // stopped trains.
    // isOnSameTrack() uses these as fallback so stopped trains remain detectable to
    // scanners.
    private Object myLastLeadingEdge;
    private Object myLastLeadingNode1;
    private Object myLastLeadingNode2;
    private double myLeadingEdgePos; // TravellingPoint.position on leading edge
    private double graphDistanceToObstacle = -1; // graph distance (blocks), -1 = n/a
    private boolean graphScanActive; // true if last detection was via graph walk

    // Cached reflection for graph-walk scanner (lazily initialized)
    private Field tpNode1Field;
    private Field tpNode2Field;
    private Field tpPositionField;
    private Method getLengthMethod; // TrackEdge.getLength()
    private Method getConnectionsFromMethod; // TrackGraph.getConnectionsFrom(TrackNode)
    private Method nodeGetLocationMethod; // TrackNode.getLocation() → TrackNodeLocation (extends BlockPos)
    private Method getTotalLengthMethod; // Train.getTotalLength() → int

    // Route-aware BFS scanner: node-level next-hop map for SIDE_TRACK filtering.
    //
    // Navigation.currentPath = List<Couple<TrackNode>> (confirmed via javap, Create
    // 6.0.1-41)
    // Each Couple(nodeA, nodeB) describes one directed hop along our route.
    //
    // routeNextHop maps: nodeA → nodeB (identity-based)
    // In BFS: if routeNextHop contains the current node, ONLY take the branch to
    // routeNextHop.get(node).
    // This constrains BFS to our scheduled route without needing edge-object
    // resolution.
    // Falls back gracefully (map empty → BFS unrestricted) if reflection fails.
    private final IdentityHashMap<Object, Object> routeNextHop = new IdentityHashMap<>();
    private Field navCurrentPathField = null;
    private boolean navPathReflInit = false;

    // Per-scan classification flags (set by graphWalkScan, consumed by
    // scanForObstacleController / handleObstacleDetected)
    private boolean graphHitIsParkingZone = false; // hit train is at a dead-end (depot/station)
    private boolean graphHitIsDeparting = false; // hit train is ahead, same direction, faster
    private boolean graphHitIsHeadOn = false; // hit train is head-on (oncoming via reverseEdgeMap or converging at
                                              // junction)
    // Set when BFS found a junction where our primary route is blocked BUT an
    // alternative
    // branch edge is FREE. scanForObstacleController will trigger an immediate
    // navigation
    // cancel so Create re-pathfinds via the free branch — proactive detour.
    private boolean graphFoundFreeDetour = false;
    // True when this train is heading AGAINST the edge's natural (node1→node2)
    // direction.
    // Used as the primary right-of-way decider: wrong-way train yields to the
    // rightful train.
    private boolean isWrongWay = false;
    // Set when BFS found a junction ahead (≥3 connections) whose exit side has
    // insufficient
    // free space for this train's full length. Train stops before entering to avoid
    // gridlock.
    private boolean graphJunctionNotEnoughSpace = false;
    // v5.3: UUID of the train that triggered graphJunctionNotEnoughSpace
    // (used for yield invisibility — the train we yield to won't see us)
    private UUID graphJunctionYieldToId = null;
    // Key of the junction this train currently has reserved (v1.0.5).
    // Released when: (a) train passes through, (b) train no longer approaching,
    // (c) train removed. -1 = no reservation.
    private long lastReservedJunctionKey = -1;
    // Set to true when graphWalkScan fully executed (had valid graph data),
    // regardless of result.
    // scanForObstacleController skips the fallback beam scanner when this is true,
    // because
    // the BFS result is then authoritative (route clear → no obstacle).
    boolean graphScanRan = false;

    // ── Adaptive track-size scaling (v1.0.5) ──
    // On small loops/tracks (20-30 blocks total), the standard BUFFER_CRITICAL=8
    // and detection range=50 cause gridlock — there's not enough space for trains
    // to keep those distances. localTrackCapacity = max forward distance the BFS
    // could walk. bufferScale = 0.3..1.0 based on that.
    // Small track (≤ 30b) → scale 0.3, medium (30-100b) → 0.3-1.0, large (≥100b) → 1.0
    private double localTrackCapacity = 100.0; // blocks, default = assume large
    private double bufferScale = 1.0;         // 0.3 .. 1.0

    // ════════════════════════════════════════════════════════════════════
    // v1.0.5 NEW ECOSYSTEM ARCHITECTURE — runs ALONGSIDE old code
    // Old fields/logic above are preserved and continue to work.
    // New components add: FLOW_FOLLOWER state, player ecosystem integration,
    // emergency follow-brake, and VarHandle-fast reads via EcosystemRegistry.
    // ════════════════════════════════════════════════════════════════════

    /** New v1.0.5 state machine — runs in parallel with old TrainState enum. */
    private final TrainControlStateMachine fsmV2 = new TrainControlStateMachine();

    /** Reusable per-tick context — zero allocations (reset each tick). */
    private final TrainControlContext ctxV2 = new TrainControlContext();

    /** New BFS perception engine — replaces old graphWalkScan for flow detection. */
    private final PerceptionEngine perceptionV2 = new PerceptionEngine();

    /**
     * True when the new FSM is in FLOW_FOLLOWER state.
     * Used to prevent old YIELDING escalation from firing while following a leader.
     */
    private boolean v2InFlowFollower = false;

    /**
     * Cached speed target from FLOW_FOLLOWER — applied to override old convoy code.
     * 0 = FLOW_FOLLOWER not active this tick.
     */
    private double v2FlowTargetSpeed = 0;

    /** Last tick the new ecosystem scan ran (staggered every 3 ticks). */
    private long v2LastEcoScanTick = -1;

    public TrainAIController(UUID trainId) {
        this.trainId = trainId;
        this.currentState = TrainState.CRUISING;
        this.previousState = TrainState.CRUISING;
        this.stateEnteredTick = 0;
        this.totalWaitTicks = 0;
        this.isEmergency = false;
        this.onOppositeTrack = false;
        this.reflectionInitialized = false;
        // v1.0.5: new FSM starts in CRUISING automatically (default constructor)
    }

    public void bindCreateTrain(Object createTrain) {
        // Only reinitialize reflection if the train object actually changed
        if (this.createTrainRef != createTrain) {
            this.createTrainRef = createTrain;
            this.reflectionInitialized = false;
        }
        if (!reflectionInitialized) {
            initReflection();
        }
    }

    /**
     * Cache reflection Field objects once so we don't do lookups every tick.
     */
    private void initReflection() {
        if (reflectionInitialized || createTrainRef == null)
            return;
        try {
            Class<?> cls = createTrainRef.getClass();
            speedField = findField(cls, "speed");
            targetSpeedField = findField(cls, "targetSpeed");
            throttleField = findField(cls, "throttle");
            carriagesField = findField(cls, "carriages");
            navigationField = findField(cls, "navigation");
            derailedField = findField(cls, "derailed");
            // Create 6.0.10: try multiple field names for player control detection
            manualTickField = findField(cls, "manualTick");
            if (manualTickField == null) manualTickField = findField(cls, "manualSteer");
            if (manualTickField == null) manualTickField = findField(cls, "controlInput");
            if (manualTickField == null) manualTickField = findField(cls, "handbrake");
            runtimeField = findField(cls, "runtime");
            graphField = findField(cls, "graph");
            occupiedSignalBlocksField = findField(cls, "occupiedSignalBlocks");
            stressField = findField(cls, "stress");

            // Train.acceleration() method — returns actual decel/accel rate from config
            try {
                accelerationMethod = cls.getMethod("acceleration");
                accelerationMethod.setAccessible(true);
            } catch (NoSuchMethodException e) {
                CreateRailwayMod.aiDebug("[AI] Train.acceleration() not found, using default");
            }

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
        if (isTurnMethod != null || edgeObject == null)
            return;
        try {
            isTurnMethod = edgeObject.getClass().getMethod("isTurn");
            isTurnMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            CreateRailwayMod.aiDebug("[AI] TrackEdge.isTurn() not found: {}", e.getMessage());
        }
    }

    /**
     * Main tick — called every server tick by TrainAIManager.
     */
    public void tick(ServerLevel level, long currentTick) {
        if (createTrainRef == null)
            return;
        this.lastKnownLevel = level;

        // Step 1: Read all train data from Create Mod
        updateTrainData(level);

        // ── v1.0.5: Ecosystem tick (FLOW_FOLLOWER + player emergency brake) ──
        // Runs before all state-machine guards so FLOW_FOLLOWER speed is available.
        // Results stored in v2InFlowFollower / v2FlowTargetSpeed for use below.
        tickEcosystemV2(level, currentTick);

        // ── v5: Create API guards — don't fight Create's own systems ──

        // Guard 1: If Create is waiting for a RED SIGNAL, don't interfere.
        // Create's Navigation already handles braking to signal distance.
        // Our AI adding speed control on top creates jittery "fight" behavior.
        if (createWaitingForSignal && !playerControlled && aiEnabled) {
            // Only suppress if signal is reasonably close (< 30 blocks)
            // Far signals don't affect immediate behavior
            if (createDistToSignal < 30.0) {
                // Let Create handle signal braking. Don't scan or override speed.
                // But still update registries so other trains see us.
                updateRegistries(currentTick);
                return;
            }
        }

        // Guard 2: If Create says we're blocked (end of track), stop trying to move.
        if (createBlocked && !playerControlled && aiEnabled) {
            if (currentState == TrainState.CRUISING) {
                forceStop();
                transitionTo(TrainState.WAIT_FOR_CLEARANCE, currentTick, "create_blocked_eot");
            }
            updateRegistries(currentTick);
            return;
        }

        // Guard 3: High stress → preventive slowdown before derail at stress>4.
        if (createStress != null && !playerControlled && aiEnabled && currentSpeed > 0.3) {
            double maxStress = 0;
            for (double s : createStress) maxStress = Math.max(maxStress, s);
            if (maxStress > 2.5) {
                // Slow down to 30% speed to reduce strain
                double safeSpeed = maxSpeed * 0.3;
                if (currentSpeed > safeSpeed) {
                    applySpeedControl(safeSpeed);
                    CreateRailwayMod.aiDebug("[AI] Train {} HIGH STRESS {}, slowing to {}b/t",
                            trainId.toString().substring(0, 8),
                            String.format("%.1f", maxStress),
                            String.format("%.2f", safeSpeed));
                }
            }
        }

        // ── PER-TICK STOP ENFORCEMENT ──
        // Create's physics overrides speed/throttle every tick.
        // WAIT_FOR_CLEARANCE and TRAFFIC_JAM: train must be completely motionless —
        // re-apply forceStop every tick.
        // YIELDING is intentionally NOT in this list: kinematic braking in
        // handleObstacleDetected() controls the speed smoothly. Adding forceStop here
        // zeros speed every tick and then Create tries to re-accelerate → "rolling then
        // abrupt stop" pattern.
        if (!playerControlled && aiEnabled
                && (currentState == TrainState.WAIT_FOR_CLEARANCE
                    || currentState == TrainState.TRAFFIC_JAM)) {
            forceStop();
            // Don't return — still need to re-evaluate if obstacle is gone

            // ── WFC / JAM auto-timeout (400 ticks = 20 seconds) ──
            // If stuck this long with no resolution, force-release.
            // Covers edge cases where obstacle disappeared but scanner missed it.
            // Reduced from 600t to 400t for better traffic flow.
            long timeInState = currentTick - stateEnteredTick;
            if (timeInState > 400) {
                forceRelease(currentTick, "wfc_timeout_30s");
                return;
            }
        }

        // Reset stuck-state backoff counters whenever the train is visibly moving.
        // This means a previous nav-cancel actually worked — counters back to zero.
        if (currentSpeed > 0.1) {
            stuckCruisingRetries = 0;
            lastStuckCruisingPos = null;
            proactiveDetourRetries = 0;
            proactiveDetourPos = null;
        }

        // If a player is manually controlling this train, skip ALL AI logic.
        // The train's position is still updated above so OTHER AI trains see it
        // as an obstacle and brake accordingly.
        if (playerControlled || !aiEnabled) {
            if (currentState != TrainState.CRUISING) {
                // Fully release any AI stop state so the player gets immediate control.
                // Clear WFC hysteresis so Create's throttle is not still locked at 0.
                Arrays.fill(wfcDistHistory, -1);
                wfcDistIdx = 0;
                wfcObstacleTrainId = null;
                totalWaitTicks = 0;
                junctionBlockedSinceTick = -1;
                junctionEntryBlocked = false;
                junctionYieldingToId = null;
                junctionYieldingSince = -1;
                obstacleTrainId = null;
                // Restore throttle ONCE so the player has full control immediately.
                restoreThrottle();
                // Do NOT call wakeCreateNavigation() when player is in control!
                // wakeCreateNavigation() sets runtime.paused = false, which removes
                // the player's manual control — causing the train to fight the player.
                if (!playerControlled) {
                    wakeCreateNavigation();
                }
                transitionTo(TrainState.CRUISING, currentTick, playerControlled ? "player_control" : "ai_disabled");
            }
            // ── FIX v1.0.5: Player trains MUST stamp VBS so other AI trains detect them! ──
            // Without proactiveReserve(), the player's train is invisible to graphWalkScan
            // and VBS segment checks → AI trains pass right through player-controlled trains.
            proactiveReserve(currentTick);
            updateRegistries(currentTick);
            return;
        }

        // Stamp VBS footprint so other trains can see us in segment-conflict checks
        proactiveReserve(currentTick);

        // Step 2b: Player safety system — detect players on tracks ahead (v1.0.3)
        // Takes absolute priority over train-train collision logic.
        if (RailwayConfig.safeModeEnabled) {
            ServerPlayer playerOnTrack = scanForPlayerOnTrack(level);
            if (playerOnTrack != null) {
                double playerDist = distanceToPlayer(playerOnTrack);
                handlePlayerOnTrack(playerOnTrack, playerDist, currentTick);
                return; // Player safety overrides all other AI logic this tick
            } else if (warningPlayerUUID != null) {
                // Player moved away — reset warning state
                playerWhistleStartTick = -1;
                warningPlayerUUID = null;
            }
        }

        // Auto-clear bypass mode early if the bypassed train is now behind us
        clearBypassIfPassed();

        // Initialize graph-walk reflection for obstacle scanning
        initGraphWalkReflection();

        // v5.3: Auto-expire junction yield invisibility (4 seconds max)
        if (junctionYieldingToId != null && junctionYieldingSince >= 0
                && (currentTick - junctionYieldingSince) > 80) {
            CreateRailwayMod.aiDebug("[AI] Train {} yield-invis expired for {}",
                    trainId.toString().substring(0, 8),
                    junctionYieldingToId.toString().substring(0, 8));
            junctionYieldingToId = null;
            junctionYieldingSince = -1;
        }

        // \u2500\u2500 Ghost-pass cleanup: remove expired entries each tick \u2500\u2500
        if (!junctionGhostPassMap.isEmpty()) {
            final long nowTick = currentTick;
            junctionGhostPassMap.entrySet().removeIf(e -> nowTick > e.getValue());
        }

        TrainAIController obstacleController = scanForObstacleController(level);
        boolean obstacleDetected = obstacleController != null;

        // Ghost-pass override (CORRECT direction):
        // We are the WINNER/passer. The yielder set junctionYieldingToId = our trainId.
        // If scan found a train yielding TO US, skip it so we can cross freely.
        if (obstacleDetected && obstacleController != null
                && obstacleController.junctionYieldingToId != null
                && obstacleController.junctionYieldingToId.equals(this.trainId)) {
            CreateRailwayMod.aiDebug(
                    "[Junction] {} ghost-skip {} (they yield to us)",
                    trainId.toString().substring(0, 8),
                    obstacleController.trainId.toString().substring(0, 8));
            obstacleController = null;
            obstacleDetected = false;
        }
        // v5.3 NOTE: Junction proximity post-scan was REMOVED here.
        // It caused cross-intersection deadlocks where ALL trains would yield
        // to each other simultaneously. BFS junction checks (mutex + converge)
        // are sufficient. The mutex reservation is the single source of truth.


        // \u2500\u2500 v5.4 + v1.0.5: ALWAYS-ON physical proximity scan \u2500\u2500
        // Last-resort collision guard. Runs every tick.
        // ONLY overrides BFS when it finds something SIGNIFICANTLY closer (>4b).
        // graphScanRan=true + no BFS obstacle = BFS says path clear = skip far zone.
        if (currentPosition != null && currentSpeed > 0.02 && headingFromMovement) {
            TrainAIManager emMgr = TrainAIManager.getInstance();
            if (emMgr != null) {
                double emergencyRange = 20.0;
                // Omni zone: tight range, catches junction cross-traffic
                // v1.0.5 FIX: reduced 8→5 blocks to avoid catching parallel-track trains
                double junctionOmniRange = 5.0;
                double closestEmDist = emergencyRange + 1;
                TrainAIController closestEmHit = null;
                for (TrainAIController other : emMgr.getAllControllers()) {
                    if (other.trainId.equals(this.trainId)) continue;
                    if (other.currentPosition == null) continue;
                    if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;
                    // Y filter \u2014 generous for slopes
                    int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                    if (dy > 6) continue;

                    double dx = other.currentPosition.getX() - currentPosition.getX();
                    double dz = other.currentPosition.getZ() - currentPosition.getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist > emergencyRange || dist < 0.5) continue;

                    double invDist = 1.0 / dist;
                    double dirX = dx * invDist;
                    double dirZ = dz * invDist;
                    double dot = dirX * headingX + dirZ * headingZ;

                    if (dist <= junctionOmniRange) {
                        // Ultra-close omni zone \u2014 catches junction cross-traffic
                        // Skip if clearly BEHIND us
                        if (dot < -0.5) continue;
                        // FIX: when graph data available, skip confirmed parallel-track trains
                        if (hasSufficientTrackData() && other.hasSufficientTrackData()) {
                            if (!isOnSameTrack(other)) continue;
                        } else {
                            // No graph data: use tight perpDist filter (3 blocks)
                            double perpDist = Math.abs(dx * headingZ - dz * headingX);
                            if (perpDist > 3.0) continue;
                        }
                        if (dist < closestEmDist) {
                            closestEmDist = dist;
                            closestEmHit = other;
                        }
                        continue; // handled, skip cone check below
                    }

                    // FIX: if BFS ran and found no obstacle, skip physical scan
                    // beyond the omni zone. BFS is authoritative on clear track.
                    if (graphScanRan && obstacleController == null) continue;

                    // Beyond omni zone: directional cone
                    if (dot < 0.3) continue; // behind us or far to side

                    // FIX: lateral tolerance reduced 8\u21924 blocks.
                    // Standard double-track spacing ~4 blocks \u2014 3.5 safely excludes parallels.
                    double lateral = Math.abs(dx * headingZ - dz * headingX);
                    if (lateral > 3.5) continue;

                    // Extra filter: when graph data available, skip parallel-track trains
                    if (hasSufficientTrackData() && other.hasSufficientTrackData()) {
                        if (!isOnSameTrack(other)) continue;
                    }

                    if (dist < closestEmDist) {
                        closestEmDist = dist;
                        closestEmHit = other;
                    }
                }
                if (closestEmHit != null) {
                    // Ghost-pass check: skip yielder
                    boolean yieldsToUs = closestEmHit.junctionYieldingToId != null
                            && closestEmHit.junctionYieldingToId.equals(this.trainId);
                    if (yieldsToUs) {
                        CreateRailwayMod.aiDebug(
                                "[Junction] {} phys-skip {} (they yield to us)",
                                trainId.toString().substring(0, 8),
                                closestEmHit.trainId.toString().substring(0, 8));
                    } else {
                        // Override BFS only if physical scan found something SIGNIFICANTLY closer.
                        // Require >4 block improvement to prevent flicker when BFS and phys agree.
                        double bfsDist = (obstacleController != null && graphDistanceToObstacle > 0)
                                ? graphDistanceToObstacle : Double.MAX_VALUE;
                        if (closestEmDist < bfsDist - 4.0 || bfsDist == Double.MAX_VALUE) {
                            obstacleController = closestEmHit;
                            obstacleDetected = true;
                            graphDistanceToObstacle = closestEmDist;
                            CreateRailwayMod.aiDebug(
                                    "[AI] Train {} PHYS-DETECT {} at {}b (spd={}, player={}, omni={})",
                                    trainId.toString().substring(0, 8),
                                    closestEmHit.trainId.toString().substring(0, 8),
                                    (int) closestEmDist,
                                    String.format("%.2f", closestEmHit.currentSpeed),
                                    closestEmHit.playerControlled,
                                    closestEmDist <= junctionOmniRange);
                        }
                    }
                }

            }
        }

        // ── Emergency pre-stop: physical distance safety ──
        // Uses PHYSICAL distance, not graph distance. Graph can miscalculate on curves.
        if (obstacleController != null && currentPosition != null
                && obstacleController.currentPosition != null) {
            double quickDist = distanceBetween(this, obstacleController);
            if (quickDist <= 4.0) {
                // IMMEDIATE: overlap or near-overlap → hard stop
                forceStop();
            } else if (quickDist <= BUFFER_CRITICAL * bufferScale) {
                // CRITICAL zone → hard stop
                forceStop();
            } else if (quickDist <= 12.0 && currentSpeed > 0.5) {
                // CAUTION zone → limit to 40% max speed
                double cautionSpeed = maxSpeed * 0.4;
                if (currentSpeed > cautionSpeed) {
                    applySpeedControl(cautionSpeed);
                }
            }
            // ── Anti-overlap enforcement (v1.0.3) ──
            // distanceBetween() now returns edge-to-edge gap. Negative = trains
            // are physically overlapping! Emergency stop + reverse nudge.
            if (quickDist < 0) {
                forceStop();
                if (currentState != TrainState.WAIT_FOR_CLEARANCE) {
                    transitionTo(TrainState.WAIT_FOR_CLEARANCE, currentTick,
                            "anti_overlap_gap=" + String.format("%.1f", quickDist));
                    CreateRailwayMod.aiWarn(
                            "[AI] Train {} OVERLAP detected (gap={}b) with {} — emergency lockout",
                            trainId.toString().substring(0, 8),
                            String.format("%.1f", quickDist),
                            obstacleController.trainId.toString().substring(0, 8));
                }
                // ── Overlap deadlock breaker (v1.0.5) ──
                // If we've been overlapping for > 60 ticks (3 seconds), one train
                // MUST reverse to physically separate. Use UUID tie-break:
                // junior (lower hash) reverses, senior waits.
                if (currentState == TrainState.WAIT_FOR_CLEARANCE
                        && (currentTick - stateEnteredTick) > 60
                        && RailwayConfig.reverseManeuverEnabled.get()) {
                    int myHash = trainId.hashCode();
                    int oppHash = obstacleController.trainId.hashCode();
                    boolean isJunior = (myHash != oppHash)
                            ? myHash < oppHash
                            : trainId.compareTo(obstacleController.trainId) < 0;
                    if (isJunior) {
                        CreateRailwayMod.aiWarn(
                                "[AI] Train {} OVERLAP DEADLOCK — junior reversing to separate from {}",
                                trainId.toString().substring(0, 8),
                                obstacleController.trainId.toString().substring(0, 8));
                        reversingStartPosition = currentPosition;
                        reversingStartTick = currentTick;
                        blockedEdge = myLeadingEdge;
                        blockedByTrainId = obstacleController.trainId;
                        avoidanceUntilTick = currentTick + AVOIDANCE_TICKS;
                        transitionTo(TrainState.REVERSING, currentTick, "overlap_deadlock_reverse");
                        obstaclePosition = null;
                        obstacleTrainId = null;
                        totalWaitTicks = 0;
                    }
                }
            }
        }

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
                    if (other.trainId.equals(this.trainId))
                        continue;
                    if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId))
                        continue;
                    // Same "yielding-to-us" exception as in scanBehindForObstacle:
                    // a stopped train whose blocker is us will move the instant we reverse.
                    if ((other.currentState == TrainState.YIELDING
                            || other.currentState == TrainState.WAIT_FOR_CLEARANCE
                            || other.currentState == TrainState.TRAFFIC_JAM)
                            && trainId.equals(other.obstacleTrainId))
                        continue;
                    if (other.myLeadingNode2 != myLeadingNode1)
                        continue;
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
                && currentState != TrainState.WAIT_FOR_CLEARANCE
                && currentState != TrainState.TRAFFIC_JAM) {
            // ── Junction look-ahead block (v2, v5 improved) ──
            // BFS found a real junction ahead that is physically occupied or the exit
            // segment does not have enough free space for our full train length.
            // v5: Use kinematic braking instead of forceStop() to approach smoothly.
            // forceStop every tick caused crawl-stop-crawl jitter near junctions.
            double juncDist = graphDistanceToObstacle > 0 ? graphDistanceToObstacle : 10;
            if (currentSpeed < 0.05 || juncDist < 3) {
                // Already slow enough or very close — hard stop
                forceStop();
            } else {
                // Kinematic deceleration — stop exactly at junction minus buffer
                double stopDist = Math.max(2.0, juncDist - 3.0);
                double decel = getDecelRate();
                double vTarget = Math.sqrt(Math.max(0, 2.0 * decel * stopDist));
                vTarget = Math.min(vTarget, maxSpeed * 0.5); // cap at 50% max for safety
                if (vTarget < currentSpeed) {
                    applySpeedControl(vTarget);
                }
            }
            junctionEntryBlocked = true;
            if (junctionEntryBlockedSince < 0) junctionEntryBlockedSince = currentTick;
            // v5.3: Make us invisible to the train we're yielding to
            if (graphJunctionYieldToId != null) {
                junctionYieldingToId = graphJunctionYieldToId;
                if (junctionYieldingSince < 0) junctionYieldingSince = currentTick;
            }
            transitionTo(TrainState.YIELDING, currentTick, "junction_entry_blocked");
        } else if (sandwiched) {
            // TRAFFIC JAM — train in front AND behind, just wait
            forceStop();
            transitionTo(TrainState.TRAFFIC_JAM, currentTick,
                    "jam_front_and_behind");
        } else if (obstacleDetected) {
            double dist = distanceBetween(this, obstacleController);
            // v5.4: Use the SMALLER of physical and graph distance (most conservative).
            // Old code overwrote physical with graph — graph can be WRONG on curves,
            // causing trains to think obstacle is far when it's actually close → collision.
            if (graphScanActive && graphDistanceToObstacle > 0) {
                dist = Math.min(dist, graphDistanceToObstacle);
            }

            // ── Priority / Pass-Signal check ──
            // Check the global priority registry before running the normal distance-based
            // braking logic. Priority tokens are granted to trains that have been starved
            // (yielded >= STARVATION_THRESHOLD times in a row without moving).
            TrainAIManager prChkMgr = TrainAIManager.getInstance();
            boolean obstacleHasPriority = prChkMgr != null
                    && prChkMgr.hasPriority(obstacleController.trainId, currentTick);
            boolean weHavePriority = prChkMgr != null
                    && prChkMgr.hasPriority(this.trainId, currentTick);

            if (weHavePriority && !obstacleHasPriority) {
                // WE are the priority train — treat the obstacle as if it is a departing
                // train (moving away). Apply only gentle braking instead of full stop.
                // The other train's AI will see our token and yield to us.
                graphHitIsDeparting = true;
                handleObstacleDetected(dist, obstacleController, currentTick);
            } else if (obstacleHasPriority && !weHavePriority) {
                // OBSTACLE has priority — yield immediately without waiting for normal
                // braking thresholds. This is the "pass signal" received from a starved
                // train: step aside and let it through.
                if (currentState != TrainState.YIELDING
                        && currentState != TrainState.WAIT_FOR_CLEARANCE) {
                    smoothStop();
                    transitionTo(TrainState.YIELDING, currentTick,
                            "yield_to_priority_" + obstacleController.trainId.toString().substring(0, 8));
                    // ── Yield Invisibility ──
                    // Tell the priority train to ignore US for 5 s (100 ticks) so it
                    // doesn't brake for a train that deliberately moved aside for it.
                    TrainAIController priorityCtrl = prChkMgr.getController(obstacleController.trainId);
                    if (priorityCtrl != null) {
                        priorityCtrl.setBypassIgnore(this.trainId, currentTick + 100);
                        CreateRailwayMod.aiDebug(
                            "[AI] Train {} granted 100t invisibility to priority train {}",
                            trainId.toString().substring(0, 8),
                            obstacleController.trainId.toString().substring(0, 8));
                    }
                } else {
                    smoothStop();
                }
            } else {
                // Normal braking logic (neither or both have priority)
                if (currentState == TrainState.WAIT_FOR_CLEARANCE && dist < BUFFER_CLEARANCE * bufferScale) {
                    smoothStop();
                } else {
                    handleObstacleDetected(dist, obstacleController, currentTick);
                }
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
                    int myHash = trainId.hashCode();
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
                    CreateRailwayMod.aiLog(
                            "[AI] Train {} JAM escalating to YIELDING (space behind clear after {}t)",
                            trainId.toString().substring(0, 8), totalWaitTicks);
                    totalWaitTicks = 0;
                    transitionTo(TrainState.YIELDING, currentTick, "jam_deescalate_space_behind");
                } else if (totalWaitTicks > maxYield * 2 && RailwayConfig.reverseManeuverEnabled.get()) {
                    // Forced de-escalation: both sides still blocked after 2× maxYield.
                    // Force into YIELDING regardless of spaceAvailableBehind — the
                    // YIELDING priority logic will decide who reverses. Without this,
                    // a two-train converging JAM at a junction waits forever because
                    // neither train ever gets spaceAvailableBehind=true.
                    CreateRailwayMod.aiLog("[AI] Train {} JAM FORCE de-escalate ({}t > maxYield*2)",
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
            // Only attempt reroute after extended wait (120 ticks = 6s) to avoid
            // constantly cancelling Create's navigation while in WFC
            if (totalWaitTicks > 120) {
                trySmartJunctionReroute(currentTick);
            }
        } else if (currentState == TrainState.YIELDING) {
            totalWaitTicks++;
            // Smart junction reroute: attempt before escalating to reverse.
            // This lets Create re-pathfind via a free adjacent edge at the junction.
            trySmartJunctionReroute(currentTick);
            long maxYield = RailwayConfig.maxYieldTicks.get();
            boolean reverseEnabled = RailwayConfig.reverseManeuverEnabled.get();

            // ── Head-on fast resolution (30 ticks = 1.5 sec) ──
            // When the BFS scanner confirmed a genuine head-on encounter
            // (graphHitIsHeadOn),
            // resolve it 4× faster than the generic 120-tick path.
            // Deterministic tie-break: higher hashCode = “senior” (waits); lower = “junior”
            // (reverses).
            // Falls through to 120-tick block for non-head-on mutual jams.
            boolean dirResolved = false;
            if (totalWaitTicks > 30 && reverseEnabled && obstacleTrainId != null && graphHitIsHeadOn) {
                TrainAIManager mgrDir = TrainAIManager.getInstance();
                TrainAIController oppDir = mgrDir != null ? mgrDir.getController(obstacleTrainId) : null;
                if (oppDir != null
                        && (oppDir.getCurrentState() == TrainState.YIELDING
                                || oppDir.getCurrentState() == TrainState.WAIT_FOR_CLEARANCE
                                || oppDir.getCurrentState() == TrainState.TRAFFIC_JAM)) {
                    int myHash = trainId.hashCode();
                    int oppHash = obstacleTrainId.hashCode();
                    boolean isSenior = (myHash != oppHash)
                            ? myHash > oppHash
                            : trainId.compareTo(obstacleTrainId) > 0;
                    dirResolved = true;
                    if (isSenior) {
                        forceStop(); // senior: hold position, junior will reverse
                    } else if (spaceAvailableBehind) {
                        // Junior: reverse to clear the head-on
                        CreateRailwayMod.aiLog(
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
            // When two trains are mutually stuck at a curve/junction (both YIELDING or
            // WFC),
            // use UUID hashCode as a deterministic priority:
            // Higher hashCode → "senior" → stays stopped and waits
            // Lower hashCode → "junior" → reverses to clear the junction
            // Tie-break on hashCode equality: use UUID compareTo (always distinct).
            // This fires 2.5× faster than maxYieldTicks (300), resolving most deadlocks
            // in 6 seconds instead of 15.
            // Also fires when obstacle is CRUISING but speed≈0 (stuck train — rear train
            // must be the one to reverse/reroute since the front train won't move on its
            // own).
            // ── Junction entry blocked: suppress REVERSING escalation ──
            // If we are stopped because of a junction (not a train-train deadlock),
            // inhibit all reverse logic. Junction congestion resolves on its own.
            // Timer fires after 10 s (200 t) of continuous blocking.
            if (junctionEntryBlocked) {
                if (!graphJunctionNotEnoughSpace) {
                    // Junction is now clear — reset flag and fall through to normal
                    // handleNoObstacle / CRUISING transition logic
                    junctionEntryBlocked = false;
                    junctionEntryBlockedSince = -1;
                    junctionYieldingToId = null;
                    junctionYieldingSince = -1;
                    // fall through: handleNoObstacle will transition to CRUISING
                } else {
                    // Still blocked: hold position, but do NOT reset the start timer
                    // v5.3: timeout 40 ticks (2s) — matches reservation expiry
                    boolean juncTimedOut = junctionEntryBlockedSince >= 0
                            && (currentTick - junctionEntryBlockedSince) > 40;
                    if (!juncTimedOut) {
                        forceStop(); // hold position without escalating to REVERSING
                        // Try reroute every 20 ticks while junction-blocked
                        if ((currentTick - junctionEntryBlockedSince) % 20 == 0) {
                            trySmartJunctionReroute(currentTick);
                        }
                        return; // suppress REVERSING / WFC escalation below
                    } else {
                        // 3-second timeout: give up, allow normal logic to take over
                        junctionEntryBlocked = false;
                        junctionEntryBlockedSince = -1;
                        junctionYieldingToId = null;
                        junctionYieldingSince = -1;
                        CreateRailwayMod.aiLog("[AI] Train {} junction entry block timed out after 40t",
                                trainId.toString().substring(0, 8));
                    }
                }
            }

            if (!dirResolved && totalWaitTicks > 120 && reverseEnabled && obstacleTrainId != null) {
                TrainAIManager mgrFast = TrainAIManager.getInstance();
                TrainAIController opp = mgrFast != null ? mgrFast.getController(obstacleTrainId) : null;
                boolean oppBlocking = opp != null && (opp.getCurrentState() == TrainState.YIELDING
                        || opp.getCurrentState() == TrainState.WAIT_FOR_CLEARANCE
                        || opp.getCurrentState() == TrainState.TRAFFIC_JAM
                        || (opp.getCurrentState() == TrainState.CRUISING && opp.getCurrentSpeed() < 0.02));
                if (oppBlocking) {
                    int myHash = trainId.hashCode();
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
                        CreateRailwayMod.aiLog(
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
            //
            // Before reverse, check if we have space behind.
            // If space is available, start the reverse-escape maneuver.
            if (!dirResolved && totalWaitTicks > maxYield && reverseEnabled && spaceAvailableBehind) {

                // Head-on deadlock tie-breaker: when two trains face each other and BOTH
                // want to reverse simultaneously, only the lower-UUID train actually reverses.
                // The higher-UUID train defers (resets partial wait), giving the lower-UUID
                // train time to clear. Without this, both reverse equal distances, return,
                // and re-collide forever.
                if (graphHitIsHeadOn && obstacleTrainId != null) {
                    TrainAIManager mgrTB = TrainAIManager.getInstance();
                    TrainAIController opp = mgrTB != null ? mgrTB.getController(obstacleTrainId) : null;
                    boolean oppStillConflicting = opp != null && (opp.getCurrentState() == TrainState.YIELDING
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
            // Clear junction entry block whenever we leave YIELDING normally
            if (junctionEntryBlocked) {
                junctionEntryBlocked = false;
                junctionEntryBlockedSince = -1;
                junctionYieldingToId = null;
                junctionYieldingSince = -1;
            }
        }

        // Step 7: Soft-resume ramp + curve-exit merge cap.
        // The resume ramp runs after ALL state handling to smoothly re-accelerate.
        // The curve-exit cap limits speed for CURVE_MERGE_TICKS after leaving a curve
        // so that main-line trains have time to detect and brake for us.
        // Set real curveExitTick when we first see the sentinel value (Long.MAX_VALUE).
        if (curveExitTick == Long.MAX_VALUE) {
            curveExitTick = currentTick;
        }
        applyResumeRamp(currentTick);
        // Curve-exit merge speed cap (lower priority than forceStop, but applied last)
        if (curveExitTick > 0 && curveExitTick != Long.MAX_VALUE) {
            long elapsed = currentTick - curveExitTick;
            if (elapsed < CURVE_MERGE_TICKS && currentState == TrainState.CRUISING) {
                double mergeCap = maxSpeed * CURVE_MERGE_SPEED_FACTOR;
                if (currentSpeed > mergeCap) {
                    applySpeedControl(mergeCap);
                    CreateRailwayMod.aiDebug(
                        "[AI] Train {} curve-exit merge cap {} b/t (t+{})",
                        trainId.toString().substring(0, 8),
                        String.format("%.2f", mergeCap), elapsed);
                }
            } else if (elapsed >= CURVE_MERGE_TICKS) {
                curveExitTick = -1; // merge window expired
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // v1.0.5 — Ecosystem tick (FLOW_FOLLOWER + emergency follow-brake)
    // Runs in parallel with old state machine. Adds new behaviours only.
    // ═══════════════════════════════════════════════════════════════

    /**
     * Ecosystem tick — добавляет два новых поведения без конфликта со старым FSM:
     *
     *   1. FLOW_FOLLOWER: если впереди попутный поезд (AI или игрок) — едем за ним
     *      плавно вместо полного останова (старый YIELDING).
     *
     *   2. EMERGENCY_FOLLOW_BRAKE: если лидер-игрок резко тормозит — немедленно
     *      снижаем скорость (за один тик, не ждём следующего цикла старого кода).
     *
     * ВАЖНО: fsmV2.tick() НЕ вызывается здесь — состояния нового FSM вызывают
     * ctx.selfHandle.forceStop/setSpeed() внутри tick(), что конфликтует со старым
     * кодом. Вместо этого мы только читаем ObstacleProfile и применяем результат
     * через старый applySpeedControl() для полной совместимости.
     */
    private void tickEcosystemV2(ServerLevel level, long currentTick) {
        // Пропускаем если AI выключен или поезд в конфликтующих состояниях
        if (!aiEnabled || playerControlled) {
            v2InFlowFollower = false;
            v2FlowTargetSpeed = 0;
            return;
        }
        // Не применяем FLOW_FOLLOWER если поезд уже в активном манёвре
        if (currentState == TrainState.REVERSING
                || currentState == TrainState.TRAFFIC_JAM) {
            v2InFlowFollower = false;
            v2FlowTargetSpeed = 0;
            return;
        }

        // Стагерированный скан (каждые 3 тика, разброс по trainId)
        long scanOffset = (trainId.getLeastSignificantBits() & 0x3L);
        boolean shouldScan = ((currentTick + scanOffset) % 3 == 0);

        // ── 1. Заполнить контекст из уже прочитанных полей (0 доп. рефлексии) ──
        ctxV2.reset();
        ctxV2.currentTick       = currentTick;
        ctxV2.speed             = currentSpeed;
        ctxV2.maxSpeed          = maxSpeed;
        ctxV2.acceleration      = createAcceleration;
        ctxV2.playerControlled  = false;
        ctxV2.headingX          = headingX;
        ctxV2.headingZ          = headingZ;
        ctxV2.headingValid      = headingFromMovement;
        ctxV2.leadingPosition   = precisePosition;
        ctxV2.graphScanRan      = graphScanRan;
        ctxV2.spaceAvailableBehind = spaceAvailableBehind;

        // Подключить ITrainHandle (только для чтения в PerceptionEngine)
        EcosystemRegistry eco = EcosystemRegistry.getInstance();
        ctxV2.selfHandle = eco.getAiHandle(trainId);
        if (ctxV2.selfHandle == null) return;

        // ── 2. PerceptionEngine скан (только если пора сканировать) ──
        if (shouldScan) {
            try {
                ObstacleProfile profile = perceptionV2.scan(
                        ctxV2.selfHandle,
                        ctxV2,
                        eco.allHandles(),
                        routeNextHop,
                        50.0 * bufferScale);
                ctxV2.obstacleProfile = profile;
                v2LastEcoScanTick = currentTick;
            } catch (Exception e) {
                CreateRailwayMod.aiDebug("[EcoV2] scan error: {}", e.getMessage());
                return;
            }
        }

        ObstacleProfile obs = ctxV2.obstacleProfile;
        if (obs == null) {
            // Нет профиля — выход из flow-режима если был
            v2InFlowFollower = false;
            v2FlowTargetSpeed = 0;
            return;
        }

        // ── 3. FLOW_FOLLOWER ─────────────────────────────────────────────────
        // Условие: препятствие является кандидатом на following (isDeparting,
        // дистанция в нужном диапазоне, скорости сопоставимы).
        // Применяем ТОЛЬКО когда старый код был бы в CRUISING или YIELDING
        // (не WFC, не REVERSING — они уже перехвачены выше).
        if (obs.isFlowCandidate
                && (currentState == TrainState.CRUISING
                    || currentState == TrainState.ANALYZING_OBSTACLE
                    || currentState == TrainState.YIELDING)) {

            v2InFlowFollower = true;

            double leaderSpeed = obs.flowTargetSpeed;
            if (leaderSpeed < 0) leaderSpeed = 0;

            // Плавный lerp α=0.15 — не прыгаем резко
            double lerpSpeed = currentSpeed + (leaderSpeed - currentSpeed) * 0.15;
            lerpSpeed = Math.max(0, Math.min(maxSpeed, lerpSpeed));
            v2FlowTargetSpeed = lerpSpeed;

            // Применяем через старый метод — совместимо с throttle/targetSpeed
            applySpeedControl(lerpSpeed);

            // Если старый код перешёл в YIELDING — отпустить его,
            // мы уже управляем скоростью через FLOW_FOLLOWER
            if (currentState == TrainState.YIELDING) {
                forceRelease(currentTick, "v2_flow_follower");
            }

            CreateRailwayMod.aiDebug(
                    "[EcoV2] {} FLOW {} → lerp={} (leader={}, dist={}, player={})",
                    trainId.toString().substring(0, 8),
                    String.format("%.2f", currentSpeed),
                    String.format("%.2f", lerpSpeed),
                    String.format("%.2f", leaderSpeed),
                    String.format("%.1f", obs.distance),
                    obs.leaderIsPlayer ? "Y" : "N");

        } else {
            v2InFlowFollower = false;
            v2FlowTargetSpeed = 0;
        }

        // ── 4. EMERGENCY_FOLLOW_BRAKE (только когда лидер резко тормозит) ──
        // Работает независимо от FLOW_FOLLOWER — даже в обычном CRUISING,
        // если впереди поезд игрока, который внезапно тормозит.
        if (obs.hasObstacle() && (obs.isFlowCandidate || obs.isDeparting)) {
            SafetyManager safety = fsmV2.getSafetyManager();
            SafetyManager.SafetyResult verdict = safety.evaluate(ctxV2, currentSpeed);
            if (verdict.verdict() == SafetyManager.Verdict.EMERGENCY_FOLLOW_BRAKE) {
                double emergencySpeed = Math.max(0, verdict.maxAllowedSpeed);
                if (emergencySpeed < currentSpeed) {
                    // Применяем через старый applySpeedControl — нет конфликта
                    applySpeedControl(emergencySpeed);
                    CreateRailwayMod.aiDebug(
                            "[EcoV2] {} EMERGENCY_BRAKE → {:.2f}b/t (leader decel)",
                            trainId.toString().substring(0, 8),
                            emergencySpeed);
                }
            }
        } else {
            // Лидер сменился или нет лидера — сбрасываем историю скоростей
            fsmV2.getSafetyManager().resetLeaderTracking();
        }
    }


    // ─── Data reading from Create Mod ───

    private void updateTrainData(ServerLevel level) {
        if (createTrainRef == null || !reflectionInitialized)
            return;

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

            // ── v5: MaxSpeed watermark ──
            // Create's targetSpeed goes to 0 when stopping (Navigation.java:278).
            // Using it as maxSpeed breaks ALL braking distance calculations.
            // Instead: track the highest speed ever observed (watermark).
            // Also read throttle for better proportional control.
            if (currentSpeed > maxSpeedWatermark) {
                maxSpeedWatermark = currentSpeed;
            }
            if (throttleField != null) {
                createThrottle = throttleField.getDouble(createTrainRef);
            }
            // maxSpeed = watermark (always valid, never 0)
            this.maxSpeed = maxSpeedWatermark;

            // Read derailed flag
            if (derailedField != null) {
                this.derailed = derailedField.getBoolean(createTrainRef);
            }

            // Detect if a player is manually controlling this train.
            // Primary: Train.manualTick=true (set when player presses WASD).
            //   NOTE: manualTick resets to false EVERY TICK by Create's Train.tick().
            //   So it's only true for 1 tick — unreliable as sole indicator.
            // Secondary: ScheduleRuntime.paused=true — set by Create when player
            //   takes manual control via Train Controls. Persists across ticks.
            // Tertiary: scan carriage entity passengers for ServerPlayer.
            this.playerControlled = false;
            if (manualTickField != null) {
                try {
                    this.playerControlled = manualTickField.getBoolean(createTrainRef);
                } catch (Exception ignored) {}
            }
            // Check ScheduleRuntime.paused — most reliable indicator of player control
            if (!this.playerControlled && runtimeField != null) {
                try {
                    Object runtime = runtimeField.get(createTrainRef);
                    if (runtime != null) {
                        Field pausedF = findField(runtime.getClass(), "paused");
                        if (pausedF != null && pausedF.getBoolean(runtime)) {
                            this.playerControlled = true;
                        }
                    }
                } catch (Exception ignored) {}
            }
            // Fallback: check for riding players even if above methods failed
            if (!this.playerControlled) {
                this.playerControlled = detectPlayerRiding(level);
            }

            // Read navigation distance
            if (navigationField != null) {
                Object nav = navigationField.get(createTrainRef);
                if (nav != null) {
                    Field distField = findField(nav.getClass(), "distanceToDestination");
                    if (distField != null) {
                        this.distToDestination = distField.getDouble(nav);
                    }

                    // ── v5 Fix #2: Read Create's signal state ──
                    // Navigation.waitingForSignal (Pair<UUID, Boolean>) — non-null = red signal ahead
                    // Navigation.distanceToSignal — how far to the signal
                    // If Create is waiting for a signal, our AI should NOT override its stop.
                    if (navWaitingForSignalField == null) {
                        navWaitingForSignalField = findField(nav.getClass(), "waitingForSignal");
                        navDistToSignalField = findField(nav.getClass(), "distanceToSignal");
                    }
                    if (navWaitingForSignalField != null) {
                        Object wfs = navWaitingForSignalField.get(nav);
                        createWaitingForSignal = (wfs != null);
                        if (createWaitingForSignal && navDistToSignalField != null) {
                            createDistToSignal = navDistToSignalField.getDouble(nav);
                        } else {
                            createDistToSignal = Double.MAX_VALUE;
                        }
                    }
                }
            }

            // ── v5 Fix #3: Read Create's actual acceleration rate ──
            if (accelerationMethod != null) {
                try {
                    Object result = accelerationMethod.invoke(createTrainRef);
                    if (result instanceof Number) {
                        createAcceleration = ((Number) result).doubleValue();
                    }
                } catch (Exception ignored) {}
            }

            // ── v5 Fix #5: Read TravellingPoint.blocked ──
            // blocked = true means the train hit end of track.
            // Create zeros speed and cancels nav. AI should not fight this.
            createBlocked = false;
            if (carriagesField != null) {
                try {
                    Object carriagesList = carriagesField.get(createTrainRef);
                    if (carriagesList instanceof java.util.List<?>) {
                        java.util.List<?> carriages = (java.util.List<?>) carriagesList;
                        if (!carriages.isEmpty()) {
                            Object firstCarriage = carriages.get(0);
                            // Carriage.getLeadingPoint() → TravellingPoint
                            try {
                                java.lang.reflect.Method getLP = firstCarriage.getClass().getMethod("getLeadingPoint");
                                Object leadTP = getLP.invoke(firstCarriage);
                                if (leadTP != null) {
                                    Field blockedF = findField(leadTP.getClass(), "blocked");
                                    if (blockedF != null) {
                                        createBlocked = blockedF.getBoolean(leadTP);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }

            // ── v5 Fix #6: Read stress array ──
            // If max(stress) > 2.0, train is under strain → reduce speed to prevent derail.
            if (stressField != null) {
                try {
                    Object stressObj = stressField.get(createTrainRef);
                    if (stressObj instanceof double[]) {
                        createStress = (double[]) stressObj;
                    }
                } catch (Exception ignored) {}
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
                            if (trainDisplayName.length() > 24)
                                trainDisplayName = trainDisplayName.substring(0, 24);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // Read carriages and extract position
            if (carriagesField != null) {
                List<?> carriages = (List<?>) carriagesField.get(createTrainRef);
                this.carriageCount = carriages != null ? carriages.size() : 1;

                // ── Precise train length via getTotalLength() (v1.0.3) ──
                // Create's Train.getTotalLength() accounts for bogeySpacing,
                // carriageSpacing, and wheelPointSpacing — much more accurate
                // than our old carriageCount*6 estimate.
                if (getTotalLengthMethod == null && createTrainRef != null) {
                    getTotalLengthMethod = findMethod(createTrainRef.getClass(), "getTotalLength");
                }
                if (getTotalLengthMethod != null) {
                    try {
                        this.trainLength = (int) getTotalLengthMethod.invoke(createTrainRef);
                    } catch (Exception e) {
                        this.trainLength = this.carriageCount * 6; // fallback
                    }
                } else {
                    // Fallback: use carriageSpacing list if available
                    int spacingTotal = 0;
                    try {
                        Field spacingField = findField(createTrainRef.getClass(), "carriageSpacing");
                        if (spacingField != null) {
                            List<?> spacing = (List<?>) spacingField.get(createTrainRef);
                            if (spacing != null) {
                                for (Object s : spacing) {
                                    if (s instanceof Number n) spacingTotal += n.intValue();
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                    this.trainLength = Math.max(6, spacingTotal + this.carriageCount * 3);
                }

                if (carriages != null && !carriages.isEmpty()) {
                    BlockPos pos = extractPositionFromCarriage(carriages.get(0), level);
                    if (pos != null) {
                        this.currentPosition = pos;
                    } else {
                        CreateRailwayMod.aiWarn("[AI] Train {} position is NULL — all extraction methods failed",
                                trainId.toString().substring(0, 8));
                    }
                }
            } else {
                CreateRailwayMod.aiWarn("[AI] Train {} has no carriagesField — reflection incomplete",
                        trainId.toString().substring(0, 8));
            }

            // Update heading vector from movement
            updateHeading();

            // Read TrackGraph data for same-track detection
            readTrackGraphData();

            // ── Register in train registries (v1.0.5) ──
            // Both registries auto-filter: StoppedTrainRegistry keeps stopped/crashed,
            // MovingTrainRegistry keeps moving. Together they cover ALL trains.
            long gameTick = lastKnownLevel != null ? lastKnownLevel.getGameTime() : 0;
            StoppedTrainRegistry.getInstance().update(
                    trainId, currentSpeed, precisePosition, currentPosition,
                    trainLength, headingX, headingZ, derailed, gameTick, myGraph);
            MovingTrainRegistry.getInstance().update(
                    trainId, currentSpeed, precisePosition, currentPosition,
                    trainLength, headingX, headingZ, derailed, gameTick, myGraph);

        } catch (Exception e) {
            CreateRailwayMod.aiWarn("[AI] Data read error for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /** Recompute normalized heading unit vector from position delta. */
    private void updateHeading() {
        boolean movedThisTick = false;

        // Prefer precise Vec3 positions for sub-block accuracy
        if (previousPrecisePosition != null && precisePosition != null) {
            double dx = precisePosition.x - previousPrecisePosition.x;
            double dz = precisePosition.z - previousPrecisePosition.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01) {
                this.headingX = dx / len;
                this.headingZ = dz / len;
                this.headingFromMovement = true;
                movedThisTick = true;
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
                movedThisTick = true;
            }
        }

        // ── Curve tangent correction (v1.0.2) ──
        // On curved edges, position-delta heading gives a CHORD direction that diverges
        // from the actual track tangent. This causes the beam scanner to "cut across"
        // the inside of the curve and falsely detect trains on adjacent parallel tracks,
        // or miss trains on the same track that have rounded the bend.
        //
        // v1.0.2 FIX: apply correction ALWAYS when on a curve, not just when moving.
        // This ensures stopped trains on curves still have correct heading for
        // obstacle detection. Previously, a stopped train on a curve kept its last
        // movement heading which could be a stale chord direction.
        if (myLeadingEdge != null && myLeadingNode2 != null) {
            boolean isCurve = false;
            if (isTurnMethod != null) {
                try {
                    isCurve = Boolean.TRUE.equals(isTurnMethod.invoke(myLeadingEdge));
                } catch (Exception ignored) {
                }
            }
            if (isCurve) {
                // Get edge target node position
                double[] n2xz = getNodeXZ(myLeadingNode2);
                Vec3 refPos = precisePosition != null ? precisePosition
                        : (currentPosition != null ? new Vec3(currentPosition.getX() + 0.5,
                                0, currentPosition.getZ() + 0.5) : null);
                if (n2xz != null && refPos != null) {
                    double toDx = n2xz[0] + 0.5 - refPos.x;
                    double toDz = n2xz[1] + 0.5 - refPos.z;
                    double toLen = Math.sqrt(toDx * toDx + toDz * toDz);
                    if (toLen > 0.5) { // Lowered from 1.5 to catch tight curves
                        double trackHX = toDx / toLen;
                        double trackHZ = toDz / toLen;
                        if (movedThisTick) {
                            // v5: Moving on curve: 95% track geometry, 5% movement delta.
                            // Create's TravellingPoint has known inaccuracy on Bezier curves
                            // (source: TravellingPoint.java:213 FIXME comment). Movement delta
                            // is noisy — trust edge geometry almost entirely.
                            this.headingX = headingX * 0.05 + trackHX * 0.95;
                            this.headingZ = headingZ * 0.05 + trackHZ * 0.95;
                        } else {
                            // Stopped on curve: use PURE edge direction
                            // This is crucial — a stopped train must still "see" ahead
                            // along the track, not along a stale chord vector
                            this.headingX = trackHX;
                            this.headingZ = trackHZ;
                        }
                        double bLen = Math.sqrt(headingX * headingX + headingZ * headingZ);
                        if (bLen > 0.01) {
                            headingX /= bLen;
                            headingZ /= bLen;
                        }
                        this.headingFromMovement = true; // Mark as valid
                    }
                }
            }
        }

        // If heading is still unknown but train has a direction flag, use ±Z as default
        if (headingX == 0 && headingZ == 0) {
            this.headingZ = direction ? 1.0 : -1.0;
            this.headingFromMovement = false;
        }
        // When stopped on STRAIGHT track, KEEP the last movement-based heading so the
        // directional filter stays active. This prevents false detections of trains on
        // other tracks. Only trains that have NEVER moved remain omnidirectional.
    }

    /**
     * v5: Update train registries without running full AI logic.
     * Called from early-exit guards so other trains can still detect us.
     */
    private void updateRegistries(long currentTick) {
        long gameTick = lastKnownLevel != null ? lastKnownLevel.getGameTime() : currentTick;
        StoppedTrainRegistry.getInstance().update(
                trainId, currentSpeed, precisePosition, currentPosition,
                trainLength, headingX, headingZ, derailed, gameTick, myGraph);
        MovingTrainRegistry.getInstance().update(
                trainId, currentSpeed, precisePosition, currentPosition,
                trainLength, headingX, headingZ, derailed, gameTick, myGraph);
    }

    /**
     * Read track-graph data from Create's Train object:
     * - Train.graph (TrackGraph) — for graph identity comparison
     * - Train.occupiedSignalBlocks (Map<UUID, UUID>) — signal block group IDs
     * - TravellingPoint.edge from leading/trailing points — for direct edge
     * comparison
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

            // Fallback: restore last-known edge/node data for stopped trains.
            // When Create stops updating TravellingPoint (train is stationary),
            // the edge/node fields become null. BFS scanner then fails entirely
            // on curves, causing other trains to not see the stopped train.
            if (myLeadingEdge == null && myLastLeadingEdge != null) {
                myLeadingEdge = myLastLeadingEdge;
            }
            if (myLeadingNode1 == null && myLastLeadingNode1 != null) {
                myLeadingNode1 = myLastLeadingNode1;
            }
            if (myLeadingNode2 == null && myLastLeadingNode2 != null) {
                myLeadingNode2 = myLastLeadingNode2;
            }
        } catch (Exception e) {
            CreateRailwayMod.aiDebug("[AI] TrackGraph read failed for {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
        // Update the beam width based on whether our leading edge is a curve or
        // straight
        updateBeamTolerance();
        // Build route next-hop map for BFS side-track filtering
        updateRouteNextHop();
    }

    /**
     * Dynamically set currentBeamTolerance based on TrackEdge.isTurn():
     * - Straight track → 1.1 blocks (tight, only our rail center)
     * - Curved track → 2.5 blocks (wider, parallel tracks can converge on bends)
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
            // ── Curve-exit lane merge detection ──
            // When we leave a curved edge and reach a straight one, flag it so
            // tick() can apply a brief speed cap, giving main-line trains time to see us.
            if (wasOnCurvedEdge && !isCurve && curveExitTick < 0) {
                curveExitTick = Long.MAX_VALUE; // will be set to real tick in tick()
            }
            wasOnCurvedEdge = isCurve;
        } catch (Exception e) {
            currentBeamTolerance = BEAM_LATERAL_TOLERANCE;
        }
    }

    /**
     * Extract TravellingPoint.edge from a Carriage via the named method
     * (getLeadingPoint/getTrailingPoint).
     */
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
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Get the XZ world-position of a Create TrackNode.
     * TrackNode.getLocation() returns a TrackNodeLocation which extends BlockPos
     * (extends Vec3i).
     * Returns null on any reflection failure.
     */
    private double[] getNodeXZ(Object node) {
        if (node == null)
            return null;
        try {
            if (nodeGetLocationMethod == null)
                nodeGetLocationMethod = findMethod(node.getClass(), "getLocation");
            if (nodeGetLocationMethod == null)
                return null;
            Object loc = nodeGetLocationMethod.invoke(node);
            if (loc instanceof net.minecraft.core.Vec3i v)
                return new double[] { v.getX(), v.getZ() };
        } catch (Exception ignored) {
        }
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
            if (getPoint == null)
                return;
            Object tp = getPoint.invoke(carriage);
            if (tp == null)
                return;

            // Lazily cache TravellingPoint field references
            if (tpNode1Field == null) {
                tpNode1Field = findField(tp.getClass(), "node1");
                tpNode2Field = findField(tp.getClass(), "node2");
                tpPositionField = findField(tp.getClass(), "position");
            }

            Field edgeField = findField(tp.getClass(), "edge");
            if (edgeField != null)
                myLeadingEdge = edgeField.get(tp);
            if (tpNode1Field != null)
                myLeadingNode1 = tpNode1Field.get(tp);
            if (tpNode2Field != null)
                myLeadingNode2 = tpNode2Field.get(tp);
            if (tpPositionField != null)
                myLeadingEdgePos = tpPositionField.getDouble(tp);
        } catch (Exception ignored) {
        }

        // Persist non-null data so same-track detection still works after Create clears
        // TravellingPoint.
        if (myLeadingEdge != null)
            myLastLeadingEdge = myLeadingEdge;
        if (myLeadingNode1 != null)
            myLastLeadingNode1 = myLeadingNode1;
        if (myLeadingNode2 != null)
            myLastLeadingNode2 = myLeadingNode2;

        // ── Right-of-way: compute whether we travel against the edge's natural
        // direction ──
        // Use currentPosition → node2 direction (NOT node1→node2) so the angle is
        // correct
        // even mid-curve: on tight bends the node1→node2 chord diverges from the
        // tangent.
        // "currentPos → node2" always points where we're headed, stable across all arc
        // positions.
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
     * Carriage.anyAvailableEntity() → CarriageContraptionEntity (Entity) →
     * position()
     * Carriage.entities (Map<ResourceKey, DimensionalCarriageEntity>) →
     * dce.positionAnchor (Vec3)
     * Carriage.bogeys (Couple) → CarriageBogey.getAnchorPosition() → Vec3
     *
     * Priority order:
     * 0. anyAvailableEntity() → Entity → position() [BEST — actual entity position]
     * 1. entities map → DimensionalCarriageEntity.positionAnchor [reliable even
     * when entity unloaded]
     * 2. bogeys → CarriageBogey.getAnchorPosition() [fallback via bogey anchor]
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
            CreateRailwayMod.aiDebug("[AI] anyAvailableEntity() failed for {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }

        // ─── Approach 1: Carriage.entities (Map) →
        // DimensionalCarriageEntity.positionAnchor (Vec3) ───
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
            CreateRailwayMod.aiDebug("[AI] entities.positionAnchor failed for {}: {}",
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
            CreateRailwayMod.aiDebug("[AI] bogey.getAnchorPosition() failed for {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }

        return null;
    }

    // ─── Obstacle Detection ───

    /**
     * TrackGraph-based forward scanner.
     *
     * Detection strategy (from Create's own collision approach):
     * 1. PRIMARY: Compare occupiedSignalBlocks group UUIDs between trains.
     * If two trains share a signal block group, they're on the same track segment.
     * 2. SECONDARY: Compare TravellingPoint.edge (object identity) — trains on
     * the same TrackEdge are definitely on the same rail.
     * 3. FALLBACK: Very narrow 1.1-block beam along heading for unsignalled track
     * or when TrackGraph data is unavailable.
     *
     * Trains with TRAFFIC_JAM status on a DIFFERENT track are always ignored.
     */
    private TrainAIController scanForObstacleController(ServerLevel level) {
        if (currentPosition == null)
            return null;

        long currentTick = level.getGameTime();

        // Expire bypass mode by time
        if (bypassingTrainId != null && currentTick >= bypassModeUntilTick) {
            CreateRailwayMod.aiLog("[AI] Train {} bypass-ignore expired for {}",
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
            if (graphHitIsDeparting && graphDistanceToObstacle > BUFFER_CLEARANCE * bufferScale) {
                CreateRailwayMod.aiDebug(
                        "[AI] Train {} sees departing train {} (dist={}) — skipping brake",
                        trainId.toString().substring(0, 8),
                        graphHit.trainId.toString().substring(0, 8),
                        (int) graphDistanceToObstacle);
                return null;
            }
            // Departing but within BUFFER_CLEARANCE — treat as normal obstacle
            if (graphHitIsDeparting)
                graphHitIsDeparting = false;
            this.obstaclePosition = graphHit.currentPosition;
            this.obstacleTrainId = graphHit.trainId;

            // ── Proactive detour ──
            // BFS found an obstacle on the primary route AND a free alternate branch.
            // Cancel navigation so Create re-pathfinds through the free route.
            //
            // Backoff (v1.0.3): if the train doesn't move after the cancel (Create failed
            // to route via the branch — e.g. it's too short or leads to a wrong
            // destination),
            // the cooldown doubles each retry: 200 → 400 → 800 ticks.
            // After 3 failed retries the detour is abandoned and normal graduated braking
            // takes over, letting the YIELDING → REVERSING escalation chain handle it.
            if (graphFoundFreeDetour && graphDistanceToObstacle > 30.0 && currentPosition != null) {
                // Reset retry counter if the train has actually moved since last attempt
                if (proactiveDetourPos != null
                        && distanceBetweenPos(currentPosition, proactiveDetourPos) >= 3.0) {
                    proactiveDetourRetries = 0;
                }
                long detourCooldown = (long) REROUTE_COOLDOWN_TICKS << Math.min(3, proactiveDetourRetries);
                boolean cooldownMet = proactiveDetourTick < 0
                        || (level.getGameTime() - proactiveDetourTick) >= detourCooldown;
                if (cooldownMet && proactiveDetourRetries < 3) {
                    // Count as a retry only when position hasn't changed (detour didn't work)
                    if (proactiveDetourPos != null
                            && distanceBetweenPos(currentPosition, proactiveDetourPos) < 3.0) {
                        proactiveDetourRetries++;
                    }
                    proactiveDetourTick = level.getGameTime();
                    proactiveDetourPos = currentPosition;
                    lastRerouteTick = level.getGameTime();
                    cancelCreateNavigation();
                    CreateRailwayMod.aiLog("[AI] Train {} proactive detour"
                            + " (blocked={}, free alt branch, dist={}b, retry={})",
                            trainId.toString().substring(0, 8),
                            graphHit.trainId.toString().substring(0, 8),
                            (int) graphDistanceToObstacle, proactiveDetourRetries);
                    // Do NOT set bypassingTrainId — see original comment above.
                    return null;
                }
                // Detour exhausted — fall through to normal braking (safe graduated stop)
                if (proactiveDetourRetries >= 3) {
                    CreateRailwayMod.aiDebug(
                            "[AI] Train {} detour gave up after {} retries — normal braking",
                            trainId.toString().substring(0, 8), proactiveDetourRetries);
                }
            }
        }

        // ── StoppedTrainRegistry scan (v1.0.5) ──
        // The registry is the AUTHORITATIVE source for stopped/crashed trains.
        // Graph-walk BFS misses them (Create clears TravellingPoint data when stopped).
        // Beam scan misses them on curves (heading doesn't follow track geometry).
        // Registry scan: O(n) over stopped trains only, with distance + Y filter.
        // Only runs when graphWalkScan didn't find an obstacle (it's the fallback).
        if (graphHit == null && (precisePosition != null || currentPosition != null)) {
            Vec3 myPos = precisePosition != null ? precisePosition
                    : Vec3.atCenterOf(currentPosition);
            List<StoppedTrainRegistry.StoppedTrain> stoppedNearby =
                    StoppedTrainRegistry.getInstance().getNearby(
                            myPos, range, trainId, myGraph);

            for (StoppedTrainRegistry.StoppedTrain stopped : stoppedNearby) {
                // Skip bypassed train
                if (bypassingTrainId != null && stopped.trainId.equals(bypassingTrainId))
                    continue;

                // Y-level filter (ignore trains on different elevations)
                Vec3 stPos = stopped.precisePosition != null ? stopped.precisePosition
                        : Vec3.atCenterOf(stopped.blockPosition);
                if (Math.abs(myPos.y - stPos.y) > 10) continue;

                // Direction filter: only detect trains AHEAD of us (dot > -0.3)
                // Generous threshold catches trains at wide angles (curves)
                if (headingX != 0 || headingZ != 0) {
                    double toX = stPos.x - myPos.x;
                    double toZ = stPos.z - myPos.z;
                    double toLen = Math.sqrt(toX * toX + toZ * toZ);
                    if (toLen > 0.5) {
                        double dot = (toX / toLen) * headingX + (toZ / toLen) * headingZ;
                        if (dot < -0.3) continue; // clearly behind us
                    }
                }

                // Found a stopped train ahead — get its controller for full data
                TrainAIManager regMgr = TrainAIManager.getInstance();
                if (regMgr != null) {
                    TrainAIController stoppedCtrl = regMgr.getController(stopped.trainId);
                    if (stoppedCtrl != null) {
                        double edgeDist = distanceBetween(this, stoppedCtrl);
                        graphDistanceToObstacle = edgeDist;
                        graphScanActive = true;
                        obstaclePosition = stopped.blockPosition;
                        obstacleTrainId = stopped.trainId;
                        CreateRailwayMod.aiDebug(
                                "[AI] Train {} found STOPPED {} via registry (gap={}b, derailed={})",
                                trainId.toString().substring(0, 8),
                                stopped.trainId.toString().substring(0, 8),
                                String.format("%.1f", edgeDist),
                                stopped.derailed);
                        return stoppedCtrl;
                    }
                }
            }
        }

        // ── MovingTrainRegistry scan (v1.0.5) ──
        // Additional safety layer for MOVING trains that BFS/beam missed on curves.
        // Uses the registry's TTC (Time-To-Collision) prediction for closing trains.
        // Only fires when graph walk didn't find anything (doesn't override BFS).
        if (graphHit == null && (precisePosition != null || currentPosition != null)) {
            Vec3 myPos = precisePosition != null ? precisePosition
                    : Vec3.atCenterOf(currentPosition);
            List<MovingTrainRegistry.MovingTrain> movingNearby =
                    MovingTrainRegistry.getInstance().getNearby(
                            myPos, range, trainId, myGraph);

            for (MovingTrainRegistry.MovingTrain moving : movingNearby) {
                // Skip bypassed train
                if (bypassingTrainId != null && moving.trainId.equals(bypassingTrainId))
                    continue;

                Vec3 mtPos = moving.precisePosition != null ? moving.precisePosition
                        : Vec3.atCenterOf(moving.blockPosition);

                // Y-level filter
                if (Math.abs(myPos.y - mtPos.y) > 10) continue;

                // Direction filter: must be roughly AHEAD (dot > 0.1)
                // Tighter than stopped registry because moving trains have fresh heading
                if (headingX != 0 || headingZ != 0) {
                    double toX = mtPos.x - myPos.x;
                    double toZ = mtPos.z - myPos.z;
                    double toLen = Math.sqrt(toX * toX + toZ * toZ);
                    if (toLen > 0.5) {
                        double dot = (toX / toLen) * headingX + (toZ / toLen) * headingZ;
                        if (dot < 0.1) continue; // not ahead of us
                    }
                }

                // Get the controller for full distance math
                TrainAIManager movMgr = TrainAIManager.getInstance();
                if (movMgr == null) continue;
                TrainAIController movingCtrl = movMgr.getController(moving.trainId);
                if (movingCtrl == null) continue;

                double edgeDist = distanceBetween(this, movingCtrl);

                // Only trigger if close enough to matter (within braking distance)
                double brakeDist = (currentSpeed * currentSpeed) / (2 * getDecelRate()) + BUFFER_CRITICAL * bufferScale;
                if (edgeDist > brakeDist && edgeDist > 30) continue;

                // TTC check: only brake if we're actually closing on this train
                MovingTrainRegistry.MovingTrain myMoving =
                        MovingTrainRegistry.getInstance().get(trainId);
                if (myMoving != null) {
                    int ttc = MovingTrainRegistry.estimateTTC(myMoving, moving);
                    if (ttc > 100) continue; // > 5 seconds TTC — not urgent
                }

                graphDistanceToObstacle = edgeDist;
                graphScanActive = true;
                obstaclePosition = moving.blockPosition;
                obstacleTrainId = moving.trainId;
                CreateRailwayMod.aiDebug(
                        "[AI] Train {} found MOVING {} via registry (gap={}b, speed={})",
                        trainId.toString().substring(0, 8),
                        moving.trainId.toString().substring(0, 8),
                        String.format("%.1f", edgeDist),
                        String.format("%.2f", moving.speed));
                return movingCtrl;
            }
        }

        // ── Ultra-close emergency scan (runs REGARDLESS of graphScanRan) ──
        // When a train is stopped on a curve, Create stops updating TravellingPoint
        // → myLeadingEdge = null → graphScanRan = false → normal proximity scan is
        // skipped entirely → trains can phase into each other around bends.
        //
        // This unconditional scan fires on ANY train within BUFFER_CRITICAL * 2 (12
        // blocks) that is clearly ahead of us.
        if (currentPosition != null) {
            TrainAIManager ultraMgr = TrainAIManager.getInstance();
            if (ultraMgr != null) {
                for (TrainAIController ultraOther : ultraMgr.getAllControllers()) {
                    if (ultraOther.trainId.equals(this.trainId))
                        continue;
                    if (bypassingTrainId != null && ultraOther.trainId.equals(bypassingTrainId))
                        continue;
                    if (ultraOther.currentPosition == null)
                        continue;
                    int dy = Math.abs(currentPosition.getY() - ultraOther.currentPosition.getY());
                    if (dy > 10)
                        continue;
                    double physDist = distanceBetween(this, ultraOther);
                    if (physDist > BUFFER_CRITICAL * bufferScale * 2)
                        continue; // only within 12 blocks
                    // Must not be clearly BEHIND us
                    if (headingFromMovement) {
                        double tox = ultraOther.currentPosition.getX() - currentPosition.getX();
                        double toz = ultraOther.currentPosition.getZ() - currentPosition.getZ();
                        double dot = tox * headingX + toz * headingZ;
                        if (dot < -2.0)
                            continue; // clearly behind
                        // ── FIX: player-controlled trains skip perpDist filter ──
                        // Player trains may have no graph data when they just sat down.
                        // At ultra-close range we ALWAYS treat them as an obstacle.
                        if (!ultraOther.playerControlled) {
                            double perpDist = Math.abs(tox * headingZ - toz * headingX);
                            if (perpDist > 4.0) continue; // clearly on a parallel track
                        }
                    }
                    // Same-track check: skip for player trains (no graph data yet)
                    if (!ultraOther.playerControlled) {
                        boolean ultraSame = isOnSameTrack(ultraOther);
                        boolean ultraNoData = !hasSufficientTrackData() || !ultraOther.hasSufficientTrackData();
                        if (!ultraSame && !ultraNoData)
                            continue; // different tracks confirmed — skip
                    }
                    graphDistanceToObstacle = physDist;
                    graphScanActive = true;
                    obstaclePosition = ultraOther.currentPosition;
                    obstacleTrainId = ultraOther.trainId;
                    CreateRailwayMod.aiWarn(
                            "[AI] Train {} ULTRA-CLOSE emergency stop ({} blocks) near {} (player={})",
                            trainId.toString().substring(0, 8),
                            String.format("%.1f", physDist),
                            ultraOther.trainId.toString().substring(0, 8),
                            ultraOther.playerControlled);
                    return ultraOther;
                }
            }
        }

        // ── Post-BFS proximity safety scan ──
        // graphWalkScan follows routeNextHop and edge-identity. In two scenarios it
        // can miss a real obstacle:
        // a) Edge-transition ticks: train slips into range before its edge data updates
        // b) Stopped trains (JAM/WFC): Create stops updating TravellingPoint when the
        // train is stationary, so myLeadingEdge becomes null → BFS never finds them.
        //
        // Detection ranges (both require isOnSameTrack or noData AND dot > 0.3):
        // • Stopped obstacles (JAM/WFC/YIELDING at speed≈0): 15 blocks
        // • Moving obstacles (edge-transition): 5 blocks (BUFFER_CRITICAL)
        if (graphScanRan) {
            TrainAIManager proxMgr = TrainAIManager.getInstance();
            if (proxMgr != null && currentPosition != null) {
                for (TrainAIController other : proxMgr.getAllControllers()) {
                    if (other.trainId.equals(this.trainId))
                        continue;
                    if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId))
                        continue;
                    if (other.currentPosition == null)
                        continue;
                    int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                    if (dy > 10)
                        continue;
                    double physDist = distanceBetween(this, other);
                    boolean otherDerailed = other.derailed;
                    // ── FIX: player trains always count as "stopped" obstacle ──
                    // Even if speed > 0, player may brake instantly — treat conservatively
                    boolean otherStopped = other.playerControlled || otherDerailed
                            || ((other.currentState == TrainState.TRAFFIC_JAM
                                    || other.currentState == TrainState.WAIT_FOR_CLEARANCE
                                    || other.currentState == TrainState.YIELDING
                                    || other.currentState == TrainState.CRUISING)
                                    && other.currentSpeed < 0.02);
                    // Player trains get extended detection range (20 blocks, same as derailed)
                    double proximityThreshold = other.playerControlled ? 20.0
                            : (otherDerailed ? 20.0 : (otherStopped ? 15.0 * bufferScale : BUFFER_CRITICAL * bufferScale));
                    if (physDist > proximityThreshold)
                        continue;
                    // ── FIX: player trains bypass same-track filter ──
                    // Player may not have graph data (just sat down), skip isOnSameTrack
                    boolean sameTrackConfirmed;
                    boolean noData;
                    if (other.playerControlled) {
                        // Treat player as always on same track within detection range
                        sameTrackConfirmed = true;
                        noData = false;
                    } else {
                        sameTrackConfirmed = isOnSameTrack(other);
                        noData = otherDerailed || !hasSufficientTrackData() || !other.hasSufficientTrackData();
                        if (!sameTrackConfirmed && !noData)
                            continue;
                    }
                    // Heading and lateral checks
                    if (headingFromMovement) {
                        double tox = other.currentPosition.getX() - currentPosition.getX();
                        double toz = other.currentPosition.getZ() - currentPosition.getZ();
                        double dot = tox * headingX + toz * headingZ;
                        double minDot = (otherStopped && sameTrackConfirmed) ? 0.0 : 0.3;
                        if (dot < minDot)
                            continue;
                        // For non-player trains without confirmed track: perp filter
                        if (!other.playerControlled && !sameTrackConfirmed && noData) {
                            double perpDist = Math.abs(tox * headingZ - toz * headingX);
                            double perpLimit = otherDerailed ? 3.0 : 2.0;
                            if (perpDist > perpLimit)
                                continue; // too far to the side — parallel track
                        }
                    }
                    graphDistanceToObstacle = physDist;
                    graphScanActive = true;
                    obstaclePosition = other.currentPosition;
                    obstacleTrainId = other.trainId;
                    CreateRailwayMod.aiLog(
                            "[AI] Train {} proximity hit {} (dist={}, player={}, stopped={})",
                            trainId.toString().substring(0, 8),
                            other.trainId.toString().substring(0, 8),
                            String.format("%.1f", physDist), other.playerControlled, otherStopped);
                    return other;
                }
            }
            // No closer proximity hit — return graph-walk result
            return graphHit;
        }


        // ── FALLBACK: Signal blocks + edge identity + beam ──
        // Used ONLY when graphWalkScan had no graph data (train just spawned, no
        // TravellingPoint yet).
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null)
            return null;

        TrainAIController closest = null;
        double closestDist = Double.MAX_VALUE;

        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId))
                continue;
            if (other.currentPosition == null)
                continue;

            // During bypass mode, completely ignore the train we're going around
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId))
                continue;

            // Different TrackGraph = trains on completely separate railway networks → skip
            if (myGraph != null && other.myGraph != null && myGraph != other.myGraph)
                continue;

            double dist = distanceBetween(this, other);
            if (dist > range)
                continue;

            // Ignore trains on completely different vertical levels
            int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
            if (dy > 10)
                continue;

            // ── Same-track check ──
            boolean sameTrack = isOnSameTrack(other);

            // If BOTH trains have TrackGraph data (signal blocks or edge), the result
            // of isOnSameTrack() is authoritative — skip the beam fallback entirely.
            // The beam fallback only runs when one of the trains has NO graph data yet.
            boolean useBeamFallback = !hasSufficientTrackData() || !other.hasSufficientTrackData();

            // Parallel track (different tracks, data is reliable) — ignore completely.
            if (!sameTrack && !useBeamFallback)
                continue;

            // Ignore TRAFFIC_JAM trains on a different track — not our concern.
            if (!sameTrack && other.currentState == TrainState.TRAFFIC_JAM)
                continue;

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
                if (dot < minDot)
                    continue; // train is clearly behind us

                if (!sameTrack) {
                    // Beam fallback: only when TrackGraph data is absent
                    // Width adapts: 1.1 on straight, 2.5 on curves (TrackEdge.isTurn())
                    double perpDist = Math.abs(toOtherX * headingZ - toOtherZ * headingX);
                    if (perpDist > currentBeamTolerance)
                        continue;
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
     * Check if another train is on the SAME track as us using Create's TrackGraph
     * data.
     *
     * Signal blocks are used ONLY for EXCLUSION (disjoint groups = different
     * tracks).
     * A signal block can span a junction or multiple parallel sections, so two
     * trains
     * sharing the same signal UUID does NOT guarantee they're on the same physical
     * rail.
     *
     * Positive same-track detection uses:
     * a) TrackEdge identity — trains on the exact same rail segment share the edge
     * object.
     * b) Beam fallback (1.1 blocks in scanner) — catches trains on the same
     * straight run
     * that are too far apart to share an edge.
     */
    public boolean isOnSameTrack(TrainAIController other) {
        // TrackGraph identity: different graph objects = completely separate railway
        // networks.
        // This is the fastest exclusion check — O(1) object identity comparison.
        if (myGraph != null && other.myGraph != null && myGraph != other.myGraph)
            return false;

        // EXCLUSION: if both have signal data and their groups are disjoint,
        // the trains are in different signal blocks → confirmed different tracks.
        // EXCEPTION: if the other train has no edge/node data (stopped, Create cleared
        // TravellingPoint), its signal group may differ even though it is ON THE SAME
        // physical rail (different signal block ahead on the same line). Skip exclusion
        // for stopped trains with no live edge/node data — fall through to last-known
        // check.
        boolean otherHasLivePositionalData = other.myLeadingEdge != null || other.myTrailingEdge != null
                || other.myLeadingNode1 != null || other.myLeadingNode2 != null;
        if (otherHasLivePositionalData
                && !myOccupiedSignalGroups.isEmpty() && !other.myOccupiedSignalGroups.isEmpty()) {
            boolean anyShared = false;
            for (UUID g : myOccupiedSignalGroups) {
                if (other.myOccupiedSignalGroups.contains(g)) {
                    anyShared = true;
                    break;
                }
            }
            if (!anyShared)
                return false; // disjoint signal blocks = different tracks
            // Shared group but a block can span parallel tracks — don't auto-confirm.
            // Fall through to the edge check below.
        }

        // Edge identity: same TrackEdge object = same physical rail.
        // Use last-known data as fallback for stopped trains whose live data is null.
        Object myEdgeLead = myLeadingEdge != null ? myLeadingEdge : myLastLeadingEdge;
        Object oEdgeLead = other.myLeadingEdge != null ? other.myLeadingEdge : other.myLastLeadingEdge;
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
        Object oN1 = other.myLeadingNode1 != null ? other.myLeadingNode1 : other.myLastLeadingNode1;
        Object oN2 = other.myLeadingNode2 != null ? other.myLeadingNode2 : other.myLastLeadingNode2;

        // Determine which node (if any) is shared
        Object sharedNode = null;
        if (myN1 != null && (myN1 == oN1 || myN1 == oN2))
            sharedNode = myN1;
        if (sharedNode == null && myN2 != null && (myN2 == oN1 || myN2 == oN2))
            sharedNode = myN2;
        // Check for MULTIPLE shared nodes (both N1 and N2 match) — a strong same-track
        // signal
        boolean multipleShared = false;
        if (sharedNode != null) {
            if (sharedNode == myN1 && myN2 != null && (myN2 == oN1 || myN2 == oN2))
                multipleShared = true;
            if (sharedNode == myN2 && myN1 != null && (myN1 == oN1 || myN1 == oN2))
                multipleShared = true;
        }
        if (multipleShared)
            return true; // share BOTH nodes → definitely same/reverse rail

        if (sharedNode != null) {
            // ── Junction guard (v1.0.2) ──
            // At crossroads (≥3 connections), perpendicular tracks CAN share a junction
            // node without being on the same physical rail (side-by-side parallel curves).
            // BUT at a SQUARE 4-way intersection (X crossroads), perpendicular trains
            // ARE on a collision course at the shared junction node.
            //
            // Distinction:
            // • Parallel side-by-side: trains heading the SAME direction, share a node
            // only because the node is a curve endpoint beside them → NOT same track
            // • X-intersection: trains heading TOWARD the junction from perpendicular
            // branches → WILL collide at the junction → treat as same track
            //
            // Detection: if BOTH trains are heading TOWARD the shared node from
            // DIFFERENT edges, they are converging and will collide at the junction.
            Map<Object, Object> nodeConns = invokeGetConnectionsFrom(sharedNode);
            if (nodeConns != null && nodeConns.size() >= 3) {
                Object myEdgeEff = myLeadingEdge != null ? myLeadingEdge : myLastLeadingEdge;
                Object oEdgeEff = other.myLeadingEdge != null ? other.myLeadingEdge : other.myLastLeadingEdge;
                if (myEdgeEff != null && oEdgeEff != null && myEdgeEff != oEdgeEff
                        && headingFromMovement && other.headingFromMovement) {
                    double hdot = headingX * other.headingX + headingZ * other.headingZ;
                    if (Math.abs(hdot) < 0.5) {
                        // Perpendicular headings — check convergence:
                        // If BOTH trains are heading TOWARD the shared node
                        // from their respective positions, they will collide there.
                        double[] snXZ = getNodeXZ(sharedNode);
                        if (snXZ != null && currentPosition != null && other.currentPosition != null) {
                            double myTox = snXZ[0] - currentPosition.getX();
                            double myToz = snXZ[1] - currentPosition.getZ();
                            double myLen = Math.sqrt(myTox * myTox + myToz * myToz);
                            double otTox = snXZ[0] - other.currentPosition.getX();
                            double otToz = snXZ[1] - other.currentPosition.getZ();
                            double otLen = Math.sqrt(otTox * otTox + otToz * otToz);
                            // Both trains heading toward the junction node (dot > 0.25)
                            // = converging = collision risk → treat as same track
                            boolean myConverging = myLen > 1.5
                                    && (myTox / myLen * headingX + myToz / myLen * headingZ) > 0.25;
                            boolean otConverging = otLen > 1.5
                                    && (otTox / otLen * other.headingX + otToz / otLen * other.headingZ) > 0.25;
                            if (myConverging && otConverging)
                                return true; // X-intersection collision risk
                        }
                        // Not converging — truly side-by-side parallel: different tracks
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
     * Returns true if this train has any TrackGraph data available (signal groups
     * or edge).
     * When both trains have data, isOnSameTrack()'s result is authoritative and the
     * beam fallback is not needed.
     */
    private boolean hasSufficientTrackData() {
        return !myOccupiedSignalGroups.isEmpty() || myLeadingEdge != null || myTrailingEdge != null;
    }

    /** Function-style wrapper for getConnectionsFrom. */
    Map<Object, Object> getConnectionsFromNode(Object node) {
        return invokeGetConnectionsFrom(node);
    }

    /** Function-style wrapper for getEdgeLength — used by CurveSpeedController. */
    double getEdgeLengthSafe(Object edge) {
        return getEdgeLength(edge);
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
     * Navigation.currentPath = List<Couple<TrackNode>> (javap-confirmed, Create
     * 6.0.1-41).
     * Each Couple(nodeA, nodeB) is one directed hop on our scheduled route.
     *
     * routeNextHop: nodeA → nodeB (IdentityHashMap, object identity).
     * In graphWalkScan BFS: when we’re at nodeA and routeNextHop has an entry,
     * only the branch leading to nodeB is followed; all other junctions are
     * ignored.
     *
     * Does NOT call getConnectionsFrom — avoids the chicken-and-egg with
     * initGraphWalkReflection.
     */
    private void updateRouteNextHop() {
        routeNextHop.clear();
        if (createTrainRef == null || navigationField == null)
            return;

        try {
            Object nav = navigationField.get(createTrainRef);
            if (nav == null)
                return;

            // Lazily discover Navigation.currentPath (confirmed field name via javap)
            if (!navPathReflInit) {
                navPathReflInit = true;
                navCurrentPathField = findField(nav.getClass(), "currentPath");
                if (navCurrentPathField == null)
                    navCurrentPathField = findField(nav.getClass(), "path");
            }
            if (navCurrentPathField == null)
                return;

            Object pathObj = navCurrentPathField.get(nav);
            if (!(pathObj instanceof List<?> pathList) || pathList.isEmpty())
                return;

            for (Object couple : pathList) {
                if (couple == null)
                    continue;

                // --- Extract nodeA (first) and nodeB (second) from Couple<TrackNode> ---
                // Primary: Couple.getFirst() / Couple.getSecond() (public API in catnip)
                Object nodeA = null, nodeB = null;
                Method mFirst = findMethod(couple.getClass(), "getFirst");
                Method mSecond = findMethod(couple.getClass(), "getSecond");
                if (mFirst != null)
                    try {
                        nodeA = mFirst.invoke(couple);
                    } catch (Exception ignored) {
                    }
                if (mSecond != null)
                    try {
                        nodeB = mSecond.invoke(couple);
                    } catch (Exception ignored) {
                    }

                // Fallback: scan every non-static field up the class hierarchy
                if (nodeA == null || nodeB == null) {
                    List<Object> vals = new ArrayList<>(2);
                    Class<?> c = couple.getClass();
                    while (c != null && c != Object.class && vals.size() < 2) {
                        for (Field f : c.getDeclaredFields()) {
                            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                                continue;
                            try {
                                f.setAccessible(true);
                                Object v = f.get(couple);
                                if (v != null && !vals.contains(v))
                                    vals.add(v);
                            } catch (Exception ignored) {
                            }
                            if (vals.size() >= 2)
                                break;
                        }
                        c = c.getSuperclass();
                    }
                    if (vals.size() >= 2) {
                        nodeA = vals.get(0);
                        nodeB = vals.get(1);
                    }
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
        if (edge == null || getLengthMethod == null)
            return 0;
        try {
            return (double) getLengthMethod.invoke(edge);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Junction look-ahead: check whether the exit segment after a junction has
     * enough
     * free space to fit our full train length.
     *
     * Walks 2 edges forward from the junction node along our route (via
     * routeNextHop),
     * accumulating edge lengths. Returns true if accumulated >= trainLength AND no
     * obstacle (edgeToTrain / reverseEdgeMap) is found in that window.
     *
     * Two-edge depth covers trains up to ~2× typical segment length (~24 blocks).
     * For
     * longer trains the check is conservative (may miss a third edge), but that is
     * safe:
     * a false positive at most causes the train to wait one extra cycle.
     */
    /**
     * Junction exit clearance check (v2).
     *
     * Returns false (= do NOT enter junction) if ANY of the following:
     *  1. Another train is physically INSIDE the junction node area (≤ 5 blocks).
     *  2. The exit edge is occupied by a forward or head-on train.
     *  3. The exit edge is too short AND the NEXT edge is also occupied
     *     (train would straddle the junction). [2-edge depth]
     *  4. The exit edge + next edge combined are still too short AND the
     *     THIRD edge is occupied. [3-edge depth for long consist safety]
     *
     * Short-but-empty exit edges are always fine: blocking on length alone
     * without an occupying train causes cascading jams on empty roads.
     */
    private boolean isJunctionExitClear(Object junctionNode,
            Object exitNode,
            Map<Object, Object> juncConns,
            IdentityHashMap<Object, TrainAIController> edgeToTrain,
            IdentityHashMap<Object, TrainAIController> reverseEdgeMap) {

        // ── Check 1: Physical junction occupancy ──
        // ONLY count STOPPED trains (speed < 0.05) within 2.5 blocks of the junction node.
        // A train passing THROUGH the junction at speed will clear in 1-2 ticks —
        // blocking on it would cause all approaching trains to halt unnecessarily.
        double[] juncXZ = getNodeXZ(junctionNode);
        if (juncXZ != null) {
            TrainAIManager juncMgr = TrainAIManager.getInstance();
            if (juncMgr != null) {
                for (TrainAIController other : juncMgr.getAllControllers()) {
                    if (other.trainId.equals(this.trainId)) continue;
                    if (other.currentPosition == null) continue;
                    if (other.currentSpeed > 0.12) continue; // passing through at speed — ignore
                    int dy = Math.abs(currentPosition != null
                            ? currentPosition.getY() - other.currentPosition.getY() : 0);
                    if (dy > 10) continue;
                    double dx = other.currentPosition.getX() - juncXZ[0];
                    double dz = other.currentPosition.getZ() - juncXZ[1];
                    if (Math.sqrt(dx * dx + dz * dz) <= 2.5) {
                        return false; // stopped train is physically blocking the junction node
                    }
                }
            }
        }

        // ── Check 2: Exit edge occupancy ──
        Object exitEdge = juncConns.get(exitNode);
        if (exitEdge == null) return true; // no edge data — assume clear
        if (edgeToTrain.containsKey(exitEdge) || reverseEdgeMap.containsKey(exitEdge))
            return false;

        // ── Check 3: Second edge (if exit too short) ──
        double firstLen = getEdgeLength(exitEdge);
        if (firstLen >= trainLength) return true; // exit long enough — done

        Map<Object, Object> exitConns = invokeGetConnectionsFrom(exitNode);
        if (exitConns == null) return true;
        Object node2 = routeNextHop.get(exitNode);
        Object edge2 = (node2 != null) ? exitConns.get(node2) : null;
        if (edge2 == null && exitConns.size() == 1)
            edge2 = exitConns.values().iterator().next();
        if (edge2 != null && (edgeToTrain.containsKey(edge2) || reverseEdgeMap.containsKey(edge2)))
            return false; // second segment occupied and exit alone too short — would straddle

        // ── Check 4: Third edge (for long consists) ──
        double secondLen = (edge2 != null) ? getEdgeLength(edge2) : 0;
        if (firstLen + secondLen >= trainLength) return true; // two edges enough

        if (node2 != null && edge2 != null) {
            Map<Object, Object> conns2 = invokeGetConnectionsFrom(node2);
            if (conns2 != null) {
                Object node3 = routeNextHop.get(node2);
                Object edge3 = (node3 != null) ? conns2.get(node3) : null;
                if (edge3 == null && conns2.size() == 1)
                    edge3 = conns2.values().iterator().next();
                if (edge3 != null && (edgeToTrain.containsKey(edge3) || reverseEdgeMap.containsKey(edge3)))
                    return false; // third segment occupied — long consist can't clear
            }
        }

        return true;
    }

    /** Invoke TrackGraph.getConnectionsFrom(node) via cached reflection. */
    @SuppressWarnings("unchecked")
    private Map<Object, Object> invokeGetConnectionsFrom(Object node) {
        if (node == null || myGraph == null || getConnectionsFromMethod == null)
            return null;
        try {
            Object result = getConnectionsFromMethod.invoke(myGraph, node);
            if (result instanceof Map)
                return (Map<Object, Object>) result;
        } catch (Exception ignored) {
        }
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
     * 1. Check our own leading edge for another train ahead of us
     * 2. BFS walk from our forward node (node2) through connected edges
     * 3. For each edge, check if any other train occupies it (edge identity)
     * 4. Sum edge lengths = accurate graph distance along rails
     *
     * Returns the controller of the nearest obstacle found, or null.
     * Sets graphDistanceToObstacle (for display) and graphScanActive.
     */
    private TrainAIController graphWalkScan(double maxDist) {
        graphDistanceToObstacle = -1;
        graphScanActive = false;
        graphScanRan = false;
        graphHitIsParkingZone = false;
        graphHitIsDeparting = false;
        graphFoundFreeDetour = false;
        graphJunctionNotEnoughSpace = false;
        graphJunctionYieldToId = null;
        // Save previous junction reservation — if BFS doesn't renew it, train passed through
        long previousJuncKey = lastReservedJunctionKey;
        lastReservedJunctionKey = -1; // will be set again by BFS if still approaching

        if (myGraph == null || myLeadingEdge == null || myLeadingNode2 == null)
            return null;

        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null)
            return null;

        initGraphWalkReflection();
        if (getLengthMethod == null || getConnectionsFromMethod == null)
            return null;

        graphScanRan = true; // BFS has valid data — its result is authoritative

        // Build edge → train lookup (identity-based map: same Java object = same
        // directed edge)
        IdentityHashMap<Object, TrainAIController> edgeToTrain = new IdentityHashMap<>();
        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId))
                continue;
            if (other.currentPosition == null)
                continue;
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId))
                continue;
            // v5.3: Skip trains yielding TO US — they're invisible
            if (other.junctionYieldingToId != null
                    && other.junctionYieldingToId.equals(this.trainId))
                continue;
            // Y-level filter
            if (currentPosition != null) {
                int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                if (dy > 10)
                    continue;
            }
            if (other.myLeadingEdge != null)
                edgeToTrain.putIfAbsent(other.myLeadingEdge, other);
            if (other.myTrailingEdge != null)
                edgeToTrain.putIfAbsent(other.myTrailingEdge, other);
        }

        // Build reverse-edge map for head-on (oncoming) detection.
        // Create uses DIRECTED edges: edge(A→B) ≠ edge(B→A). A train traveling B→A
        // has myLeadingEdge = edge(B→A). Our BFS walks edge(A→B). Without this map,
        // BFS never finds head-on trains → collisions.
        // For each other train on edge(N1→N2), find the reverse edge(N2→N1) via
        // getConnectionsFrom(N2).get(N1). Map that reverse edge to the train.
        IdentityHashMap<Object, TrainAIController> reverseEdgeMap = new IdentityHashMap<>();
        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId))
                continue;
            if (other.myLeadingNode1 == null || other.myLeadingNode2 == null)
                continue;
            if (other.myLeadingEdge == null)
                continue;
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId))
                continue;
            // v5.3: Skip trains yielding TO US
            if (other.junctionYieldingToId != null
                    && other.junctionYieldingToId.equals(this.trainId))
                continue;
            Map<Object, Object> revConns = invokeGetConnectionsFrom(other.myLeadingNode2);
            if (revConns != null) {
                Object revEdge = revConns.get(other.myLeadingNode1);
                if (revEdge != null)
                    reverseEdgeMap.putIfAbsent(revEdge, other);
            }
        }

        // Build junction-convergence map: node → train approaching from a SIDE branch.
        // When train B is on a branch whose myLeadingNode2 is a junction node on OUR
        // route,
        // B will physically arrive at that junction and merge onto our track —
        // collision risk.
        // Distinct from oval-end nodes: we only flag if B's edge is NOT in edgeToTrain
        // (i.e. not on our own route edges) AND B is not behind us.
        // Map: junctionNode → closest approaching side-branch train (physical dist as
        // tiebreak).
        IdentityHashMap<Object, TrainAIController> junctionApproachMap = new IdentityHashMap<>();
        IdentityHashMap<Object, Double> junctionApproachDist = new IdentityHashMap<>();
        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId))
                continue;
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId))
                continue;
            // v5.3: Skip trains yielding TO US
            if (other.junctionYieldingToId != null
                    && other.junctionYieldingToId.equals(this.trainId))
                continue;
            if (other.myLeadingNode2 == null || other.myLeadingEdge == null)
                continue;
            if (other.currentPosition == null)
                continue;
            // Skip if this train is already captured in edgeToTrain (on our route edge)
            if (edgeToTrain.containsKey(other.myLeadingEdge))
                continue;
            // Y-level filter
            if (currentPosition != null) {
                int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                if (dy > 10)
                    continue;
            }
            double physDist = distanceBetween(this, other);
            Object jNode = other.myLeadingNode2; // node this train is heading toward
            Double existing = junctionApproachDist.get(jNode);
            if (existing == null || physDist < existing) {
                junctionApproachMap.put(jNode, other);
                junctionApproachDist.put(jNode, physDist);
            }
        }

        if (edgeToTrain.isEmpty() && reverseEdgeMap.isEmpty() && junctionApproachMap.isEmpty())
            return null;

        // ── Step 1: check if another train is AHEAD on our own leading edge ──
        // (Our own leading edge is always on our route — no side-track filter needed
        // here)
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
            // We're at myLeadingEdgePos from A. Hit is at hit.myLeadingEdgePos from B (on
            // B→A).
            // Distance between = edgeLen - ourPos - theirPos.
            // Allow negative (overlap) — trains inside each other need the emergency stop.
            double headOnDist = edgeLenHere - myLeadingEdgePos - hit.myLeadingEdgePos;
            // Threshold was 0.3 — that left a blindspot when trains got within 0.3 blocks
            // (step 1b skipped, BFS doesn't walk backward) → CRUISING until collision.
            // Now fire at any non-absurd distance (> -3 blocks allows up to 3 blocks
            // overlap).
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
        queue.add(new Object[] { myLeadingNode2, remainingOnCurrent, myLeadingEdge });

        // NOTE: Converging-junction detection (nodeToConvTrain) was removed.
        // It fired on SHARED JUNCTION NODES between parallel oval-track loops, causing
        // false stops. The stopped train then got hit because the other train (on a
        // separate loop) missed it at close range. reverseEdgeMap catches all real
        // head-on cases; same-direction trains are caught by edgeToTrain.

        TrainAIController closestHit = null;
        double closestDist = Double.MAX_VALUE;
        Object closestHitExitNode = null; // destination node of closest hit's edge (dead-end check)
        boolean closestHitIsReverse = false; // true when closestHit found via reverseEdgeMap (head-on)

        double maxReachDist = remainingOnCurrent; // track deepest BFS reach

        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            Object node = cur[0];
            double dist = (double) cur[1];
            Object fromEdge = cur[2];

            if (dist > maxReachDist) maxReachDist = dist; // update deepest reach

            if (dist > maxDist)
                continue;

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
                // v5.3: Skip if we already hold the reservation for this junction
                double[] jcXZ = getNodeXZ(node);
                if (isRealJunction && jcXZ != null) {
                    long jcKey = JunctionReservationManager.packKey(jcXZ[0], jcXZ[1]);
                    if (jcKey == lastReservedJunctionKey) {
                        isRealJunction = false; // we hold the reservation — skip converge
                    }
                }
                if (isRealJunction) {
                    // Cross-junction safety (v1.0.1): at a crossroads, two trains can
                    // enter from perpendicular branches and exit to different branches
                    // without ever being on the same rail segment. Three-layer check:
                    // 1. Route exit comparison (most reliable when both have route data)
                    // 2. Heading angle — perpendicular approach = crossing paths
                    // 3. Entry edge identity — different entry edges at 4+ junction
                    Object myExit = routeNextHop.isEmpty() ? null : routeNextHop.get(node);
                    Object theirExit = juncHit.routeNextHop.isEmpty() ? null : juncHit.routeNextHop.get(node);
                    // Layer 1: different exit nodes → non-conflicting routes
                    if (myExit != null && theirExit != null && myExit != theirExit)
                        continue;
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
                    // FCFS priority: only yield to the approaching train if THEY
                    // are closer to the junction than US, or equal distance + UUID tiebreak.
                    // Without this, both trains detect each other and both stop → deadlock.
                    double[] jcXZ2 = getNodeXZ(node);
                    double otherToJunc = Double.MAX_VALUE;
                    if (jcXZ2 != null && juncHit.currentPosition != null) {
                        double ojdx = juncHit.currentPosition.getX() - jcXZ2[0];
                        double ojdz = juncHit.currentPosition.getZ() - jcXZ2[1];
                        otherToJunc = Math.sqrt(ojdx * ojdx + ojdz * ojdz);
                    }
                    boolean otherIsCloser = otherToJunc < dist - 2.0;
                    boolean tieRange = Math.abs(otherToJunc - dist) <= 2.0;
                    boolean weYieldOnTie = tieRange && trainId.compareTo(juncHit.trainId) < 0;
                    if (!otherIsCloser && !weYieldOnTie) {
                        // WE have priority — don't treat converging train as obstacle
                        continue;
                    }
                    if (dist < closestDist && dist <= maxDist) {
                        closestDist = dist;
                        closestHit = juncHit;
                        closestHitExitNode = node;
                        closestHitIsReverse = false; // converging — treat as same-direction obstacle
                        CreateRailwayMod.aiDebug(
                                "[AI] Train {} junction-converge yield to {} at node (dist={}b, otherDist={}b)",
                                trainId.toString().substring(0, 8),
                                juncHit.trainId.toString().substring(0, 8), (int) dist, (int) otherToJunc);
                    }
                }
            }

            Map<Object, Object> connections = invokeGetConnectionsFrom(node);
            if (connections == null)
                continue;

            // ── Junction look-ahead (v3) — "First come, first served" ──
            // Every junction node found in the BFS walk:
            // 1. Check if our exit branch is occupied (isJunctionExitClear).
            // 2. Scan ALL trains converging on this junction from ANY branch.
            //    The train closer to the junction has right-of-way.
            //    The train further away (dist > otherDistToJunc + 4) yields.
            // This covers perpendicular crossings, T-junctions, curve exits.
            if (connections != null && connections.size() >= 3) {
                double[] jXZ = getNodeXZ(node);

                // ── v5.3: Compute braking distance for dynamic ranges ──
                // Capped at 18 blocks to prevent random stops far from junctions.
                // At max speed 1.4 b/t, real braking dist = 26 blocks but we only
                // need to START checking ~18 blocks out (enough to decelerate smoothly).
                double decelForJunc = getDecelRate();
                double juncBrakeDist = (currentSpeed * currentSpeed) / (2.0 * decelForJunc) + 3.0;
                juncBrakeDist = Math.min(Math.max(8.0, juncBrakeDist), 18.0);

                // ── Check 0 (v1.0.5, v5.2): Junction Reservation Mutex ──
                // MUST acquire reservation before entering ANY junction.
                // Range: braking distance + 3 (enough to stop before junction)
                boolean signalProtected = createWaitingForSignal && createDistToSignal < dist + 10;
                if (!graphJunctionNotEnoughSpace && jXZ != null
                        && dist <= juncBrakeDist && !signalProtected) {
                    long juncKey = JunctionReservationManager.packKey(jXZ[0], jXZ[1]);
                    long tick = lastKnownLevel != null ? lastKnownLevel.getGameTime() : 0;
                    // v1.0.5: Pass our distance so closer train wins priority
                    JunctionReservationManager.ReserveResult result =
                            JunctionReservationManager.getInstance()
                                    .tryReserve(juncKey, trainId, tick, dist);
                    if (!result.isGranted) {
                        graphJunctionNotEnoughSpace = true;
                        UUID winner = result.ghostPassTarget; // the train that has priority
                        if (winner != null) {
                            // We (B) lost the junction to A (winner).
                            // Set junctionYieldingToId = A so that A's scans skip US.
                            // This is the correct direction: the PASSER (A) ignores the
                            // YIELDER (B) — not the other way around.
                            junctionYieldingToId = winner;
                            junctionYieldingSince = tick;
                            CreateRailwayMod.aiDebug(
                                    "[Junction] {} yields to {} (myDist={:.1f}b) — passer will ghost-skip us",
                                    trainId.toString().substring(0, 8),
                                    winner.toString().substring(0, 8), dist);
                        }
                    } else {
                        // We got the reservation — we are the PASSER.
                        // junctionYieldingToId on yielder trains already points to us,
                        // so our scans will automatically skip them.
                        lastReservedJunctionKey = juncKey;
                    }
                }


                // Check 1: exit path occupied?
                // SKIP if we hold the reservation (exit safety is covered by reservation).
                // Range: braking distance (need to stop before entering if exit blocked)
                boolean weHoldReservation = (lastReservedJunctionKey != -1 && jXZ != null
                        && lastReservedJunctionKey == JunctionReservationManager.packKey(jXZ[0], jXZ[1]));
                if (!weHoldReservation && !graphJunctionNotEnoughSpace && !routeNextHop.isEmpty()
                        && dist <= juncBrakeDist) {
                    Object exitNode = routeNextHop.get(node);
                    if (exitNode != null) {
                        boolean enough = isJunctionExitClear(node, exitNode, connections,
                                edgeToTrain, reverseEdgeMap);
                        if (!enough) {
                            graphJunctionNotEnoughSpace = true;
                            CreateRailwayMod.aiDebug(
                                    "[AI] Train {} junction exit blocked (dist={}b)",
                                    trainId.toString().substring(0, 8), (int) dist);
                        }
                    }
                }

                // ── Check 2 (v5.3): ANY train physically inside junction ──
                // Blocks if ANY train is within 5 blocks of the junction center.
                // This catches player-controlled trains, moving trains, stopped trains —
                // everything. The mutex reservation (Check 0) handles who APPROACHES.
                // This check prevents entering a junction that is physically occupied.
                if (!graphJunctionNotEnoughSpace && jXZ != null
                        && dist <= juncBrakeDist && currentPosition != null) {
                    TrainAIManager jMgr = TrainAIManager.getInstance();
                    if (jMgr != null) {
                        for (TrainAIController other : jMgr.getAllControllers()) {
                            if (other.trainId.equals(this.trainId)) continue;
                            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId)) continue;
                            if (other.junctionYieldingToId != null
                                    && other.junctionYieldingToId.equals(this.trainId)) continue;
                            if (other.currentPosition == null) continue;
                            if (myGraph != null && other.myGraph != null && myGraph != other.myGraph) continue;
                            int dyo = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                            if (dyo > 6) continue;

                            double ddx = other.currentPosition.getX() - jXZ[0];
                            double ddz = other.currentPosition.getZ() - jXZ[1];
                            double otherDistToJunc = Math.sqrt(ddx * ddx + ddz * ddz);

                            // Block if ANY train is physically inside junction (≤ 5 blocks)
                            if (otherDistToJunc <= 5.0) {
                                graphJunctionNotEnoughSpace = true;
                                CreateRailwayMod.aiDebug(
                                        "[AI] Train {} junction occupied by {} (d={}b, spd={})",
                                        trainId.toString().substring(0, 8),
                                        other.trainId.toString().substring(0, 8),
                                        (int) otherDistToJunc,
                                        String.format("%.2f", other.currentSpeed));
                                break;
                            }
                        }
                    }
                }
            }

            for (Map.Entry<Object, Object> entry : connections.entrySet()) {
                Object nextNode = entry.getKey();
                Object nextEdge = entry.getValue();

                // Skip the edge we came from and already-visited edges
                if (nextEdge == fromEdge)
                    continue;
                if (!visited.add(nextEdge))
                    continue;
                // Skip already-visited nodes — prevents backward walks via reverse edges
                if (!visitedNodes.add(nextNode))
                    continue;

                // ── Route-path filter (SIDE_TRACK elimination) ──
                // routeNextHop: maps each route node to its expected next node.
                // If we’re at a known route node, ONLY follow the branch toward
                // routeNextHop.get(node).
                // Any other branch (parallel sidings, opposite loop side, depots) is invisible.
                // Degrades safely: if routeNextHop is empty, BFS walks all branches (old
                // behavior).
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
                            // Dead-end guard (v1.0.3): only flag as a viable detour if
                            // the branch node has ≥2 connections (i.e. it leads somewhere).
                            // A single-connection node is a siding/depot dead-end; Create
                            // cannot route to a scheduled destination through it, so
                            // canceling navigation just produces "can't find path" spam.
                            Map<Object, Object> branchNodeConns = invokeGetConnectionsFrom(nextNode);
                            if (branchNodeConns != null && branchNodeConns.size() >= 2) {
                                graphFoundFreeDetour = true;
                            }
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
                    queue.add(new Object[] { nextNode, dist + nextLen, nextEdge });
                }
            }
        }

        // ── Adaptive track-size scaling (v1.0.5) ──
        // maxReachDist = deepest forward distance BFS could walk = available track ahead.
        // Compute bufferScale: clamp(capacity / 100, 0.3, 1.0)
        // Small loop (20b) → scale 0.3 → effective BUFFER_CRITICAL = 2.4, range = 15
        // Medium track (60b) → scale 0.6 → BUFFER_CRITICAL = 4.8, range = 30
        // Large network (100+) → scale 1.0 → full limits
        localTrackCapacity = maxReachDist;
        bufferScale = Math.max(0.3, Math.min(1.0, localTrackCapacity / 100.0));

        // ── Junction reservation release (v1.0.5) ──
        // If the old reservation key was NOT re-acquired in this BFS walk,
        // the train has passed through the junction → release it.
        if (previousJuncKey != -1 && previousJuncKey != lastReservedJunctionKey) {
            JunctionReservationManager.getInstance().release(previousJuncKey, trainId);
        }

        if (closestHit != null) {
            graphDistanceToObstacle = closestDist;
            graphScanActive = true;

            // ── Dead-end / Parking Zone detection ──
            // If the exit node of the hit edge has no further rail connections (or only
            // the back-edge we came from), the train is sitting at a depot / station
            // siding.
            // We apply a soft approach profile instead of hard YIELDING/WFC braking.
            if (closestHitExitNode != null) {
                Map<Object, Object> beyond = invokeGetConnectionsFrom(closestHitExitNode);
                graphHitIsParkingZone = (beyond == null || beyond.isEmpty() || beyond.size() <= 1);
            }

            // ── Departing train detection ──
            // If the obstacle is moving in the same direction as us AND faster, the gap is
            // increasing. No braking is needed while the train is pulling away from us.
            // NEVER apply this to head-on (oncoming) trains: both trains have
            // direction=true
            // (positive speed), so the direction check would falsely pass → collision.
            graphHitIsHeadOn = closestHitIsReverse;
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
     * a) The avoidance marker is still active (hasn't expired), AND
     * b) The train that was blocking us is still in front on the same edge.
     *
     * This prevents the train from immediately charging back onto the blocked
     * segment
     * right after it backs up.
     */
    private boolean isBlockedEdgeStillOccupied(long currentTick) {
        if (blockedEdge == null && blockedByTrainId == null)
            return false;
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
        if (manager == null)
            return false;
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
     * Stage 0 (≤ BUFFER_CRITICAL = 6 blocks): WAIT_FOR_CLEARANCE — complete lockout
     * Stage 1 (≤ minimumStopDistance = 7 blocks): hard stop — never go closer
     * Stage 2 (≤ 30% of range = 15 blocks): full stop and yield
     * Stage 3 (≤ 50% of range = 25 blocks): crawl at 20% max speed
     * Stage 4 (≤ 75% of range = 37 blocks): half max speed
     * Stage 5 (≤ range = 50 blocks): proportional braking
     *
     * Micro-braking: instead of instantly zeroing speed, stages 1-2 reduce at
     * max 30% per tick to avoid the "nose dip" effect from abrupt stops.
     */
    private static final double BUFFER_CRITICAL = 8.0; // blocks — lockout zone (edge-to-edge)
    private static final double BUFFER_CLEARANCE = 12.0; // blocks — hysteresis resume threshold
    private static final double CONTEXTUAL_EARLY_BRAKE_DIST = 40.0; // blocks — early smoothStop when obstacle is stuck

    /**
     * Handle a train detected ahead — kinematic adaptive braking.
     *
     * Braking stages (v² = v₀² + 2·a·d):
     * CRITICAL (≤ BUFFER_CRITICAL = 6 b) : WAIT_FOR_CLEARANCE + hard stop
     * HARD STOP (≤ minStop = 7 b) : YIELDING + force stop
     * CONVOY (gap ≥ CONVOY_MIN_GAP, same dir): FOLLOWING — match obstacle speed
     * KINEMATIC (required braking dist > gap) : set speed proportional to gap
     * FAR (> kinematic dist) : proportional slow-down
     *
     * Predictive TTC: if closing speed gives time-to-contact < CRITICAL_TTC_TICKS,
     * override to hard stop regardless of absolute distance.
     */
    private void handleObstacleDetected(double distance, TrainAIController obstacle, long currentTick) {
        double minStop = RailwayConfig.minimumStopDistance.get();
        double range = RailwayConfig.obstacleDetectionRange.get();

        // ── Adaptive track-size scaling (v1.0.5) ──
        // On small tracks, reduce all buffer thresholds proportionally.
        // bufferScale = 0.3 (tiny loop) .. 1.0 (big network)
        double scaledCritical = BUFFER_CRITICAL * bufferScale;
        double scaledMinStop = minStop * bufferScale;
        double scaledClearance = BUFFER_CLEARANCE * bufferScale;
        double scaledRange = range * bufferScale;

        // ─── Stage 0: CRITICAL ───
        if (distance <= scaledCritical) {
            forceStop();
            transitionTo(TrainState.WAIT_FOR_CLEARANCE, currentTick,
                    "kinematic_critical_d=" + String.format("%.1f", distance)
                    + "_scale=" + String.format("%.1f", bufferScale));
            return;
        }

        // ─── Stage 1: HARD STOP ───
        if (distance <= scaledMinStop) {
            forceStop();
            transitionTo(TrainState.YIELDING, currentTick,
                    "kinematic_hard_stop_d=" + (int) distance);
            return;
        }

        // ─── Predictive TTC check ───
        // Closing speed = my speed - obstacle speed (positive = we are approaching)
        double obstacleSpeed = (obstacle.direction == this.direction)
                ? obstacle.currentSpeed
                : -obstacle.currentSpeed; // head-on: obstacle coming toward us
        double closingSpeed = this.currentSpeed - obstacleSpeed;
        if (closingSpeed > 0.01) {
            double ttcTicks = distance / closingSpeed;
            if (ttcTicks < CRITICAL_TTC_TICKS) {
                // TTC < 1.5 s — hard stop regardless of distance
                forceStop();
                transitionTo(TrainState.YIELDING, currentTick,
                        "ttc_brake_ttc=" + String.format("%.0f", ttcTicks) + "t");
                return;
            }
        }

        // ─── Stage 2: CONVOY FOLLOWING ───
        // If the obstacle is ahead, same direction, same speed, within following gap:
        // increase a counter. After CONVOY_CONFIRM_TICKS enter following mode.
        boolean sameDir = obstacle.direction == this.direction;
        boolean fastEnough = obstacle.currentSpeed > maxSpeed * 0.15; // not nearly stopped
        boolean gapOk = distance >= CONVOY_MIN_GAP && distance <= CONVOY_MAX_GAP;
        if (sameDir && fastEnough && gapOk
                && Math.abs(obstacle.currentSpeed - this.currentSpeed) < CONVOY_SPEED_MATCH * 3) {
            convoyFollowTicks++;
            if (convoyFollowTicks >= CONVOY_CONFIRM_TICKS) {
                convoyLeaderId = obstacle.trainId;
                convoyTargetSpeed = Math.max(0, obstacle.currentSpeed - 0.01); // slight underrun to keep gap
                applySpeedControl(convoyTargetSpeed);
                transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick,
                        "convoy_following_d=" + (int) distance);
                return;
            }
        } else {
            convoyFollowTicks = 0;
            if (convoyLeaderId != null && convoyLeaderId.equals(obstacle.trainId)) {
                convoyLeaderId = null; // convoy broken
            }
        }

        // ─── Stage 3: Kinematic braking curve ───
        // Required braking distance to reach obstacle's speed from our speed.
        // d_needed = (v_us² - v_obs²) / (2 * DECEL_RATE)
        double vUs = this.currentSpeed;
        double vObs = sameDir ? Math.max(0, obstacle.currentSpeed) : 0;
        double dNeeded = (vUs * vUs - vObs * vObs) / (2.0 * getDecelRate());
        // Add safety margin = scaledMinStop (adapts to track size)
        double brakeTrigger = dNeeded + scaledMinStop;

        if (distance <= brakeTrigger) {
            // Compute target speed that would stop exactly at minStop gap
            // v_target = sqrt(max(0, v_obs² + 2·a·(d - scaledMinStop)))
            double excess = Math.max(0, distance - scaledMinStop);
            double vTarget = Math.sqrt(Math.max(0, vObs * vObs + 2.0 * getDecelRate() * excess));
            vTarget = Math.min(vTarget, maxSpeed); // never exceed max

            if (vTarget < 0.03) {
                forceStop();
                transitionTo(TrainState.YIELDING, currentTick,
                        "kinematic_full_stop_d=" + (int) distance);
            } else {
                applySpeedControl(vTarget);
                transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick,
                        "kinematic_brake_v=" + String.format("%.2f", vTarget)
                                + "_d=" + (int) distance);
            }
            return;
        }

        // ─── Stage 4: Far approaching — proportional slow-down ───
        // Scale to 60% speed at 75% of range, 30% at 50% of range.
        double factor = Math.max(0.3, Math.min(1.0, (distance - brakeTrigger) / (scaledRange - brakeTrigger)));
        double targetSpd = maxSpeed * factor;
        applySpeedControl(targetSpd);
        transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick,
                "kinematic_approach_f=" + String.format("%.2f", factor));
    }

    /**
     * Handle the case when no obstacle is detected — resume normal speed.
     *
     * WAIT_FOR_CLEARANCE uses hysteresis: entered at < 5 blocks, only exits
     * when the scanner reports NO obstacle at all (meaning the forward train is
     * now beyond the full scan range or has moved to a different track).
     * The BUFFER_CLEARANCE (7 blocks) threshold is enforced in the
     * obstacle-detected
     * path — if an obstacle IS detected but farther than 7 blocks, the standard
     * graduated braking handles it instead of re-entering WAIT_FOR_CLEARANCE.
     */
    // Soft-resume: after stopping, limit speed for RESUME_RAMP_TICKS so we don't
    // instantly slam into a train that just barely left detection range.
    private long resumedAtTick = -1;
    private static final int RESUME_RAMP_TICKS = 40; // 2 seconds of gradual acceleration

    private void handleNoObstacle(long currentTick) {
        if (currentState == TrainState.WAIT_FOR_CLEARANCE) {
            // Guard 1 — minimum 30 ticks in WFC before any exit is allowed.
            long timeInState = currentTick - stateEnteredTick;
            if (timeInState < 30) {
                forceStop();
                return;
            }

            // Guard 3 — rolling distance history check.
            // We sample the physical distance to the WFC obstacle every tick and store
            // the last 3 readings. Only exit WFC when ALL 3 samples >= BUFFER_CLEARANCE.
            // This prevents a single "scan miss" tick from falsely granting clearance.
            if (wfcObstacleTrainId != null) {
                TrainAIManager mgr = TrainAIManager.getInstance();
                if (mgr != null) {
                    TrainAIController lockedObstacle = mgr.getController(wfcObstacleTrainId);
                    if (lockedObstacle != null && lockedObstacle.currentPosition != null
                            && currentPosition != null) {
                        double physDist = distanceBetween(this, lockedObstacle);
                        wfcDistHistory[wfcDistIdx % wfcDistHistory.length] = physDist;
                        wfcDistIdx++;
                        // Require all history slots filled and ALL >= BUFFER_CLEARANCE
                        boolean allClear = true;
                        for (double d : wfcDistHistory) {
                            if (d < 0 || d < BUFFER_CLEARANCE * bufferScale) {
                                allClear = false;
                                break;
                            }
                        }
                        if (!allClear) {
                            forceStop();
                            return;
                        }
                    }
                }
            }

            // Guard 4 — do NOT exit WFC while the locked obstacle is still YIELDING.
            if (wfcObstacleTrainId != null) {
                TrainAIManager mgrG4 = TrainAIManager.getInstance();
                if (mgrG4 != null) {
                    TrainAIController g4obs = mgrG4.getController(wfcObstacleTrainId);
                    if (g4obs != null && g4obs.getCurrentState() == TrainState.YIELDING) {
                        forceStop();
                        return;
                    }
                }
            }

            // All guards passed — safe to resume.
            Arrays.fill(wfcDistHistory, -1);
            wfcDistIdx = 0;
            convoyFollowTicks = 0;
            convoyLeaderId = null;
            junctionBlockedSinceTick = -1;
            resumedAtTick = currentTick;
            restoreThrottle();
            wakeCreateNavigation();
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
                return;
            }

            // Path is clear — reset convoy tracking and resume.
            convoyFollowTicks = 0;
            convoyLeaderId = null;
            junctionBlockedSinceTick = -1;
            resumedAtTick = currentTick;
            restoreThrottle();
            wakeCreateNavigation();
            transitionTo(TrainState.CRUISING, currentTick, "path_clear_resuming");
            totalWaitTicks = 0;
        }

        // ── Stuck CRUISING recovery ──
        // When the train has been CRUISING with speed≈0 longer than
        // STUCK_CRUISING_REROUTE_TICKS,
        // Create's navigation likely has no valid path (broken rail, invalid schedule
        // entry).
        // Cancelling navigation makes Create re-pathfind. If an alternative route
        // exists the
        // train will take it. Guard: distToDestination > 2 avoids disturbing station
        // waits.
        if (currentState == TrainState.CRUISING
                && currentSpeed < 0.02
                && distToDestination > 2.0
                && (currentTick - stateEnteredTick) > STUCK_CRUISING_REROUTE_TICKS) {
            // Exponential backoff: 200 → 400 → 800 → 1600 ticks between retries.
            // Counter resets when the train actually moves ≥1 block between attempts.
            long stuckCooldown = (long) REROUTE_COOLDOWN_TICKS << Math.min(3, stuckCruisingRetries);
            if (lastRerouteTick < 0 || (currentTick - lastRerouteTick) > stuckCooldown) {
                if (lastStuckCruisingPos != null && currentPosition != null
                        && distanceBetweenPos(currentPosition, lastStuckCruisingPos) < 1.0) {
                    stuckCruisingRetries++; // still stuck in same spot — back off longer
                } else {
                    stuckCruisingRetries = 0; // moved → fresh start
                }
                lastRerouteTick = currentTick;
                lastStuckCruisingPos = currentPosition;
                cancelCreateNavigation();
                CreateRailwayMod.aiLog("[AI] Train {} stuck-CRUISING nav retry #{} (dist={}b, cd={}t)",
                        trainId.toString().substring(0, 8), stuckCruisingRetries,
                        String.format("%.1f", distToDestination), stuckCooldown);

                // After 2+ failed wake attempts: if space behind exists, escalate to
                // YIELDING so the YIELDING→REVERSING chain backs us to the nearest
                // junction. After reversing, wakeCreateNavigation() is called and Create
                // re-routes via the detour (broken-track-ahead + detour-behind scenario).
                if (stuckCruisingRetries >= 2 && spaceAvailableBehind
                        && RailwayConfig.reverseManeuverEnabled.get()) {
                    CreateRailwayMod.aiLog(
                            "[AI] Train {} stuck-CRUISING {} retries → escalating to YIELDING for reversal",
                            trainId.toString().substring(0, 8), stuckCruisingRetries);
                    forceStop();
                    stuckCruisingRetries = 0;
                    lastStuckCruisingPos = null;
                    totalWaitTicks = 0;
                    transitionTo(TrainState.YIELDING, currentTick, "stuck_cruising_escalate_reverse");
                }
            }
        }
    }

    /**
     * Soft-resume ramp: for RESUME_RAMP_TICKS after resuming from a stop,
     * gradually ramp speed from zero to maxSpeed using the kinematic constant.
     * This prevents instant full-throttle jerk after AI stops.
     */
    private void applyResumeRamp(long currentTick) {
        if (resumedAtTick <= 0)
            return;
        long elapsed = currentTick - resumedAtTick;
        if (elapsed >= RESUME_RAMP_TICKS) {
            resumedAtTick = -1;
            return;
        }
        // Kinematic ramp: v = sqrt(2 * a * d_elapsed) where d_elapsed = elapsed *
        // meanSpeed
        // Simpler approximation: linear ramp from 0.1*max to max over RESUME_RAMP_TICKS
        double rampFactor = 0.1 + 0.9 * ((double) (elapsed + 1) / RESUME_RAMP_TICKS);
        double rampSpeed = maxSpeed * rampFactor;
        if (currentSpeed < rampSpeed) {
            applySpeedControl(rampSpeed);
        }
    }

    /**
     * Restore Create's throttle multiplier to 1.0 so Navigation can drive the
     * train.
     */
    private void restoreThrottle() {
        if (createTrainRef == null || throttleField == null)
            return;
        try {
            throttleField.setDouble(createTrainRef, 1.0);
        } catch (Exception e) {
            CreateRailwayMod.aiDebug("[AI] Failed to restore throttle for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /**
     * v5: Get the actual deceleration rate — uses Create's acceleration() if available,
     * otherwise falls back to the static DECEL_RATE constant.
     * This ensures braking distances match Create's own physics exactly.
     */
    private double getDecelRate() {
        return createAcceleration > 0.001 ? createAcceleration : DECEL_RATE;
    }

    /**
     * Public force-release: clears ALL AI stop locks and resumes the train.
     * Called by /railway release command or WFC auto-timeout.
     * Safe to call from any context.
     */
    public void forceRelease(long currentTick, String reason) {
        // Clear WFC hysteresis
        Arrays.fill(wfcDistHistory, -1);
        wfcDistIdx = 0;
        wfcObstacleTrainId = null;
        // Clear junction locks
        junctionBlockedSinceTick = -1;
        junctionEntryBlocked = false;
        junctionEntryBlockedSince = -1;
        junctionYieldingToId = null;
        junctionYieldingSince = -1;
        // Clear obstacle tracking
        obstacleTrainId = null;
        obstaclePosition = null;
        graphScanActive = false;
        // Clear wait counters
        totalWaitTicks = 0;
        convoyFollowTicks = 0;
        convoyLeaderId = null;
        // Restore Create's throttle and restart navigation
        restoreThrottle();
        wakeCreateNavigation();
        transitionTo(TrainState.CRUISING, currentTick, "force_release:" + reason);
        CreateRailwayMod.aiLog("[AI] Train {} force-released ({})",
                trainId.toString().substring(0, 8), reason);
    }

    // ─── Speed Control ───

    /**
     * Force the train to stop completely by setting both speed and targetSpeed to
     * 0.
     * This overrides Create's Navigation each tick.
     */
    private void forceStop() {
        if (createTrainRef == null)
            return;
        try {
            // Set throttle=0 first so Navigation.tick() computes topSpeed=0
            if (throttleField != null) {
                throttleField.setDouble(createTrainRef, 0.0);
            }
            // Zero the actual speed
            if (speedField != null) {
                speedField.setDouble(createTrainRef, 0.0);
            }
            // v5: ALSO zero targetSpeed so Create's approachTargetSpeed() doesn't
            // re-accelerate on the next tick. This was the root cause of "rolling"
            // into collisions. Safe because v5 uses maxSpeedWatermark for maxSpeed
            // (never reads targetSpeed as maxSpeed anymore).
            if (targetSpeedField != null) {
                targetSpeedField.setDouble(createTrainRef, 0.0);
            }
        } catch (Exception e) {
            CreateRailwayMod.aiDebug("[AI] Failed to force stop train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /**
     * Smooth micro-braking stop: reduces speed by 50% per tick instead of
     * instantly zeroing it. Once speed drops below 0.05, delegates to forceStop()
     * for a clean zero. Stops in ~4 ticks (0.2s) from max speed.
     */
    private void smoothStop() {
        if (createTrainRef == null)
            return;

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
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Apply a specific speed to the train (for gradual deceleration).
     * Clamps to the target so the train slows rather than accelerates.
     * Sets throttle proportional to desiredSpeed/maxSpeed so Create's Navigation
     * doesn't re-accelerate the train between our ticks.
     */
    private void applySpeedControl(double desiredSpeed) {
        if (createTrainRef == null)
            return;
        try {
            double sign = direction ? 1.0 : -1.0;

            // Set throttle so Create's Navigation cannot accelerate beyond this fraction of
            // topSpeed.
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
            CreateRailwayMod.aiDebug("[AI] Failed to set speed for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    // ─── Reverse Maneuver ───

    /**
     * Apply reverse (negative) speed to the train via reflection.
     */
    private void applyReverseSpeed() {
        if (createTrainRef == null)
            return;
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
            CreateRailwayMod.aiDebug("[AI] Failed to apply reverse speed for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /**
     * Smart Junction Re-routing.
     *
     * Called every tick while the train is stuck in YIELDING or WAIT_FOR_CLEARANCE.
     * Two-phase approach:
     *
     * Phase 1 — Deadlock detection (0 → REROUTE_WAIT_TICKS ticks):
     * Just wait. The obstacle may move on its own.
     *
     * Phase 2 — Reroute attempt (> REROUTE_WAIT_TICKS ticks, cooldown respected):
     * a) Look at forward connections from our leading node (myLeadingNode2).
     * b) Classify each edge as BLOCKED (another train is on it) or FREE.
     * c) If at least one FREE edge exists alongside a BLOCKED one → junction
     * is partially available → cancel navigation so Create re-pathfinds.
     * Create's BFS will prefer the free edge because occupied edges carry
     * a higher cost via Train.Penalties (and our own graph-walk avoidance).
     * d) If ALL forward edges are blocked (full deadlock) or there's only one
     * edge (no junction) → skip; the existing YIELDING → REVERSING escalation
     * will handle it eventually.
     *
     * Hysteresis: REROUTE_COOLDOWN_TICKS (200 ticks / 10 s) between reroute
     * attempts
     * prevents the train from thrashing back and forth at a switch.
     */
    private void trySmartJunctionReroute(long currentTick) {
        // Phase 1: wait REROUTE_WAIT_TICKS before attempting anything
        if (junctionBlockedSinceTick < 0) {
            junctionBlockedSinceTick = currentTick;
            return;
        }
        long waitedTicks = currentTick - junctionBlockedSinceTick;
        if (waitedTicks < REROUTE_WAIT_TICKS)
            return;

        // Hysteresis: respect cooldown between reroute attempts
        if (lastRerouteTick >= 0 && (currentTick - lastRerouteTick) < REROUTE_COOLDOWN_TICKS)
            return;

        // Need graph data to inspect junction topology
        if (myGraph == null || myLeadingNode2 == null) {
            // No graph data yet — fall back to blind navigation cancel after long wait
            if (waitedTicks >= REROUTE_WAIT_TICKS * 2) {
                lastRerouteTick = currentTick;
                cancelCreateNavigation();
                CreateRailwayMod.aiLog("[AI] Train {} no graph data, blind reroute after {}t",
                        trainId.toString().substring(0, 8), waitedTicks);
            }
            return;
        }

        initGraphWalkReflection();
        if (getConnectionsFromMethod == null)
            return;

        // Build set of edges occupied by OTHER trains (identity-based)
        Set<Object> occupiedEdges = Collections.newSetFromMap(new IdentityHashMap<>());
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager != null) {
            for (TrainAIController other : manager.getAllControllers()) {
                if (other.trainId.equals(this.trainId))
                    continue;
                if (other.myLeadingEdge != null)
                    occupiedEdges.add(other.myLeadingEdge);
                if (other.myTrailingEdge != null)
                    occupiedEdges.add(other.myTrailingEdge);
            }
        }

        // Get all forward edges from our leading node
        Map<Object, Object> connections = invokeGetConnectionsFrom(myLeadingNode2);
        if (connections == null || connections.isEmpty())
            return;

        // Skip the edge we're currently on (myLeadingEdge) to avoid counting backwards
        int freeCount = 0;
        int blockedCount = 0;
        for (Map.Entry<Object, Object> entry : connections.entrySet()) {
            Object edge = entry.getValue();
            if (edge == myLeadingEdge)
                continue; // skip reverse direction
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
            CreateRailwayMod.aiLog(
                    "[AI] Train {} smart-junction reroute: {}f/{}b fwd-edges, waited {}t",
                    trainId.toString().substring(0, 8), freeCount, blockedCount, waitedTicks);
            // Transition to CRUISING so the train can actually move on the bypass route.
            // Without this, forceStop() in YIELDING/TRAFFIC_JAM stops the train every tick
            // even after Create has built a new path — the bypass is never taken.
            // If the new route is not ready yet (1-tick delay), BFS will return the
            // obstacle
            // on the next tick and graduated braking will safely re-enter YIELDING.
            totalWaitTicks = 0;
            restoreThrottle();
            transitionTo(TrainState.CRUISING, currentTick, "free_bypass_rerouted");
        } else if (!hasJunction && waitedTicks >= REROUTE_WAIT_TICKS * 3) {
            // Single-track section, no junction — try a plain reroute anyway after 15 s
            // in case navigation is stuck in a bad state
            lastRerouteTick = currentTick;
            cancelCreateNavigation();
            CreateRailwayMod.aiLog(
                    "[AI] Train {} single-track reroute after {}t (no junction detected)",
                    trainId.toString().substring(0, 8), waitedTicks);
        }
    }

    /**
     * Wake Create's navigation brain after a stop or deadlock.
     *
     * This method ONLY unpauses the ScheduleRuntime and resets cooldown if the
     * runtime is paused/stuck. It does NOT cancel active navigation — that would
     * discard the current route and force Create to re-pathfind, which is slow
     * and can permanently break navigation if no path exists.
     *
     * When to call:
     * - After stopping the train (WFC/YIELDING exit → CRUISING)
     * - NOT during normal CRUISING (schedule runtime handles itself)
     *
     * When cancelCreateNavigation() is needed for rerouting:
     * - trySmartJunctionReroute() — explicitly needs a new path via different
     * junction
     * - handleReversing() completion — route needs restart after backing up
     * These call cancelCreateNavigation() which calls wakeCreateNavigation()
     * internally.
     */
    private void wakeCreateNavigation() {
        if (createTrainRef == null)
            return;
        try {
            // ── Step 1: Access ScheduleRuntime ──
            Field runtimeFieldLocal = findField(createTrainRef.getClass(), "runtime");
            if (runtimeFieldLocal == null)
                return;
            Object runtime = runtimeFieldLocal.get(createTrainRef);
            if (runtime == null)
                return;

            // Need a schedule present — no schedule = no automated navigation
            Field scheduleField = findField(runtime.getClass(), "schedule");
            if (scheduleField == null || scheduleField.get(runtime) == null)
                return;

            // Don't wake schedules that have truly completed (non-cyclic schedule reached
            // end)
            Field completedField = findField(runtime.getClass(), "completed");
            if (completedField != null && (boolean) completedField.get(runtime))
                return;

            // ── Step 2: Unpause if the runtime is paused ──
            // runtime.paused=true → tick() returns immediately → navigation never runs.
            Field pausedField = findField(runtime.getClass(), "paused");
            boolean wasPaused = pausedField != null && (boolean) pausedField.get(runtime);
            if (wasPaused && pausedField != null) {
                pausedField.set(runtime, false);
                CreateRailwayMod.aiLog("[AI] Train {} runtime unpaused",
                        trainId.toString().substring(0, 8));
            }

            // ── Step 3: Reset cooldown if runtime is stuck in cooldown ──
            // Only reset if not currently navigating (destination == null)
            // to avoid disrupting active in-transit navigation.
            boolean hasActiveNav = false;
            if (navigationField != null) {
                Object nav = navigationField.get(createTrainRef);
                if (nav != null) {
                    Field destField = findField(nav.getClass(), "destination");
                    hasActiveNav = destField != null && destField.get(nav) != null;
                }
            }

            if (!hasActiveNav) {
                // Not navigating — only reset cooldown if we're in PRE_TRANSIT.
                // NEVER change POST_TRANSIT to PRE_TRANSIT: POST_TRANSIT means the train
                // arrived at a station and is waiting for departure conditions (schedule timer,
                // item fill, etc.). Resetting it to PRE_TRANSIT would abort the station wait
                // and immediately navigate away — trains would never stop at stations!
                Field stateField = findField(runtime.getClass(), "state");
                if (stateField != null) {
                    Object currentStateEnum = stateField.get(runtime);
                    Object[] enumVals = stateField.getType().getEnumConstants();
                    // enumVals[0]=PRE_TRANSIT, enumVals[1]=IN_TRANSIT, enumVals[2]=POST_TRANSIT
                    // Only reset cooldown if ALREADY in PRE_TRANSIT (stuck waiting to search)
                    if (enumVals != null && enumVals.length > 0 && currentStateEnum == enumVals[0]) {
                        Field cooldownField = findField(runtime.getClass(), "cooldown");
                        if (cooldownField != null)
                            cooldownField.set(runtime, 0);
                    }
                    // POST_TRANSIT: do nothing — let Create manage its own departure conditions
                    // IN_TRANSIT: do nothing — active navigation, throttle restore is enough
                }
            }

        } catch (Exception e) {
            CreateRailwayMod.aiDebug("[AI] Failed to wake navigation for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
    }

    /**
     * Cancel Create's active navigation route and force a re-pathfind.
     * Use only when a different route is needed (rerouting, post-reverse).
     * For simple resume after stop, use wakeCreateNavigation() instead.
     */
    void cancelCreateNavigation() {
        if (createTrainRef == null)
            return;
        try {
            if (navigationField != null) {
                Object nav = navigationField.get(createTrainRef);
                if (nav != null) {
                    Field destField = findField(nav.getClass(), "destination");
                    boolean hasActive = destField != null && destField.get(nav) != null;
                    if (hasActive) {
                        Method cancelMethod = findMethod(nav.getClass(), "cancelNavigation");
                        if (cancelMethod != null)
                            cancelMethod.invoke(nav);
                        CreateRailwayMod.aiLog("[AI] Train {} navigation cancelled for reroute",
                                trainId.toString().substring(0, 8));
                    } else {
                        // Destination already null — clear stale path data
                        Field pathField = findField(nav.getClass(), "currentPath");
                        if (pathField != null) {
                            Object pathObj = pathField.get(nav);
                            if (pathObj instanceof java.util.List<?> list)
                                ((java.util.List<?>) list).clear();
                        }
                    }
                }
            }
        } catch (Exception e) {
            CreateRailwayMod.aiDebug("[AI] cancelNav failed for train {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
        // After cancelling, wake the runtime to re-pathfind
        wakeCreateNavigation();
    }

    /**
     * Clear bypass-ignore when the bypassed train is far enough away that
     * our scanner wouldn't detect it anyway. Uses pure distance check —
     * NOT heading-based, because heading can be reversed/wrong during and
     * right after the reverse maneuver.
     */
    private void clearBypassIfPassed() {
        if (bypassingTrainId == null || currentPosition == null)
            return;

        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null)
            return;
        TrainAIController bypassed = manager.getController(bypassingTrainId);
        if (bypassed == null || bypassed.currentPosition == null) {
            CreateRailwayMod.aiLog("[AI] Train {} bypassed train disappeared, clearing bypass",
                    trainId.toString().substring(0, 8));
            bypassingTrainId = null;
            return;
        }

        double dist = rawDistanceBetween(this, bypassed);
        double range = RailwayConfig.obstacleDetectionRange.get();

        // Only clear when the bypassed train is well outside scanner range
        // Use rawDistanceBetween (center-to-center) since this is a proximity check,
        // not a braking distance check.
        if (dist > range + 15) {
            CreateRailwayMod.aiLog("[AI] Train {} far from bypassed train {} ({}b), clearing bypass",
                    trainId.toString().substring(0, 8),
                    bypassingTrainId.toString().substring(0, 8), (int) dist);
            bypassingTrainId = null;
        }
    }

    /**
     * Scan behind the train (opposite of stored heading) for other trains.
     */
    private TrainAIController scanBehindForObstacle() {
        if (currentPosition == null)
            return null;
        if (headingX == 0 && headingZ == 0)
            return null; // no heading yet — assume clear

        double range = RailwayConfig.obstacleDetectionRange.get();
        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null)
            return null;

        for (TrainAIController other : manager.getAllControllers()) {
            if (other.trainId.equals(this.trainId))
                continue;
            if (other.currentPosition == null)
                continue;

            // During bypass mode, ignore the train we're going around
            if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId))
                continue;

            // If the train behind is YIELDING/WFC/JAM specifically because of US (it set
            // obstacleTrainId = our UUID), it is passively waiting and will back up as soon
            // as we start moving backward. Counting it as "behind obstacle" produces НЗД,
            // which prevents us from ever reversing — a permanent head-on deadlock.
            // Safe to ignore: the moment we reverse, it will detect us approaching and
            // itself enter REVERSING or yield further.
            if ((other.currentState == TrainState.YIELDING
                    || other.currentState == TrainState.WAIT_FOR_CLEARANCE
                    || other.currentState == TrainState.TRAFFIC_JAM)
                    && trainId.equals(other.obstacleTrainId))
                continue;

            double dist = distanceBetween(this, other);
            if (dist > range)
                continue;

            int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
            if (dy > 10)
                continue;

            // Junction convergence guard: if both trains have different leading edges but
            // share the SAME myLeadingNode2 (both heading toward the same junction from
            // different branches), this is a forward convergence scenario — NOT a
            // behind-train.
            // Without this, branch Train D targeting junction node J
            // would be counted as "behind" our Train B (also targeting J from main track).
            if (myLeadingNode2 != null && myLeadingNode2 == other.myLeadingNode2
                    && myLeadingEdge != null && other.myLeadingEdge != null
                    && myLeadingEdge != other.myLeadingEdge)
                continue;

            // Same-track check: TrackGraph primary, beam only when data is absent
            boolean sameTrack = isOnSameTrack(other);
            boolean useBeamFallback = !hasSufficientTrackData() || !other.hasSufficientTrackData();
            if (!sameTrack && !useBeamFallback)
                continue; // authoritative: different tracks
            if (!sameTrack && other.currentState == TrainState.TRAFFIC_JAM)
                continue;

            double toOtherX = other.currentPosition.getX() - currentPosition.getX();
            double toOtherZ = other.currentPosition.getZ() - currentPosition.getZ();
            double dot = toOtherX * headingX + toOtherZ * headingZ;
            // dot > 0.5 means the train is clearly IN FRONT of us — skip it.
            // We accept trains at the side (dot ≈ 0) and behind (dot < 0).
            // Old threshold -0.3 missed trains at 90° to our heading (curve stops):
            // a train directly behind us on a curve may approach from a perpendicular
            // heading, giving dot ≈ 0 which was incorrectly ignored → spaceAvailableBehind
            // = true → all queued curve trains tried to reverse simultaneously.
            if (dot > 0.5)
                continue;

            if (!sameTrack) {
                // Beam fallback only when one train has no TrackGraph data
                // Width adapts: 1.1 on straight, 2.5 on curves (TrackEdge.isTurn())
                double perpDist = Math.abs(toOtherX * headingZ - toOtherZ * headingX);
                if (perpDist > currentBeamTolerance)
                    continue;
            }

            return other;
        }
        return null;
    }

    /**
     * Handle the REVERSING state — the train is backing up to escape a deadlock.
     *
     * Continues reversing until:
     * a) Backed up >= reverseBackupDistance blocks → CRUISING (nav reroutes)
     * b) Another train detected in the reverse path (after initial movement) →
     * YIELDING
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
        // When a train enters REVERSING but does not physically move (myLeadingEdge
        // became
        // null because Create stopped updating TravellingPoint when the train was
        // stopped,
        // leaving applyReverseSpeed() unable to apply any throttle), we rescue it by
        // calling
        // cancelCreateNavigation(). This forces Create to re-initialize the navigation
        // path
        // and TravellingPoint data on the very next schedule cycle, unblocking the
        // train.
        if (backedUp < 0.5 && (currentTick - reversingStartTick) > 40) {
            CreateRailwayMod.aiWarn(
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
            CreateRailwayMod.aiLog("[AI] Train {} reverse complete ({}b), handing to navigation",
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
                CreateRailwayMod.aiLog("[AI] Train {} blocked edge still occupied after reverse, yielding",
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

        // ── Instant ultra-close safety scan (no heading requirement) ──
        // Catches fast-approaching trains BEFORE heading flips (first 2 blocks of
        // reverse).
        // Uses BUFFER_CRITICAL range — any train this close WILL be hit if we keep
        // moving.
        if (currentPosition != null) {
            TrainAIManager ultraMgr = TrainAIManager.getInstance();
            if (ultraMgr != null) {
                for (TrainAIController ub : ultraMgr.getAllControllers()) {
                    if (ub.trainId.equals(this.trainId))
                        continue;
                    if (bypassingTrainId != null && ub.trainId.equals(bypassingTrainId))
                        continue;
                    if (ub.currentPosition == null)
                        continue;
                    if (Math.abs(currentPosition.getY() - ub.currentPosition.getY()) > 10)
                        continue;
                    double ubDist = distanceBetween(this, ub);
                    if (ubDist > BUFFER_CRITICAL * bufferScale)
                        continue; // only within 8 blocks (edge-to-edge)
                    // Skip a train that is clearly BEHIND the original forward heading
                    // (i.e. it is the obstacle that caused us to reverse in first place).
                    // Use original forward direction: the obstacle has positive dot.
                    // A train in the REVERSE path has a negative dot (behind original heading).
                    // We stop for that — they're in our way.
                    CreateRailwayMod.aiWarn(
                            "[AI] Train {} aborted reverse — {} within {}b (ultra-close safety)",
                            trainId.toString().substring(0, 8),
                            ub.trainId.toString().substring(0, 8),
                            String.format("%.1f", ubDist));
                    forceStop();
                    totalWaitTicks = 0;
                    transitionTo(TrainState.YIELDING, currentTick, "reverse_ultra_close_abort");
                    return;
                }
            }
        }

        // ── Heading-based scan (runs after heading has flipped, backedUp > 2 blocks)
        // ──
        // graphWalkScan cannot be used here: it walks from myLeadingEdge which is the
        // SCHEDULE-FORWARD edge, NOT the direction of physical movement.
        // Trains in our reverse path will have dot > 0.3 with the reversed heading.
        // bypassingTrainId excludes the original obstacle (forward direction).
        if (backedUp > 2.0 && headingFromMovement && currentPosition != null) {
            TrainAIManager mgr = TrainAIManager.getInstance();
            if (mgr != null) {
                double range = RailwayConfig.obstacleDetectionRange.get();
                for (TrainAIController other : mgr.getAllControllers()) {
                    if (other.trainId.equals(this.trainId))
                        continue;
                    if (bypassingTrainId != null && other.trainId.equals(bypassingTrainId))
                        continue;
                    if (other.currentPosition == null)
                        continue;
                    double dist = distanceBetween(this, other);
                    if (dist > range)
                        continue;
                    int dy = Math.abs(currentPosition.getY() - other.currentPosition.getY());
                    if (dy > 10)
                        continue;
                    boolean sameTrack = isOnSameTrack(other);
                    boolean useFallback = !hasSufficientTrackData() || !other.hasSufficientTrackData();
                    if (!sameTrack && !useFallback)
                        continue;
                    if (!sameTrack && other.currentState == TrainState.TRAFFIC_JAM)
                        continue;
                    double toOtherX = other.currentPosition.getX() - currentPosition.getX();
                    double toOtherZ = other.currentPosition.getZ() - currentPosition.getZ();
                    double dot = toOtherX * headingX + toOtherZ * headingZ;
                    // dot > 0.3: train is within ~72° of our reverse movement direction
                    if (dot < 0.3)
                        continue;
                    if (!sameTrack) {
                        double perp = Math.abs(toOtherX * headingZ - toOtherZ * headingX);
                        if (perp > currentBeamTolerance)
                            continue;
                    }
                    CreateRailwayMod.aiLog("[AI] Train {} aborted reverse — {} in reverse path (heading scan)",
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
        if (currentPosition == null)
            return;
        if (headingX == 0 && headingZ == 0)
            return;
        // Reserve segment from current pos to 15 blocks ahead
        BlockPos aheadPos = new BlockPos(
                (int) (currentPosition.getX() + headingX * 15),
                currentPosition.getY(),
                (int) (currentPosition.getZ() + headingZ * 15));
        VirtualBlockSystem.getInstance().tryReserve(
                trainId, currentPosition, aheadPos, direction, currentTick);
    }

    // ─── State Machine ───

    private void transitionTo(TrainState newState, long currentTick, String reason) {
        if (newState == currentState)
            return;
        this.previousState = this.currentState;
        this.currentState = newState;
        this.stateEnteredTick = currentTick;

        // Track near-collision events
        if (newState == TrainState.WAIT_FOR_CLEARANCE && reason.startsWith("kinematic_critical")) {
            this.collisionAtTick = currentTick;
        }

        // WFC: lock obstacle for exit validation + reset distance history
        if (newState == TrainState.WAIT_FOR_CLEARANCE) {
            wfcObstacleTrainId = obstacleTrainId;
            Arrays.fill(wfcDistHistory, -1);
            wfcDistIdx = 0;
        } else {
            wfcObstacleTrainId = null;
        }

        // Leaving ANALYZING_OBSTACLE resets convoy counter so we don't carry
        // stale follow-ticks into an unrelated obstacle scenario.
        if (previousState == TrainState.ANALYZING_OBSTACLE && newState != TrainState.ANALYZING_OBSTACLE) {
            if (convoyLeaderId != null && obstacleTrainId != null
                    && !convoyLeaderId.equals(obstacleTrainId)) {
                convoyFollowTicks = 0;
                convoyLeaderId = null;
            }
        }

        // ── Priority / Pass-Signal: track starvation and grant / revoke tokens ──
        TrainAIManager prMgr = TrainAIManager.getInstance();
        if (prMgr != null) {
            if (newState == TrainState.YIELDING && obstacleTrainId != null) {
                // Entered YIELDING due to another train — increment starvation counter
                consecutiveYields++;
                if (yieldSessionStart < 0) yieldSessionStart = currentTick;
                if (consecutiveYields >= STARVATION_THRESHOLD) {
                    // We've yielded enough: earn priority right-of-way
                    long expiry = currentTick + PRIORITY_DURATION_TICKS;
                    prMgr.grantPriority(trainId, expiry);
                    CreateRailwayMod.aiLog(
                        "[AI] Train {} earned PRIORITY TOKEN (yielded {} times) – expires t+{}",
                        trainId.toString().substring(0, 8), consecutiveYields, PRIORITY_DURATION_TICKS);
                }
            } else if (newState == TrainState.CRUISING) {
                // Resumed normally — revoke any token and reset counter
                if (consecutiveYields > 0) {
                    prMgr.revokePriority(trainId);
                    CreateRailwayMod.aiDebug(
                        "[AI] Train {} priority token revoked (resumed CRUISING after {} yields)",
                        trainId.toString().substring(0, 8), consecutiveYields);
                    consecutiveYields = 0;
                    yieldSessionStart = -1;
                }
            }
        }

        // When starting bypass or reverse, shrink the scan beam for 5 seconds
        if ((newState == TrainState.BYPASSING || newState == TrainState.REVERSING) && obstacleTrainId != null) {
            bypassingTrainId = obstacleTrainId;
            bypassModeUntilTick = currentTick + BYPASS_MODE_TICKS;
        }

        CreateRailwayMod.aiLog("[AI] Train {} state: {} -> {} ({})",
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

    // ─── Reflection helpers ───

    static Field findField(Class<?> cls, String name) {
        // ── Static cache lookup — O(1) after first call per class+field combo ──
        String cacheKey = cls.getName();
        ConcurrentHashMap<String, Field> classCache = FIELD_CACHE.computeIfAbsent(cacheKey,
                k -> new ConcurrentHashMap<>());
        Field cached = classCache.get(name);
        if (cached != null)
            return cached;
        // sentinel: "" stored when the field truly doesn't exist → skip hierarchy again
        if (classCache.containsKey(name + "\0"))
            return null;

        // Check public fields first
        try {
            Field f = cls.getField(name);
            classCache.put(name, f);
            return f;
        } catch (NoSuchFieldException ignored) {
        }

        // Walk the class hierarchy checking declared fields
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                classCache.put(name, f);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            current = current.getSuperclass();
        }
        // Record miss so next call skips the walk
        classCache.put(name + "\0", null);
        return null;
    }

    static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        // ── Static cache lookup (keyed by name + param count to avoid ambiguity) ──
        String cacheKey = cls.getName();
        String methodKey = name + "/" + paramTypes.length;
        ConcurrentHashMap<String, Method> classCache = METHOD_CACHE.computeIfAbsent(cacheKey,
                k -> new ConcurrentHashMap<>());
        if (classCache.containsKey(methodKey + "\0"))
            return null; // cached miss
        Method cachedM = classCache.get(methodKey);
        if (cachedM != null)
            return cachedM;

        try {
            Method m = cls.getMethod(name, paramTypes);
            classCache.put(methodKey, m);
            return m;
        } catch (NoSuchMethodException ignored) {
        }

        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try {
                Method m = current.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                classCache.put(methodKey, m);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
            current = current.getSuperclass();
        }
        classCache.put(methodKey + "\0", null);
        return null;
    }

    // ─── Utility ───

    /**
     * Compute the EDGE-TO-EDGE gap between two trains (direction-aware).
     *
     * Create's position = center of the LEADING (first) carriage.
     * Front overhang from position ≈ 3 blocks (half of one carriage).
     * Rear overhang from position ≈ trainLength - 3 blocks.
     *
     * Which end faces the other train depends on heading:
     * - If B is AHEAD of A (dot > 0): A's FRONT (3b) faces B
     * - If B is BEHIND A (dot < 0): A's REAR (trainLength-3) faces B
     *
     * Same logic applies to B looking at A.
     *
     * v1.0.4 used trainLength/2 for both sides which was WRONG:
     * - Same-direction: subtracted too little from the leader (should be full rear)
     * - Head-on: subtracted too much from both (should be just 3+3=6)
     *
     * If the result is negative, the trains are physically overlapping.
     */
    static double distanceBetween(TrainAIController a, TrainAIController b) {
        double centerDist;
        double ax, az, bx, bz;
        // Prefer precise Vec3 positions for sub-block accuracy
        if (a.precisePosition != null && b.precisePosition != null) {
            centerDist = a.precisePosition.distanceTo(b.precisePosition);
            ax = a.precisePosition.x; az = a.precisePosition.z;
            bx = b.precisePosition.x; bz = b.precisePosition.z;
        } else if (a.currentPosition == null || b.currentPosition == null) {
            return Double.MAX_VALUE;
        } else {
            double dx = a.currentPosition.getX() - b.currentPosition.getX();
            double dy = a.currentPosition.getY() - b.currentPosition.getY();
            double dz = a.currentPosition.getZ() - b.currentPosition.getZ();
            centerDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            ax = a.currentPosition.getX(); az = a.currentPosition.getZ();
            bx = b.currentPosition.getX(); bz = b.currentPosition.getZ();
        }

        // Direction vector from A to B
        double toX = bx - ax;
        double toZ = bz - az;
        double toLen = Math.sqrt(toX * toX + toZ * toZ);

        // If trains are on top of each other (< 0.5 blocks center distance), overlap
        if (toLen < 0.5) return -1.0;

        toX /= toLen;
        toZ /= toLen;

        // Front overhang = half of one carriage (~3 blocks)
        // Rear overhang = rest of the train
        double FRONT_HALF = 3.0;

        // Which end of A faces B?
        // dotA > 0 = B is ahead of A → A's FRONT faces B (overhang = 3)
        // dotA < 0 = B is behind A → A's REAR faces B (overhang = trainLength - 3)
        double dotA = (a.headingX != 0 || a.headingZ != 0)
                ? (toX * a.headingX + toZ * a.headingZ)
                : 1.0; // no heading → assume B is ahead
        double overA = dotA >= 0
                ? FRONT_HALF
                : Math.max(FRONT_HALF, a.trainLength - FRONT_HALF);

        // Which end of B faces A? (reverse direction: A is at -toX,-toZ from B)
        double dotB = (b.headingX != 0 || b.headingZ != 0)
                ? (-toX * b.headingX + -toZ * b.headingZ)
                : 1.0; // no heading → assume A is ahead of B
        double overB = dotB >= 0
                ? FRONT_HALF
                : Math.max(FRONT_HALF, b.trainLength - FRONT_HALF);

        return centerDist - overA - overB;
    }

    /**
     * Raw center-to-center distance (used by clearBypass and other non-braking checks
     * where the physical gap doesn't matter — only spatial proximity).
     */
    private static double rawDistanceBetween(TrainAIController a, TrainAIController b) {
        if (a.precisePosition != null && b.precisePosition != null) {
            return a.precisePosition.distanceTo(b.precisePosition);
        }
        if (a.currentPosition == null || b.currentPosition == null)
            return Double.MAX_VALUE;
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

    /** Send a chat message only to players wearing our AI Goggles within range. */
    @SuppressWarnings("null")
    private void broadcastToNearbyPlayers(ServerLevel level, String message, double range) {
        if (currentPosition == null || level == null)
            return;
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

    // ─── Player Safety System (v1.0.3) ───

    /**
     * Scan for players standing on or near the tracks within the configured range
     * ahead.
     * Uses the heading vector to only detect players in the train's forward path.
     * Lateral tolerance matches playerSafeDistanceFromRail — if a player is farther
     * than that from the track center, they are considered safe.
     *
     * Performance: only iterates server players (small list even on 110+ player
     * servers),
     * uses squared-distance pre-filter and dot-product directional check. No block
     * scanning.
     */
    private ServerPlayer scanForPlayerOnTrack(ServerLevel level) {
        if (currentPosition == null || (headingX == 0 && headingZ == 0))
            return null;

        double range = RailwayConfig.playerDetectionRange.get();
        double safeDist = RailwayConfig.playerSafeDistanceFromRail.get();
        double rangeSq = range * range;

        ServerPlayer closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive())
                continue;
            // Skip players riding any entity (train passengers, horses, etc.)
            // — they are NOT "on tracks", they are controlled by the vehicle.
            // Without this, safe mode brakes for its OWN passengers.
            if (player.isPassenger())
                continue;

            // Quick squared-distance pre-filter
            double dx = player.getX() - currentPosition.getX();
            double dz = player.getZ() - currentPosition.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq > rangeSq || distSq < 1.0)
                continue; // too far or riding the train

            // Height check — player must be at roughly the same level as the train
            double dy = Math.abs(player.getY() - currentPosition.getY());
            if (dy > 3)
                continue;

            // Directional check — player must be AHEAD of the train
            double dot = dx * headingX + dz * headingZ;
            if (dot < 0)
                continue; // behind us

            // Lateral check — player must be close to the track center line
            double perpDist = Math.abs(dx * headingZ - dz * headingX);
            if (perpDist > safeDist)
                continue; // player is far enough from the rail

            if (distSq < closestDistSq) {
                closest = player;
                closestDistSq = distSq;
            }
        }

        return closest;
    }

    /**
     * Handle player detected on tracks ahead.
     *
     * Phase 1 (Warning): If train has a whistle/horn/bell block, play warning
     * sound.
     * Player has playerWhistleWarningTicks (default 3 seconds) to move >= 2 blocks
     * from the rail center. During this phase, train slows to 30% max speed.
     *
     * Phase 2 (Emergency Braking): If no whistle, or warning time expired and
     * player
     * is still on tracks, train performs graduated emergency braking to stop 5
     * blocks
     * from the player.
     */
    private void handlePlayerOnTrack(ServerPlayer player, double distance, long currentTick) {
        double emergencyStop = RailwayConfig.playerEmergencyStopDistance.get();
        int whistleWarningTicks = RailwayConfig.playerWhistleWarningTicks.get();

        // Always hard stop if critically close, regardless of whistle state
        if (distance <= emergencyStop) {
            forceStop();
            transitionTo(TrainState.WAIT_FOR_CLEARANCE, currentTick,
                    "player_emergency_stop_d=" + (int) distance);
            return;
        }

        // Check whistle (cached, re-scanned every 5 seconds)
        if (!whistleCached || currentTick > whistleCacheExpireTick) {
            hasWhistleBlock = detectWhistleOnTrain();
            whistleCached = true;
            whistleCacheExpireTick = currentTick + 100; // re-check every 5s
        }

        boolean samePlayer = player.getUUID().equals(warningPlayerUUID);

        if (hasWhistleBlock) {
            // ── Phase 1: Whistle warning ──
            if (!samePlayer || playerWhistleStartTick < 0) {
                // Start new warning cycle for this player
                playerWhistleStartTick = currentTick;
                warningPlayerUUID = player.getUUID();
            }

            // Play whistle sound every second (20 ticks)
            if ((currentTick - playerWhistleStartTick) % 20 == 0) {
                playWhistleSound(player);
            }

            long warningElapsed = currentTick - playerWhistleStartTick;

            if (warningElapsed < whistleWarningTicks) {
                // Still in warning phase — approach slowly
                double approachSpeed = maxSpeed * 0.3;
                if (distance <= emergencyStop * 2) {
                    approachSpeed = maxSpeed * 0.1;
                }
                applySpeedControl(approachSpeed);
                transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick,
                        "whistle_warning_d=" + (int) distance);
                return;
            }
            // Warning expired and player still on tracks — fall through to emergency brake
        }

        // ── Phase 2: Graduated emergency braking ──
        if (distance <= emergencyStop * 2) {
            // Very close — hard stop
            smoothStop();
            transitionTo(TrainState.YIELDING, currentTick,
                    "player_brake_close_d=" + (int) distance);
        } else if (distance <= emergencyStop * 4) {
            // Close — crawl
            applySpeedControl(maxSpeed * 0.2);
            transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick,
                    "player_brake_mid_d=" + (int) distance);
        } else {
            // Detected at range — reduce to half speed
            applySpeedControl(maxSpeed * 0.5);
            transitionTo(TrainState.ANALYZING_OBSTACLE, currentTick,
                    "player_detected_d=" + (int) distance);
        }
    }

    /**
     * Detect if this train has a Steam Whistle, Horn, or Bell block mounted on any
     * carriage.
     * Inspects Create's Contraption block data via reflection.
     *
     * Checks registry names for: "whistle", "horn", "bell" (covers Create's
     * steam_whistle,
     * peculiar_bell, and any addon horn/whistle blocks).
     */
    private boolean detectWhistleOnTrain() {
        if (createTrainRef == null || carriagesField == null)
            return false;
        try {
            List<?> carriages = (List<?>) carriagesField.get(createTrainRef);
            if (carriages == null)
                return false;

            for (Object carriage : carriages) {
                Method anyEntity = findMethod(carriage.getClass(), "anyAvailableEntity");
                if (anyEntity == null)
                    continue;
                Object entity = anyEntity.invoke(carriage);
                if (entity == null)
                    continue;

                // CarriageContraptionEntity → getContraption()
                Method getContraption = findMethod(entity.getClass(), "getContraption");
                if (getContraption == null)
                    continue;
                Object contraption = getContraption.invoke(entity);
                if (contraption == null)
                    continue;

                // Contraption.blocks (field) or getBlocks() (method)
                Object blocksObj = null;
                Field blocksField = findField(contraption.getClass(), "blocks");
                if (blocksField != null)
                    blocksObj = blocksField.get(contraption);
                if (blocksObj == null) {
                    Method getBlocksM = findMethod(contraption.getClass(), "getBlocks");
                    if (getBlocksM != null)
                        blocksObj = getBlocksM.invoke(contraption);
                }

                if (blocksObj instanceof Map<?, ?> blocks) {
                    for (Object info : blocks.values()) {
                        // StructureBlockInfo.state() (record accessor) or field "state"
                        Object blockState = null;
                        Method stateMethod = findMethod(info.getClass(), "state");
                        if (stateMethod != null)
                            blockState = stateMethod.invoke(info);
                        if (blockState == null) {
                            Field stateF = findField(info.getClass(), "state");
                            if (stateF != null)
                                blockState = stateF.get(info);
                        }
                        if (blockState == null)
                            continue;

                        // BlockState.getBlock() → Block
                        Method getBlock = findMethod(blockState.getClass(), "getBlock");
                        if (getBlock == null)
                            continue;
                        Object block = getBlock.invoke(blockState);
                        if (!(block instanceof net.minecraft.world.level.block.Block b))
                            continue;

                        var rl = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
                        if (rl != null) {
                            String path = rl.getPath();
                            if (path.contains("whistle") || path.contains("horn") || path.contains("bell")) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            CreateRailwayMod.aiDebug("[AI] Whistle detection failed for {}: {}",
                    trainId.toString().substring(0, 8), e.getMessage());
        }
        return false;
    }

    /**
     * Play a whistle/horn warning sound at the player's position.
     * Attempts to use Create's "create:whistle_train_low" sound first,
     * falls back to vanilla bell block sound.
     */
    @SuppressWarnings("removal")
    private void playWhistleSound(ServerPlayer player) {
        if (lastKnownLevel == null || currentPosition == null)
            return;

        // Set train.honk = true so Create's own conductor/whistle contraption
        // activates.
        if (createTrainRef != null) {
            try {
                Field honkF = findField(createTrainRef.getClass(), "honk");
                if (honkF != null)
                    honkF.setBoolean(createTrainRef, true);
            } catch (Exception ignored) {
            }
        }

        // Play warning sound at the TRAIN position (not player position) so it
        // sounds like it's coming from the approaching locomotive.
        net.minecraft.core.BlockPos trainPos = currentPosition;
        try {
            // Try all known Create whistle/horn sound names for 1.20.1
            String[] candidates = {
                    "create:block.steam_whistle.steam",
                    "create:block.steam_whistle.all",
                    "create:train_whistle_low",
                    "create:whistle_train_low"
            };
            for (String soundId : candidates) {
                java.util.Optional<net.minecraft.sounds.SoundEvent> evtOpt = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT
                        .getOptional(
                                net.minecraft.resources.ResourceLocation.tryParse(soundId));
                if (evtOpt.isPresent()) {
                    lastKnownLevel.playSound(null, trainPos, evtOpt.get(),
                            net.minecraft.sounds.SoundSource.BLOCKS, 3.0f, 0.8f);
                    return;
                }
            }
            // Fallback: vanilla bell — always registered
            lastKnownLevel.playSound(null, trainPos,
                    net.minecraft.sounds.SoundEvents.BELL_BLOCK,
                    net.minecraft.sounds.SoundSource.BLOCKS, 3.0f, 0.5f);
        } catch (Exception e) {
            CreateRailwayMod.aiDebug("[AI] Failed to play whistle sound: {}", e.getMessage());
        }
    }

    /**
     * Robust player detection — 3 independent layers so it works regardless of
     * Create version or field name changes.
     *
     * Layer 1: reflection on carriagesField → anyAvailableEntity / entity field
     * Layer 2: scan world entities near the train for OUR OWN carriage entities with players
     *
     * NOTE: Layer 3 (proximity scan for any player within 5 blocks) was REMOVED.
     * It caused ALL AI trains near the player to get playerControlled=true,
     * disabling their collision detection entirely → chain crashes.
     */
    private boolean detectPlayerRiding(ServerLevel level) {
        // ── Layer 1: carriages reflection ──
        if (carriagesField != null && createTrainRef != null) {
            try {
                List<?> carriages = (List<?>) carriagesField.get(createTrainRef);
                if (carriages != null) {
                    for (Object carriage : carriages) {
                        // Try multiple method names Create uses across versions
                        for (String mName : new String[]{"anyAvailableEntity", "entity", "getEntity", "getContraptionEntity"}) {
                            Method anyEnt = findMethod(carriage.getClass(), mName);
                            if (anyEnt == null) continue;
                            try {
                                Object entityObj = anyEnt.invoke(carriage);
                                // Handle Optional<Entity>
                                if (entityObj instanceof java.util.Optional<?> opt) {
                                    entityObj = opt.orElse(null);
                                }
                                if (entityObj instanceof net.minecraft.world.entity.Entity e) {
                                    for (net.minecraft.world.entity.Entity p : e.getPassengers()) {
                                        if (p instanceof ServerPlayer) return true;
                                    }
                                }
                            } catch (Exception ignored) {}
                            break; // found method, tried it — move to next carriage
                        }
                        // Also try direct "entity" field
                        try {
                            Field entField = findField(carriage.getClass(), "entity");
                            if (entField != null) {
                                Object entityObj = entField.get(carriage);
                                if (entityObj instanceof java.util.Optional<?> opt) {
                                    entityObj = opt.orElse(null);
                                }
                                if (entityObj instanceof net.minecraft.world.entity.Entity e) {
                                    for (net.minecraft.world.entity.Entity p : e.getPassengers()) {
                                        if (p instanceof ServerPlayer) return true;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }

        // ── Layer 2: world entity scan — only OUR carriage entities ──
        // Scans a tight box (5 blocks) and only matches entities whose train UUID
        // equals THIS controller's trainId to avoid false positives from adjacent trains.
        if (currentPosition != null && createTrainRef != null) {
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                    currentPosition.getX() - 5, currentPosition.getY() - 4, currentPosition.getZ() - 5,
                    currentPosition.getX() + 5, currentPosition.getY() + 4, currentPosition.getZ() + 5);
            try {
                List<net.minecraft.world.entity.Entity> near = level.getEntitiesOfClass(
                        net.minecraft.world.entity.Entity.class, box);
                for (net.minecraft.world.entity.Entity entity : near) {
                    String sn = entity.getClass().getSimpleName();
                    if (!sn.contains("Carriage") && !sn.contains("Contraption")) continue;
                    // Guard: only count carriages belonging to OUR train
                    // Check by seeing if this entity is referenced in our own carriage list
                    boolean isOurCarriage = false;
                    try {
                        List<?> carriages = (List<?>) carriagesField.get(createTrainRef);
                        if (carriages != null) {
                            for (Object c : carriages) {
                                for (String mName : new String[]{"anyAvailableEntity", "entity", "getEntity"}) {
                                    Method m = findMethod(c.getClass(), mName);
                                    if (m == null) continue;
                                    Object eObj = m.invoke(c);
                                    if (eObj instanceof java.util.Optional<?> opt) eObj = opt.orElse(null);
                                    if (entity.equals(eObj)) { isOurCarriage = true; break; }
                                }
                                if (isOurCarriage) break;
                            }
                        }
                    } catch (Exception ignored) {}
                    if (!isOurCarriage) continue;
                    for (net.minecraft.world.entity.Entity passenger : entity.getPassengers()) {
                        if (passenger instanceof ServerPlayer) return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }



    /** Distance from train to a player (prefers precise Vec3 position). */

    private double distanceToPlayer(ServerPlayer player) {
        if (precisePosition != null) {
            return precisePosition.distanceTo(player.position());
        }
        if (currentPosition == null)
            return Double.MAX_VALUE;
        double dx = currentPosition.getX() + 0.5 - player.getX();
        double dy = currentPosition.getY() - player.getY();
        double dz = currentPosition.getZ() + 0.5 - player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ─── Getters ───

    public UUID getTrainId() {
        return trainId;
    }

    public TrainState getCurrentState() {
        return currentState;
    }

    public BlockPos getCurrentPosition() {
        return currentPosition;
    }

    public Vec3 getPrecisePosition() {
        return precisePosition;
    }

    public double getCurrentSpeed() {
        return currentSpeed;
    }

    public double getHeadingX() {
        return headingX;
    }

    public double getHeadingZ() {
        return headingZ;
    }

    public boolean isOnOppositeTrack() {
        return onOppositeTrack;
    }

    public boolean isEmergency() {
        return isEmergency;
    }

    public boolean isPlayerControlled() {
        return playerControlled;
    }

    public void setEmergency(boolean emergency) {
        this.isEmergency = emergency;
    }

    public void setPosition(BlockPos pos) {
        this.currentPosition = pos;
    }

    // ── Control-panel API ──
    public boolean isChatSilenced() {
        return chatSilenced;
    }

    public void setChatSilenced(boolean v) {
        this.chatSilenced = v;
    }

    public boolean isAiEnabled() {
        return aiEnabled;
    }

    public void setAiEnabled(boolean v) {
        this.aiEnabled = v;
    }

    /**
     * Returns true for 60 s after a buffer-critical near-collision was recorded.
     */
    public boolean hadRecentCollision(long currentTick) {
        return collisionAtTick > 0 && (currentTick - collisionAtTick) < 1200;
    }

    public void clearCollision() {
        this.collisionAtTick = -1;
    }

    public String getTrainDisplayName() {
        return trainDisplayName;
    }

    public void setTrainDisplayName(String n) {
        this.trainDisplayName = n == null ? "" : n;
    }

    // ─── TrackGraph Getters (for debug visualizer and external tools) ───
    /**
     * Signal block group UUIDs this train currently occupies (from Create's
     * occupiedSignalBlocks).
     */
    public Set<UUID> getSignalGroups() {
        return Collections.unmodifiableSet(myOccupiedSignalGroups);
    }

    /**
     * TravellingPoint.edge of the leading carriage (object identity). Null if not
     * available.
     */
    public Object getLeadingEdge() {
        return myLeadingEdge;
    }

    /**
     * TravellingPoint.edge of the trailing carriage (object identity). Null if not
     * available.
     */
    public Object getTrailingEdge() {
        return myTrailingEdge;
    }

    /** The TrackGraph this train belongs to. Null if not available. */
    public Object getTrackGraph() {
        return myGraph;
    }

    /** Current beam lateral tolerance — 1.1 on straight, 1.0 on curve. */
    public double getCurrentBeamTolerance() {
        return currentBeamTolerance;
    }

    /** Whether the leading edge is a curve (TrackEdge.isTurn()). */
    public boolean isOnCurvedEdge() {
        return currentBeamTolerance <= BEAM_LATERAL_TOLERANCE_CURVE;
    }

    /**
     * Graph distance (blocks along rail) to the last detected obstacle. -1 if
     * unavailable.
     */
    public double getGraphDistanceToObstacle() {
        return graphDistanceToObstacle;
    }

    /**
     * Whether the last obstacle detection was done via graph walk (true) or beam
     * fallback (false).
     */
    public boolean isGraphScanActive() {
        return graphScanActive;
    }

    /**
     * Leading edge hashCode for display — stable identifier for the current rail
     * segment.
     */
    public int getLeadingEdgeHash() {
        return myLeadingEdge != null ? System.identityHashCode(myLeadingEdge) : 0;
    }

    /** UUID of the train currently blocking this train (null if none). */
    public UUID getObstacleTrainId() {
        return obstacleTrainId;
    }

    /**
     * Tell this train to ignore a specific other train for a fixed window.
     * Called by a yielding train so the priority train can pass without braking.
     * Uses the existing bypass-mode infrastructure (bypassingTrainId /
     * bypassModeUntilTick).
     *
     * @param trainToIgnore UUID of the train that yielded and should be invisible
     * @param untilTick     game tick after which the ignore expires
     */
    public void setBypassIgnore(UUID trainToIgnore, long untilTick) {
        this.bypassingTrainId = trainToIgnore;
        this.bypassModeUntilTick = untilTick;
    }

    /** True when BFS confirmed the obstacle is head-on (oncoming). */
    public boolean isGraphHitHeadOn() {
        return graphHitIsHeadOn;
    }

    /**
     * True when this train is moving against the edge's natural node1→node2
     * direction.
     */
    public boolean isWrongWay() {
        return isWrongWay;
    }

    /** True when there is no train directly behind (safe to reverse). */
    public boolean isSpaceAvailableBehind() {
        return spaceAvailableBehind;
    }

    /**
     * True when BFS found a free alternative branch while the primary route is
     * blocked.
     */
    public boolean isGraphFoundFreeDetour() {
        return graphFoundFreeDetour;
    }

    /**
     * True when a junction ahead has insufficient exit space for the full train.
     */
    public boolean isGraphJunctionNotEnoughSpace() {
        return graphJunctionNotEnoughSpace;
    }

    /** The underlying Create Train object (for schedule programming etc.). */
    public Object getCreateTrainRef() {
        return createTrainRef;
    }

    @Override
    public String toString() {
        return String.format("TrainAI[%s, state=%s, speed=%.2f, pos=%s]",
                trainId.toString().substring(0, 8), currentState, currentSpeed,
                currentPosition != null ? currentPosition.toShortString() : "null");
    }
}
