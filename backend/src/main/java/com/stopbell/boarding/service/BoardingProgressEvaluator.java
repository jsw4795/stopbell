package com.stopbell.boarding.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import com.stopbell.boarding.domain.*;
import com.stopbell.boarding.domain.BoardingTrackingDecision.Outcome;
import com.stopbell.boarding.entity.*;
import com.stopbell.transit.domain.TransitProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BoardingProgressEvaluator {
    public static final Duration FRESHNESS = Duration.ofSeconds(60);
    private final Duration maxObservationGap;
    private final Duration prepareTtl;

    public BoardingProgressEvaluator(@Value("${boarding.recovery.max-observation-gap:PT2M}") Duration maxObservationGap,
            @Value("${notification.decision.ttl.prepare-alight:PT30S}") Duration prepareTtl) {
        // Same finite, positive microsecond validation as the existing Session lifetime.
        var validation = new BoardingSessionProperties();
        validation.setMaxLifetime(maxObservationGap);
        validation.setMaxLifetime(prepareTtl);
        this.maxObservationGap = maxObservationGap;
        this.prepareTtl = prepareTtl;
    }

    public BoardingTrackingDecision evaluate(BoardingTrackingSnapshot session, BoardingProgressObservation observation,
            LocalDateTime now) {
        if (session.requiresConfirmation()) {
            return BoardingTrackingDecision.held(session.reason());
        }
        if (!session.status().isOpen() || !now.isBefore(session.expiresAt())) {
            return BoardingTrackingDecision.held(BoardingStateReason.STALE_PROGRESS);
        }
        var vehicle = observation.vehicle();
        if (observation.provider() != session.provider() || !Objects.equals(observation.routeId(), session.routeId())
                || !Objects.equals(observation.cityCode(), session.cityCode()) || vehicle == null
                || !session.vehicleId().equals(vehicle.id()) || !session.vehicleNumber().equals(vehicle.number())) {
            return BoardingTrackingDecision.held(BoardingStateReason.IDENTITY_CONFLICT);
        }
        LocalDateTime observedAt = vehicle.observedAt() == null ? null
                : LocalDateTime.ofInstant(vehicle.observedAt(), ZoneOffset.UTC);
        LocalDateTime providerTime = vehicle.providerDataTime() == null ? null
                : LocalDateTime.ofInstant(vehicle.providerDataTime(), ZoneOffset.UTC);
        if (invalidTime(observedAt, now) || vehicle.timeIssue() != null
                || (session.provider() == TransitProvider.SEOUL_BUS && providerTime == null)
                || (providerTime != null && (invalidTime(providerTime, now) || providerTime.isAfter(observedAt)))) {
            return BoardingTrackingDecision.held(BoardingStateReason.STALE_PROGRESS);
        }
        var previous = session.checkpoint();
        if (observedAt.isBefore(previous.getObservedAt())
                || (previous.getProviderDataTime() != null && (providerTime == null
                    || providerTime.isBefore(previous.getProviderDataTime())))) {
            return BoardingTrackingDecision.held(BoardingStateReason.PROGRESS_REGRESSION);
        }
        if (Duration.between(previous.getObservedAt(), observedAt).compareTo(maxObservationGap) > 0) {
            return BoardingTrackingDecision.held(BoardingStateReason.CONTINUITY_GAP_EXCEEDED);
        }
        if (observation.traversalAmbiguous()
                || !Objects.equals(previous.getDirectionContext(), observation.directionContext())
                || observation.meaning() == null) {
            return BoardingTrackingDecision.held(BoardingStateReason.TRAVERSAL_AMBIGUOUS);
        }
        if ((vehicle.order() == null || vehicle.stopId() == null && !observation.orderOnlyVerified())
                && previous.getEvidence() != BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE) {
            return BoardingTrackingDecision.held(BoardingStateReason.STALE_PROGRESS);
        }
        if (observation.meaning() == BoardingProgressObservation.Meaning.UNVERIFIED
                && previous.getEvidence() != BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE) {
            return BoardingTrackingDecision.held(BoardingStateReason.TRAVERSAL_AMBIGUOUS);
        }
        var traversal = session.traversal();
        int index = -1;
        if (vehicle.order() != null) {
            for (int i = 0; i < traversal.occurrences().size(); i++) {
                var occurrence = traversal.occurrences().get(i);
                if (occurrence.order() == vehicle.order() && (vehicle.stopId() == null
                        ? observation.orderOnlyVerified() : occurrence.externalStopId().equals(vehicle.stopId()))) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0 && !(previous.getEvidence() == BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                && (vehicle.stopId() == null || vehicle.order() == null))) {
            return BoardingTrackingDecision.held(BoardingStateReason.TRAVERSAL_AMBIGUOUS);
        }
        if (index < 0 && previous.getIndex() >= 0) {
            return BoardingTrackingDecision.held(BoardingStateReason.STALE_PROGRESS);
        }
        if (index < previous.getIndex()) {
            return BoardingTrackingDecision.held(BoardingStateReason.PROGRESS_REGRESSION);
        }
        boolean pending = previous.getEvidence() == BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE;
        if (pending && traversal.occurrences().stream().map(item -> item.externalStopId()).distinct().count()
                != traversal.occurrences().size()) {
            return BoardingTrackingDecision.held(BoardingStateReason.TRAVERSAL_AMBIGUOUS);
        }
        boolean sameProviderData = providerTime != null && providerTime.equals(previous.getProviderDataTime());
        if ((sameProviderData || observedAt.equals(previous.getObservedAt())) && index != previous.getIndex()) {
            return BoardingTrackingDecision.held(BoardingStateReason.TRAVERSAL_AMBIGUOUS);
        }
        // Repeated source data is not new progress or new freshness, including TAGO without dataTm.
        boolean repeated = sameProviderData || observedAt.equals(previous.getObservedAt())
                || (providerTime == null && index == previous.getIndex());
        int gap = traversal.targetIndex() - index;
        if (repeated) {
            if (session.status() == BoardingSessionStatus.SUSPENDED) {
                return BoardingTrackingDecision.held(session.reason());
            }
            return new BoardingTrackingDecision(Outcome.NO_CANDIDATE, null, null, gap,
                    previous.getObservedAt(), null, now);
        }
        if (pending && index >= traversal.targetIndex()) {
            // No trusted baseline before the target: never infer a missed destination.
            return BoardingTrackingDecision.held(BoardingStateReason.TARGET_NOT_AHEAD);
        }
        boolean verified = !pending || index >= 0 && observation.meaning() == BoardingProgressObservation.Meaning.DIRECT_ARRIVAL;
        var checkpoint = new BoardingCheckpoint(index < 0 ? null : vehicle.stopId(), index < 0 ? 0 : vehicle.order(), index,
                !verified ? BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                        : vehicle.stopId() == null ? BoardingCheckpoint.Evidence.VERIFIED_ORDER_ONLY
                        : BoardingCheckpoint.Evidence.ID_ORDER,
                observation.directionContext(), observedAt, providerTime);
        if (!verified) {
            return new BoardingTrackingDecision(Outcome.NO_CANDIDATE, checkpoint, null, null,
                    checkpoint.getObservedAt(), null, now);
        }
        observedAt = checkpoint.getObservedAt();
        LocalDateTime expiry = Duration.between(observedAt, session.expiresAt()).compareTo(prepareTtl) <= 0
                ? session.expiresAt() : observedAt.plus(prepareTtl);
        if (gap == 0 && (observation.meaning() == BoardingProgressObservation.Meaning.VERIFIED_REACHED
                || (vehicle.stopId() != null
                    && observation.meaning() == BoardingProgressObservation.Meaning.DIRECT_ARRIVAL))) {
            return new BoardingTrackingDecision(Outcome.ARRIVAL_OBSERVED, checkpoint,
                    BoardingStateReason.ARRIVAL_OBSERVED, gap, observedAt, expiry, now);
        }
        if (gap < 0 && previous.getIndex() <= traversal.targetIndex()) {
            return new BoardingTrackingDecision(Outcome.PASSED_OBSERVED, checkpoint,
                    BoardingStateReason.PASSED_OBSERVED, gap, observedAt, expiry, now);
        }
        Outcome outcome = session.prepareNotificationEnabled() && (gap == 1 || gap == 2)
                ? now.isBefore(expiry) ? Outcome.PREPARE_ALIGHT : Outcome.EXPIRED_CANDIDATE
                : Outcome.NO_CANDIDATE;
        return new BoardingTrackingDecision(outcome, checkpoint, null, gap, observedAt, expiry, now);
    }

    public boolean continuityExpired(BoardingTrackingSnapshot session, LocalDateTime now) {
        return Duration.between(session.checkpoint().getObservedAt(), now).compareTo(maxObservationGap) > 0;
    }

    private static boolean invalidTime(LocalDateTime time, LocalDateTime now) {
        return time == null || time.isAfter(now) || time.isBefore(now.minus(FRESHNESS));
    }
}
