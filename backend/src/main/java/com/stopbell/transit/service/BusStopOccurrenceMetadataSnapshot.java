package com.stopbell.transit.service;

import java.math.BigDecimal;

public record BusStopOccurrenceMetadataSnapshot(
        String externalStopId,
        String stopName,
        BigDecimal latitude,
        BigDecimal longitude,
        int stopOrder,
        String destinationName
) {

    public BusStopOccurrenceMetadataSnapshot(String externalStopId, String stopName,
            BigDecimal latitude, BigDecimal longitude, int stopOrder) {
        this(externalStopId, stopName, latitude, longitude, stopOrder, null);
    }

    public BusStopOccurrenceMetadataSnapshot {
        if (externalStopId == null || externalStopId.isBlank()) {
            throw new IllegalArgumentException("External stop ID must not be blank");
        }
        if (stopName == null || stopName.isBlank()) {
            throw new IllegalArgumentException("Stop name must not be blank");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("Stop latitude and longitude must both be present or absent");
        }
        if (latitude != null && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new IllegalArgumentException("Stop latitude must be between -90 and 90");
        }
        if (longitude != null && (longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new IllegalArgumentException("Stop longitude must be between -180 and 180");
        }
        if (stopOrder <= 0) {
            throw new IllegalArgumentException("Stop order must be positive");
        }
        if (destinationName != null && (destinationName.isBlank() || destinationName.length() > 255)) {
            throw new IllegalArgumentException("Destination name must be non-blank and at most 255 characters");
        }
    }
}
