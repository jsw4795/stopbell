package com.stopbell.alarm.service;

/**
 * Identifies one in-memory evaluation state for a persisted Alarm activation cycle.
 */
public record AlarmEvaluationKey(Long alarmId, long activationGeneration) {

    public AlarmEvaluationKey {
        if (alarmId == null || alarmId <= 0) {
            throw new IllegalArgumentException("Alarm ID must be positive");
        }
        if (activationGeneration < 0) {
            throw new IllegalArgumentException("Activation generation must not be negative");
        }
    }
}
