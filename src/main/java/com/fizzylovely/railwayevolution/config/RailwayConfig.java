package com.fizzylovely.railwayevolution.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Mod configuration — tunable parameters for the Train AI system.
 * Vanilla edition: only core collision avoidance + player safety.
 *
 * All values can be changed in config/create_railway-common.toml
 */
public class RailwayConfig {

    // ─── Runtime toggles (changed via /railway command, not persisted to TOML) ───
    /** When false, all AI log messages (info/debug/warn) are suppressed. Default: OFF. */
    public static volatile boolean loggingEnabled = false;
    /** When true, trains detect and brake for players on tracks. Default: ON. */
    public static volatile boolean safeModeEnabled = true;
    /**
     * When true, attempts to remove Create's contraption size limits so you can
     * build longer trains and place unlimited rails. Default: OFF.
     */
    public static volatile boolean infinityMode = false;

    public static final ModConfigSpec SPEC;

    // ─── Obstacle Detection ───
    public static final ModConfigSpec.DoubleValue obstacleDetectionRange;

    // ─── Virtual Block System (VBS) ───
    public static final ModConfigSpec.LongValue reservationTTLTicks;
    public static final ModConfigSpec.IntValue maxLookaheadSegments;
    public static final ModConfigSpec.IntValue vbsCleanupIntervalTicks;

    // ─── Right-of-Way ───
    public static final ModConfigSpec.DoubleValue minimumStopDistance;
    public static final ModConfigSpec.LongValue maxYieldTicks;

    // ─── Reverse Maneuver ───
    public static final ModConfigSpec.BooleanValue reverseManeuverEnabled;
    public static final ModConfigSpec.DoubleValue reverseBackupDistance;
    public static final ModConfigSpec.DoubleValue reverseSpeed;

    // ─── Manager ───
    public static final ModConfigSpec.IntValue trainScanIntervalTicks;

    // ─── Player Safety System ───
    public static final ModConfigSpec.BooleanValue playerSafetyEnabled;
    public static final ModConfigSpec.DoubleValue playerDetectionRange;
    public static final ModConfigSpec.DoubleValue playerEmergencyStopDistance;
    public static final ModConfigSpec.DoubleValue playerSafeDistanceFromRail;
    public static final ModConfigSpec.IntValue playerWhistleWarningTicks;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Create: Railway Evolution — Train AI Configuration (Vanilla Edition)");
        builder.push("obstacle_detection");
        {
            obstacleDetectionRange = builder
                    .comment("How far ahead (in blocks) a train scans for obstacles. Increase for faster trains. Default: 50.")
                    .defineInRange("detection_range", 50.0, 10.0, 256.0);
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

        builder.push("right_of_way");
        {
            minimumStopDistance = builder
                    .comment("Minimum safe stopping distance (blocks) — train will never get closer than this to any obstacle. Default: 7.")
                    .defineInRange("minimum_stop_distance", 7.0, 2.0, 20.0);

            maxYieldTicks = builder
                    .comment("Max ticks a train will yield before reverse-escape maneuver kicks in.")
                    .defineInRange("max_yield_ticks", 300L, 100L, 6000L);
        }
        builder.pop();

        builder.push("reverse_maneuver");
        {
            reverseManeuverEnabled = builder
                    .comment("Enable reverse-escape maneuver when a train is stuck yielding too long.")
                    .define("reverse_maneuver_enabled", true);

            reverseBackupDistance = builder
                    .comment("How far (blocks) the train backs up before handing control back to navigation.")
                    .defineInRange("reverse_backup_distance", 8.0, 3.0, 40.0);

            reverseSpeed = builder
                    .comment("Speed (blocks/tick) used during the reverse-escape maneuver.")
                    .defineInRange("reverse_speed", 0.4, 0.1, 1.0);
        }
        builder.pop();

        builder.push("manager");
        {
            trainScanIntervalTicks = builder
                    .comment("How often (ticks) to scan Create for new/removed trains.")
                    .defineInRange("train_scan_interval_ticks", 40, 10, 200);
        }
        builder.pop();

        builder.push("player_safety");
        {
            builder.comment("Player safety system — trains detect and avoid players on tracks.");

            playerSafetyEnabled = builder
                    .comment("Enable player detection and safety braking system.")
                    .define("enabled", true);

            playerDetectionRange = builder
                    .comment("How far ahead (blocks) trains scan for players on tracks.")
                    .defineInRange("detection_range", 30.0, 10.0, 100.0);

            playerEmergencyStopDistance = builder
                    .comment("Distance (blocks) at which the train must fully stop if player doesn't move.")
                    .defineInRange("emergency_stop_distance", 5.0, 2.0, 15.0);

            playerSafeDistanceFromRail = builder
                    .comment("How far (blocks) the player must be from the rail center to be considered safe.")
                    .defineInRange("safe_distance_from_rail", 2.0, 1.0, 5.0);

            playerWhistleWarningTicks = builder
                    .comment("How long (ticks) the whistle warns before emergency braking begins.",
                             "60 ticks = 3 seconds.")
                    .defineInRange("whistle_warning_ticks", 60, 20, 200);
        }
        builder.pop();

        SPEC = builder.build();
    }
}
