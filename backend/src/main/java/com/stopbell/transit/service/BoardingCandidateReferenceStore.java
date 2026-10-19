package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Ephemeral references only. Resolution does not replace TASK-905's current-provider revalidation. */
@Component
public class BoardingCandidateReferenceStore {
    public static final Duration FRESHNESS = Duration.ofSeconds(60);
    private static final int MAX_REFERENCES = 10_000;
    private final Map<String, Snapshot> references = new LinkedHashMap<>();
    private final Clock clock;
    private final int capacity;

    @Autowired
    public BoardingCandidateReferenceStore(@Qualifier("transitMetadataClock") Clock clock) {
        this(clock, MAX_REFERENCES);
    }

    BoardingCandidateReferenceStore(Clock clock, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Reference capacity must be positive");
        }
        this.clock = clock;
        this.capacity = capacity;
    }

    public record Context(Long userId, Long routeId, Long targetOccurrenceId, Long boardingOccurrenceId) {
    }

    public record Snapshot(Context context, BoardingRouteSnapshot route, String metadataFingerprint,
            Vehicle rosterVehicle, Vehicle detailVehicle, Instant expiresAt) {
    }

    /** A fresh lookup replaces this exact screen context; old refs are evicted, never refreshed. */
    public synchronized void replace(Context context) {
        Instant now = clock.instant();
        references.values().removeIf(value -> value.context().equals(context) || expired(value, now));
    }

    public synchronized String issue(Context context, BoardingRouteSnapshot route, Vehicle roster, Vehicle detail) {
        Instant now = clock.instant();
        references.values().removeIf(value -> expired(value, now));
        Instant expiresAt = expiry(roster);
        if (detail != null && expiry(detail).isBefore(expiresAt)) {
            expiresAt = expiry(detail);
        }
        if (now.isAfter(expiresAt) || future(roster, now) || (detail != null && future(detail, now))) {
            return null;
        }
        while (references.size() >= capacity) {
            references.remove(references.keySet().iterator().next());
        }
        String reference = UUID.randomUUID().toString();
        references.put(reference, new Snapshot(context, route, route.fingerprint(), roster, detail, expiresAt));
        return reference;
    }

    /** Start requests carry only a reference: derive all Route/occurrence context on the server. */
    public synchronized Optional<Snapshot> resolve(String reference, Long authenticatedUserId) {
        Snapshot snapshot = references.get(reference);
        if (snapshot == null || !snapshot.context().userId().equals(authenticatedUserId)) {
            return Optional.empty();
        }
        if (expired(snapshot, clock.instant())) {
            references.remove(reference);
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    /** Reconfirmation or other context-aware callers must also match the full original context. */
    public synchronized Optional<Snapshot> resolve(String reference, Context expectedContext) {
        return resolve(reference, expectedContext.userId())
                .filter(snapshot -> snapshot.context().equals(expectedContext));
    }

    private static boolean expired(Snapshot snapshot, Instant now) {
        return now.isAfter(snapshot.expiresAt()) || future(snapshot.rosterVehicle(), now)
                || (snapshot.detailVehicle() != null && future(snapshot.detailVehicle(), now));
    }

    private static boolean future(Vehicle vehicle, Instant now) {
        return vehicle.observedAt().isAfter(now)
                || (vehicle.providerDataTime() != null && vehicle.providerDataTime().isAfter(now));
    }

    private static Instant expiry(Vehicle vehicle) {
        Instant observedExpiry = vehicle.observedAt().plus(FRESHNESS);
        return vehicle.providerDataTime() != null && vehicle.providerDataTime().isBefore(vehicle.observedAt())
                ? vehicle.providerDataTime().plus(FRESHNESS) : observedExpiry;
    }
}
