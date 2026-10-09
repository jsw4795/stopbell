package com.stopbell.notification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.UUID;

import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.user.entity.User;

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
@Table(name = "notification_events")
public class NotificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alarm_id", nullable = false, updatable = false)
    private Long alarmId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "activation_generation", nullable = false, updatable = false)
    private long activationGeneration;

    @Column(name = "tracking_cycle_id", nullable = false, updatable = false, length = 36)
    private String trackingCycleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 20)
    private TransitEventType eventType;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private LocalDateTime observedAt;

    @Column(name = "event_detected_at", nullable = false, updatable = false)
    private LocalDateTime eventDetectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "current_stop_external_id", updatable = false, length = 255)
    private String currentStopExternalId;

    @Column(name = "current_stop_name", updatable = false, length = 255)
    private String currentStopName;

    @Column(name = "current_stop_order", updatable = false)
    private Integer currentStopOrder;

    @Column(name = "latitude", updatable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", updatable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "provider_data_time", updatable = false)
    private LocalDateTime providerDataTime;

    @Column(name = "stops_past_target", updatable = false)
    private Integer stopsPastTarget;

    protected NotificationEvent() {
    }

    public NotificationEvent(
            Long alarmId, User user, long activationGeneration, TransitEvent event,
            LocalDateTime eventDetectedAt, LocalDateTime createdAt
    ) {
        if (alarmId == null || alarmId <= 0 || user == null || event == null || activationGeneration < 0
                || eventDetectedAt == null || createdAt == null) {
            throw new IllegalArgumentException("Event identity, owner and timestamps must be valid");
        }
        LocalDateTime observedAt = LocalDateTime.ofInstant(event.observedAt(), ZoneOffset.UTC);
        if (eventDetectedAt.isBefore(observedAt) || createdAt.isBefore(eventDetectedAt)) {
            throw new IllegalArgumentException("Event timestamps must not move backwards");
        }
        if ((event.currentStopExternalId() != null && event.currentStopExternalId().length() > 255)
                || (event.currentStopName() != null && event.currentStopName().length() > 255)) {
            throw new IllegalArgumentException("Stop evidence exceeds maximum length 255");
        }
        this.alarmId = alarmId;
        this.user = user;
        this.activationGeneration = activationGeneration;
        this.trackingCycleId = event.trackingCycleId().toString();
        this.eventType = event.type();
        this.observedAt = observedAt.truncatedTo(ChronoUnit.MICROS);
        this.eventDetectedAt = eventDetectedAt.truncatedTo(ChronoUnit.MICROS);
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.currentStopExternalId = event.currentStopExternalId();
        this.currentStopName = event.currentStopName();
        this.currentStopOrder = event.currentStopOrder();
        this.latitude = event.latitude();
        this.longitude = event.longitude();
        this.providerDataTime = event.providerDataTime() == null ? null
                : LocalDateTime.ofInstant(event.providerDataTime(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        this.stopsPastTarget = event.stopsPastTarget();
    }

    public Long getId() {
        return id;
    }

    public Long getAlarmId() {
        return alarmId;
    }

    public User getUser() {
        return user;
    }

    public long getActivationGeneration() {
        return activationGeneration;
    }

    public UUID getTrackingCycleId() {
        return UUID.fromString(trackingCycleId);
    }

    public TransitEventType getEventType() {
        return eventType;
    }

    public LocalDateTime getObservedAt() {
        return observedAt;
    }

    public LocalDateTime getEventDetectedAt() {
        return eventDetectedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getCurrentStopExternalId() {
        return currentStopExternalId;
    }

    public String getCurrentStopName() {
        return currentStopName;
    }

    public Integer getCurrentStopOrder() {
        return currentStopOrder;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public LocalDateTime getProviderDataTime() {
        return providerDataTime;
    }

    public Integer getStopsPastTarget() {
        return stopsPastTarget;
    }
}
