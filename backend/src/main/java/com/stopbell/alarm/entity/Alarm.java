package com.stopbell.alarm.entity;

import java.time.LocalDateTime;

import com.stopbell.user.entity.User;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "alarms")
public class Alarm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "transit_type", nullable = false, length = 20)
    private TransitType transitType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlarmStatus status = AlarmStatus.INACTIVE;

    @Column(name = "follow_up_vehicle_tracking_id", length = 255)
    private String followUpVehicleTrackingId;

    @Column(name = "follow_up_started_at")
    private LocalDateTime followUpStartedAt;

    @Column(name = "follow_up_expires_at")
    private LocalDateTime followUpExpiresAt;

    @OneToOne(mappedBy = "alarm", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private BusAlarmTarget busAlarmTarget;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Alarm() {
    }

    public Alarm(User user, TransitType transitType) {
        if (transitType == TransitType.BUS) {
            throw new IllegalArgumentException("Bus alarm requires a BusAlarmTarget");
        }
        this.user = user;
        this.transitType = transitType;
    }

    public Alarm(User user, BusAlarmTarget busAlarmTarget) {
        if (busAlarmTarget == null) {
            throw new IllegalArgumentException("Bus alarm target must not be null");
        }
        this.user = user;
        this.transitType = TransitType.BUS;
        this.busAlarmTarget = busAlarmTarget;
        busAlarmTarget.assignTo(this);
    }

    @PrePersist
    private void onPrePersist() {
        validateLifecycleState();
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void onPreUpdate() {
        validateLifecycleState();
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public TransitType getTransitType() {
        return transitType;
    }

    public AlarmStatus getStatus() {
        return status;
    }

    public void activate() {
        if (transitType == TransitType.BUS && busAlarmTarget == null) {
            throw new IllegalStateException("Bus alarm cannot activate without a BusAlarmTarget");
        }
        status = AlarmStatus.ACTIVE;
        clearFollowUpRuntime();
    }

    public void deactivate() {
        status = AlarmStatus.INACTIVE;
        clearFollowUpRuntime();
    }

    public void startFollowUp(
            String vehicleTrackingId,
            LocalDateTime startedAt,
            LocalDateTime expiresAt
    ) {
        if (status != AlarmStatus.ACTIVE) {
            throw new IllegalStateException("Follow-up can only start from an active alarm");
        }
        if (busAlarmTarget == null || !busAlarmTarget.isNotifyOneStopAfter()) {
            throw new IllegalStateException("Follow-up requires the one-stop-after option");
        }
        if (vehicleTrackingId == null || vehicleTrackingId.isBlank()) {
            throw new IllegalArgumentException("Follow-up vehicle tracking ID must not be blank");
        }
        if (vehicleTrackingId.length() > 255) {
            throw new IllegalArgumentException("Follow-up vehicle tracking ID exceeds maximum length 255");
        }
        if (startedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("Follow-up start and expiry timestamps must not be null");
        }
        if (!expiresAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("Follow-up expiry must be after its start timestamp");
        }

        status = AlarmStatus.FOLLOW_UP;
        followUpVehicleTrackingId = vehicleTrackingId;
        followUpStartedAt = startedAt;
        followUpExpiresAt = expiresAt;
    }

    public void completeFollowUp() {
        if (status != AlarmStatus.FOLLOW_UP) {
            throw new IllegalStateException("Only a follow-up alarm can complete follow-up");
        }
        status = AlarmStatus.INACTIVE;
        clearFollowUpRuntime();
    }

    private void validateLifecycleState() {
        if (status == null) {
            throw new IllegalStateException("Alarm status must not be null");
        }
        boolean hasCompleteRuntime = followUpVehicleTrackingId != null
                && !followUpVehicleTrackingId.isBlank()
                && followUpStartedAt != null
                && followUpExpiresAt != null
                && followUpExpiresAt.isAfter(followUpStartedAt);
        boolean hasAnyRuntime = followUpVehicleTrackingId != null
                || followUpStartedAt != null
                || followUpExpiresAt != null;

        if (status == AlarmStatus.FOLLOW_UP) {
            if (!hasCompleteRuntime || busAlarmTarget == null || !busAlarmTarget.isNotifyOneStopAfter()) {
                throw new IllegalStateException("Follow-up status requires complete runtime and one-stop-after option");
            }
        } else if (hasAnyRuntime) {
            throw new IllegalStateException("Non-follow-up status must not retain follow-up runtime");
        }
    }

    private void clearFollowUpRuntime() {
        followUpVehicleTrackingId = null;
        followUpStartedAt = null;
        followUpExpiresAt = null;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public BusAlarmTarget getBusAlarmTarget() {
        return busAlarmTarget;
    }

    public String getFollowUpVehicleTrackingId() {
        return followUpVehicleTrackingId;
    }

    public LocalDateTime getFollowUpStartedAt() {
        return followUpStartedAt;
    }

    public LocalDateTime getFollowUpExpiresAt() {
        return followUpExpiresAt;
    }
}
