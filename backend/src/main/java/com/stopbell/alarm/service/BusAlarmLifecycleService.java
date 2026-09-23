package com.stopbell.alarm.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an evaluated polling result only after a short lifecycle row-lock transaction.
 */
@Service
public class BusAlarmLifecycleService {

    private final AlarmRepository alarmRepository;

    public BusAlarmLifecycleService(AlarmRepository alarmRepository) {
        this.alarmRepository = alarmRepository;
    }

    @Transactional
    public boolean applyIfCurrent(
            AlarmEvaluationKey evaluationKey,
            AlarmStatus expectedStatus,
            BusAlarmEvaluationResult evaluationResult,
            Instant now
    ) {
        Alarm alarm = alarmRepository.findByIdForUpdate(evaluationKey.alarmId()).orElse(null);
        if (alarm == null || alarm.getActivationGeneration() != evaluationKey.activationGeneration()
                || alarm.getStatus() != expectedStatus) {
            return false;
        }

        if (evaluationResult.followUpExpired() && alarm.getStatus() == AlarmStatus.FOLLOW_UP) {
            alarm.completeFollowUp();
            return true;
        }

        for (TransitEvent event : evaluationResult.eventCandidates()) {
            if (event.type() == TransitEventType.ARRIVED && alarm.getStatus() == AlarmStatus.ACTIVE) {
                applyArrival(alarm, event, now);
                break;
            }
            if (event.type() == TransitEventType.ONE_STOP_AFTER && alarm.getStatus() == AlarmStatus.FOLLOW_UP) {
                alarm.completeFollowUp();
                break;
            }
        }
        return true;
    }

    private static void applyArrival(Alarm alarm, TransitEvent event, Instant now) {
        if (alarm.getStatus() != AlarmStatus.ACTIVE) {
            return;
        }
        if (!alarm.getBusAlarmTarget().isNotifyOneStopAfter()) {
            alarm.deactivate();
            return;
        }

        LocalDateTime startedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                now.plus(BusAlarmEvaluationPolicy.FOLLOW_UP_TIMEOUT), ZoneOffset.UTC
        );
        alarm.startFollowUp(event.vehicleTrackingId(), startedAt, expiresAt);
    }
}
