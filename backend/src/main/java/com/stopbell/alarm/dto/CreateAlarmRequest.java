package com.stopbell.alarm.dto;

public record CreateAlarmRequest(
        Long targetStopOccurrenceId,
        boolean notifyOneStopBefore,
        boolean notifyOneStopAfter
) {
}
