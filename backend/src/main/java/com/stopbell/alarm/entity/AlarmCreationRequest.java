package com.stopbell.alarm.entity;

import java.time.LocalDateTime;

import com.stopbell.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "alarm_creation_requests")
public class AlarmCreationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "client_request_id", nullable = false, length = 36)
    private String clientRequestId;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] requestFingerprint;

    @Column(name = "alarm_id", nullable = false)
    private Long alarmId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected AlarmCreationRequest() {
    }

    public AlarmCreationRequest(User user, String clientRequestId, byte[] fingerprint, Long alarmId, LocalDateTime createdAt) {
        this.user = user;
        this.clientRequestId = clientRequestId;
        this.requestFingerprint = fingerprint.clone();
        this.alarmId = alarmId;
        this.createdAt = createdAt;
        this.expiresAt = createdAt.plusDays(30);
    }

    public byte[] getRequestFingerprint() { return requestFingerprint.clone(); }
    public Long getAlarmId() { return alarmId; }
}
