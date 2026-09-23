package com.stopbell.alarm.service;

import com.stopbell.transit.domain.TransitProvider;

/**
 * Reproduces the provider route request context shared by Bus Alarms.
 */
public record BusPollingKey(
        TransitProvider provider,
        String externalRouteId,
        String cityCode
) {

    public BusPollingKey {
        if (provider == null) {
            throw new IllegalArgumentException("Transit provider must not be null");
        }
        if (externalRouteId == null || externalRouteId.isBlank()) {
            throw new IllegalArgumentException("External route ID must not be blank");
        }

        switch (provider) {
            case TAGO -> {
                if (cityCode == null || cityCode.isBlank()) {
                    throw new IllegalArgumentException("TAGO city code must not be blank");
                }
            }
            case SEOUL_BUS -> {
                if (cityCode != null) {
                    throw new IllegalArgumentException("Seoul Bus polling key must not have a TAGO city code");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported transit provider: " + provider);
        }
    }
}
