package com.stopbell.transit.dto;

import java.time.Instant;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;

public record BoardingCandidatesResponse(
        Instant observedAt,
        Instant providerDataTime,
        CandidateSetStatus candidateSetStatus,
        List<Candidate> candidates
) {
    public BoardingCandidatesResponse {
        candidates = List.copyOf(candidates);
    }

    public enum CandidateSetStatus { AVAILABLE, EMPTY, PARTIAL }
    public enum ConfirmationStatus { AVAILABLE, HELD }
    public enum TrackingSupport { CONDITIONAL, HELD }
    public enum ReasonCode {
        VEHICLE_ID_MISSING, VEHICLE_NUMBER_MISSING, IDENTITY_CONFLICT,
        POSITION_UNAVAILABLE, METADATA_MISMATCH, PROVIDER_TIME_MISSING, PROVIDER_TIME_INVALID,
        STALE_OBSERVATION, FUTURE_OBSERVATION, DETAIL_NOT_REQUESTED, DETAIL_EMPTY,
        DETAIL_HTTP_FAILURE, DETAIL_TRANSPORT_FAILURE, DETAIL_PROVIDER_FAILURE, DETAIL_PROTOCOL_FAILURE,
        DETAIL_ID_MISMATCH, DETAIL_NUMBER_MISMATCH,
        TAGO_ALIGHT_UNVERIFIED, TARGET_NOT_AHEAD, REVALIDATION_REQUIRED
    }

    public record Candidate(
            String candidateRef,
            String selectionKey,
            String vehicleNumber,
            ConfirmationStatus confirmationStatus,
            ReasonCode reasonCode,
            Progress progress,
            Integer boardingOccurrenceGap,
            Instant observedAt,
            Instant providerDataTime,
            TrackingSupport trackingSupport,
            ReasonCode trackingReasonCode,
            List<ReasonCode> limitations
    ) {
        public Candidate {
            limitations = List.copyOf(limitations);
        }
    }

    /** Provider progress reference, never a claim of physical stopping or user boarding. */
    public record Progress(Long occurrenceId, String stopName, int order, ProgressKind kind) {
    }

    public enum ProgressKind { ROUTE_PROGRESS, ARRIVAL_OBSERVED, MOVING_REFERENCE }
}
