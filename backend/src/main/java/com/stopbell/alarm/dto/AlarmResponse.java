package com.stopbell.alarm.dto;

import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.TransitType;

public record AlarmResponse(
        Long id,
        TransitType transitType,
        AlarmStatus status,
        String routeNumber,
        String stopName,
        boolean notifyOneStopBefore,
        boolean notifyOneStopAfter
) {
}
