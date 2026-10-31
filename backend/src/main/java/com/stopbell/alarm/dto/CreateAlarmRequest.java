package com.stopbell.alarm.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class CreateAlarmRequest {
    @NotNull
    @Positive
    private Long targetStopOccurrenceId;
    private Boolean notifyOneStopBefore = false;
    private Boolean notifyOneStopAfter = false;
    private String clientRequestId;

    @JsonCreator
    public CreateAlarmRequest() {
    }

    public CreateAlarmRequest(Long targetStopOccurrenceId, boolean notifyOneStopBefore, boolean notifyOneStopAfter) {
        this.targetStopOccurrenceId = targetStopOccurrenceId;
        this.notifyOneStopBefore = notifyOneStopBefore;
        this.notifyOneStopAfter = notifyOneStopAfter;
    }

    @JsonSetter("targetStopOccurrenceId")
    public void setTargetStopOccurrenceId(Long value) { this.targetStopOccurrenceId = value; }

    @JsonSetter("notifyOneStopBefore")
    public void setNotifyOneStopBefore(Boolean value) { this.notifyOneStopBefore = Boolean.TRUE.equals(value); }

    @JsonSetter("notifyOneStopAfter")
    public void setNotifyOneStopAfter(Boolean value) { this.notifyOneStopAfter = Boolean.TRUE.equals(value); }

    @JsonSetter(value = "clientRequestId", nulls = Nulls.FAIL)
    public void setClientRequestId(String value) { this.clientRequestId = value; }

    public Long targetStopOccurrenceId() { return targetStopOccurrenceId; }
    public boolean notifyOneStopBefore() { return Boolean.TRUE.equals(notifyOneStopBefore); }
    public boolean notifyOneStopAfter() { return Boolean.TRUE.equals(notifyOneStopAfter); }
    public String clientRequestId() { return clientRequestId; }
}
