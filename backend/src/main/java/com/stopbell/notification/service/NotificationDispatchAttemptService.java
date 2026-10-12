package com.stopbell.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.entity.NotificationDeliveryStatus;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns transaction/lock boundaries and bounded result reapplication. */
public class NotificationDispatchAttemptService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchAttemptService.class);
    private final NotificationDeliveryRepository deliveries;
    private final NotificationRecipientPreflight recipients;
    private final NotificationAttemptHandler handler;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public NotificationDispatchAttemptService(NotificationDeliveryRepository deliveries,
            NotificationRecipientPreflight recipients, NotificationAttemptHandler handler,
            PlatformTransactionManager manager, Clock clock) {
        this.deliveries = deliveries;
        this.recipients = recipients;
        this.handler = handler;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<NotificationAttemptSnapshot> beginAttempt(long id) {
        // execute() returns only after commit. A commit exception never exposes a sendable snapshot.
        return transaction.execute(status -> {
            NotificationDelivery delivery = deliveries.findByIdForUpdate(id).orElse(null);
            LocalDateTime now = now();
            if (delivery == null || delivery.getStatus() != NotificationDeliveryStatus.PENDING
                    || delivery.getNextAttemptAt() == null || delivery.getNextAttemptAt().isAfter(now)) {
                log.debug("Notification dispatch skipped: deliveryId={}, reason=NOT_DUE_PENDING", id);
                return Optional.empty();
            }
            if (!handler.checkEligibility(delivery, now)) {
                return Optional.empty();
            }
            // A policy implementation cannot bypass freshness even if it incorrectly reports eligibility.
            if (!now.isBefore(delivery.getExpiresAt())) {
                throw new IllegalStateException("Attempt handler allowed an expired Delivery");
            }
            NotificationRecipientPreflight.Result recipient = recipients.check(delivery);
            if (recipient.rejection() != null) {
                handler.rejectRecipient(delivery, recipient.rejection(), now);
                log.debug("Notification dispatch skipped: deliveryId={}, reason={}", id, recipient.rejection());
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
        transaction.executeWithoutResult(status -> {
            NotificationDelivery delivery = deliveries.findByIdForUpdate(id).orElse(null);
            LocalDateTime now = now();
            if (delivery != null && delivery.getStatus() == NotificationDeliveryStatus.PENDING
                    && !now.isBefore(delivery.getExpiresAt())) {
                delivery.expire(now);
                deliveries.flush();
            }
        });
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
