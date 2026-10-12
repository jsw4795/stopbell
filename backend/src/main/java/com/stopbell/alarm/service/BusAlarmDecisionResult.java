package com.stopbell.alarm.service;

import java.util.UUID;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;

/** Current lifecycle snapshot returned only after the decision transaction commits. */
public record BusAlarmDecisionResult(
        Outcome outcome, long activationGeneration, AlarmStatus status,
        String followUpVehicleId, UUID followUpTrackingCycleId
) {
    public enum Outcome { COMMITTED, DUPLICATE, STALE, RETRY_REQUIRED }

    static BusAlarmDecisionResult current(Outcome outcome, Alarm alarm) {
        return alarm == null ? new BusAlarmDecisionResult(outcome, -1, null, null, null)
                : new BusAlarmDecisionResult(outcome, alarm.getActivationGeneration(), alarm.getStatus(),
                        alarm.getFollowUpVehicleTrackingId(), alarm.getFollowUpTrackingCycleId());
    }

    public boolean permitsMemory(AlarmEvaluationKey key, AlarmStatus expectedStatus, TransitEvent event) {
        if ((outcome != Outcome.COMMITTED && outcome != Outcome.DUPLICATE)
                || activationGeneration != key.activationGeneration()) {
            return false;
        }
        if (event == null) {
            return outcome == Outcome.COMMITTED;
        }
        if (event.type() == TransitEventType.ARRIVED) {
            return status == AlarmStatus.INACTIVE || (status == AlarmStatus.FOLLOW_UP
                    && event.vehicleTrackingId().equals(followUpVehicleId)
                    && event.trackingCycleId().equals(followUpTrackingCycleId));
        }
        if (event.type() == TransitEventType.ONE_STOP_AFTER) {
            return status == AlarmStatus.INACTIVE;
        }
        return expectedStatus == AlarmStatus.ACTIVE && status == AlarmStatus.ACTIVE;
    }
}
