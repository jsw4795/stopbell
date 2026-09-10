package com.stopbell.alarm.entity;

public record AdjacentStopSnapshot(String externalStopId, int stopOrder) {

    public AdjacentStopSnapshot {
        if (externalStopId == null || externalStopId.isBlank()) {
            throw new IllegalArgumentException("Adjacent stop external ID must not be blank");
        }
        if (stopOrder <= 0) {
            throw new IllegalArgumentException("Adjacent stop order must be positive");
        }
    }
}
