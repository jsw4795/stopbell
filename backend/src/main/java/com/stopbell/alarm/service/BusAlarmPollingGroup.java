package com.stopbell.alarm.service;

import java.util.List;

import com.stopbell.alarm.entity.Alarm;

/**
 * Bus Alarms that can share a single provider route polling response.
 */
public record BusAlarmPollingGroup(BusPollingKey key, List<Alarm> alarms) {

    public BusAlarmPollingGroup {
        if (key == null) {
            throw new IllegalArgumentException("Bus polling key must not be null");
        }
        if (alarms == null || alarms.isEmpty()) {
            throw new IllegalArgumentException("Bus polling group must contain at least one alarm");
        }
        alarms = List.copyOf(alarms);
    }
}
