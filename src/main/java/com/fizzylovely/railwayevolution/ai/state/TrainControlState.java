package com.fizzylovely.railwayevolution.ai.state;

import com.fizzylovely.railwayevolution.ai.core.TrainControlContext;
import org.jetbrains.annotations.Nullable;

/**
 * TrainControlState — интерфейс одного состояния конечного автомата.
 *
 * Принципы:
 *   1. Нет аллокаций в {@link #tick} — используй поля из ctx.
 *   2. {@link #onEnter} вызывается ровно один раз при входе.
 *   3. {@link #onExit} вызывается ровно один раз при выходе.
 *   4. {@link #tick} возвращает null чтобы остаться в текущем состоянии,
 *      или TrainStateId следующего состояния.
 *
 * Copyright (c) 2026 Fizzy. Licensed under the MIT License.
 */
public interface TrainControlState {

    TrainStateId getId();

    /**
     * Вход в состояние. Инициализируй таймеры, логируй переход.
     * Не аллоцируй — используй уже существующие поля.
     */
    void onEnter(TrainControlContext ctx);

    /**
     * Выход из состояния. Освобождай ресурсы, сбрасывай таймеры.
     */
    void onExit(TrainControlContext ctx);

    /**
     * Основной тик состояния.
     *
     * @return null = остаться, иначе = ID следующего состояния
     */
    @Nullable
    TrainStateId tick(TrainControlContext ctx);

    /**
     * Допускает ли это состояние мгновенный перехват Player Override?
     * По умолчанию — да. Переопределяется в WaitForClearanceState при overlap.
     */
    default boolean allowsPlayerOverride(TrainControlContext ctx) {
        return true;
    }
}
