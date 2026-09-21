package com.stopbell.transit.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A provider-neutral current observation of one vehicle.
 */
public record TransitObservation(
        TransitProvider provider,
        String externalRouteId,
        String vehicleTrackingId,
        String currentStopExternalId,
        Integer currentStopOrder,
        String currentStopName,
        String directionContext,
        String sectionContext,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant observedAt,
        Instant providerDataTime,
        ArrivalEvidence arrivalEvidence
) {

    public TransitObservation {
        if (provider == null) {
            throw new IllegalArgumentException("Transit provider must not be null");
        }
        if (externalRouteId == null || externalRouteId.isBlank()) {
            throw new IllegalArgumentException("External route ID must not be blank");
        }
        if (vehicleTrackingId == null || vehicleTrackingId.isBlank()) {
            throw new IllegalArgumentException("Vehicle tracking ID must not be blank");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("Observed at must not be null");
        }
        if (arrivalEvidence == null) {
            throw new IllegalArgumentException("Arrival evidence must not be null");
        }
    }
}
