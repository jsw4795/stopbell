package com.stopbell.alarm.service;

import java.util.List;

import com.stopbell.transit.domain.TransitEvent;

/**
 * Pure evaluation output consumed by the later polling/lifecycle orchestration task.
 */
public record BusAlarmEvaluationResult(
        List<TransitEvent> eventCandidates,
        BusAlarmEvaluationState nextState,
        boolean followUpExpired
) {

    public BusAlarmEvaluationResult {
        eventCandidates = List.copyOf(eventCandidates);
        if (nextState == null) {
            throw new IllegalArgumentException("Next evaluation state must not be null");
        }
    }
}
