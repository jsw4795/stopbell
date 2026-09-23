package com.stopbell.alarm.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Memory-only state for an Alarm monitoring cycle. A fresh instance establishes a baseline.
 */
public record BusAlarmEvaluationState(
        boolean baselineEstablished,
        Map<String, VehicleTrackingState> trackedVehicles,
        Map<String, Instant> baselineAfterVehicles,
        Map<String, VehicleTrackingState> followUpVehicles
) {

    public BusAlarmEvaluationState {
        trackedVehicles = Map.copyOf(trackedVehicles);
        baselineAfterVehicles = Map.copyOf(baselineAfterVehicles);
        followUpVehicles = Map.copyOf(followUpVehicles);
    }

    public static BusAlarmEvaluationState initial() {
        return new BusAlarmEvaluationState(false, Map.of(), Map.of(), Map.of());
    }

    public Mutable mutableCopy() {
        return new Mutable(
                baselineEstablished,
                new LinkedHashMap<>(trackedVehicles),
                new LinkedHashMap<>(baselineAfterVehicles),
                new LinkedHashMap<>(followUpVehicles)
        );
    }

    public BusAlarmEvaluationState forFollowUp(String vehicleTrackingId) {
        VehicleTrackingState tracking = trackedVehicles.get(vehicleTrackingId);
        if (tracking == null) {
            throw new IllegalArgumentException("Follow-up vehicle must have active tracking state");
        }
        return new BusAlarmEvaluationState(true, Map.of(), Map.of(), Map.of(vehicleTrackingId, tracking));
    }

    public static final class Mutable {
        private boolean baselineEstablished;
        private final Map<String, VehicleTrackingState> trackedVehicles;
        private final Map<String, Instant> baselineAfterVehicles;
        private final Map<String, VehicleTrackingState> followUpVehicles;

        private Mutable(
                boolean baselineEstablished,
                Map<String, VehicleTrackingState> trackedVehicles,
                Map<String, Instant> baselineAfterVehicles,
                Map<String, VehicleTrackingState> followUpVehicles
        ) {
            this.baselineEstablished = baselineEstablished;
            this.trackedVehicles = trackedVehicles;
            this.baselineAfterVehicles = baselineAfterVehicles;
            this.followUpVehicles = followUpVehicles;
        }

        public boolean baselineEstablished() {
            return baselineEstablished;
        }

        public void establishBaseline() {
            baselineEstablished = true;
        }

        public Map<String, VehicleTrackingState> trackedVehicles() {
            return trackedVehicles;
        }

        public Map<String, Instant> baselineAfterVehicles() {
            return baselineAfterVehicles;
        }

        public Map<String, VehicleTrackingState> followUpVehicles() {
            return followUpVehicles;
        }

        public BusAlarmEvaluationState freeze() {
            return new BusAlarmEvaluationState(
                    baselineEstablished,
                    trackedVehicles,
                    baselineAfterVehicles,
                    followUpVehicles
            );
        }
    }
}
