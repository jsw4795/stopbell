package com.stopbell.alarm.service;

import java.time.Duration;

/**
 * Initial V1 evidence thresholds for Bus Alarm evaluation.
 */
public final class BusAlarmEvaluationPolicy {

    public static final double TAGO_TARGET_GPS_THRESHOLD_METERS = 100.0;
    public static final Duration OBSERVATION_FRESHNESS = Duration.ofSeconds(60);
    public static final Duration VEHICLE_MISSING_GRACE = Duration.ofSeconds(60);
    public static final Duration FOLLOW_UP_TIMEOUT = Duration.ofMinutes(5);

    private BusAlarmEvaluationPolicy() {
    }
}
