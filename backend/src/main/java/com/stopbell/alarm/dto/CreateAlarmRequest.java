package com.stopbell.alarm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateAlarmRequest(
        @NotNull
        @Positive
        Long targetStopOccurrenceId,
        boolean notifyOneStopBefore,
        boolean notifyOneStopAfter
) {

    @JsonCreator
    public static CreateAlarmRequest fromJson(
            @JsonProperty("targetStopOccurrenceId") Long targetStopOccurrenceId,
            @JsonProperty("notifyOneStopBefore") Boolean notifyOneStopBefore,
            @JsonProperty("notifyOneStopAfter") Boolean notifyOneStopAfter
    ) {
        return new CreateAlarmRequest(
                targetStopOccurrenceId,
                Boolean.TRUE.equals(notifyOneStopBefore),
                Boolean.TRUE.equals(notifyOneStopAfter)
        );
    }
}
