package com.stopbell.boarding.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.boarding.entity.BoardingStateReason;

public record BoardingSessionResponse(
        Long id, BoardingSessionStatus status, long revision, String routeNumber, String vehicleNumber,
        String targetName, int targetOrder, Integer remainingOccurrenceGap, Instant checkpointObservedAt,
        Instant providerDataTime, EvaluationStatus evaluationStatus, BoardingStateReason reasonCode,
        Instant expiresAt, Instant endedAt
) {
    public enum EvaluationStatus { READY, RECOVERY_PENDING, HELD, ENDED }

    public static BoardingSessionResponse from(BoardingSession session) {
        var traversal = session.getTraversal();
        // No polling or recovery gate is implemented in TASK-905 step 1. Never expose a cached
        // checkpoint gap as live progress, even in a successful creation response.
        EvaluationStatus evaluation = !session.getStatus().isOpen() ? EvaluationStatus.ENDED
                : session.getStatus() == BoardingSessionStatus.SUSPENDED ? EvaluationStatus.HELD
                : EvaluationStatus.RECOVERY_PENDING;
        return new BoardingSessionResponse(session.getId(), session.getStatus(), session.getRevision(),
                session.getRouteNumber(), session.getVehicleNumber(), session.getTargetName(),
                traversal.occurrences().get(traversal.targetIndex()).order(), null,
                instant(session.getCheckpoint().getObservedAt()), instant(session.getCheckpoint().getProviderDataTime()),
                evaluation, session.getStateReason(), instant(session.getExpiresAt()), instant(session.getEndedAt()));
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
