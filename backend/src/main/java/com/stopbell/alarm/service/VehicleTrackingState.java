package com.stopbell.alarm.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitObservation;

/**
 * The minimal in-memory state retained for one active vehicle tracking cycle.
 */
public record VehicleTrackingState(
        UUID trackingCycleId,
        TransitObservation lastObservation,
        Instant lastKnownProviderDataTime,
        Instant lastSeenAt,
        boolean hasObservedBeforeTarget,
        Set<TransitEventType> emittedEventTypes
) {

    public VehicleTrackingState {
        if (trackingCycleId == null || lastObservation == null || lastSeenAt == null || emittedEventTypes == null) {
            throw new IllegalArgumentException("Vehicle tracking state must be complete");
        }
        emittedEventTypes = Set.copyOf(emittedEventTypes);
    }

    public static VehicleTrackingState begin(TransitObservation observation, Instant seenAt) {
        return new VehicleTrackingState(UUID.randomUUID(), observation, observation.providerDataTime(), seenAt, false, Set.of());
    }

    public static VehicleTrackingState beginBeforeTarget(TransitObservation observation, Instant seenAt) {
        return new VehicleTrackingState(UUID.randomUUID(), observation, observation.providerDataTime(), seenAt, true, Set.of());
    }

    public static VehicleTrackingState restoreFollowUp(UUID trackingCycleId, TransitObservation observation, Instant seenAt) {
        return new VehicleTrackingState(trackingCycleId, observation, observation.providerDataTime(), seenAt,
                false, Set.of(TransitEventType.ARRIVED));
    }

    public VehicleTrackingState observe(TransitObservation observation, Instant seenAt) {
        return new VehicleTrackingState(trackingCycleId, observation, providerTimeAfter(observation), seenAt,
                hasObservedBeforeTarget, emittedEventTypes);
    }

    public VehicleTrackingState emit(TransitEventType eventType, TransitObservation observation, Instant seenAt) {
        EnumSet<TransitEventType> emitted = emittedEventTypes.isEmpty()
                ? EnumSet.noneOf(TransitEventType.class)
                : EnumSet.copyOf(emittedEventTypes);
        emitted.add(eventType);
        return new VehicleTrackingState(trackingCycleId, observation, providerTimeAfter(observation), seenAt,
                hasObservedBeforeTarget, emitted);
    }

    private Instant providerTimeAfter(TransitObservation observation) {
        // A nullable source time must not erase the last validated provider clock.
        return observation.providerDataTime() == null ? lastKnownProviderDataTime : observation.providerDataTime();
    }

    public boolean hasEmitted(TransitEventType eventType) {
        return emittedEventTypes.contains(eventType);
    }
}
