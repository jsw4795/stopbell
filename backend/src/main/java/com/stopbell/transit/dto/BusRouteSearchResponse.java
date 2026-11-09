package com.stopbell.transit.dto;

import com.stopbell.transit.domain.BusRouteType;
import com.stopbell.transit.domain.TransitProvider;

public record BusRouteSearchResponse(
        Long id,
        String routeNumber,
        String regionName,
        TransitProvider provider,
        BusRouteType routeType
) {
}
