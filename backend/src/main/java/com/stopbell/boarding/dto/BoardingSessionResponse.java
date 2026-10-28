package com.stopbell.boarding.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.boarding.entity.BoardingStateReason;

public record BoardingSessionResponse(
        Long id, BoardingSessionStatus status, long revision, String routeNumber, String vehicleNumber,
        String targetName, int targetOrder, Integer remainingOccurrenceGap, Instant checkpointObservedAt,
        Instant providerDataTime, EvaluationStatus evaluationStatus, BoardingStateReason reasonCode,
        Instant expiresAt, Instant endedAt, boolean prepareNotificationEnabled, Integer notificationOffsetStops,
        String preparationNotificationStatus
) {
    public enum EvaluationStatus { VERIFICATION_PENDING, READY, RECOVERY_PENDING, HELD, ENDED }

    public static BoardingSessionResponse from(BoardingSession session) {
        return from(session, null);
    }

    public static BoardingSessionResponse from(BoardingSession session, Integer verifiedGap) {
        var traversal = session.getTraversal();
        EvaluationStatus evaluation = !session.getStatus().isOpen() ? EvaluationStatus.ENDED
                : session.getStatus() == BoardingSessionStatus.SUSPENDED ? EvaluationStatus.HELD
                : session.getCheckpoint().getEvidence() == BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                    ? EvaluationStatus.VERIFICATION_PENDING
                : verifiedGap != null ? EvaluationStatus.READY : EvaluationStatus.RECOVERY_PENDING;
        return new BoardingSessionResponse(session.getId(), session.getStatus(), session.getRevision(),
                session.getRouteNumber(), session.getVehicleNumber(), session.getTargetName(),
                traversal.occurrences().get(traversal.targetIndex()).order(),
                evaluation == EvaluationStatus.READY ? verifiedGap : null,
                session.getCheckpoint().getEvidence() == BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                    ? null : instant(session.getCheckpoint().getObservedAt()), instant(session.getCheckpoint().getProviderDataTime()),
                evaluation, session.getStateReason(), instant(session.getExpiresAt()), instant(session.getEndedAt()),
                session.isPrepareNotificationEnabled(), session.getNotificationOffsetStops(), "UNKNOWN");
    }

    public BoardingSessionResponse withPreparationStatus(String status) {
        return new BoardingSessionResponse(id, this.status, revision, routeNumber, vehicleNumber, targetName,
                targetOrder, remainingOccurrenceGap, checkpointObservedAt, providerDataTime, evaluationStatus,
                reasonCode, expiresAt, endedAt, prepareNotificationEnabled, notificationOffsetStops, status);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
