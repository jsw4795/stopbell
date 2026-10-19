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
    public Long prepare(BoardingSession session, BoardingTrackingSnapshot expected, BoardingTrackingDecision decision) {
        if (decision.outcome() != BoardingTrackingDecision.Outcome.PREPARE_ALIGHT
                || session.getStatus() != BoardingSessionStatus.TRACKING) {
            return null;
        }
        var checkpoint = decision.checkpoint();
        int gap = session.getTraversal().targetIndex() - session.getCheckpoint().getIndex();
        if (checkpoint == null || !checkpoint.equals(session.getCheckpoint())
                || !checkpoint.getObservedAt().equals(decision.observedAt())
                || !checkpoint.getObservedAt().isAfter(expected.checkpoint().getObservedAt())
                || expected.requiresConfirmation() || (gap != 1 && gap != 2)
                || !Integer.valueOf(gap).equals(decision.remainingOccurrenceGap())) {
            throw new IllegalArgumentException("Preparation requires newly verified current progress");
        }
        var existing = events.findByBoardingSessionIdAndBoardingEventType(
                session.getId(), BoardingNotificationEventType.PREPARE_ALIGHT);
        if (existing.isPresent()) {
            return existing.orElseThrow().getId();
        }
        LocalDateTime expiresAt = Duration.between(checkpoint.getObservedAt(), session.getExpiresAt()).compareTo(prepareTtl) <= 0
                ? session.getExpiresAt() : checkpoint.getObservedAt().plus(prepareTtl);
        if (!expiresAt.equals(decision.expiresAt())) {
            throw new IllegalArgumentException("Preparation must preserve original observation TTL");
        }
        // Read the Clock after identity locking and immediately before IDENTITY INSERT.
        LocalDateTime createdAt = now();
        if (!createdAt.isBefore(expiresAt)) {
            return null;
        }
        NotificationEvent event = events.saveAndFlush(NotificationEvent.forBoardingSession(session.getId(),
                session.getUser(), BoardingNotificationEventType.PREPARE_ALIGHT, checkpoint,
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
                    log.info("Boarding notification outcome=NO_RECIPIENT eventType=PREPARE_ALIGHT");
                }
            });
        }
        return event.getId();
    }

    /** Exact identity check in a fresh reconciliation transaction; never selects recipients. */
    public boolean exists(Long sessionId, Long eventId) {
        return events.findByBoardingSessionIdAndBoardingEventType(sessionId,
                BoardingNotificationEventType.PREPARE_ALIGHT).filter(event -> eventId == null
                        || eventId.equals(event.getId())).isPresent();
    }

    public void terminatePending(BoardingSession session) {
        boolean expired = session.getStatus() == BoardingSessionStatus.EXPIRED;
        boolean disallowed = !session.getStatus().isOpen()
                || (session.getStatus() == BoardingSessionStatus.SUSPENDED
                    && !session.getStateReason().allowsAutomaticRevalidation());
        if (!disallowed) {
            return;
        }
        // Lock Event separately: MySQL FOR UPDATE OF Delivery alone would read a joined
        // Event from an earlier REPEATABLE READ snapshot established by API authentication.
        var event = events.findByBoardingSessionIdAndBoardingEventType(session.getId(),
                BoardingNotificationEventType.PREPARE_ALIGHT);
        if (event.isEmpty()) {
            return;
        }
        for (var delivery : deliveries.findPendingByBoardingEventIdForUpdate(event.orElseThrow().getId())) {
            // Re-read time after waiting for Delivery locks. Freshness takes precedence.
            LocalDateTime now = now();
            if (expired || !now.isBefore(delivery.getExpiresAt())) {
                delivery.expire(now);
            } else {
                delivery.terminateDispatch(now);
            }
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
