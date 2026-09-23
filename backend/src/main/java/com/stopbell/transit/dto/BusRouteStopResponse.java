package com.stopbell.transit.dto;

public record BusRouteStopResponse(
        Long id,
        String name,
        int order,
        boolean canNotifyOneStopBefore,
        boolean canNotifyOneStopAfter
) {
}
