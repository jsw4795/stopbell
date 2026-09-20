package com.stopbell.transit.client;

public record VehicleLocationRequest<C extends VehicleLocationRequestContext>(
        String externalRouteId,
        C context
) {

    public VehicleLocationRequest {
        if (externalRouteId == null || externalRouteId.isBlank()) {
            throw new IllegalArgumentException("External route ID must not be blank");
        }
        if (context == null) {
            throw new IllegalArgumentException("Vehicle location request context must not be null");
        }
    }
}
