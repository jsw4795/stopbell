package com.stopbell.boarding.domain;

import java.time.LocalDateTime;

import com.stopbell.boarding.entity.*;
import com.stopbell.transit.domain.TransitProvider;

/** Detached immutable input; no managed Entity or lock crosses Provider I/O. */
public record BoardingTrackingSnapshot(
        Long id, long revision, BoardingSessionStatus status, BoardingStateReason reason,
        TransitProvider provider, String routeId, String cityCode, String vehicleId, String vehicleNumber,
        BoardingTraversalSnapshot traversal, BoardingCheckpoint checkpoint, LocalDateTime expiresAt,
        boolean prepareNotificationEnabled
) {
    public BoardingTrackingSnapshot(Long id, long revision, BoardingSessionStatus status, BoardingStateReason reason,
            TransitProvider provider, String routeId, String cityCode, String vehicleId, String vehicleNumber,
            BoardingTraversalSnapshot traversal, BoardingCheckpoint checkpoint, LocalDateTime expiresAt) {
        this(id, revision, status, reason, provider, routeId, cityCode, vehicleId, vehicleNumber, traversal, checkpoint,
                expiresAt, true);
    }

    public static BoardingTrackingSnapshot from(BoardingSession session) {
        return new BoardingTrackingSnapshot(session.getId(), session.getRevision(), session.getStatus(),
                session.getStateReason(), session.getProvider(), session.getExternalRouteId(), session.getCityCode(),
                session.getVehicleTrackingId(), session.getVehicleNumber(), session.getTraversal(),
                session.getCheckpoint(), session.getExpiresAt(), session.isPrepareNotificationEnabled());
    }

    /** Terminal completion is immutable and can authorize its own durable destination Event after restart. */
    public boolean destinationReached() {
        return status == BoardingSessionStatus.COMPLETED
                && (reason == BoardingStateReason.ARRIVAL_OBSERVED && checkpoint.getIndex() == traversal.targetIndex()
                    || reason == BoardingStateReason.PASSED_OBSERVED && checkpoint.getIndex() > traversal.targetIndex());
    }

    public boolean matches(BoardingSession session) {
        return equals(from(session));
    }

    public boolean requiresConfirmation() {
        return status == BoardingSessionStatus.SUSPENDED && !reason.allowsAutomaticRevalidation();
    }
}
