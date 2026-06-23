package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.SafetyManager;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * TrainControlStateMachine — главный конечный автомат управления поездом.
 *
 * Архитектура Authority Layers (приоритет сверху вниз):
 *   [LAYER 0] SafetyVeto       ← блокирует опасные команды даже игрока
 *   [LAYER 1] PlayerOverride   ← немедленный захват управления игроком (0 задержки)
 *   [LAYER 2] AIStateMachine   ← нормальная логика AI
 *   [LAYER 3] CreateNavigation ← базовая навигация Create (не трогаем)
 *
 * Ключевые гарантии:
 *   1. Переход в PLAYER_OVERRIDE происходит НЕМЕДЛЕННО при playerControlled=true.
 *      Без задержки в 1 тик (которая была в старом коде).
 *   2. SafetyManager применяется даже в PLAYER_OVERRIDE, но только для
 *      физического перекрытия и схода с рельс.
 *   3. FLOW_FOLLOWER — новое состояние экосистемной интеграции.
 *
 * Потокобезопасность: только серверный поток.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class TrainControlStateMachine {

    private final Map<TrainStateId, TrainControlState> states;
    private final SafetyManager safety;

    private TrainControlState currentState;
    private long              stateEnteredTick = 0;

    // ──────────────────────────────────────────────────────────────────────

    public TrainControlStateMachine() {
        this.safety = new SafetyManager();
        this.states = buildStateMap();
        this.currentState = states.get(TrainStateId.CRUISING);
    }

    private Map<TrainStateId, TrainControlState> buildStateMap() {
        Map<TrainStateId, TrainControlState> map = new EnumMap<>(TrainStateId.class);
        map.put(TrainStateId.CRUISING,           new CruisingState());
        map.put(TrainStateId.ANALYZING_OBSTACLE, new AnalyzingObstacleState());
        map.put(TrainStateId.FLOW_FOLLOWER,      new FlowFollowerState());
        map.put(TrainStateId.YIELDING,           new YieldingState());
        map.put(TrainStateId.WAIT_FOR_CLEARANCE, new WaitForClearanceState());
        map.put(TrainStateId.REVERSING,          new ReversingState());
        map.put(TrainStateId.TRAFFIC_JAM,        new TrafficJamState());
        // PlayerOverrideState нужен SafetyManager
        map.put(TrainStateId.PLAYER_OVERRIDE,    new PlayerOverrideState(safety));
        return map;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Главный тик
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Обработать один тик.
     *
     * Вызывается ПОСЛЕ заполнения ctx из ITrainHandle и ПОСЛЕ
     * завершения PerceptionEngine.scan() (ctx.obstacleProfile уже готов).
     *
     * @param ctx контекст текущего тика (уже заполнен)
     */
    public void tick(TrainControlContext ctx) {

        // ── LAYER 1: Player Override ─────────────────────────────────────
        if (ctx.playerControlled) {
            if (currentState.getId() != TrainStateId.PLAYER_OVERRIDE) {
                // Проверяем разрешение текущего состояния
                if (currentState.allowsPlayerOverride(ctx)) {
                    transitionTo(TrainStateId.PLAYER_OVERRIDE, ctx);
                }
                // Если не разрешает (overlap) — SafetyManager уже обрабатывает
            }
            // PLAYER_OVERRIDE.tick() сам применяет SafetyVeto
            tickCurrentState(ctx);
            return;
        }

        // ── LAYER 1 EXIT: Игрок отпустил управление ──────────────────────
        if (currentState.getId() == TrainStateId.PLAYER_OVERRIDE) {
            transitionTo(TrainStateId.CRUISING, ctx);
        }

        // ── LAYER 0: SafetyManager pre-check ────────────────────────────
        // Применяем к текущей скорости (не к запрошенной командой)
        // чтобы поймать залипший overlap/derail
        SafetyManager.SafetyResult preCheck = safety.evaluate(ctx, ctx.speed);
        if (preCheck.blocks()) {
            safety.applyVerdict(preCheck, ctx);
            // Не прерываем тик — состояния могут делать дополнительные проверки
        } else if (preCheck.verdict() == SafetyManager.Verdict.EMERGENCY_FOLLOW_BRAKE) {
            safety.applyVerdict(preCheck, ctx);
        }

        // ── LAYER 2: AI State Machine ────────────────────────────────────
        tickCurrentState(ctx);
    }

    // ──────────────────────────────────────────────────────────────────────

    private void tickCurrentState(TrainControlContext ctx) {
        TrainStateId next = currentState.tick(ctx);
        if (next != null && next != currentState.getId()) {
            transitionTo(next, ctx);
        }
    }

    /**
     * Принудительный переход в заданное состояние.
     * Используется внешним кодом (например, TrainAIController при тайм-аутах).
     */
    public void forceTransition(TrainStateId targetId, TrainControlContext ctx) {
        if (targetId != currentState.getId()) {
            transitionTo(targetId, ctx);
        }
    }

    private void transitionTo(TrainStateId targetId, TrainControlContext ctx) {
        TrainControlState target = states.get(targetId);
        if (target == null) {
            CreateRailwayMod.LOGGER.error("[FSM] Unknown state: {}", targetId);
            return;
        }

        currentState.onExit(ctx);

        // Сбрасываем tracking SafetyManager при смене состояния
        if (currentState.getId() != TrainStateId.FLOW_FOLLOWER
                && targetId != TrainStateId.FLOW_FOLLOWER) {
            safety.resetLeaderTracking();
        }

        currentState    = target;
        stateEnteredTick = ctx.currentTick;
        currentState.onEnter(ctx);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    public TrainStateId getCurrentStateId()      { return currentState.getId(); }
    public long         getStateEnteredTick()     { return stateEnteredTick; }
    public SafetyManager getSafetyManager()       { return safety; }

    /** Время нахождения в текущем состоянии (тики). */
    public long getTimeInCurrentState(long currentTick) {
        return currentTick - stateEnteredTick;
    }

    public boolean isInState(TrainStateId stateId) {
        return currentState.getId() == stateId;
    }
}
