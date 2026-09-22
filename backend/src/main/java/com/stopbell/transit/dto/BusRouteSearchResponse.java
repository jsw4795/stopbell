package com.stopbell.transit.dto;

public record BusRouteSearchResponse(
        Long id,
        String routeNumber,
        String regionName
) {
}
