package com.stopbell.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.boarding.service.BoardingTrackingRuntime;
import com.stopbell.boarding.service.BoardingProgressEvaluator;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.notification.entity.BoardingNotificationEventType;
import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.entity.NotificationEvent;
import com.stopbell.notification.entity.NotificationDeliveryStatus;
import com.stopbell.notification.entity.NotificationSourceType;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import com.stopbell.common.observability.OperationalMetrics;

/** Owns transaction/lock boundaries and bounded result reapplication. */
public class NotificationDispatchAttemptService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchAttemptService.class);
    private final NotificationDeliveryRepository deliveries;
    private final NotificationRecipientPreflight recipients;
    private final NotificationAttemptHandler handler;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final BoardingSessionRepository sessions;
    private final BoardingTrackingRuntime runtime;
    private OperationalMetrics metrics;

    void setOperationalMetrics(OperationalMetrics metrics) {
        this.metrics = metrics;
    }

    public NotificationDispatchAttemptService(NotificationDeliveryRepository deliveries,
            NotificationRecipientPreflight recipients, NotificationAttemptHandler handler,
            PlatformTransactionManager manager, Clock clock) {
        this(deliveries, recipients, handler, manager, clock, null, null);
    }

    public NotificationDispatchAttemptService(NotificationDeliveryRepository deliveries,
            NotificationRecipientPreflight recipients, NotificationAttemptHandler handler,
            PlatformTransactionManager manager, Clock clock, BoardingSessionRepository sessions,
            BoardingTrackingRuntime runtime) {
        this.sessions = sessions;
        this.runtime = runtime;
        this.deliveries = deliveries;
        this.recipients = recipients;
        this.handler = handler;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<NotificationAttemptSnapshot> beginAttempt(long id) {
        // This projection crosses no managed entity/snapshot into the locking transaction.
        var identity = deliveries.findSourceIdentity(id).orElse(null);
        if (identity == null) {
            return Optional.empty();
        }
        // execute() returns only after commit. A commit exception never exposes a sendable snapshot.
        return transaction.execute(status -> {
            BoardingTrackingSnapshot validated = null;
            Long sessionOwner = null;
            if (identity.getSourceType() == NotificationSourceType.BOARDING_SESSION) {
                if (sessions == null || runtime == null || identity.getBoardingSessionId() == null) {
                    return Optional.empty();
                }
                // Never load Delivery first. This current read precedes any consistent entity read.
                var session = sessions.findForUpdate(identity.getBoardingSessionId()).orElse(null);
                if (session == null || (session.getStatus() != BoardingSessionStatus.TRACKING
                            && session.getStatus() != BoardingSessionStatus.COMPLETED)
                        || !now().isBefore(session.getExpiresAt())) {
                    return Optional.empty();
                }
                validated = BoardingTrackingSnapshot.from(session);
                sessionOwner = session.getUser().getId();
            }
            NotificationDelivery delivery = deliveries.findByIdForUpdate(id).orElse(null);
            LocalDateTime now = now();
            if (delivery == null || delivery.getStatus() != NotificationDeliveryStatus.PENDING
                    || delivery.getNextAttemptAt() == null || delivery.getNextAttemptAt().isAfter(now)) {
                log.debug("Notification dispatch skipped: deliveryId={}, reason=NOT_DUE_PENDING", id);
                return Optional.empty();
            }
            var event = delivery.getNotificationEvent();
            if (!Objects.equals(event.getId(), identity.getEventId()) || event.getSourceType() != identity.getSourceType()
                    || !Objects.equals(event.getBoardingSessionId(), identity.getBoardingSessionId())) {
                return Optional.empty();
            }
            // Expiry wins even when recovery is pending; no recipient locks or attempts are needed.
            if (validated != null && !now.isBefore(delivery.getExpiresAt())) {
                delivery.expire(now);
                return Optional.empty();
            }
            if (validated != null && (!allowsSessionEvent(validated, event, now)
                    || !Objects.equals(event.getUser().getId(), sessionOwner)
                    || !Objects.equals(event.getExpiresAt(), delivery.getExpiresAt()))) {
                return Optional.empty();
            }
            if (!handler.checkEligibility(delivery, now)) {
                return Optional.empty();
            }
            // A policy implementation cannot bypass freshness even if it incorrectly reports eligibility.
            if (!now.isBefore(delivery.getExpiresAt())) {
                throw new IllegalStateException("Attempt handler allowed an expired Delivery");
            }
            NotificationRecipientPreflight.Result recipient = validated == null
                    ? recipients.check(delivery) : recipients.check(delivery, validated);
            if (validated != null) {
                // Device lock waits must not turn an old eligibility timestamp into a fresh attempt or rejection.
                now = now();
                if (!now.isBefore(delivery.getExpiresAt())) {
                    delivery.expire(now);
                    return Optional.empty();
                }
            }
            if (recipient.rejection() != null) {
                handler.rejectRecipient(delivery, recipient.rejection(), now);
                log.debug("Notification dispatch skipped: deliveryId={}, reason={}", id, recipient.rejection());
                return Optional.empty();
            }
            if (validated != null && !allowsSessionEvent(validated, event, now)) {
                return Optional.empty();
            }
            int previousCount = delivery.getAttemptCount();
            NotificationAttemptSnapshot snapshot = handler.beginAttempt(delivery, recipient.target(), now);
            if (snapshot == null || !recipient.target().equals(snapshot.target())
                    || snapshot.attemptCount() != Math.incrementExact(previousCount)
                    || !now.equals(snapshot.lastAttemptAt()) || !sameAttempt(delivery, snapshot)
                    || delivery.getLastProviderResult() != null || delivery.getLastFailureCode() != null
                    || delivery.getNextAttemptAt() == null || !delivery.getNextAttemptAt().isAfter(now)
                    || delivery.getNextAttemptAt().isAfter(delivery.getExpiresAt())) {
                throw new IllegalStateException("Attempt handler did not persist the prepared attempt");
            }
            deliveries.flush();
            return Optional.of(snapshot);
        });
    }

    /** No DB locks cross this final runtime/deadline check or the subsequent Provider I/O. */
    public boolean allowsBeforeSend(NotificationAttemptSnapshot snapshot) {
        var target = snapshot.target();
        return target.source().sourceType() == NotificationSourceType.ALARM
                || (runtime != null && target.validatedSession() != null
                    && target.source().boardingSessionId().equals(target.validatedSession().id())
                    && now().isBefore(target.expiresAt())
                    && (target.source().boardingEventType() == BoardingNotificationEventType.DESTINATION_REACHED
                        ? destinationReady(target.validatedSession(), now())
                        : runtime.isReady(target.validatedSession(), now())));
    }

    private boolean allowsSessionEvent(BoardingTrackingSnapshot session,
            NotificationEvent event, LocalDateTime now) {
        if (event.getBoardingEventType() == BoardingNotificationEventType.DESTINATION_REACHED) {
            var checkpoint = session.checkpoint();
            return destinationReady(session, now)
                    && checkpoint.getObservedAt().equals(event.getObservedAt())
                    && Objects.equals(checkpoint.getProviderDataTime(), event.getProviderDataTime())
                    && Objects.equals(checkpoint.getStopExternalId(), event.getCurrentStopExternalId())
                    && Objects.equals(checkpoint.getStopOrder(), event.getCurrentStopOrder());
        }
        return event.getBoardingEventType() == BoardingNotificationEventType.PREPARE_ALIGHT
                && session.prepareNotificationEnabled() && runtime.isReady(session, now);
    }

    private boolean destinationReady(BoardingTrackingSnapshot session, LocalDateTime now) {
        var checkpoint = session.checkpoint();
        var providerTime = checkpoint.getProviderDataTime();
        return session.notificationOffsetStops() == null && session.destinationReached() && now.isBefore(session.expiresAt())
                && !checkpoint.getObservedAt().isAfter(now)
                && !checkpoint.getObservedAt().isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))
                && (session.provider() != TransitProvider.SEOUL_BUS || providerTime != null)
                && (providerTime == null || (!providerTime.isAfter(checkpoint.getObservedAt())
                    && !providerTime.isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS))));
    }

    public void completeAttempt(NotificationAttemptSnapshot snapshot, NotificationSendResult result) {
        try {
            applyResult(snapshot, result);
        } catch (DataAccessException | TransactionException exception) {
            // Fresh current locking read reconciles both rollback and a lost successful commit response.
            // Keep the exact in-memory result; never invoke the Provider here.
            log.warn("Notification result transaction retry: deliveryId={}, exceptionType={}",
                    snapshot.target().deliveryId(), exception.getClass().getSimpleName());
            applyResult(snapshot, result);
        }
    }

    private void applyResult(NotificationAttemptSnapshot snapshot, NotificationSendResult result) {
        transaction.executeWithoutResult(status -> {
            NotificationDelivery delivery = deliveries.findByIdForUpdate(snapshot.target().deliveryId()).orElse(null);
            if (sameAttempt(delivery, snapshot) && delivery.getLastProviderResult() == null) {
                handler.completeAttempt(delivery, snapshot, result);
                deliveries.flush();
            }
        });
    }

    /** A configuration-blocked worker may sweep expiry without spending any new attempt. */
    public void expireDuePending(long id) {
        Boolean expired = transaction.execute(status -> {
            NotificationDelivery delivery = deliveries.findByIdForUpdate(id).orElse(null);
            LocalDateTime now = now();
            if (delivery != null && delivery.getStatus() == NotificationDeliveryStatus.PENDING
                    && !now.isBefore(delivery.getExpiresAt())) {
                delivery.expire(now);
                deliveries.flush();
                return true;
            }
            return false;
        });
        if (Boolean.TRUE.equals(expired) && metrics != null) {
            metrics.delivery(OperationalMetrics.DeliveryTransition.EXPIRED);
        }
    }

    public void expireBeforeSend(NotificationAttemptSnapshot snapshot) {
        transaction.executeWithoutResult(status -> {
            NotificationDelivery delivery = deliveries.findByIdForUpdate(snapshot.target().deliveryId()).orElse(null);
            LocalDateTime now = now();
            if (sameAttempt(delivery, snapshot) && delivery.getLastProviderResult() == null
                    && !now.isBefore(delivery.getExpiresAt())) {
                handler.expireBeforeSend(delivery, snapshot, now);
                deliveries.flush();
            }
        });
    }

    private boolean sameAttempt(NotificationDelivery delivery, NotificationAttemptSnapshot snapshot) {
        return delivery != null && delivery.getStatus() == NotificationDeliveryStatus.PENDING
                && delivery.getId().equals(snapshot.target().deliveryId())
                && delivery.getAttemptCount() == snapshot.attemptCount()
                && Objects.equals(delivery.getLastAttemptAt(), snapshot.lastAttemptAt())
                && Objects.equals(delivery.getLastAttemptRegistrationRevision(), snapshot.target().registrationRevision());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
