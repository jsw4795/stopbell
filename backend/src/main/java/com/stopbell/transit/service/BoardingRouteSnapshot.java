package com.stopbell.transit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;

/** Immutable, consistently read metadata; names are excluded from the structural fingerprint. */
public record BoardingRouteSnapshot(
        Long routeId, TransitProvider provider, String externalRouteId, String cityCode,
        Long targetOccurrenceId, Long boardingOccurrenceId, List<Occurrence> traversal
) {
    public BoardingRouteSnapshot {
        traversal = List.copyOf(traversal);
    }

    public record Occurrence(Long id, String externalStopId, int order, String stopName) {
    }

    public int indexOf(Long occurrenceId) {
        for (int index = 0; index < traversal.size(); index++) {
            if (traversal.get(index).id().equals(occurrenceId)) {
                return index;
            }
        }
        return -1;
    }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, routeId);
            add(digest, provider);
            add(digest, externalRouteId);
            add(digest, cityCode);
            for (Occurrence occurrence : traversal) {
                add(digest, occurrence.id());
                add(digest, occurrence.externalStopId());
                add(digest, occurrence.order());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void add(MessageDigest digest, Object value) {
        String text = value == null ? "" : value.toString();
        digest.update((text.length() + ":" + text).getBytes(StandardCharsets.UTF_8));
    }
}
