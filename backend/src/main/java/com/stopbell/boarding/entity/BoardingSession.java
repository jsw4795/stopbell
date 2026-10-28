package com.stopbell.boarding.entity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "boarding_sessions")
public class BoardingSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "client_request_id", nullable = false, updatable = false, length = 36)
    private String clientRequestId;

    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private TransitProvider provider;

    @Column(name = "external_route_id", nullable = false, updatable = false, length = 255)
    private String externalRouteId;

    @Column(name = "vehicle_tracking_id", nullable = false, updatable = false, length = 255)
    private String vehicleTrackingId;

    @Column(name = "vehicle_number", nullable = false, updatable = false, length = 255)
    private String vehicleNumber;

    @Column(name = "city_code", updatable = false, length = 50)
    private String cityCode;

    @Column(name = "route_number", nullable = false, updatable = false, length = 100)
    private String routeNumber;

    @Column(name = "target_name", nullable = false, updatable = false, length = 255)
    private String targetName;

    @Column(name = "prepare_notification_enabled", nullable = false, updatable = false)
    private boolean prepareNotificationEnabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "traversal_snapshot", nullable = false, updatable = false)
    private String traversalSnapshot;

    @Column(name = "traversal_fingerprint", nullable = false, updatable = false, length = 64)
    private String traversalFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BoardingSessionStatus status;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_reason", length = 64)
    private BoardingStateReason stateReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Embedded
    private BoardingCheckpoint checkpoint;

    protected BoardingSession() {
    }

    public BoardingSession(User user, String clientRequestId, String requestFingerprint,
            String vehicleTrackingId, String vehicleNumber, String routeNumber, String targetName,
            BoardingTraversalSnapshot traversal, BoardingCheckpoint checkpoint,
            LocalDateTime createdAt, LocalDateTime expiresAt) {
        this(user, clientRequestId, requestFingerprint, vehicleTrackingId, vehicleNumber, routeNumber, targetName,
                traversal, checkpoint, createdAt, expiresAt, true);
    }

    public BoardingSession(User user, String clientRequestId, String requestFingerprint,
            String vehicleTrackingId, String vehicleNumber, String routeNumber, String targetName,
            BoardingTraversalSnapshot traversal, BoardingCheckpoint checkpoint,
            LocalDateTime createdAt, LocalDateTime expiresAt, boolean prepareNotificationEnabled) {
        this.prepareNotificationEnabled = prepareNotificationEnabled;
        if (user == null || traversal == null || checkpoint == null) {
            throw new IllegalArgumentException("Owner, traversal and checkpoint are required");
        }
        clientRequestId = validateUuid(clientRequestId);
        if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid request fingerprint");
        }
        required(vehicleTrackingId, 255);
        required(vehicleNumber, 255);
        required(routeNumber, 100);
        required(targetName, 255);
        checkpoint.validateAgainst(traversal);
        if (checkpoint.getEvidence() != BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                && checkpoint.getIndex() >= traversal.targetIndex()) {
            throw new IllegalArgumentException("An exact target occurrence ahead is required");
        }
        this.createdAt = time(createdAt);
        this.expiresAt = time(expiresAt);
        if (!this.expiresAt.isAfter(this.createdAt) || checkpoint.getObservedAt().isAfter(this.createdAt)) {
            throw new IllegalArgumentException("Invalid session time ordering");
        }
        this.user = user;
        this.clientRequestId = clientRequestId;
        this.requestFingerprint = requestFingerprint;
        this.provider = traversal.provider();
        this.externalRouteId = traversal.externalRouteId();
        this.vehicleTrackingId = vehicleTrackingId;
        this.vehicleNumber = vehicleNumber;
        this.cityCode = traversal.cityCode();
        this.routeNumber = routeNumber;
        this.targetName = targetName;
        this.traversalSnapshot = traversal.toJson();
        this.traversalFingerprint = traversal.fingerprint();
        this.checkpoint = checkpoint;
        this.status = BoardingSessionStatus.TRACKING;
        this.stateReason = checkpoint.getEvidence() == BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                ? BoardingStateReason.BASELINE_UNVERIFIED : null;
        this.updatedAt = this.createdAt;
    }

    public void cancel(long expectedRevision, LocalDateTime now) {
        if (!status.isOpen()) {
            return;
        }
        requireRevision(expectedRevision);
        finish(BoardingSessionStatus.CANCELLED, BoardingStateReason.USER_CANCELLED, now);
    }

    public void expire(LocalDateTime now) {
        if (status.isOpen() && !time(now).isBefore(expiresAt)) {
            finish(BoardingSessionStatus.EXPIRED, BoardingStateReason.SESSION_LIFETIME_EXPIRED, now);
        }
    }

    public void suspend(long expectedRevision, BoardingStateReason reason, LocalDateTime now) {
        requireOpenRevision(expectedRevision);
        if (reason == null || !reason.isSuspension()) {
            throw new IllegalArgumentException("Suspension requires a suspension reason");
        }
        if (reason == BoardingStateReason.TEMPORARY_PROVIDER_FAILURE || reason == BoardingStateReason.STALE_PROGRESS) {
            if (status != BoardingSessionStatus.TRACKING || stateReason != reason) {
                change(BoardingSessionStatus.TRACKING, reason, now);
            }
            return;
        }
        if (status != BoardingSessionStatus.SUSPENDED || stateReason != reason) {
            change(BoardingSessionStatus.SUSPENDED, reason, now);
        }
    }

    public void track(long expectedRevision, BoardingCheckpoint next, BoardingStateReason completion, LocalDateTime now) {
        requireOpenRevision(expectedRevision);
        if (status == BoardingSessionStatus.SUSPENDED && !stateReason.allowsAutomaticRevalidation()) {
            throw new IllegalStateException("Continuity loss requires user confirmation");
        }
        if (!time(now).isBefore(expiresAt) || next == null || next.getObservedAt().isAfter(now)) {
            throw new IllegalArgumentException("Live checkpoint is required");
        }
        next.validateAgainst(getTraversal());
        if (next.getIndex() < checkpoint.getIndex() || next.getObservedAt().isBefore(checkpoint.getObservedAt())
                || !java.util.Objects.equals(next.getDirectionContext(), checkpoint.getDirectionContext())
                || (next.getIndex() != checkpoint.getIndex() && (next.getObservedAt().equals(checkpoint.getObservedAt())
                    || (next.getProviderDataTime() != null && next.getProviderDataTime().equals(checkpoint.getProviderDataTime()))))
                || (checkpoint.getProviderDataTime() != null && (next.getProviderDataTime() == null
                    || next.getProviderDataTime().isBefore(checkpoint.getProviderDataTime())))) {
            throw new IllegalArgumentException("Checkpoint must preserve monotonic progress and time");
        }
        if (checkpoint.getEvidence() == BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE && completion != null) {
            throw new IllegalArgumentException("Initial verification cannot complete a destination notification");
        }
        if (completion != null && completion != BoardingStateReason.ARRIVAL_OBSERVED
                && completion != BoardingStateReason.PASSED_OBSERVED) {
            throw new IllegalArgumentException("Invalid completion reason");
        }
        if (next.equals(checkpoint) && status == BoardingSessionStatus.TRACKING && stateReason == null && completion == null) {
            return;
        }
        if (next.getEvidence() == BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE) {
            if (checkpoint.getEvidence() != BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE || completion != null) {
                throw new IllegalArgumentException("Unverified evidence cannot replace a validated checkpoint");
            }
            change(BoardingSessionStatus.TRACKING, BoardingStateReason.BASELINE_UNVERIFIED, now);
        } else if (completion == null) {
            change(BoardingSessionStatus.TRACKING, null, now);
        } else {
            finish(BoardingSessionStatus.COMPLETED, completion, now);
        }
        checkpoint = next;
    }

    public void reconfirm(long expectedRevision, BoardingCheckpoint next, LocalDateTime now) {
        requireOpenRevision(expectedRevision);
        if (status != BoardingSessionStatus.SUSPENDED || !time(now).isBefore(expiresAt)
                || next == null || next.getObservedAt().isAfter(now)) {
            throw new IllegalStateException("Only a live suspended session can be reconfirmed");
        }
        next.validateAgainst(getTraversal());
        if (next.getEvidence() != BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                && next.getIndex() >= getTraversal().targetIndex()) {
            throw new IllegalArgumentException("Reconfirmed target must remain ahead");
        }
        // This is a new verified baseline, never a claim of continuity across the uncertain interval.
        change(BoardingSessionStatus.TRACKING,
                next.getEvidence() == BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                    ? BoardingStateReason.BASELINE_UNVERIFIED : null, now);
        checkpoint = next;
    }

    private void finish(BoardingSessionStatus next, BoardingStateReason reason, LocalDateTime now) {
        change(next, reason, now);
        endedAt = updatedAt;
    }

    private void change(BoardingSessionStatus next, BoardingStateReason reason, LocalDateTime now) {
        LocalDateTime timestamp = time(now);
        if (timestamp.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Session time must not move backwards");
        }
        long nextRevision = Math.incrementExact(revision);
        status = next;
        stateReason = reason;
        revision = nextRevision;
        updatedAt = timestamp;
    }

    private void requireOpenRevision(long expectedRevision) {
        if (!status.isOpen()) {
            throw new IllegalStateException("Terminal session cannot accept a late result");
        }
        requireRevision(expectedRevision);
    }

    private void requireRevision(long expectedRevision) {
        if (revision != expectedRevision) {
            throw new IllegalStateException("Session revision has changed");
        }
    }

    public static String validateUuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Canonical UUID is required");
        }
        return UUID.fromString(value).toString();
    }

    private static void required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("Required session text is invalid");
        }
    }

    private static LocalDateTime time(LocalDateTime value) {
        if (value == null || value.getYear() < 1000 || value.getYear() > 9999) {
            throw new IllegalArgumentException("Time must fit UTC MySQL DATETIME");
        }
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    public BoardingTraversalSnapshot getTraversal() {
        var snapshot = BoardingTraversalSnapshot.fromJson(traversalSnapshot);
        if (!snapshot.fingerprint().equals(traversalFingerprint) || snapshot.provider() != provider
                || !snapshot.externalRouteId().equals(externalRouteId)
                || !java.util.Objects.equals(snapshot.cityCode(), cityCode)) {
            throw new IllegalStateException("Stored traversal fingerprint or identity is inconsistent");
        }
        checkpoint.validateAgainst(snapshot);
        if (updatedAt.isBefore(createdAt) || checkpoint.getObservedAt().isAfter(updatedAt)
                || (endedAt != null && (endedAt.isBefore(createdAt) || endedAt.isAfter(updatedAt)))) {
            throw new IllegalStateException("Stored session time ordering is inconsistent");
        }
        return snapshot;
    }

    public boolean isPrepareNotificationEnabled() { return prepareNotificationEnabled; }

    public Long getId() {
        return id;
    }

    public BoardingCheckpoint getCheckpoint() {
        return checkpoint;
    }

    public User getUser() {
        return user;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public TransitProvider getProvider() {
        return provider;
    }

    public String getExternalRouteId() {
        return externalRouteId;
    }

    public String getVehicleTrackingId() {
        return vehicleTrackingId;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public String getCityCode() {
        return cityCode;
    }

    public String getRouteNumber() {
        return routeNumber;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getTraversalFingerprint() {
        return traversalFingerprint;
    }

    public BoardingSessionStatus getStatus() {
        return status;
    }

    public long getRevision() {
        return revision;
    }

    public BoardingStateReason getStateReason() {
        return stateReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }
}
