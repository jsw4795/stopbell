package com.stopbell.boarding.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.stopbell.boarding.domain.BoardingTrackingDecision;
import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.notification.entity.BoardingNotificationEventType;
import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.entity.NotificationEvent;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.notification.repository.NotificationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Joins the caller's Session lock transaction; never commits an Event independently. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class BoardingNotificationService {
    private static final Logger log = LoggerFactory.getLogger(BoardingNotificationService.class);
    private final NotificationEventRepository events;
    private final NotificationDeliveryRepository deliveries;
    private final DeviceRepository devices;
    private final Duration prepareTtl;
    private final Clock clock;

    public BoardingNotificationService(NotificationEventRepository events, NotificationDeliveryRepository deliveries,
            DeviceRepository devices, @Value("${notification.decision.ttl.prepare-alight:PT30S}") Duration prepareTtl,
            @Qualifier("transitMetadataClock") Clock clock) {
        var validation = new BoardingSessionProperties();
        validation.setMaxLifetime(prepareTtl);
        this.events = events;
        this.deliveries = deliveries;
        this.devices = devices;
        this.prepareTtl = prepareTtl;
        this.clock = clock;
    }

    /** Called only after current Session, metadata and a new checkpoint have been validated under lock. */
    public Long recordDecision(BoardingSession session, BoardingTrackingSnapshot expected, BoardingTrackingDecision decision) {
        boolean destination = decision.outcome() == BoardingTrackingDecision.Outcome.ARRIVAL_OBSERVED
                || decision.outcome() == BoardingTrackingDecision.Outcome.PASSED_OBSERVED;
        if (!destination && (decision.outcome() != BoardingTrackingDecision.Outcome.PREPARE_ALIGHT
                || !session.isPrepareNotificationEnabled())) {
            return null;
        }
        if (session.getStatus() != (destination ? BoardingSessionStatus.COMPLETED : BoardingSessionStatus.TRACKING)) {
            return null;
        }
        var type = destination ? BoardingNotificationEventType.DESTINATION_REACHED
                : BoardingNotificationEventType.PREPARE_ALIGHT;
        var checkpoint = decision.checkpoint();
        int gap = session.getTraversal().targetIndex() - session.getCheckpoint().getIndex();
        if (checkpoint == null || !checkpoint.equals(session.getCheckpoint())
                || !checkpoint.getObservedAt().equals(decision.observedAt())
                || !checkpoint.getObservedAt().isAfter(expected.checkpoint().getObservedAt())
                || expected.requiresConfirmation() || (destination ? gap > 0 : gap != 1 && gap != 2)
                || (destination && (session.getStateReason() != decision.reason()
                    || expected.checkpoint().getIndex() > session.getTraversal().targetIndex()))
                || !Integer.valueOf(gap).equals(decision.remainingOccurrenceGap())) {
            throw new IllegalArgumentException("Notification requires newly verified current progress");
        }
        var existing = events.findByBoardingSessionIdAndBoardingEventType(session.getId(), type);
        if (existing.isPresent()) {
            return existing.orElseThrow().getId();
        }
        LocalDateTime expiresAt = Duration.between(checkpoint.getObservedAt(), session.getExpiresAt()).compareTo(prepareTtl) <= 0
                ? session.getExpiresAt() : checkpoint.getObservedAt().plus(prepareTtl);
        if (!expiresAt.equals(decision.expiresAt())) {
            throw new IllegalArgumentException("Notification must preserve original observation TTL");
        }
        // Read the Clock after identity locking and immediately before IDENTITY INSERT.
        LocalDateTime createdAt = now();
        if (!createdAt.isBefore(expiresAt)) {
            return null;
        }
        NotificationEvent event = events.saveAndFlush(NotificationEvent.forBoardingSession(session.getId(),
                session.getUser(), type, checkpoint,
                decision.eventDetectedAt(), createdAt, expiresAt));
        // Session -> Event -> current eligible Device rows in ascending ID order -> Delivery.
        var recipients = devices.findEligibleByUserIdForUpdate(session.getUser().getId());
        LocalDateTime deliveryCreatedAt = now();
        for (var device : recipients) {
            deliveries.save(new NotificationDelivery(event, device, device.getOwnershipGeneration(),
                    expiresAt, deliveryCreatedAt));
        }
        if (recipients.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("Boarding notification outcome=NO_RECIPIENT eventType={}", type);
                }
            });
        }
        return event.getId();
    }

    /** Exact identity check in a fresh reconciliation transaction; never selects recipients. */
    public boolean exists(Long sessionId, Long eventId) {
        for (var type : BoardingNotificationEventType.values()) {
            if (events.findByBoardingSessionIdAndBoardingEventType(sessionId, type)
                    .filter(event -> eventId == null || eventId.equals(event.getId())).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public void terminatePending(BoardingSession session) {
        boolean expired = session.getStatus() == BoardingSessionStatus.EXPIRED;
        boolean disallowed = !session.getStatus().isOpen()
                || (session.getStatus() == BoardingSessionStatus.SUSPENDED
                    && !session.getStateReason().allowsAutomaticRevalidation());
        if (!disallowed) {
            return;
        }
        for (var type : BoardingNotificationEventType.values()) {
            // Completion ends preparation but preserves the atomic destination notification.
            if (session.getStatus() == BoardingSessionStatus.COMPLETED
                    && type == BoardingNotificationEventType.DESTINATION_REACHED) {
                continue;
            }
            // Session -> exact Event -> PENDING Delivery current locks, without a stale joined snapshot.
            var event = events.findByBoardingSessionIdAndBoardingEventType(session.getId(), type);
            if (event.isEmpty()) {
                continue;
            }
            for (var delivery : deliveries.findPendingByBoardingEventIdForUpdate(event.orElseThrow().getId())) {
                LocalDateTime now = now();
                if (expired || !now.isBefore(delivery.getExpiresAt())) {
                    delivery.expire(now);
                } else {
                    delivery.terminateDispatch(now);
                }
            }
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
