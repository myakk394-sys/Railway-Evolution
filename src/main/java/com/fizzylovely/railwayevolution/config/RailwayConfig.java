package com.fizzylovely.railwayevolution.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Mod configuration — all tunable parameters for the Train AI system.
 *
 * Конфигурация мода / Mod Configuration:
 * ──────────────────────────────────────
 * All values can be changed in config/create_railway-common.toml
 */
public class RailwayConfig {

    public static final ForgeConfigSpec SPEC;

    // ─── Obstacle Detection ───
    public static final ForgeConfigSpec.DoubleValue obstacleDetectionRange;

    // ─── Virtual Block System (VBS) ───
    public static final ForgeConfigSpec.LongValue reservationTTLTicks;
    public static final ForgeConfigSpec.IntValue maxLookaheadSegments;
    public static final ForgeConfigSpec.IntValue vbsCleanupIntervalTicks;

    // ─── Overtaking & Bypass ───
    public static final ForgeConfigSpec.DoubleValue maxBypassDistance;
    public static final ForgeConfigSpec.DoubleValue oncomingSafetyDistance;

    // ─── Right-of-Way ───
    public static final ForgeConfigSpec.DoubleValue emergencyStopDistance;
    public static final ForgeConfigSpec.DoubleValue criticalYieldDistance;
    public static final ForgeConfigSpec.DoubleValue minimumStopDistance;
    public static final ForgeConfigSpec.LongValue maxYieldTicks;
    public static final ForgeConfigSpec.IntValue pocketSafetyBuffer;

    // ─── Reverse Maneuver ───
    public static final ForgeConfigSpec.BooleanValue reverseManeuverEnabled;
    public static final ForgeConfigSpec.DoubleValue reverseBackupDistance;
    public static final ForgeConfigSpec.DoubleValue reverseSpeed;

    // ─── Priority Weights ───
    public static final ForgeConfigSpec.IntValue priorityWeightLane;
    public static final ForgeConfigSpec.IntValue priorityWeightSpeed;
    public static final ForgeConfigSpec.IntValue priorityWeightDistance;
    public static final ForgeConfigSpec.IntValue priorityWeightLoad;
    public static final ForgeConfigSpec.IntValue priorityWeightWait;
    public static final ForgeConfigSpec.IntValue priorityWeightEmergency;

    // ─── Conflict Resolution ───
    public static final ForgeConfigSpec.LongValue deadlockTimeoutTicks;

    // ─── Manager ───
    public static final ForgeConfigSpec.IntValue trainScanIntervalTicks;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Create: Railway Evolution — Train AI Configuration");
        builder.push("obstacle_detection");
        {
            obstacleDetectionRange = builder
                    .comment("How far ahead (in blocks) a train scans for obstacles.")
                    .defineInRange("detection_range", 15.0, 7.0, 256.0);
        }
        builder.pop();

        builder.push("virtual_block_system");
        {
            reservationTTLTicks = builder
                    .comment("Time-to-live for a track segment reservation (in ticks). Safety net.")
                    .defineInRange("reservation_ttl_ticks", 200L, 40L, 1200L);

            maxLookaheadSegments = builder
                    .comment("Max number of segments to reserve ahead of the train.")
                    .defineInRange("max_lookahead_segments", 5, 1, 20);

            vbsCleanupIntervalTicks = builder
                    .comment("How often (in ticks) to clean up expired VBS reservations.")
                    .defineInRange("cleanup_interval_ticks", 100, 20, 600);
        }
        builder.pop();

        builder.push("overtaking");
        {
            maxBypassDistance = builder
                    .comment("Maximum allowed bypass distance (blocks). Prevents excessively long wrong-way travel.")
                    .defineInRange("max_bypass_distance", 200.0, 50.0, 1000.0);

            oncomingSafetyDistance = builder
                    .comment("Distance (blocks) at which an oncoming train triggers yielding.")
                    .defineInRange("oncoming_safety_distance", 80.0, 30.0, 300.0);
        }
        builder.pop();

        builder.push("right_of_way");
        {
            emergencyStopDistance = builder
                    .comment("Distance (blocks) for emergency stop when oncoming is too close.")
                    .defineInRange("emergency_stop_distance", 15.0, 5.0, 50.0);

            criticalYieldDistance = builder
                    .comment("Within this distance, bypassing train ALWAYS yields to lane owner.")
                    .defineInRange("critical_yield_distance", 40.0, 15.0, 120.0);

            minimumStopDistance = builder
                    .comment("Minimum safe stopping distance (blocks) from any train directly ahead. Hard stop at this range.")
                    .defineInRange("minimum_stop_distance", 5.0, 2.0, 20.0);

            maxYieldTicks = builder
                    .comment("Max ticks a train will yield before reverse-escape maneuver kicks in.")
                    .defineInRange("max_yield_ticks", 300L, 100L, 6000L);

            pocketSafetyBuffer = builder
                    .comment("Extra blocks of buffer when calculating required pocket length.")
                    .defineInRange("pocket_safety_buffer", 4, 1, 16);
        }
        builder.pop();

        builder.push("reverse_maneuver");
        {
            reverseManeuverEnabled = builder
                    .comment("Enable reverse-escape maneuver when a train is stuck yielding too long.",
                             "The train backs up, then navigation reroutes it to the destination from the other direction.")
                    .define("reverse_maneuver_enabled", true);

            reverseBackupDistance = builder
                    .comment("How far (blocks) the train backs up before handing control back to navigation.",
                             "This should be just enough to reach the nearest junction/switch to the bypass track.")
                    .defineInRange("reverse_backup_distance", 8.0, 3.0, 40.0);

            reverseSpeed = builder
                    .comment("Speed (blocks/tick) used during the reverse-escape maneuver.")
                    .defineInRange("reverse_speed", 0.4, 0.1, 1.0);
        }
        builder.pop();

        builder.push("priority_weights");
        {
            builder.comment("Weights for each factor in the priority calculation.",
                    "Higher values make that factor more decisive.");

            priorityWeightLane = builder
                    .comment("Bonus for being on the correct (right) lane.")
                    .defineInRange("weight_lane", 100, 0, 500);

            priorityWeightSpeed = builder
                    .comment("Max bonus from current speed ratio.")
                    .defineInRange("weight_speed", 10, 0, 50);

            priorityWeightDistance = builder
                    .comment("Max bonus from proximity to destination.")
                    .defineInRange("weight_distance", 20, 0, 100);

            priorityWeightLoad = builder
                    .comment("Max bonus from train size/load.")
                    .defineInRange("weight_load", 15, 0, 50);

            priorityWeightWait = builder
                    .comment("Max bonus from accumulated wait time (anti-starvation).")
                    .defineInRange("weight_wait", 30, 0, 100);

            priorityWeightEmergency = builder
                    .comment("Flat bonus for emergency-designated trains.")
                    .defineInRange("weight_emergency", 50, 0, 200);
        }
        builder.pop();

        builder.push("conflict_resolution");
        {
            deadlockTimeoutTicks = builder
                    .comment("Ticks before two mutually-yielding trains are declared deadlocked.")
                    .defineInRange("deadlock_timeout_ticks", 400L, 100L, 2000L);
        }
        builder.pop();

        builder.push("manager");
        {
            trainScanIntervalTicks = builder
                    .comment("How often (ticks) to scan Create for new/removed trains.")
                    .defineInRange("train_scan_interval_ticks", 40, 10, 200);
        }
        builder.pop();

        SPEC = builder.build();
    }
}
