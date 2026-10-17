package com.fizzylovely.railwayevolution.ai.adapter;

import java.util.Locale;
import java.util.UUID;

/** Immutable diagnostic view of the native Create 6.0.9 train state. */
public record TrainDiagnosticSnapshot(
        UUID trainUuid,
        String trainName,
        double speed,
        double targetSpeed,
        double throttle,
        boolean derailed,
        boolean destinationPresent,
        String destinationName,
        String destinationIdentifier,
        Double distanceToDestination,
        Boolean waitingForSignal,
        Boolean schedulePaused,
        Boolean firstCarriageStalled,
        Double speedBeforeStall,
        boolean transientBrakeEngaged) {

    public boolean effectiveStopRequested() {
        return Double.compare(targetSpeed, 0.0) == 0;
    }

    public String format(String source, Long tick) {
        return String.format(Locale.ROOT,
                "[Create Train Diagnostic] source=%s tick=%s trainUuid=%s trainName=%s " +
                "speed=%s targetSpeed=%s throttle=%s derailed=%s " +
                "navigation.destination.present=%s navigation.destination.name=%s " +
                "navigation.destination.id=%s navigation.distanceToDestination=%s " +
                "navigation.waitingForSignal=%s scheduleRuntime.paused=%s " +
                "firstCarriage.stalled=%s speedBeforeStall=%s " +
                "brakes(native)=unavailable effectiveStopRequested(derived:targetSpeed==0)=%s " +
                "transientBrake(own)=%s",
                safeToken(source), tick == null ? "unavailable" : tick, trainUuid,
                quoted(trainName), number(speed), number(targetSpeed), number(throttle), derailed,
                destinationPresent, optionalQuoted(destinationName), optionalToken(destinationIdentifier),
                optionalNumber(distanceToDestination), optionalBoolean(waitingForSignal),
                optionalBoolean(schedulePaused), optionalBoolean(firstCarriageStalled),
                speedBeforeStall == null ? "null" : number(speedBeforeStall),
                effectiveStopRequested(), transientBrakeEngaged);
    }

    private static String number(double value) {
        return Double.toString(value);
    }

    private static String optionalNumber(Double value) {
        return value == null ? "unavailable" : number(value);
    }

    private static String optionalBoolean(Boolean value) {
        return value == null ? "unavailable" : value.toString();
    }

    private static String optionalQuoted(String value) {
        return value == null || value.isBlank() ? "unavailable" : quoted(value);
    }

    private static String optionalToken(String value) {
        return value == null || value.isBlank() ? "unavailable" : safeToken(value);
    }

    private static String quoted(String value) {
        String safe = value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace('\n', ' ').replace('\r', ' ');
        return '"' + safe + '"';
    }

    private static String safeToken(String value) {
        return value == null || value.isBlank() ? "unavailable" : value.replaceAll("\\s+", "_");
    }
}
