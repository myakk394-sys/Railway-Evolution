package com.fizzylovely.railwayevolution.ai.adapter;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.IGraphEdgeEntry;
import com.fizzylovely.railwayevolution.ai.core.ITrackEdge;
import com.fizzylovely.railwayevolution.ai.core.ITrainGraph;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Create1211TrainGraph — адаптер графа путей Create 1.21.1.
 *
 * Переиспользуется через единственный экземпляр в Create1211TrainHandle.
 * BFS вызывает getEdgesFrom() на каждом узле — метод не должен аллоцировать.
 *
 * Для минимизации аллокаций: IGraphEdgeEntry и ITrackEdge возвращаются
 * из пуля (перезаписываемые объекты).
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class Create1211TrainGraph implements ITrainGraph {

    private final Object graphRef; // com.simibubi.create.content.trains.graph.TrackGraph

    // ─── Пул объектов для getEdgesFrom (нет аллокаций в горячем пути) ────
    private static final int INITIAL_EDGE_POOL_SIZE = 16;
    private EdgeEntryImpl[] edgePool  = new EdgeEntryImpl[INITIAL_EDGE_POOL_SIZE];
    private EdgeImpl[]      edgeImplPool = new EdgeImpl[INITIAL_EDGE_POOL_SIZE];
    private final List<IGraphEdgeEntry> edgeList = new ArrayList<>(INITIAL_EDGE_POOL_SIZE);

    // ─── Static MethodHandles (один раз) ──────────────────────────────────
    private static volatile boolean INIT_DONE = false;
    private static MethodHandle MH_GET_CONNECTIONS_FROM; // Map<TrackNode, TrackEdge>
    private static MethodHandle MH_NODE_DEGREE;
    private static MethodHandle MH_NODE_GET_LOCATION;    // TrackNodeLocation (extends Vec3i)
    private static VarHandle    VH_EDGE_LENGTH;
    private static MethodHandle MH_EDGE_GET_LENGTH;
    private static VarHandle    VH_EDGE_NODE1;
    private static VarHandle    VH_EDGE_NODE2;
    private static VarHandle    VH_EDGE_TURN;            // BezierConnection
    private static MethodHandle MH_BEZIER_SAMPLE;        // BezierConnection.getPosition(t) → Vec3

    // ──────────────────────────────────────────────────────────────────────

    public Create1211TrainGraph(Object graphRef) {
        this.graphRef = graphRef;
        for (int i = 0; i < INITIAL_EDGE_POOL_SIZE; i++) {
            edgePool[i]     = new EdgeEntryImpl();
            edgeImplPool[i] = new EdgeImpl();
        }
        if (!INIT_DONE) initStatic();
    }

    // ──────────────────────────────────────────────────────────────────────
    // ITrainGraph
    // ──────────────────────────────────────────────────────────────────────

    @Override
    @SuppressWarnings("unchecked")
    public List<IGraphEdgeEntry> getEdgesFrom(Object fromNodeRef, @Nullable Object exceptNodeRef) {
        edgeList.clear();
        if (MH_GET_CONNECTIONS_FROM == null || fromNodeRef == null) return edgeList;

        try {
            Map<Object, Object> connections = (Map<Object, Object>)
                MH_GET_CONNECTIONS_FROM.invoke(graphRef, fromNodeRef);
            if (connections == null) return edgeList;

            int idx = 0;
            for (Map.Entry<Object, Object> e : connections.entrySet()) {
                Object neighborNode = e.getKey();
                Object nativeEdge   = e.getValue();

                // Пропускаем назад (откуда пришли)
                if (neighborNode == exceptNodeRef) continue;
                ensureEdgePoolCapacity(idx + 1);

                EdgeImpl    edgeImpl  = edgeImplPool[idx];
                EdgeEntryImpl entry   = edgePool[idx];

                edgeImpl.bind(nativeEdge);
                entry.bind(neighborNode, edgeImpl);
                edgeList.add(entry);
                idx++;
            }
        } catch (Throwable t) {
            CreateRailwayMod.aiDebug("[Graph] getEdgesFrom error: {}", t.getMessage());
        }
        return edgeList;
    }

    /** Grow once for unusually complex junctions instead of silently dropping routes. */
    private void ensureEdgePoolCapacity(int required) {
        if (required <= edgePool.length) return;

        int oldSize = edgePool.length;
        int newSize = Math.max(required, oldSize * 2);
        EdgeEntryImpl[] newEntries = new EdgeEntryImpl[newSize];
        EdgeImpl[] newEdges = new EdgeImpl[newSize];
        System.arraycopy(edgePool, 0, newEntries, 0, oldSize);
        System.arraycopy(edgeImplPool, 0, newEdges, 0, oldSize);
        for (int i = oldSize; i < newSize; i++) {
            newEntries[i] = new EdgeEntryImpl();
            newEdges[i] = new EdgeImpl();
        }
        edgePool = newEntries;
        edgeImplPool = newEdges;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable ITrackEdge getEdge(Object node1Ref, Object node2Ref) {
        if (MH_GET_CONNECTIONS_FROM == null) return null;
        try {
            Map<Object, Object> connections = (Map<Object, Object>)
                MH_GET_CONNECTIONS_FROM.invoke(graphRef, node1Ref);
            if (connections == null) return null;
            Object nativeEdge = connections.get(node2Ref);
            if (nativeEdge == null) return null;
            EdgeImpl e = new EdgeImpl(); // РЕДКО вызывается → допустима аллокация
            e.bind(nativeEdge);
            return e;
        } catch (Throwable t) { return null; }
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable ITrackEdge getReverseEdge(Object node1Ref, Object node2Ref) {
        // В Create: ребра двунаправленные, но stored в обоих узлах
        return getEdge(node2Ref, node1Ref);
    }

    @Override
    @SuppressWarnings("unchecked")
    public int getNodeDegree(Object nodeRef) {
        if (MH_GET_CONNECTIONS_FROM == null || nodeRef == null) return 0;
        try {
            Map<?, ?> connections = (Map<?, ?>) MH_GET_CONNECTIONS_FROM.invoke(graphRef, nodeRef);
            return connections != null ? connections.size() : 0;
        } catch (Throwable t) { return 0; }
    }

    @Override
    public Vec3 getNodePosition(Object nodeRef) {
        if (MH_NODE_GET_LOCATION == null || nodeRef == null) return Vec3.ZERO;
        try {
            Object loc = MH_NODE_GET_LOCATION.invoke(nodeRef);
            if (loc == null) return Vec3.ZERO;
            // TrackNodeLocation extends BlockPos (Vec3i)
            if (loc instanceof net.minecraft.core.BlockPos bp) {
                return new Vec3(bp.getX(), bp.getY(), bp.getZ());
            }
            return Vec3.ZERO;
        } catch (Throwable t) { return Vec3.ZERO; }
    }

    @Override public boolean isSameGraph(Object otherGraphRef) { return graphRef == otherGraphRef; }
    @Override public Object  getNativeRef()                    { return graphRef; }

    // ──────────────────────────────────────────────────────────────────────
    // Внутренний ITrackEdge адаптер
    // ──────────────────────────────────────────────────────────────────────

    private final class EdgeImpl implements ITrackEdge {
        private Object nativeEdge;
        private double cachedLength = 1.0;
        private boolean cachedCurve = false;
        private boolean bound = false;

        void bind(Object edge) {
            if (nativeEdge == edge && bound) return; // не перечитываем если тот же объект
            nativeEdge = edge;
            bound = true;
            cachedLength = readEdgeLength(edge);
            if (VH_EDGE_TURN != null) {
                try { cachedCurve = VH_EDGE_TURN.get(edge) != null; }
                catch (Exception e) { cachedCurve = false; }
            }
        }

        @Override public double  getLength()  { return cachedLength; }
        @Override public boolean isCurve()    { return cachedCurve; }
        @Override public Object  getNativeRef(){ return nativeEdge; }

        @Override
        public List<Vec3> sampleCurve(int numSamples) {
            if (!cachedCurve || MH_BEZIER_SAMPLE == null || nativeEdge == null) {
                return Collections.emptyList();
            }
            try {
                Object turn = VH_EDGE_TURN.get(nativeEdge);
                if (turn == null) return Collections.emptyList();
                List<Vec3> pts = new ArrayList<>(numSamples);
                for (int i = 0; i < numSamples; i++) {
                    float t = (float) i / (numSamples - 1);
                    Object result = MH_BEZIER_SAMPLE.invoke(turn, t);
                    if (result instanceof Vec3 v) pts.add(v);
                }
                return pts;
            } catch (Throwable e) { return Collections.emptyList(); }
        }
    }

    /**
     * Create 6.x exposes TrackEdge length through getLength(), while older mappings
     * may expose a field. Never let a missing optional representation disable graph
     * initialization; the node-to-node distance is a safe final fallback.
     */
    private static double readEdgeLength(Object edge) {
        if (edge == null) return 1.0;
        if (MH_EDGE_GET_LENGTH != null) {
            try {
                Object value = MH_EDGE_GET_LENGTH.invoke(edge);
                if (value instanceof Number number && number.doubleValue() >= 0.0) {
                    return number.doubleValue();
                }
            } catch (Throwable ignored) { }
        }
        if (VH_EDGE_LENGTH != null) {
            try {
                Object value = VH_EDGE_LENGTH.get(edge);
                if (value instanceof Number number && number.doubleValue() >= 0.0) {
                    return number.doubleValue();
                }
            } catch (Throwable ignored) { }
        }
        if (VH_EDGE_NODE1 != null && VH_EDGE_NODE2 != null && MH_NODE_GET_LOCATION != null) {
            try {
                Object node1 = VH_EDGE_NODE1.get(edge);
                Object node2 = VH_EDGE_NODE2.get(edge);
                Object loc1 = MH_NODE_GET_LOCATION.invoke(node1);
                Object loc2 = MH_NODE_GET_LOCATION.invoke(node2);
                if (loc1 instanceof net.minecraft.core.Vec3i a
                        && loc2 instanceof net.minecraft.core.Vec3i b) {
                    double dx = a.getX() - b.getX();
                    double dy = a.getY() - b.getY();
                    double dz = a.getZ() - b.getZ();
                    return Math.sqrt(dx * dx + dy * dy + dz * dz);
                }
            } catch (Throwable ignored) { }
        }
        return 1.0;
    }

    // ─── Pooled EdgeEntry ─────────────────────────────────────────────────

    private static final class EdgeEntryImpl implements IGraphEdgeEntry {
        private Object     neighborNodeRef;
        private ITrackEdge edge;

        void bind(Object neighborRef, ITrackEdge edgeImpl) {
            this.neighborNodeRef = neighborRef;
            this.edge            = edgeImpl;
        }

        @Override public Object     getNeighborNodeRef() { return neighborNodeRef; }
        @Override public ITrackEdge getEdge()            { return edge; }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Static init
    // ──────────────────────────────────────────────────────────────────────

    private static synchronized void initStatic() {
        if (INIT_DONE) return;
        try {
            Class<?> graphCls = Class.forName(
                "com.simibubi.create.content.trains.graph.TrackGraph");
            MethodHandles.Lookup graphLookup = MethodHandles.privateLookupIn(
                graphCls, MethodHandles.lookup());

            // TrackGraph.getConnectionsFrom(TrackNode) → Map<TrackNode, TrackEdge>
            Class<?> nodeCls = Class.forName(
                "com.simibubi.create.content.trains.graph.TrackNode");
            MH_GET_CONNECTIONS_FROM = graphLookup.findVirtual(graphCls,
                "getConnectionsFrom",
                MethodType.methodType(Map.class, nodeCls));

            // TrackNode.getLocation() → TrackNodeLocation
            MethodHandles.Lookup nodeLookup = MethodHandles.privateLookupIn(
                nodeCls, MethodHandles.lookup());
            MH_NODE_GET_LOCATION = nodeLookup.findVirtual(nodeCls,
                "getLocation",
                MethodType.methodType(
                    Class.forName("com.simibubi.create.content.trains.graph.TrackNodeLocation")));

            // TrackEdge length changed between Create versions. In Create 6.0.10 it
            // is computed by public getLength(), not stored in a length field.
            Class<?> edgeCls = Class.forName(
                "com.simibubi.create.content.trains.graph.TrackEdge");
            MethodHandles.Lookup edgeLookup = MethodHandles.privateLookupIn(
                edgeCls, MethodHandles.lookup());
            MH_EDGE_GET_LENGTH = safeMH(edgeLookup, edgeCls, "getLength",
                MethodType.methodType(double.class));
            VH_EDGE_LENGTH = safeVH(edgeLookup, edgeCls, "length");
            VH_EDGE_NODE1 = safeVH(edgeLookup, edgeCls, "node1");
            VH_EDGE_NODE2 = safeVH(edgeLookup, edgeCls, "node2");
            VH_EDGE_TURN   = safeVH(edgeLookup, edgeCls, "turn");

            // BezierConnection.getPosition(float t) → Vec3
            if (VH_EDGE_TURN != null) {
                try {
                    Class<?> bezierCls = Class.forName(
                        "com.simibubi.create.content.trains.graph.BezierConnection");
                    MethodHandles.Lookup bezierLookup = MethodHandles.privateLookupIn(
                        bezierCls, MethodHandles.lookup());
                    MH_BEZIER_SAMPLE = bezierLookup.findVirtual(bezierCls,
                        "getPosition",
                        MethodType.methodType(Vec3.class, float.class));
                } catch (Exception e) {
                    CreateRailwayMod.aiDebug("[Graph] BezierConnection.getPosition not found");
                }
            }

            CreateRailwayMod.aiLog("[Adapter] Create1211TrainGraph static init OK");
        } catch (Exception e) {
            CreateRailwayMod.LOGGER.warn("[Adapter] Graph init partial; graph fallbacks remain active", e);
        } finally {
            INIT_DONE = true;
        }
    }

    @Nullable
    private static VarHandle safeVH(MethodHandles.Lookup lookup, Class<?> cls, String name) {
        try {
            java.lang.reflect.Field f = findField(cls, name);
            if (f == null) return null;
            f.setAccessible(true);
            return MethodHandles.privateLookupIn(cls, MethodHandles.lookup()).unreflectVarHandle(f);
        } catch (Exception e) { return null; }
    }

    @Nullable
    private static MethodHandle safeMH(MethodHandles.Lookup lookup, Class<?> cls,
                                       String name, MethodType type) {
        try {
            return lookup.findVirtual(cls, name, type);
        } catch (Exception e) {
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
