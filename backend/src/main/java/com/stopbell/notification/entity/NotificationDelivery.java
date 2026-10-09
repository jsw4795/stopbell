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
