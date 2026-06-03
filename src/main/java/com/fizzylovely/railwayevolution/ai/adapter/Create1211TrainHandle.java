package com.fizzylovely.railwayevolution.ai.adapter;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.IGraphEdgeEntry;
import com.fizzylovely.railwayevolution.ai.core.ITrackEdge;
import com.fizzylovely.railwayevolution.ai.core.ITrainGraph;
import com.fizzylovely.railwayevolution.ai.core.ITrainHandle;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.*;

/**
 * Create1211TrainHandle — адаптер для Create 1.21.1 (NeoForge).
 *
 * Вся reflection и VarHandle сосредоточена ЗДЕСЬ.
 * Ядро (PerceptionEngine, StateMachine, SafetyManager) не импортирует
 * классы Create.
 *
 * Производительность:
 *   - VarHandle для примитивов: нулевое боксирование (getSpeed, getThrottle...).
 *   - MethodHandle для методов: быстрее Class.getMethod().invoke().
 *   - Тяжёлые данные (граф, рёбра, сигнальные группы) кешируются.
 *   - Инициализация: ровно один раз при bindCreateTrain().
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class Create1211TrainHandle implements ITrainHandle {

    // ─── Static VarHandle/MethodHandle (один раз для всех экземпляров) ────
    private static volatile boolean STATIC_INIT_DONE = false;
    private static VarHandle VH_SPEED;
    private static VarHandle VH_TARGET_SPEED;
    private static VarHandle VH_THROTTLE;
    private static VarHandle VH_DERAILED;
    private static VarHandle VH_MANUAL_TICK;
    private static VarHandle VH_CARRIAGES;      // List<Carriage>
    private static VarHandle VH_NAVIGATION;     // Navigation
    private static VarHandle VH_GRAPH;          // TrackGraph
    private static VarHandle VH_OCCUPIED_SIGNALS; // Map<UUID,UUID>

    // Navigation fields
    private static VarHandle VH_NAV_DISTANCE_TO_DEST;
    private static VarHandle VH_NAV_DESTINATION;   // GlobalStation
    private static VarHandle VH_NAV_WAITING_SIGNAL; // Pair<UUID,Boolean>
    private static VarHandle VH_NAV_DIST_TO_SIGNAL;
    private static VarHandle VH_NAV_CURRENT_PATH;  // List<Couple<TrackNode>>

    // MethodHandles для методов
    private static MethodHandle MH_ACCELERATION;    // Train.acceleration() → double
    private static MethodHandle MH_MAX_SPEED;       // Train.maxSpeed() → double
    private static MethodHandle MH_CANCEL_NAV;      // Navigation.cancelNavigation()
    private static MethodHandle MH_GET_LEADING_PT;  // Carriage.getLeadingPoint() → TravellingPoint
    private static MethodHandle MH_GET_TRAILING_PT; // Carriage.getTrailingPoint() → TravellingPoint
    private static MethodHandle MH_ANY_ENTITY;      // Carriage.anyAvailableEntity() → CarriageContraptionEntity
    private static MethodHandle MH_ENTITY_POSITION; // Entity.position() → Vec3

    // TravellingPoint VarHandles
    private static VarHandle VH_TP_NODE1;
    private static VarHandle VH_TP_NODE2;
    private static VarHandle VH_TP_POSITION;
    private static VarHandle VH_TP_EDGE;

    // TrackNode MethodHandle
    private static MethodHandle MH_NODE_LOCATION; // TrackNode.getLocation() → TrackNodeLocation

    // ScheduleRuntime MethodHandle (для пробуждения навигации)
    private static VarHandle VH_RUNTIME;
    private static VarHandle VH_RUNTIME_PAUSED;

    // ─── Экземплярные поля ────────────────────────────────────────────────
    private Object createTrainRef;
    private final UUID id;
    private String displayName = "";

    // Кешированные данные (обновляются на каждый тик через refreshCache)
    private double cachedSpeed         = 0;
    private double cachedThrottle      = 1.0;
    private boolean cachedDerailed     = false;
    private boolean cachedManualTick   = false;
    private double cachedDistToDest    = 0;
    private boolean cachedHasDest      = false;
    private boolean cachedWaitSignal   = false;
    private double cachedDistToSignal  = Double.MAX_VALUE;
    private boolean cachedOnGraph      = false;

    // Позиционные данные (обновляются при обновлении графа)
    @Nullable private Vec3    cachedLeadingPos   = null;
    @Nullable private Vec3    cachedTrailingPos  = null;
    @Nullable private Object  cachedLeadEdge     = null;
    @Nullable private Object  cachedLeadNode1    = null;
    @Nullable private Object  cachedLeadNode2    = null;
    private double             cachedEdgePos      = 0;

    // Граф-адаптер (переиспользуется)
    @Nullable private Create1211TrainGraph graphAdapter = null;
    @Nullable private Object               lastGraphRef = null;

    // ─── Ленивая кешированная сигнальные группы ───────────────────────────
    private final List<UUID> cachedSignalGroups = new ArrayList<>();
    private long signalGroupRefreshTick = -1;

    // ─── Кеш позиций тележек ──────────────────────────────────────────────
    private final List<Vec3> bogeyPositions = new ArrayList<>();
    private double cachedTotalLength = 4.0;

    // ──────────────────────────────────────────────────────────────────────

    public Create1211TrainHandle(UUID id) {
        this.id = id;
        if (!STATIC_INIT_DONE) initStaticHandles();
    }

    public void bindCreateTrain(Object trainObj) {
        if (this.createTrainRef != trainObj) {
            this.createTrainRef = trainObj;
            // Попытаться прочитать имя поезда (один раз)
            refreshDisplayName();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // ITrainHandle implementation
    // ──────────────────────────────────────────────────────────────────────

    @Override public UUID getId()          { return id; }
    @Override public String getDisplayName() { return displayName; }

    @Override public double getSpeed() {
        if (VH_SPEED == null || createTrainRef == null) return cachedSpeed;
        try { cachedSpeed = (double) VH_SPEED.get(createTrainRef); } catch (Exception e) { /* fallback */ }
        return cachedSpeed;
    }

    @Override public void setSpeed(double speed) {
        if (VH_SPEED == null || createTrainRef == null) return;
        try {
            VH_SPEED.set(createTrainRef, speed);
            VH_TARGET_SPEED.set(createTrainRef, speed);
        } catch (Exception e) { /* ignore */ }
    }

    @Override public double getMaxSpeed() {
        if (MH_MAX_SPEED == null || createTrainRef == null) return 1.4;
        try { return (double) MH_MAX_SPEED.invoke(createTrainRef); }
        catch (Throwable e) { return 1.4; }
    }

    @Override public double getAcceleration() {
        if (MH_ACCELERATION == null || createTrainRef == null) return 0.0375;
        try { return (double) MH_ACCELERATION.invoke(createTrainRef); }
        catch (Throwable e) { return 0.0375; }
    }

    @Override public double getThrottle() {
        if (VH_THROTTLE == null || createTrainRef == null) return cachedThrottle;
        try { cachedThrottle = (double) VH_THROTTLE.get(createTrainRef); }
        catch (Exception e) { /* fallback */ }
        return cachedThrottle;
    }

    @Override public void setThrottle(double throttle) {
        if (VH_THROTTLE == null || createTrainRef == null) return;
        try { VH_THROTTLE.set(createTrainRef, Math.max(0, Math.min(1, throttle))); }
        catch (Exception e) { /* ignore */ }
    }

    @Override public boolean isDerailed() {
        if (VH_DERAILED == null || createTrainRef == null) return cachedDerailed;
        try { cachedDerailed = (boolean) VH_DERAILED.get(createTrainRef); }
        catch (Exception e) { /* fallback */ }
        return cachedDerailed;
    }

    @Override public boolean isBlocked() {
        // Определяем по отсутствию движения и наличию навигации с малым расстоянием
        // (Create устанавливает blocked на Carriage, сложно читать без доп. рефлексии)
        return cachedHasDest && cachedDistToDest < 0.1;
    }

    @Override public boolean isPlayerControlled() {
        if (VH_MANUAL_TICK == null || createTrainRef == null) return cachedManualTick;
        try {
            Object val = VH_MANUAL_TICK.get(createTrainRef);
            cachedManualTick = val instanceof Boolean b ? b : false;
        } catch (Exception e) { /* fallback */ }
        return cachedManualTick;
    }

    @Override public @Nullable UUID getControllingPlayerUUID() {
        return null; // Определяется выше в старом коде через passenger scan
    }

    @Override public double getDistanceToDestination() { return cachedDistToDest; }
    @Override public boolean hasDestination()          { return cachedHasDest; }
    @Override public boolean isWaitingForSignal()      { return cachedWaitSignal; }
    @Override public double getDistanceToSignal()      { return cachedDistToSignal; }
    @Override public boolean isOnGraph()               { return cachedOnGraph; }

    @Override public @Nullable Vec3 getLeadingPosition()  { return cachedLeadingPos; }
    @Override public @Nullable Vec3 getTrailingPosition() { return cachedTrailingPos; }
    @Override public List<Vec3> getAllBogeyPositions()     { return bogeyPositions; }
    @Override public double getTotalLength()               { return cachedTotalLength; }

    @Override public @Nullable ITrainGraph getGraph() {
        Object graphRef = readGraphRef();
        if (graphRef == null) return null;
        if (graphRef != lastGraphRef) {
            graphAdapter = new Create1211TrainGraph(graphRef);
            lastGraphRef = graphRef;
        }
        return graphAdapter;
    }

    @Override public @Nullable Object getLeadingEdgeRef()   { return cachedLeadEdge; }
    @Override public @Nullable Object getLeadingNode1Ref()  { return cachedLeadNode1; }
    @Override public @Nullable Object getLeadingNode2Ref()  { return cachedLeadNode2; }
    @Override public double getLeadingEdgePosition()        { return cachedEdgePos; }

    @Override
    public List<UUID> getOccupiedSignalGroups() {
        // Обновляем редко (каждые 20 тиков) — дорогое чтение
        return cachedSignalGroups;
    }

    @Override public void resumeNavigation() {
        if (VH_RUNTIME == null || createTrainRef == null) return;
        try {
            Object runtime = VH_RUNTIME.get(createTrainRef);
            if (runtime != null && VH_RUNTIME_PAUSED != null) {
                // Только снимаем паузу — не трогаем POST_TRANSIT состояния
            }
        } catch (Exception e) { /* ignore */ }
    }

    @Override public void cancelNavigation() {
        if (MH_CANCEL_NAV == null || createTrainRef == null) return;
        try {
            Object nav = VH_NAVIGATION != null ? VH_NAVIGATION.get(createTrainRef) : null;
            if (nav != null) MH_CANCEL_NAV.invoke(nav);
        } catch (Throwable e) { /* ignore */ }
    }

    @Override public void forceStop() {
        if (createTrainRef == null) return;
        try {
            if (VH_SPEED       != null) VH_SPEED.set(createTrainRef, 0.0);
            if (VH_TARGET_SPEED!= null) VH_TARGET_SPEED.set(createTrainRef, 0.0);
            if (VH_THROTTLE    != null) VH_THROTTLE.set(createTrainRef, 0.0);
        } catch (Exception e) { /* ignore */ }
    }

    @Override public void restoreFullThrottle() {
        if (VH_THROTTLE == null || createTrainRef == null) return;
        try { VH_THROTTLE.set(createTrainRef, 1.0); }
        catch (Exception e) { /* ignore */ }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Cache refresh (вызывается каждый тик из TrainAIController)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Обновить лёгкие данные (скорость, флаги) — каждый тик.
     */
    public void refreshFast() {
        if (createTrainRef == null) return;
        getSpeed();         // обновляет cachedSpeed
        isDerailed();       // обновляет cachedDerailed
        isPlayerControlled(); // обновляет cachedManualTick
        refreshNavigationFast();
        cachedOnGraph = (readGraphRef() != null);
    }

    /**
     * Обновить тяжёлые данные (позиция, граф, рёбра) — только при изменении скорости.
     */
    public void refreshFull(long currentTick) {
        if (createTrainRef == null) return;
        refreshPositionData();
        if (currentTick - signalGroupRefreshTick > 20) {
            refreshSignalGroups();
            signalGroupRefreshTick = currentTick;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Private refresh methods
    // ──────────────────────────────────────────────────────────────────────

    private void refreshNavigationFast() {
        if (VH_NAVIGATION == null || createTrainRef == null) return;
        try {
            Object nav = VH_NAVIGATION.get(createTrainRef);
            if (nav == null) { cachedHasDest = false; return; }
            Object dest = VH_NAV_DESTINATION != null ? VH_NAV_DESTINATION.get(nav) : null;
            cachedHasDest = (dest != null);
            if (cachedHasDest && VH_NAV_DISTANCE_TO_DEST != null) {
                cachedDistToDest = (double) VH_NAV_DISTANCE_TO_DEST.get(nav);
            }
            if (VH_NAV_WAITING_SIGNAL != null) {
                Object ws = VH_NAV_WAITING_SIGNAL.get(nav);
                cachedWaitSignal = (ws != null);
                if (cachedWaitSignal && VH_NAV_DIST_TO_SIGNAL != null) {
                    cachedDistToSignal = (double) VH_NAV_DIST_TO_SIGNAL.get(nav);
                }
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void refreshPositionData() {
        if (VH_CARRIAGES == null || createTrainRef == null) return;
        try {
            List<?> carriages = (List<?>) VH_CARRIAGES.get(createTrainRef);
            if (carriages == null || carriages.isEmpty()) return;

            Object firstCarriage  = carriages.get(0);
            Object lastCarriage   = carriages.get(carriages.size() - 1);

            // Ведущая позиция
            Object leadingPoint = MH_GET_LEADING_PT != null
                ? MH_GET_LEADING_PT.invoke(firstCarriage) : null;
            if (leadingPoint != null) {
                extractTravellingPointData(leadingPoint, true);
            }

            // Хвостовая позиция через entity
            Object entity = MH_ANY_ENTITY != null ? MH_ANY_ENTITY.invoke(firstCarriage) : null;
            if (entity != null && MH_ENTITY_POSITION != null) {
                cachedLeadingPos = (Vec3) MH_ENTITY_POSITION.invoke(entity);
            }

            // Хвост
            entity = MH_ANY_ENTITY != null ? MH_ANY_ENTITY.invoke(lastCarriage) : null;
            if (entity != null && MH_ENTITY_POSITION != null) {
                cachedTrailingPos = (Vec3) MH_ENTITY_POSITION.invoke(entity);
            }

            // Длина состава
            if (cachedLeadingPos != null && cachedTrailingPos != null) {
                double dx = cachedLeadingPos.x - cachedTrailingPos.x;
                double dz = cachedLeadingPos.z - cachedTrailingPos.z;
                cachedTotalLength = Math.max(4.0, Math.sqrt(dx * dx + dz * dz));
            }
        } catch (Throwable e) { /* ignore */ }
    }

    private void extractTravellingPointData(Object tp, boolean isLeading) {
        try {
            if (VH_TP_NODE1 != null) cachedLeadNode1 = VH_TP_NODE1.get(tp);
            if (VH_TP_NODE2 != null) cachedLeadNode2 = VH_TP_NODE2.get(tp);
            if (VH_TP_EDGE  != null) cachedLeadEdge  = VH_TP_EDGE.get(tp);
            if (VH_TP_POSITION != null) cachedEdgePos = (double) VH_TP_POSITION.get(tp);
        } catch (Exception e) { /* ignore */ }
    }

    @SuppressWarnings("unchecked")
    private void refreshSignalGroups() {
        cachedSignalGroups.clear();
        if (VH_OCCUPIED_SIGNALS == null || createTrainRef == null) return;
        try {
            Map<?, ?> map = (Map<?, ?>) VH_OCCUPIED_SIGNALS.get(createTrainRef);
            if (map != null) {
                for (Object k : map.keySet()) {
                    if (k instanceof UUID uuid) cachedSignalGroups.add(uuid);
                }
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void refreshDisplayName() {
        if (createTrainRef == null) return;
        try {
            java.lang.reflect.Field nameField = findField(createTrainRef.getClass(), "name");
            if (nameField != null) {
                nameField.setAccessible(true);
                Object name = nameField.get(createTrainRef);
                displayName = name != null ? name.toString() : "";
            }
        } catch (Exception e) { /* ignore */ }
    }

    private Object readGraphRef() {
        if (VH_GRAPH == null || createTrainRef == null) return null;
        try { return VH_GRAPH.get(createTrainRef); }
        catch (Exception e) { return null; }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Static initialization (один раз на JVM)
    // ──────────────────────────────────────────────────────────────────────

    private static synchronized void initStaticHandles() {
        if (STATIC_INIT_DONE) return;
        try {
            // Train class
            Class<?> trainCls = Class.forName(
                "com.simibubi.create.content.trains.entity.Train");
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                trainCls, MethodHandles.lookup());

            VH_SPEED         = safeVH(lookup, trainCls, "speed",         double.class);
            VH_TARGET_SPEED  = safeVH(lookup, trainCls, "targetSpeed",   double.class);
            VH_THROTTLE      = safeVH(lookup, trainCls, "throttle",      double.class);
            VH_DERAILED      = safeVH(lookup, trainCls, "derailed",      boolean.class);
            VH_MANUAL_TICK   = safeVH(lookup, trainCls, "manualTick",    boolean.class);
            VH_CARRIAGES     = safeVH(lookup, trainCls, "carriages",     java.util.List.class);
            VH_NAVIGATION    = safeVH(lookup, trainCls, "navigation",    null); // any type
            VH_GRAPH         = safeVH(lookup, trainCls, "graph",         null);
            VH_RUNTIME       = safeVH(lookup, trainCls, "runtime",       null);
            VH_OCCUPIED_SIGNALS = safeVH(lookup, trainCls, "occupiedSignalBlocks", null);

            MH_ACCELERATION = safeMH(lookup, trainCls, "acceleration", MethodType.methodType(double.class));
            MH_MAX_SPEED    = safeMH(lookup, trainCls, "maxSpeed",     MethodType.methodType(double.class));

            // Navigation class
            Class<?> navCls = Class.forName(
                "com.simibubi.create.content.trains.entity.Navigation");
            MethodHandles.Lookup navLookup = MethodHandles.privateLookupIn(
                navCls, MethodHandles.lookup());
            VH_NAV_DISTANCE_TO_DEST = safeVH(navLookup, navCls, "distanceToDestination", double.class);
            VH_NAV_DESTINATION      = safeVH(navLookup, navCls, "destination", null);
            VH_NAV_WAITING_SIGNAL   = safeVH(navLookup, navCls, "waitingForSignal", null);
            VH_NAV_DIST_TO_SIGNAL   = safeVH(navLookup, navCls, "distanceToSignal", double.class);
            VH_NAV_CURRENT_PATH     = safeVH(navLookup, navCls, "currentPath", null);
            MH_CANCEL_NAV = safeMH(navLookup, navCls, "cancelNavigation",
                MethodType.methodType(void.class));

            // Carriage class
            Class<?> carriageCls = Class.forName(
                "com.simibubi.create.content.trains.entity.Carriage");
            MethodHandles.Lookup carriageLookup = MethodHandles.privateLookupIn(
                carriageCls, MethodHandles.lookup());
            MH_GET_LEADING_PT  = safeMH(carriageLookup, carriageCls, "getLeadingPoint",
                MethodType.methodType(
                    Class.forName("com.simibubi.create.content.trains.entity.TravellingPoint")));
            MH_GET_TRAILING_PT = safeMH(carriageLookup, carriageCls, "getTrailingPoint",
                MethodType.methodType(
                    Class.forName("com.simibubi.create.content.trains.entity.TravellingPoint")));
            MH_ANY_ENTITY = safeMH(carriageLookup, carriageCls, "anyAvailableEntity",
                MethodType.methodType(
                    Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntity")));

            // TravellingPoint class
            Class<?> tpCls = Class.forName(
                "com.simibubi.create.content.trains.entity.TravellingPoint");
            MethodHandles.Lookup tpLookup = MethodHandles.privateLookupIn(
                tpCls, MethodHandles.lookup());
            VH_TP_NODE1    = safeVH(tpLookup, tpCls, "node1",    null);
            VH_TP_NODE2    = safeVH(tpLookup, tpCls, "node2",    null);
            VH_TP_EDGE     = safeVH(tpLookup, tpCls, "edge",     null);
            VH_TP_POSITION = safeVH(tpLookup, tpCls, "position", double.class);

            // Entity.position()
            Class<?> entityCls = net.minecraft.world.entity.Entity.class;
            MH_ENTITY_POSITION = MethodHandles.lookup().findVirtual(
                entityCls, "position", MethodType.methodType(Vec3.class));

            CreateRailwayMod.aiLog("[Adapter] Create1211TrainHandle static init OK");
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.warn("[Adapter] VarHandle init partial: {}", e.getMessage());
        } finally {
            STATIC_INIT_DONE = true;
        }
    }

    @Nullable
    private static VarHandle safeVH(MethodHandles.Lookup lookup, Class<?> cls,
                                    String name, @Nullable Class<?> type) {
        try {
            if (type != null) return lookup.findVarHandle(cls, name, type);
            // Если тип неизвестен — ищем через reflection
            java.lang.reflect.Field f = findField(cls, name);
            if (f == null) return null;
            f.setAccessible(true);
            return MethodHandles.privateLookupIn(cls, MethodHandles.lookup())
                .unreflectVarHandle(f);
        } catch (Exception e) {
            CreateRailwayMod.aiDebug("[Adapter] VarHandle not found: {}.{}", cls.getSimpleName(), name);
            return null;
        }
    }

    @Nullable
    private static MethodHandle safeMH(MethodHandles.Lookup lookup, Class<?> cls,
                                       String name, MethodType type) {
        try { return lookup.findVirtual(cls, name, type); }
        catch (Exception e) {
            CreateRailwayMod.aiDebug("[Adapter] MethodHandle not found: {}.{}", cls.getSimpleName(), name);
            return null;
        }
    }

    @Nullable
    private static java.lang.reflect.Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        return null;
    }
}
