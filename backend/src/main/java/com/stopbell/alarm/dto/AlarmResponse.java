package com.stopbell.alarm.dto;

import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.transit.domain.BusRouteType;
import com.stopbell.transit.domain.TransitProvider;

public record AlarmResponse(
        Long id,
        TransitType transitType,
        AlarmStatus status,
        String routeNumber,
        String stopName,
        boolean notifyOneStopBefore,
        boolean notifyOneStopAfter,
        String startStopName,
        String endStopName,
        TransitProvider provider,
        BusRouteType routeType,
        boolean canNotifyOneStopBefore,
        boolean canNotifyOneStopAfter,
        Long routeId,
        Long targetStopOccurrenceId
) {
}
