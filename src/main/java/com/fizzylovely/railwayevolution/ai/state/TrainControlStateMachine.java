package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.SafetyManager;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;

import java.util.EnumMap;
import java.util.Map;

/**
 * Конечный автомат управления поездом.
 */
public final class TrainControlStateMachine {

    private final Map<TrainStateId, TrainControlState> states;
    private final SafetyManager safety;

    private TrainControlState currentState;
    private long              stateEnteredTick = 0;

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
        map.put(TrainStateId.PLAYER_OVERRIDE,    new PlayerOverrideState(safety));
        return map;
    }

    public void tick(TrainControlContext ctx) {
        // Управление игроком
        if (ctx.playerControlled) {
            if (currentState.getId() != TrainStateId.PLAYER_OVERRIDE) {
                if (currentState.allowsPlayerOverride(ctx)) {
                    transitionTo(TrainStateId.PLAYER_OVERRIDE, ctx);
                }
            }
            tickCurrentState(ctx);
            return;
        }

        // Возврат из ручного управления
        if (currentState.getId() == TrainStateId.PLAYER_OVERRIDE) {
            transitionTo(TrainStateId.CRUISING, ctx);
        }

        // Проверка безопасности
        SafetyManager.SafetyResult preCheck = safety.evaluate(ctx, ctx.speed);
        if (preCheck.blocks()
                || preCheck.verdict() == SafetyManager.Verdict.CLAMP_SPEED
                || preCheck.verdict() == SafetyManager.Verdict.EMERGENCY_FOLLOW_BRAKE) {
            safety.applyVerdict(preCheck, ctx);
            return;
        }

        tickCurrentState(ctx);
    }


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


    // Public API

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
