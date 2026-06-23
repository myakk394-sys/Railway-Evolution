package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.CreateRailwayMod;
import com.fizzylovely.railwayevolution.ai.core.SafetyManager;
import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/**
 * PlayerOverrideState — управление игрока: AI полностью уступает.
 *
 * Экосистемная роль:
 *   - Поезд игрока остаётся полноправным участником трафика:
 *     другие AI-поезда «видят» его через PerceptionEngine и реагируют.
 *   - SafetyManager продолжает работать ТОЛЬКО для крайних случаев:
 *     физическое перекрытие или сход с рельс блокируются даже для игрока.
 *   - Все AI-блокировки освобождаются при входе в это состояние.
 *
 * Переход назад в CRUISING: при потере управления игроком (playerControlled=false).
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public final class PlayerOverrideState implements TrainControlState {

    private final SafetyManager safety;

    public PlayerOverrideState(SafetyManager safety) {
        this.safety = safety;
    }

    @Override
    public TrainStateId getId() { return TrainStateId.PLAYER_OVERRIDE; }

    @Override
    public void onEnter(TrainControlContext ctx) {
        // Немедленно снимаем все AI-блокировки
        if (ctx.selfHandle != null) {
            ctx.selfHandle.restoreFullThrottle();
            ctx.selfHandle.resumeNavigation();
        }
        safety.resetLeaderTracking();
        CreateRailwayMod.aiDebug("[FSM] {} → PLAYER_OVERRIDE", shortId(ctx));
    }

    @Override
    public void onExit(TrainControlContext ctx) {
        CreateRailwayMod.aiDebug("[FSM] {} ← PLAYER_OVERRIDE (AI resuming)", shortId(ctx));
    }

    @Override
    public @Nullable TrainStateId tick(TrainControlContext ctx) {
        // Игрок отпустил управление → возврат к AI
        if (!ctx.playerControlled) {
            return TrainStateId.CRUISING;
        }

        // SafetyManager: оцениваем текущую скорость игрока
        SafetyManager.SafetyResult verdict = safety.evaluate(ctx, ctx.speed);

        if (verdict.blocks()) {
            // ТОЛЬКО при overlap или derail — принудительная остановка
            safety.applyVerdict(verdict, ctx);
            CreateRailwayMod.aiDebug("[FSM] {} SAFETY VETO on player ({})",
                shortId(ctx), verdict.verdict());
        }
        // Всё остальное — игрок в полном контроле

        return null; // остаёмся в PLAYER_OVERRIDE
    }

    /** PLAYER_OVERRIDE всегда допускает повторный перехват (это само перехватывающее состояние). */
    @Override
    public boolean allowsPlayerOverride(TrainControlContext ctx) { return true; }

    private String shortId(TrainControlContext ctx) {
        if (ctx.selfHandle == null) return "?";
        return ctx.selfHandle.getId().toString().substring(0, 8);
    }
}
