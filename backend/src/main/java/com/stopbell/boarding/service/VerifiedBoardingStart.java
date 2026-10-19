package com.stopbell.boarding.service;

import java.time.Instant;

import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.entity.BoardingTraversalSnapshot;
import com.stopbell.transit.service.BoardingRouteSnapshot;

/** Only validated evidence crosses into the creation transaction; no live Provider objects. */
public record VerifiedBoardingStart(
        BoardingRouteSnapshot metadata, BoardingTraversalSnapshot traversal, BoardingCheckpoint checkpoint,
        Instant referenceExpiresAt, String vehicleTrackingId, String vehicleNumber, String routeNumber, String targetName
) {
    public VerifiedBoardingStart {
        if (metadata == null || traversal == null || checkpoint == null || referenceExpiresAt == null
                || traversal.targetAlightAvailability() != BoardingTraversalSnapshot.AlightAvailability.ALLOWED
                || checkpoint.getIndex() >= traversal.targetIndex()) {
            throw new IllegalArgumentException("Supported target ahead and validated checkpoint are required");
        }
        checkpoint.validateAgainst(traversal);
        if (metadata.provider() != traversal.provider()
                || !metadata.externalRouteId().equals(traversal.externalRouteId())
                || !java.util.Objects.equals(metadata.cityCode(), traversal.cityCode())
                || metadata.indexOf(metadata.targetOccurrenceId()) != traversal.targetIndex()
                || !metadata.traversal().stream().map(occurrence -> new BoardingTraversalSnapshot.Occurrence(
                        occurrence.id(), occurrence.externalStopId(), occurrence.order()))
                    .toList().equals(traversal.occurrences())) {
            throw new IllegalArgumentException("Verified snapshot must match original metadata context");
        }
    }
}
