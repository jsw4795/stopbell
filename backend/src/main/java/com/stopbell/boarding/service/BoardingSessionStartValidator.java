package com.stopbell.boarding.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.entity.BoardingTraversalSnapshot;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.service.BoardingCandidateMetadataService;
import com.stopbell.transit.service.BoardingCandidateReferenceStore;
import com.stopbell.transit.service.BoardingCandidateService;
import org.springframework.stereotype.Service;

@Service
public class BoardingSessionStartValidator {
    private final BoardingCandidateReferenceStore references;
    private final BoardingCandidateMetadataService metadata;
    private final BoardingCandidateService candidates;

    public BoardingSessionStartValidator(BoardingCandidateReferenceStore references,
            BoardingCandidateMetadataService metadata, BoardingCandidateService candidates) {
        this.references = references;
        this.metadata = metadata;
        this.candidates = candidates;
    }

    public VerifiedBoardingStart validate(Long userId, String candidateRef, String confirmedVehicleNumber) {
        var original = references.resolve(candidateRef, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED));
        var context = original.context();
        var current = metadata.read(context.routeId(), context.targetOccurrenceId(), context.boardingOccurrenceId());
        var verified = candidates.revalidateSelected(original, current, confirmedVehicleNumber);
        var vehicle = verified.vehicle();
        var checkpoint = new BoardingCheckpoint(vehicle.stopId(), vehicle.order(),
                verified.progressIndex(), BoardingCheckpoint.Evidence.ID_ORDER, null,
                LocalDateTime.ofInstant(vehicle.observedAt(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(vehicle.providerDataTime(), ZoneOffset.UTC));
        // No passenger-access classification is required or inferred from stopFlag.
        var traversal = new BoardingTraversalSnapshot(1, current.provider(), current.externalRouteId(), current.cityCode(),
                current.traversal().stream().map(item -> new BoardingTraversalSnapshot.Occurrence(
                        item.id(), item.externalStopId(), item.order())).toList(),
                current.indexOf(current.targetOccurrenceId()), BoardingTraversalSnapshot.AlightAvailability.UNKNOWN, null);
        return new VerifiedBoardingStart(current, traversal, checkpoint, original.expiresAt(),
                vehicle.id(), vehicle.number(), current.routeNumber(),
                current.traversal().get(current.indexOf(current.targetOccurrenceId())).stopName());
    }
}
