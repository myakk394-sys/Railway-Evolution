package com.fizzylovely.railwayevolution.ai.adapter;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.ITrainHandle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EcosystemRegistry — реестр ВСЕХ участников железнодорожного трафика.
 *
 * Объединяет в единый список:
 *   - AI-поезда (Create1211TrainHandle)
 *   - Поезда под управлением игроков (PlayerTrainHandle)
 *
 * PerceptionEngine запрашивает allHandles() для скана — получает полный
 * список без различия между AI и игроком. Экосистема едина.
 *
 * Обновление:
 *   - AI-хэндлы обновляются при scanForTrains() из TrainAIManager.
 *   - Player-хэндлы создаются/удаляются при detectPlayerControlled().
 *
 * Потокобезопасность: только серверный поток (NeoForge tick).
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class EcosystemRegistry {

    private static EcosystemRegistry instance;

    /** UUID поезда → хэндл AI (Create1211TrainHandle). */
    private final Map<UUID, Create1211TrainHandle> aiHandles = new LinkedHashMap<>();

    /**
     * UUID поезда → хэндл игрока (PlayerTrainHandle).
     * Ключ = тот же UUID что и в aiHandles.
     * Если поезд управляется игроком, ОБА хэндла существуют одновременно.
     */
    private final Map<UUID, PlayerTrainHandle> playerHandles = new ConcurrentHashMap<>();

    /**
     * Снимок полного списка участников для текущего тика.
     * Пересобирается один раз в начале тика (refreshSnapshot).
     * PerceptionEngine итерирует этот список.
     */
    private final List<ITrainHandle> snapshot = new ArrayList<>();
    private boolean snapshotDirty = true;

    /** Reused across scanPlayerControls() calls — avoids allocating new HashSet every 20 ticks. */
    private final Set<UUID> stillControlledBuf = new HashSet<>();


    // ──────────────────────────────────────────────────────────────────────

    public static EcosystemRegistry getInstance() {
        if (instance == null) instance = new EcosystemRegistry();
        return instance;
    }

    public static void reset() {
        instance = new EcosystemRegistry();
    }

    private EcosystemRegistry() {}

    // ──────────────────────────────────────────────────────────────────────
    // AI-хэндлы
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Зарегистрировать или обновить AI-хэндл для поезда.
     * Вызывается из TrainAIManager.scanForTrains().
     */
    public Create1211TrainHandle getOrCreateAiHandle(UUID trainId, Object createTrainRef) {
        Create1211TrainHandle handle = aiHandles.get(trainId);
        if (handle == null) {
            handle = new Create1211TrainHandle(trainId);
            aiHandles.put(trainId, handle);
            snapshotDirty = true;
            CreateRailwayMod.aiDebug("[Ecosystem] Registered AI train: {}",
                trainId.toString().substring(0, 8));
        }
        handle.bindCreateTrain(createTrainRef);
        return handle;
    }

    /**
     * Удалить хэндл поезда (поезд удалён из мира).
     */
    public void removeTrainHandle(UUID trainId) {
        boolean changed = aiHandles.remove(trainId) != null;
        changed |= playerHandles.remove(trainId) != null;
        if (changed) {
            snapshotDirty = true;
            CreateRailwayMod.aiDebug("[Ecosystem] Removed train: {}",
                trainId.toString().substring(0, 8));
        }
    }

    @Nullable
    public Create1211TrainHandle getAiHandle(UUID trainId) {
        return aiHandles.get(trainId);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Player-хэндлы
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Обновить статус управления игроком для поезда.
     *
     * @param trainId        UUID поезда
     * @param playerUUID     UUID игрока (null = никто не управляет)
     * @param player         ServerPlayer для оповещений
     */
    public void updatePlayerControl(UUID trainId, @Nullable UUID playerUUID,
                                    @Nullable ServerPlayer player) {
        if (playerUUID == null) {
            // Игрок отпустил управление
            if (playerHandles.remove(trainId) != null) {
                snapshotDirty = true;
                CreateRailwayMod.aiDebug("[Ecosystem] Player released train: {}",
                    trainId.toString().substring(0, 8));
            }
            return;
        }

        // Создаём PlayerTrainHandle обёртку над AI-хэндлом
        Create1211TrainHandle aiHandle = aiHandles.get(trainId);
        if (aiHandle == null) return; // поезд не зарегистрирован

        PlayerTrainHandle existing = playerHandles.get(trainId);
        if (existing == null || !playerUUID.equals(existing.getControllingPlayerUUID())) {
            PlayerTrainHandle pHandle = new PlayerTrainHandle(aiHandle, playerUUID, player);
            playerHandles.put(trainId, pHandle);
            snapshotDirty = true;
            CreateRailwayMod.aiDebug("[Ecosystem] Player {} took train: {}",
                player != null ? player.getGameProfile().getName() : playerUUID.toString().substring(0, 8),
                trainId.toString().substring(0, 8));
        }
    }

    @Nullable
    public PlayerTrainHandle getPlayerHandle(UUID trainId) {
        return playerHandles.get(trainId);
    }

    public boolean isPlayerControlled(UUID trainId) {
        return playerHandles.containsKey(trainId);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Snapshot — единый список для PerceptionEngine
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Обновить снимок (если нужно) и вернуть текущий список участников.
     *
     * Возвращает:
     *   - PlayerTrainHandle для поездов под управлением игрока (приоритет).
     *   - Create1211TrainHandle для остальных.
     *
     * PerceptionEngine видит поезд игрока как «обычного» участника с
     * leaderIsPlayer=true — никаких специальных путей кода.
     */
    public List<ITrainHandle> allHandles() {
        if (snapshotDirty) refreshSnapshot();
        return snapshot;
    }

    private void refreshSnapshot() {
        snapshot.clear();
        for (Map.Entry<UUID, Create1211TrainHandle> e : aiHandles.entrySet()) {
            UUID trainId = e.getKey();
            // Если поезд управляется игроком — добавляем PlayerTrainHandle
            PlayerTrainHandle ph = playerHandles.get(trainId);
            snapshot.add(ph != null ? ph : e.getValue());
        }
        snapshotDirty = false;
    }

    /**
     * Пометить snapshot устаревшим (вызывается при изменении состава поездов).
     */
    public void invalidateSnapshot() {
        snapshotDirty = true;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tick-обновление
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Обновить все AI-хэндлы (лёгкие данные каждый тик).
     * Тяжёлые данные обновляются только при изменении скорости.
     */
    public void tickRefreshAll(long currentTick) {
        for (Create1211TrainHandle handle : aiHandles.values()) {
            double prevSpeed = handle.getSpeed();
            handle.refreshFast();
            double newSpeed = handle.getSpeed();
            if (Math.abs(newSpeed - prevSpeed) > 0.001 || currentTick % 10 == 0) {
                handle.refreshFull(currentTick);
            }
        }
    }

    /**
     * Сканировать игроков в поездах и обновить playerHandles.
     * Вызывается каждые N тиков из TrainAIManager.
     */
    public void scanPlayerControls(ServerLevel level) {
        stillControlledBuf.clear();

        for (Map.Entry<UUID, Create1211TrainHandle> e : aiHandles.entrySet()) {
            UUID trainId = e.getKey();
            Create1211TrainHandle aiHandle = e.getValue();

            if (!aiHandle.isPlayerControlled()) {
                // AI-контроль — убираем player handle если был
                if (playerHandles.containsKey(trainId)) {
                    updatePlayerControl(trainId, null, null);
                }
                continue;
            }

            // Поезд управляется игроком — ищем кто именно
            UUID playerUUID = aiHandle.getControllingPlayerUUID();
            if (playerUUID == null) {
                // manualTick=true но UUID неизвестен — сканируем пассажиров
                ServerPlayer found = findPlayerInTrain(level, aiHandle);
                if (found != null) {
                    playerUUID = found.getUUID();
                    updatePlayerControl(trainId, playerUUID, found);
                    stillControlledBuf.add(trainId);
                }
            } else {
                net.minecraft.world.entity.player.Player raw = level.getPlayerByUUID(playerUUID);
                ServerPlayer p = (raw instanceof ServerPlayer sp) ? sp : null;
                updatePlayerControl(trainId, playerUUID, p);
                stillControlledBuf.add(trainId);
            }
        }

        // Удаляем устаревшие player handles
        playerHandles.keySet().removeIf(id -> !stillControlledBuf.contains(id));
    }

    @Nullable
    private ServerPlayer findPlayerInTrain(ServerLevel level, Create1211TrainHandle handle) {
        // Ищем игрока рядом с головой поезда
        net.minecraft.world.phys.Vec3 pos = handle.getLeadingPosition();
        if (pos == null) return null;

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) continue;
            double dx = player.getX() - pos.x;
            double dy = Math.abs(player.getY() - pos.y);
            double dz = player.getZ() - pos.z;
            double dist2D = Math.sqrt(dx * dx + dz * dz);
            if (dist2D < 5.0 && dy < 3.0) return player;
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────

    public int getAiTrainCount()     { return aiHandles.size(); }
    public int getPlayerTrainCount() { return playerHandles.size(); }
    public Collection<UUID>                getRegisteredIds() { return aiHandles.keySet(); }
    public Collection<Create1211TrainHandle> getAiHandles()   { return aiHandles.values(); }
}
