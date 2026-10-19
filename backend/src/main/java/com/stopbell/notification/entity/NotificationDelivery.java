package com.stopbell.notification.entity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_event_id", nullable = false, updatable = false)
    private NotificationEvent notificationEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false, updatable = false)
    private Device device;

    @Column(name = "recipient_ownership_generation", nullable = false, updatable = false)
    private long recipientOwnershipGeneration;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private NotificationDeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_attempt_registration_revision")
    private Long lastAttemptRegistrationRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_provider_result", length = 24)
    private NotificationProviderResult lastProviderResult;

    @Column(name = "last_failure_code", length = 64)
    private String lastFailureCode;

    @Column(name = "provider_accepted_at")
    private LocalDateTime providerAcceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected NotificationDelivery() {
    }

    public NotificationDelivery(
            NotificationEvent notificationEvent, Device device, long recipientOwnershipGeneration,
            LocalDateTime expiresAt, LocalDateTime createdAt
    ) {
        if (notificationEvent == null || device == null || recipientOwnershipGeneration < 0
                || expiresAt == null || createdAt == null) {
            throw new IllegalArgumentException("Delivery recipient, generation and timestamps must be valid");
        }
        if (createdAt.isBefore(notificationEvent.getCreatedAt())
                || !expiresAt.isAfter(notificationEvent.getObservedAt())) {
            throw new IllegalArgumentException("Delivery must preserve Event time and a positive freshness window");
        }
        if (notificationEvent.getSourceType() == NotificationSourceType.BOARDING_SESSION
                && !expiresAt.truncatedTo(ChronoUnit.MICROS).equals(notificationEvent.getExpiresAt())) {
            throw new IllegalArgumentException("Boarding Delivery must preserve its Event expiry");
        }
        this.notificationEvent = notificationEvent;
        this.device = device;
        this.recipientOwnershipGeneration = recipientOwnershipGeneration;
        this.expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = this.createdAt;
        if (!this.createdAt.isBefore(this.expiresAt)) {
            status = NotificationDeliveryStatus.EXPIRED;
            lastFailureCode = "FRESHNESS_EXPIRED";
        } else {
            status = NotificationDeliveryStatus.PENDING;
            nextAttemptAt = this.createdAt;
        }
    }

    public void terminateDispatch(LocalDateTime terminatedAt) {
        if (status != NotificationDeliveryStatus.PENDING) {
            return;
        }
        if (terminatedAt == null || terminatedAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Dispatch termination time must not move backwards");
        }
        status = NotificationDeliveryStatus.FAILED;
        nextAttemptAt = null;
        lastFailureCode = "DISPATCH_NOT_ALLOWED";
        updatedAt = terminatedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public void beginAttempt(long revision, LocalDateTime startedAt, LocalDateTime recoveryAt) {
        requirePending();
        LocalDateTime start = time(startedAt);
        LocalDateTime recovery = time(recoveryAt);
        if (revision < 0 || start.isBefore(nextAttemptAt) || !start.isBefore(expiresAt)
                || !recovery.isAfter(start) || recovery.isAfter(expiresAt)) {
            throw new IllegalArgumentException("Invalid durable attempt schedule");
        }
        attemptCount = Math.incrementExact(attemptCount);
        lastAttemptAt = start;
        lastAttemptRegistrationRevision = revision;
        lastProviderResult = null;
        lastFailureCode = null;
        nextAttemptAt = recovery;
        updatedAt = start;
    }

    public void markAccepted(LocalDateTime receivedAt, LocalDateTime now) {
        requirePendingAttempt();
        providerAcceptedAt = time(receivedAt);
        lastProviderResult = NotificationProviderResult.ACCEPTED;
        lastFailureCode = null;
        status = NotificationDeliveryStatus.ACCEPTED;
        nextAttemptAt = null;
        updatedAt = time(now);
    }

    public void recordFailure(NotificationProviderResult result, String code) {
        requirePendingAttempt();
        if (result == null || result == NotificationProviderResult.ACCEPTED) {
            throw new IllegalArgumentException("Expected a non-accepted Provider result");
        }
        lastFailureCode = failureCode(code);
        lastProviderResult = result;
    }

    public void markFailed(String code, LocalDateTime now) {
        terminate(NotificationDeliveryStatus.FAILED, code, now);
    }

    public void expire(LocalDateTime now) {
        if (status != NotificationDeliveryStatus.PENDING) {
            return;
        }
        if (time(now).isBefore(expiresAt)) {
            throw new IllegalArgumentException("Delivery has not expired");
        }
        terminate(NotificationDeliveryStatus.EXPIRED, "FRESHNESS_EXPIRED", now);
    }

    public void scheduleRetry(LocalDateTime nextAt, LocalDateTime now) {
        requirePending();
        if (lastProviderResult != NotificationProviderResult.RETRYABLE
                && lastProviderResult != NotificationProviderResult.AMBIGUOUS_TIMEOUT) {
            throw new IllegalStateException("Expected a retryable Provider result");
        }
        LocalDateTime next = time(nextAt);
        if (next.isAfter(expiresAt) || !next.isAfter(lastAttemptAt)) {
            throw new IllegalArgumentException("Invalid retry schedule");
        }
        nextAttemptAt = next;
        updatedAt = time(now);
    }

    private void terminate(NotificationDeliveryStatus terminal, String code, LocalDateTime now) {
        if (status != NotificationDeliveryStatus.PENDING) {
            return;
        }
        lastFailureCode = failureCode(code);
        updatedAt = time(now);
        status = terminal;
        nextAttemptAt = null;
    }

    private void requirePending() {
        if (status != NotificationDeliveryStatus.PENDING) {
            throw new IllegalStateException("Terminal Delivery cannot be mutated");
        }
    }

    private void requirePendingAttempt() {
        requirePending();
        if (attemptCount == 0 || lastProviderResult != null) {
            throw new IllegalStateException("Expected an uncompleted durable attempt");
        }
    }

    private static String failureCode(String code) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Expected a safe failure code");
        }
        return code;
    }

    private static LocalDateTime time(LocalDateTime value) {
        if (value == null) {
            throw new IllegalArgumentException("Operational timestamp is required");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public NotificationEvent getNotificationEvent() {
        return notificationEvent;
    }

    public Device getDevice() {
        return device;
    }

    public long getRecipientOwnershipGeneration() {
        return recipientOwnershipGeneration;
    }

    public NotificationDeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public LocalDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Long getLastAttemptRegistrationRevision() {
        return lastAttemptRegistrationRevision;
    }

    public NotificationProviderResult getLastProviderResult() {
        return lastProviderResult;
    }

    public String getLastFailureCode() {
        return lastFailureCode;
    }

    public LocalDateTime getProviderAcceptedAt() {
        return providerAcceptedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
