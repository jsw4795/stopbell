package com.stopbell.alarm.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateAlarmOptionsRequest(
        @NotNull Boolean notifyOneStopBefore,
        @NotNull Boolean notifyOneStopAfter
) {
}
