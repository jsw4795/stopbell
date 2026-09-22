package com.stopbell.transit.service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;

/**
 * A provider snapshot whose source adapter has finished every required fetch and validation.
 */
public record CompleteBusMetadataSnapshot(TransitProvider provider, List<BusRouteMetadataSnapshot> routes) {

    public CompleteBusMetadataSnapshot {
        provider = Objects.requireNonNull(provider, "Provider must not be null");
        routes = List.copyOf(Objects.requireNonNull(routes, "Routes must not be null"));
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("A complete provider snapshot must not be empty");
        }
        Set<String> routeIds = new HashSet<>();
        for (BusRouteMetadataSnapshot route : routes) {
            if (route == null || route.provider() != provider) {
                throw new IllegalArgumentException("Complete snapshot contains an invalid provider route");
            }
            if (!routeIds.add(route.externalRouteId())) {
                throw new IllegalArgumentException("Complete snapshot contains duplicate route identity");
            }
        }
    }
}
