package com.stopbell.alarm.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
            BusAlarmEvaluationResult evaluationResult
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

        Optional<TransitEvent> event = selectEvent(evaluationResult.eventCandidates(), alarm.getStatus());
        if (event.isPresent() && event.get().type() == TransitEventType.ARRIVED
                && alarm.getStatus() == AlarmStatus.ACTIVE) {
            applyArrival(alarm, event.get());
        }
        if (event.isPresent() && event.get().type() == TransitEventType.ONE_STOP_AFTER
                && alarm.getStatus() == AlarmStatus.FOLLOW_UP) {
            alarm.completeFollowUp();
        }
        return true;
    }

    static Optional<TransitEvent> selectEvent(List<TransitEvent> candidates, AlarmStatus status) {
        return candidates.stream()
                .filter(event -> status == AlarmStatus.FOLLOW_UP
                        ? event.type() == TransitEventType.ONE_STOP_AFTER
                        : status == AlarmStatus.ACTIVE)
                .min(Comparator.comparingInt(BusAlarmLifecycleService::eventPriority)
                        .thenComparing(TransitEvent::vehicleTrackingId));
    }

    private static int eventPriority(TransitEvent event) {
        return switch (event.type()) {
            case ARRIVED -> 0;
            case PASSED -> 1;
            case ONE_STOP_BEFORE -> 2;
            case ONE_STOP_AFTER -> 3;
        };
    }

    private static void applyArrival(Alarm alarm, TransitEvent event) {
        if (alarm.getStatus() != AlarmStatus.ACTIVE) {
            return;
        }
        if (!alarm.getBusAlarmTarget().isNotifyOneStopAfter()) {
            alarm.deactivate();
            return;
        }

        Instant observedAt = event.observedAt();
        LocalDateTime startedAt = LocalDateTime.ofInstant(observedAt, ZoneOffset.UTC);
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                observedAt.plus(BusAlarmEvaluationPolicy.FOLLOW_UP_TIMEOUT), ZoneOffset.UTC
        );
        alarm.startFollowUp(event.vehicleTrackingId(), startedAt, expiresAt);
    }
}
