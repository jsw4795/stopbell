package com.stopbell.boarding.entity;

import java.util.HashSet;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import tools.jackson.databind.json.JsonMapper;

/** Versioned immutable evidence, independent of live metadata Entity foreign keys. */
public record BoardingTraversalSnapshot(
        int version, TransitProvider provider, String externalRouteId, String cityCode,
        List<Occurrence> occurrences, int targetIndex, AlightAvailability targetAlightAvailability,
        String targetClassificationEvidence
) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public enum AlightAvailability { ALLOWED, PROHIBITED, UNKNOWN }

    public record Occurrence(long occurrenceId, String externalStopId, int order) {
        public Occurrence {
            if (occurrenceId <= 0 || order <= 0 || externalStopId == null
                    || externalStopId.isBlank() || externalStopId.length() > 255) {
                throw new IllegalArgumentException("Invalid traversal occurrence");
            }
        }
    }

    public BoardingTraversalSnapshot {
        if (version != 1 || provider == null || externalRouteId == null || externalRouteId.isBlank()
                || externalRouteId.length() > 255 || occurrences == null || occurrences.isEmpty()
                || targetIndex < 0 || targetIndex >= occurrences.size() || targetAlightAvailability == null
                || (provider == TransitProvider.TAGO ? cityCode == null || cityCode.isBlank()
                    || cityCode.length() > 50 : cityCode != null)) {
            throw new IllegalArgumentException("Invalid traversal snapshot");
        }
        if (targetAlightAvailability == AlightAvailability.UNKNOWN ? targetClassificationEvidence != null
                : targetClassificationEvidence == null || targetClassificationEvidence.isBlank()
                    || targetClassificationEvidence.length() > 255) {
            throw new IllegalArgumentException("Target classification requires verified evidence");
        }
        occurrences = List.copyOf(occurrences);
        var ids = new HashSet<Long>();
        int previousOrder = 0;
        for (Occurrence occurrence : occurrences) {
            if (!ids.add(occurrence.occurrenceId()) || occurrence.order() <= previousOrder) {
                throw new IllegalArgumentException("Traversal must have unique occurrences and increasing orders");
            }
            previousOrder = occurrence.order();
        }
    }

    public String toJson() {
        return JSON.writeValueAsString(this);
    }

    public static BoardingTraversalSnapshot fromJson(String json) {
        return JSON.readValue(json, BoardingTraversalSnapshot.class);
    }

    public String fingerprint() {
        return BoardingFingerprint.hash(toJson());
    }
}
