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
        String targetName, int targetOrder, Integer remainingOccurrenceGap, String currentStopName, Instant checkpointObservedAt,
        Instant providerDataTime, EvaluationStatus evaluationStatus, BoardingStateReason reasonCode,
        Instant expiresAt, Instant endedAt, boolean prepareNotificationEnabled, Integer notificationOffsetStops,
        String preparationNotificationStatus, InitialObservation initialObservation, Timeline timeline
) {
    public record Timeline(String provider, String routeType, int boardingIndex, int targetIndex,
            Integer positionIndex, String positionBasis, java.util.List<TimelineStop> stops) { }
    public record TimelineStop(long id, int index, int order, String name) { }

    public record InitialObservation(String stopName, int remainingOccurrenceGap) { }

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
                evaluation == EvaluationStatus.READY ? verifiedGap : null, null,
                session.getCheckpoint().getEvidence() != BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                        || (evaluation == EvaluationStatus.VERIFICATION_PENDING
                            && traversal.provider() == com.stopbell.transit.domain.TransitProvider.TAGO
                            && session.getCheckpoint().getIndex() >= 0
                            && session.getCheckpoint().getIndex() < traversal.targetIndex())
                    ? instant(session.getCheckpoint().getObservedAt()) : null, instant(session.getCheckpoint().getProviderDataTime()),
                evaluation, session.getStateReason(), instant(session.getExpiresAt()), instant(session.getEndedAt()),
                session.isPrepareNotificationEnabled(), session.getNotificationOffsetStops(), "UNKNOWN", null, null);
    }

    public BoardingSessionResponse withPreparationStatus(String status) {
        return new BoardingSessionResponse(id, this.status, revision, routeNumber, vehicleNumber, targetName,
                targetOrder, remainingOccurrenceGap, currentStopName, checkpointObservedAt, providerDataTime, evaluationStatus,
                reasonCode, expiresAt, endedAt, prepareNotificationEnabled, notificationOffsetStops, status, initialObservation, timeline);
    }

    public BoardingSessionResponse withCurrentStopName(String name) {
        return new BoardingSessionResponse(id, status, revision, routeNumber, vehicleNumber, targetName,
                targetOrder, remainingOccurrenceGap, evaluationStatus == EvaluationStatus.READY ? name : null,
                checkpointObservedAt, providerDataTime, evaluationStatus, reasonCode, expiresAt, endedAt,
                prepareNotificationEnabled, notificationOffsetStops, preparationNotificationStatus, initialObservation, timeline);
    }

    public BoardingSessionResponse withInitialObservation(InitialObservation observation) {
        return new BoardingSessionResponse(id, status, revision, routeNumber, vehicleNumber, targetName,
                targetOrder, remainingOccurrenceGap, currentStopName, checkpointObservedAt, providerDataTime,
                evaluationStatus, reasonCode, expiresAt, endedAt, prepareNotificationEnabled,
                notificationOffsetStops, preparationNotificationStatus, observation, timeline);
    }

    public BoardingSessionResponse withTimeline(Timeline value) {
        return new BoardingSessionResponse(id, status, revision, routeNumber, vehicleNumber, targetName,
                targetOrder, remainingOccurrenceGap, currentStopName, checkpointObservedAt, providerDataTime,
                evaluationStatus, reasonCode, expiresAt, endedAt, prepareNotificationEnabled,
                notificationOffsetStops, preparationNotificationStatus, initialObservation, value);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
