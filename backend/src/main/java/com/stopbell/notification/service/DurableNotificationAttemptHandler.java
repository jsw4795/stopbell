package com.stopbell.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.common.observability.OperationalMetrics;
import com.stopbell.notification.entity.Device;
import com.stopbell.notification.entity.NotificationDelivery;
import com.stopbell.notification.entity.NotificationDeliveryStatus;
import com.stopbell.notification.entity.NotificationProviderResult;
import com.stopbell.notification.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

/** Mutates managed entities inside caller-owned Delivery → Device transactions. */
public class DurableNotificationAttemptHandler implements NotificationAttemptHandler {
    private static final Logger log = LoggerFactory.getLogger(DurableNotificationAttemptHandler.class);
    private static final Set<String> PROVIDER_CODES = Set.of("UNREGISTERED", "CONNECTION_NOT_SENT",
            "UNKNOWN_ACCEPTANCE", "SUCCESS_RESPONSE_INVALID", "NOT_FOUND_UNCLASSIFIED", "AUTH_OR_PROJECT_ERROR",
            "QUOTA_EXCEEDED", "INTERNAL", "UNAVAILABLE", "INVALID_ARGUMENT_UNCLASSIFIED", "UNCLASSIFIED_REJECTION",
            "PROVIDER_NOT_CONFIGURED", "TRANSACTION_ACTIVE", "REQUEST_BUDGET_EXHAUSTED", "AUTH_BUDGET_EXHAUSTED",
            "CALL_INTERRUPTED", "AUTH_INITIALIZATION_FAILED");
    private final NotificationRetryPolicy retry;
    private final DeviceRepository devices;
    private final Clock clock;
    private OperationalMetrics metrics;

    void setOperationalMetrics(OperationalMetrics metrics) {
        this.metrics = metrics;
    }

    private void recordAfterCommit(OperationalMetrics.DeliveryTransition transition) {
        if (metrics != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { metrics.delivery(transition); }
            });
        }
    }

    public DurableNotificationAttemptHandler(NotificationRetryPolicy retry, DeviceRepository devices, Clock clock) {
        this.retry = retry;
        this.devices = devices;
        this.clock = clock;
    }

    @Override
    public boolean checkEligibility(NotificationDelivery delivery, LocalDateTime now) {
        requireTransaction();
        if (delivery.getStatus() != NotificationDeliveryStatus.PENDING) {
            return false;
        }
        if (!now.isBefore(delivery.getExpiresAt())) {
            delivery.expire(now);
            recordAfterCommit(OperationalMetrics.DeliveryTransition.EXPIRED);
            return false;
        }
        if (delivery.getAttemptCount() >= retry.maxAttempts()) {
            delivery.markFailed("ATTEMPT_LIMIT_REACHED", now);
            recordAfterCommit(OperationalMetrics.DeliveryTransition.FAILED);
            return false;
        }
        return !delivery.getNextAttemptAt().isAfter(now);
    }

    @Override
    public void rejectRecipient(NotificationDelivery delivery, NotificationRecipientPreflight.Rejection reason,
            LocalDateTime now) {
        requireTransaction();
        if (!now.isBefore(delivery.getExpiresAt())) {
            delivery.expire(now);
            recordAfterCommit(OperationalMetrics.DeliveryTransition.EXPIRED);
        } else {
            // DEVICE_MISSING is impossible under the FK; use the existing dispatch local allowlist.
            String code = reason == NotificationRecipientPreflight.Rejection.DEVICE_MISSING
                    ? "DISPATCH_NOT_ALLOWED" : "RECIPIENT_" + reason.name();
            delivery.markFailed(code, now);
            recordAfterCommit(OperationalMetrics.DeliveryTransition.FAILED);
        }
    }

    @Override
    public NotificationAttemptSnapshot beginAttempt(NotificationDelivery delivery, NotificationDispatchTarget target,
            LocalDateTime now) {
        requireTransaction();
        delivery.beginAttempt(target.registrationRevision(), now, retry.recoveryAt(now, delivery.getExpiresAt()));
        return new NotificationAttemptSnapshot(target, delivery.getAttemptCount(), delivery.getLastAttemptAt());
    }

    @Override
    public void completeAttempt(NotificationDelivery delivery, NotificationAttemptSnapshot snapshot,
            NotificationSendResult result) {
        requireTransaction();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (result.result() == NotificationProviderResult.ACCEPTED) {
            delivery.markAccepted(LocalDateTime.ofInstant(result.receivedAt(), ZoneOffset.UTC), now);
        } else {
            String code = safeCode(result.failureCode());
            delivery.recordFailure(result.result(), code);
            if (!now.isBefore(delivery.getExpiresAt())) {
                delivery.expire(now);
            } else if (result.result() == NotificationProviderResult.RETRYABLE
                    || result.result() == NotificationProviderResult.AMBIGUOUS_TIMEOUT) {
                if (delivery.getAttemptCount() >= retry.maxAttempts()) {
                    delivery.markFailed("ATTEMPT_LIMIT_REACHED", now);
                } else {
                    delivery.scheduleRetry(retry.retryAt(delivery.getAttemptCount(), result, delivery.getExpiresAt()), now);
                }
            } else {
                delivery.markFailed(code, now);
            }
            if (result.result() == NotificationProviderResult.INVALID_TARGET && "UNREGISTERED".equals(code)) {
                cleanup(snapshot.target());
            }
        }
        log.debug("Notification result applied: eventId={}, deliveryId={}, eventType={}, result={}, failureCode={}, "
                        + "attemptCount={}, retryScheduled={}, expired={}, elapsedMs={}",
                snapshot.target().notificationEventId(), delivery.getId(), snapshot.target().eventType(),
                result.result(), delivery.getLastFailureCode(), delivery.getAttemptCount(),
                delivery.getStatus() == NotificationDeliveryStatus.PENDING,
                delivery.getStatus() == NotificationDeliveryStatus.EXPIRED, result.elapsedMs());
        recordAfterCommit(switch (delivery.getStatus()) {
            case ACCEPTED -> OperationalMetrics.DeliveryTransition.ACCEPTED;
            case FAILED -> OperationalMetrics.DeliveryTransition.FAILED;
            case EXPIRED -> OperationalMetrics.DeliveryTransition.EXPIRED;
            case PENDING -> OperationalMetrics.DeliveryTransition.RETRY_SCHEDULED;
        });
    }

    @Override
    public void expireBeforeSend(NotificationDelivery delivery, NotificationAttemptSnapshot snapshot, LocalDateTime now) {
        requireTransaction();
        delivery.expire(now);
        recordAfterCommit(OperationalMetrics.DeliveryTransition.EXPIRED);
    }

    private void cleanup(NotificationDispatchTarget target) {
        Device device = devices.findByIdForUpdate(target.deviceId()).orElse(null);
        if (device != null && device.getId().equals(target.deviceId()) && device.isEnabled()
                && device.getUser().getId().equals(target.eventOwnerId())
                && device.getOwnershipGeneration() == target.ownershipGeneration()
                && device.getRegistrationRevision() == target.registrationRevision()
                && target.fid().equals(device.getCurrentPushTargetId())) {
            device.disableUnregisteredTarget();
        }
    }

    private String safeCode(String code) {
        return code != null && PROVIDER_CODES.contains(code) ? code : "UNKNOWN_ACCEPTANCE";
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Notification attempt policy requires a caller-owned transaction");
        }
    }
}
