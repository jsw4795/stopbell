package com.stopbell.transit.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Provider-neutral evidence for one transit event candidate.
 */
public record TransitEvent(
        TransitEventType type,
        String vehicleTrackingId,
        UUID trackingCycleId,
        Instant observedAt,
        String currentStopExternalId,
        String currentStopName,
        Integer currentStopOrder,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant providerDataTime,
        Integer stopsPastTarget
) {

    public TransitEvent {
        if (type == null) {
            throw new IllegalArgumentException("Transit event type must not be null");
        }
        if (vehicleTrackingId == null || vehicleTrackingId.isBlank()) {
            throw new IllegalArgumentException("Vehicle tracking ID must not be blank");
        }
        if (trackingCycleId == null) {
            throw new IllegalArgumentException("Tracking cycle ID must not be null");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("Event observed time must not be null");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("Event latitude and longitude must both be present or absent");
        }
        if (stopsPastTarget != null && stopsPastTarget < 1) {
            throw new IllegalArgumentException("Stops past target must be positive when present");
        }
    }
}
