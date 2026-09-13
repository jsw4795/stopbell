package com.stopbell.transit.service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;

public record BusRouteMetadataSnapshot(
        TransitProvider provider,
        String externalRouteId,
        String routeNumber,
        String cityCode,
        List<BusStopOccurrenceMetadataSnapshot> occurrences
) {

    public BusRouteMetadataSnapshot {
        requireNonBlank(provider, externalRouteId, "External route ID");
        requireNonBlank(provider, routeNumber, "Route number");
        if (provider == TransitProvider.TAGO) {
            requireNonBlank(provider, cityCode, "TAGO city code");
        } else if (cityCode != null) {
            throw new IllegalArgumentException("Seoul Bus route must not have a TAGO city code");
        }
        occurrences = List.copyOf(Objects.requireNonNull(occurrences, "Occurrences must not be null"));
        if (occurrences.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Occurrences must not contain null");
        }
        Set<Integer> stopOrders = new HashSet<>();
        for (BusStopOccurrenceMetadataSnapshot occurrence : occurrences) {
            if (!stopOrders.add(occurrence.stopOrder())) {
                throw new IllegalArgumentException("Route snapshot contains duplicate stop order");
            }
        }
    }

    private static void requireNonBlank(TransitProvider provider, String value, String fieldName) {
        if (provider == null) {
            throw new IllegalArgumentException("Transit provider must not be null");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
