package com.stopbell.alarm.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.notification.service.NotificationDecisionTtlPolicy;
import com.stopbell.notification.entity.Device;
import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.entity.NotificationEvent;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.notification.repository.NotificationEventRepository;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.stopbell.alarm.service.BusAlarmDecisionResult.Outcome.*;

/** Commits lifecycle, logical Event and the initial recipient set in one short transaction. */
@Service
public class BusAlarmLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(BusAlarmLifecycleService.class);
    private static final String EVENT_UNIQUE = "uk_notification_events_logical_identity";
    private final AlarmRepository alarmRepository;
    private final NotificationEventRepository events;
    private final NotificationDeliveryRepository deliveries;
    private final DeviceRepository devices;
    private final NotificationDecisionTtlPolicy ttlPolicy;
    private final Clock clock;
    private final TransactionTemplate decisionTransaction;

    public BusAlarmLifecycleService(
            AlarmRepository alarmRepository, NotificationEventRepository events,
            NotificationDeliveryRepository deliveries, DeviceRepository devices,
            NotificationDecisionTtlPolicy ttlPolicy, PlatformTransactionManager transactionManager,
            @Qualifier("transitMetadataClock") Clock clock
    ) {
        this.alarmRepository = alarmRepository;
        this.events = events;
        this.deliveries = deliveries;
        this.devices = devices;
        this.ttlPolicy = ttlPolicy;
        this.clock = clock;
        decisionTransaction = new TransactionTemplate(transactionManager);
        // The entire decision, never Event alone; execute() returns only after commit.
        decisionTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public BusAlarmDecisionResult applyIfCurrent(
            AlarmEvaluationKey key, AlarmStatus expectedStatus,
            BusAlarmEvaluationResult result, Instant eventDetectedAt
    ) {
        if (key == null || expectedStatus == null || result == null || eventDetectedAt == null
                || result.eventCandidates().size() > 1) {
            throw new IllegalArgumentException("Decision requires the original key, status, selected result and detected time");
        }
        TransitEvent event = result.eventCandidates().stream().findFirst().orElse(null);
        // Two attempts per invocation; Scheduler retains this same result if DB availability remains uncertain.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return decisionTransaction.execute(status -> decide(key, expectedStatus, result, event, eventDetectedAt));
            } catch (DataIntegrityViolationException exception) {
                if (!isEventIdentityConflict(exception) || event == null) {
                    throw exception;
                }
                // Failed transaction and its EntityManager are gone. Never SELECT in rollback-only context.
                try {
                    return decisionTransaction.execute(status -> {
                        Alarm current = alarmRepository.findByIdForUpdate(key.alarmId()).orElse(null);
                        if (findEvent(key, event).isEmpty()) {
                            throw exception;
                        }
                        return BusAlarmDecisionResult.current(current == null
                                || current.getActivationGeneration() != key.activationGeneration() ? STALE : DUPLICATE, current);
                    });
                } catch (TransientDataAccessException | TransactionException retryFailure) {
                    return BusAlarmDecisionResult.current(RETRY_REQUIRED, null);
                }
            } catch (TransientDataAccessException | TransactionException exception) {
                log.warn("Notification decision database retry: attempt={}, failureType={}",
                        attempt + 1, exception.getClass().getSimpleName());
            }
        }
        return BusAlarmDecisionResult.current(RETRY_REQUIRED, null);
    }

    private BusAlarmDecisionResult decide(AlarmEvaluationKey key, AlarmStatus expectedStatus,
            BusAlarmEvaluationResult result, TransitEvent event, Instant detectedAt) {
        Alarm alarm = alarmRepository.findByIdForUpdate(key.alarmId()).orElse(null);
        if (alarm == null || alarm.getActivationGeneration() != key.activationGeneration()) {
            return BusAlarmDecisionResult.current(STALE, alarm);
        }
        // Current locking read BEFORE expected-status check: ARRIVED and after change lifecycle.
        if (event != null && findEvent(key, event).isPresent()) {
            return BusAlarmDecisionResult.current(DUPLICATE, alarm);
        }
        Instant now = clock.instant();
        if (alarm.getStatus() != expectedStatus || !validCandidate(alarm, result, event, detectedAt, now)) {
            return BusAlarmDecisionResult.current(STALE, alarm);
        }
        if (result.followUpExpired()) {
            alarm.completeFollowUp();
        } else if (event != null) {
            if (event.type() == TransitEventType.ARRIVED) {
                applyArrival(alarm, event);
            } else if (event.type() == TransitEventType.ONE_STOP_AFTER) {
                alarm.completeFollowUp();
            }
            LocalDateTime createdAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
            NotificationEvent saved = events.save(new NotificationEvent(key.alarmId(), alarm.getUser(),
                    key.activationGeneration(), event, LocalDateTime.ofInstant(detectedAt, ZoneOffset.UTC), createdAt));
            // Alarm -> exact Event -> current Device rows ordered by ID. Registration holds Device locks only.
            List<Device> recipients = devices.findEligibleByUserIdForUpdate(alarm.getUser().getId());
            LocalDateTime expiresAt = LocalDateTime.ofInstant(ttlPolicy.expiresAt(event.type(), event.observedAt()), ZoneOffset.UTC);
            for (Device device : recipients) {
                deliveries.save(new NotificationDelivery(saved, device, device.getOwnershipGeneration(), expiresAt, createdAt));
            }
            if (recipients.isEmpty()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        log.info("Notification decision outcome=NO_RECIPIENT eventType={}", event.type());
                    }
                });
            }
        }
        alarmRepository.flush();
        return BusAlarmDecisionResult.current(COMMITTED, alarm);
    }

    private Optional<NotificationEvent> findEvent(AlarmEvaluationKey key, TransitEvent event) {
        return events.findByAlarmIdAndActivationGenerationAndTrackingCycleIdAndEventType(
                key.alarmId(), key.activationGeneration(), event.trackingCycleId().toString(), event.type());
    }

    private static boolean validCandidate(Alarm alarm, BusAlarmEvaluationResult result,
            TransitEvent event, Instant detectedAt, Instant now) {
        BusAlarmTarget target = alarm.getBusAlarmTarget();
        if (target == null || detectedAt.isAfter(now)) {
            return false;
        }
        if (alarm.getStatus() == AlarmStatus.FOLLOW_UP) {
            VehicleTrackingState tracking = result.nextState().followUpVehicles().get(alarm.getFollowUpVehicleTrackingId());
            if ((!result.nextState().followUpVehicles().isEmpty() && tracking == null)
                    || (tracking != null && !tracking.trackingCycleId().equals(alarm.getFollowUpTrackingCycleId()))) {
                return false;
            }
            if (result.followUpExpired()) {
                return event == null && !now.isBefore(alarm.getFollowUpExpiresAt().toInstant(ZoneOffset.UTC));
            }
            if (!now.isBefore(alarm.getFollowUpExpiresAt().toInstant(ZoneOffset.UTC))) {
                return false;
            }
        } else if (result.followUpExpired() || alarm.getStatus() != AlarmStatus.ACTIVE) {
            return false;
        }
        if (event == null) {
            return true;
        }
        if (event.observedAt().isAfter(detectedAt)
                || conflicts(event, target.getExternalStopId(), target.getTargetStopOrder())
                || conflicts(event, target.getPredecessorExternalStopId(), target.getPredecessorStopOrder())
                || conflicts(event, target.getSuccessorExternalStopId(), target.getSuccessorStopOrder())) {
            return false;
        }
        return switch (event.type()) {
            case ONE_STOP_BEFORE -> alarm.getStatus() == AlarmStatus.ACTIVE && target.isNotifyOneStopBefore()
                    && target.getPredecessorExternalStopId().equals(event.currentStopExternalId())
                    && target.getPredecessorStopOrder().equals(event.currentStopOrder());
            case ARRIVED -> alarm.getStatus() == AlarmStatus.ACTIVE
                    && target.getExternalStopId().equals(event.currentStopExternalId())
                    && event.currentStopOrder() != null && target.getTargetStopOrder() == event.currentStopOrder();
            case PASSED -> alarm.getStatus() == AlarmStatus.ACTIVE
                    && event.currentStopExternalId() != null && event.currentStopOrder() != null
                    && event.currentStopOrder() > target.getTargetStopOrder();
            case ONE_STOP_AFTER -> alarm.getStatus() == AlarmStatus.FOLLOW_UP && target.isNotifyOneStopAfter()
                    && alarm.getFollowUpVehicleTrackingId().equals(event.vehicleTrackingId())
                    && alarm.getFollowUpTrackingCycleId().equals(event.trackingCycleId())
                    && !event.observedAt().isBefore(alarm.getFollowUpStartedAt().toInstant(ZoneOffset.UTC))
                    && event.currentStopOrder() != null && event.currentStopExternalId() != null
                    && ((target.getSuccessorExternalStopId().equals(event.currentStopExternalId())
                        && target.getSuccessorStopOrder().equals(event.currentStopOrder()))
                        || event.currentStopOrder() > target.getSuccessorStopOrder());
        };
    }

    private static boolean conflicts(TransitEvent event, String stopId, Integer stopOrder) {
        if (stopId == null || stopOrder == null || event.currentStopExternalId() == null || event.currentStopOrder() == null) {
            return false;
        }
        return stopId.equals(event.currentStopExternalId()) != stopOrder.equals(event.currentStopOrder());
    }

    private static boolean isEventIdentityConflict(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (EVENT_UNIQUE.equals(name) || (name != null && name.endsWith("." + EVENT_UNIQUE))) {
                    return true;
                }
            }
        }
        return false;
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
        LocalDateTime startedAt = LocalDateTime.ofInstant(observedAt, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                observedAt.plus(BusAlarmEvaluationPolicy.FOLLOW_UP_TIMEOUT), ZoneOffset.UTC
        ).truncatedTo(ChronoUnit.MICROS);
        alarm.startFollowUp(event.vehicleTrackingId(), event.trackingCycleId(), startedAt, expiresAt);
    }
}
