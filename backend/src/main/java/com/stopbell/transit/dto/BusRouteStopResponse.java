package com.stopbell.transit.dto;

public record BusRouteStopResponse(
        Long id,
        String name,
        int order,
        String destinationName,
        String previousStopName,
        String nextStopName,
        boolean canNotifyOneStopBefore,
        boolean canNotifyOneStopAfter
) {
}
