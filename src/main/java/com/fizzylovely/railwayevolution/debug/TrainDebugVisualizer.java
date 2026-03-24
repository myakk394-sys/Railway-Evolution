package com.fizzylovely.railwayevolution.debug;

import com.fizzylovely.railwayevolution.ai.TrainAIController;
import com.fizzylovely.railwayevolution.ai.TrainAIManager;
import com.fizzylovely.railwayevolution.ai.VirtualBlockSystem;
import com.fizzylovely.railwayevolution.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Дебаг-визуализатор поездного ИИ — отображает состояние ИИ через частицы
 * и текст на панели действий, когда игрок надевает очки ИИ (шлем ai_goggles).
 *
 * Легенда частиц:
 *   HAPPY_VILLAGER  (зелёные)  — у поезда есть данные сигнальных блоков
 *   DRIPPING_HONEY  (янтарные) — есть только данные ребра (без сигналов)
 *   CLOUD           (серые)    — нет данных графа путей
 *   FLAME           (оранжевые)— зарезервированные сегменты пути (VBS)
 *   ELECTRIC_SPARK  (белые)    — текущее положение поезда
 *   END_ROD         (белые, линия) — вектор движения (8 блоков вперёд)
 *   SOUL_FIRE_FLAME (синие)    — опасная зона (препятствие ближе 15б по BFS)
 *   WITCH           (фиолет.)  — линия между поездами на одном пути
 *
 * Строка над интерфейсом:
 *   [ИИ] ID:СОСТОЯНИЕ скорость [расстояние_до_преграды] [флаги]
 *
 * Флаги состояния:
 *   ВСТР  — BFS обнаружил встречный поезд (head-on)
 *   НП    — поезд едет против направления рельса (not proper direction)
 *   ОБЪ   — найден свободный объездной путь
 *   НЗД   — нет места позади (нельзя сдать назад)
 *   ЛУЧ   — сканирование лучом (нет BFS-данных)
 *
 * Вызывается каждый серверный тик; внутри ограничено до каждых 4 тиков.
 */
@SuppressWarnings("null")
public class TrainDebugVisualizer {

    private static final int TICK_INTERVAL  = 4;
    private static final double RANGE_SQ   = 20000; // радиус ~141 блок (частицы)
    private static final double BAR_RANGE_SQ = 2304; // радиус ~48 блоков (панель действий)
    private static final int MAX_BAR_TRAINS = 4;

    /** Entry point — call from TrainEventHandler once per server tick. */
    public static void tickAll(ServerLevel level, long currentTick) {
        if (currentTick % TICK_INTERVAL != 0) return;

        TrainAIManager manager = TrainAIManager.getInstance();
        if (manager == null) return;

        Collection<TrainAIController> controllers = manager.getAllControllers();
        if (controllers.isEmpty()) return;

        // Pre-fetch VBS midpoints once for all players
        Map<UUID, List<BlockPos>> vbsMidpoints =
                VirtualBlockSystem.getInstance().getReservationMidpoints();

        for (ServerPlayer player : level.players()) {
            if (!isWearingGoggles(player)) continue;
            renderForPlayer(player, level, controllers, vbsMidpoints);
        }
    }

    // ─── Per-player render ───

    private static void renderForPlayer(ServerPlayer player,
                                         ServerLevel level,
                                         Collection<TrainAIController> controllers,
                                         Map<UUID, List<BlockPos>> vbsMidpoints) {
        BlockPos pPos = player.blockPosition();
        List<TrainAIController> nearby = new java.util.ArrayList<>();

        for (TrainAIController ctrl : controllers) {
            BlockPos tPos = ctrl.getCurrentPosition();
            if (tPos == null) continue;
            if (pPos.distSqr(tPos) > RANGE_SQ) continue;
            nearby.add(ctrl);

            // ── Train position dot ──
            level.sendParticles(player, ParticleTypes.ELECTRIC_SPARK,
                    true,
                    tPos.getX() + 0.5, tPos.getY() + 2.0, tPos.getZ() + 0.5,
                    3, 0.3, 0.2, 0.3, 0.0);

            // ── TrackGraph status halo ──
            int sigCount = ctrl.getSignalGroups().size();
            boolean hasEdge = ctrl.getLeadingEdge() != null || ctrl.getTrailingEdge() != null;
            net.minecraft.core.particles.ParticleOptions haloParticle;
            if (sigCount > 0) {
                haloParticle = ParticleTypes.HAPPY_VILLAGER;
            } else if (hasEdge) {
                haloParticle = ParticleTypes.DRIPPING_HONEY;
            } else {
                haloParticle = ParticleTypes.CLOUD;
            }
            double[] dx = {1.2, 0, -1.2, 0};
            double[] dz = {0, 1.2, 0, -1.2};
            for (int i = 0; i < 4; i++) {
                level.sendParticles(player, haloParticle,
                        true,
                        tPos.getX() + 0.5 + dx[i], tPos.getY() + 1.5, tPos.getZ() + 0.5 + dz[i],
                        1, 0, 0, 0, 0.01);
            }

            // ── VBS footprint (orange FLAME particles) ──
            List<BlockPos> reserved = vbsMidpoints.get(ctrl.getTrainId());
            if (reserved != null) {
                for (BlockPos rp : reserved) {
                    if (pPos.distSqr(rp) > RANGE_SQ) continue;
                    level.sendParticles(player, ParticleTypes.FLAME,
                            true,
                            rp.getX() + 0.5, rp.getY() + 0.5, rp.getZ() + 0.5,
                            1, 0.15, 0.1, 0.15, 0.01);
                }
            }

            // ── Heading vector (END_ROD particles — 8 blocks ahead) ──
            double hx = ctrl.getHeadingX();
            double hz = ctrl.getHeadingZ();
            if (hx != 0 || hz != 0) {
                for (int step = 2; step <= 8; step += 2) {
                    level.sendParticles(player, ParticleTypes.END_ROD,
                            true,
                            tPos.getX() + 0.5 + hx * step,
                            tPos.getY() + 1.0,
                            tPos.getZ() + 0.5 + hz * step,
                            1, 0, 0, 0, 0.0);
                }
            }

            // ── Danger zone (SOUL_FIRE_FLAME when graph-detected obstacle is close) ──
            if (ctrl.isGraphScanActive() && ctrl.getGraphDistanceToObstacle() > 0
                    && ctrl.getGraphDistanceToObstacle() <= 15) {
                for (int step = 3; step <= 12; step += 3) {
                    level.sendParticles(player, ParticleTypes.SOUL_FIRE_FLAME,
                            true,
                            tPos.getX() + 0.5 + hx * step,
                            tPos.getY() + 0.6,
                            tPos.getZ() + 0.5 + hz * step,
                            2, 0.2, 0.1, 0.2, 0.01);
                }
            }
        }

        // ── Same-track connection lines ──
        for (int i = 0; i < nearby.size(); i++) {
            for (int j = i + 1; j < nearby.size(); j++) {
                TrainAIController a = nearby.get(i);
                TrainAIController b = nearby.get(j);
                if (!a.isOnSameTrack(b)) continue;
                BlockPos aPos = a.getCurrentPosition();
                BlockPos bPos = b.getCurrentPosition();
                if (aPos == null || bPos == null) continue;
                double ax = aPos.getX() + 0.5, ay = aPos.getY() + 1.8, az = aPos.getZ() + 0.5;
                double bx = bPos.getX() + 0.5, by = bPos.getY() + 1.8, bz = bPos.getZ() + 0.5;
                double len = Math.sqrt((bx-ax)*(bx-ax) + (by-ay)*(by-ay) + (bz-az)*(bz-az));
                int steps = Math.max(2, (int)(len / 3.0));
                for (int s = 1; s < steps; s++) {
                    double t = (double) s / steps;
                    level.sendParticles(player, ParticleTypes.WITCH,
                            true,
                            ax + (bx-ax)*t, ay + (by-ay)*t, az + (bz-az)*t,
                            1, 0, 0, 0, 0.01);
                }
            }
        }

        // ── Панель действий ──
        // Сортируем ближайшие поезда по расстоянию, берём MAX_BAR_TRAINS ближайших.
        List<TrainAIController> barTrains = nearby.stream()
                .filter(c -> c.getCurrentPosition() != null
                          && pPos.distSqr(c.getCurrentPosition()) <= BAR_RANGE_SQ)
                .sorted(java.util.Comparator.comparingDouble(
                        c -> pPos.distSqr(c.getCurrentPosition())))
                .limit(MAX_BAR_TRAINS)
                .collect(Collectors.toList());

        if (barTrains.isEmpty()) return;

        int total = (int) nearby.stream()
                .filter(c -> c.getCurrentPosition() != null
                          && pPos.distSqr(c.getCurrentPosition()) <= BAR_RANGE_SQ)
                .count();
        int hidden = total - barTrains.size();

        StringBuilder bar = new StringBuilder("§b[ИИ]");
        for (TrainAIController ctrl : barTrains) {
            String цвет = цветСостояния(ctrl.getCurrentState());
            String сост = сокрСостояния(ctrl.getCurrentState());

            bar.append(" §e")
               .append(ctrl.getTrainId().toString(), 0, 4)
               .append("§f:")
               .append(цвет).append(сост)
               .append(String.format("§7 %.2fб", ctrl.getCurrentSpeed()));

            // Расстояние до преграды по BFS
            if (ctrl.isGraphScanActive() && ctrl.getGraphDistanceToObstacle() > 0) {
                bar.append(String.format("§d%.0fм", ctrl.getGraphDistanceToObstacle()));
            }

            // Кто блокирует
            UUID блок = ctrl.getObstacleTrainId();
            if (блок != null) {
                bar.append("§8→§c").append(блок.toString(), 0, 4);
            }

            // Флаги состояния
            StringBuilder флаги = new StringBuilder();
            if (ctrl.isGraphHitHeadOn())        флаги.append("§cВСТР ");  // встречка
            if (ctrl.isWrongWay())               флаги.append("§4НП ");    // не по правилам
            if (ctrl.isGraphFoundFreeDetour())   флаги.append("§aОБЪ ");   // объезд найден
            if (!ctrl.isSpaceAvailableBehind())  флаги.append("§6НЗД ");   // нет места назад
            if (!ctrl.isGraphScanActive())       флаги.append("§8ЛУЧ ");   // луч (не BFS)

            if (флаги.length() > 0) {
                bar.append("§8[").append(флаги.toString().trim()).append("§8]");
            }

            if (barTrains.indexOf(ctrl) < barTrains.size() - 1) {
                bar.append("§8|");
            }
        }
        if (hidden > 0) {
            bar.append("§8 ещё+").append(hidden);
        }

        player.displayClientMessage(Component.literal(bar.toString()), true);
    }

    // ─── Вспомогательные методы ───

    private static boolean isWearingGoggles(ServerPlayer player) {
        ItemStack helmet = player.getInventory().armor.get(3);
        return !helmet.isEmpty() && helmet.getItem() == ModItems.AI_GOGGLES.get();
    }

    private static String цветСостояния(com.fizzylovely.railwayevolution.ai.TrainState state) {
        return switch (state) {
            case CRUISING           -> "§a";  // зелёный  — едет
            case ANALYZING_OBSTACLE -> "§e";  // жёлтый   — анализирует
            case YIELDING           -> "§c";  // красный  — уступает
            case BYPASSING          -> "§d";  // сиреневый— объезжает
            case RETURNING          -> "§b";  // голубой  — возвращается
            case REVERSING          -> "§6";  // оранжевый— едет назад
            case TRAFFIC_JAM        -> "§4";  // тёмно-красный — пробка
            case WAIT_FOR_CLEARANCE -> "§5";  // тёмно-фиол.  — ожидает
        };
    }

    private static String сокрСостояния(com.fizzylovely.railwayevolution.ai.TrainState state) {
        return switch (state) {
            case CRUISING            -> "ЕДЕТ";
            case ANALYZING_OBSTACLE  -> "АНЛ";
            case BYPASSING           -> "ОБЪ";
            case YIELDING            -> "УСТ";   // уступает
            case RETURNING           -> "ВОЗ";   // возврат
            case REVERSING           -> "НАЗ";   // назад
            case TRAFFIC_JAM         -> "ПРБ";   // пробка
            case WAIT_FOR_CLEARANCE  -> "ОЖД";   // ожидание
        };
    }
}
