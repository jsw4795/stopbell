package com.stopbell.boarding.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.stopbell.boarding.domain.BoardingTrackingDecision;
import com.stopbell.boarding.entity.BoardingStateReason;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.service.VehiclePollingCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class BoardingSessionPollingService {
    private static final Logger log = LoggerFactory.getLogger(BoardingSessionPollingService.class);
    private final BoardingTrackingStore store;
    private final BoardingTrackingMetadata metadata;
    private final BoardingTrackingRuntime runtime;
    private final BoardingVehicleTracker tracker;
    private final BoardingProgressEvaluator evaluator;
    private final Clock clock;

    public BoardingSessionPollingService(BoardingTrackingStore store, BoardingTrackingMetadata metadata,
            BoardingTrackingRuntime runtime, BoardingVehicleTracker tracker, BoardingProgressEvaluator evaluator,
            @Qualifier("transitMetadataClock") Clock clock) {
        this.store = store;
        this.metadata = metadata;
        this.runtime = runtime;
        this.tracker = tracker;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    /** Results are candidates, may recur across polls, and are not durable Notification consumption. */
    public List<BoardingTrackingStore.Applied> poll(VehiclePollingCycle cycle) {
        var beforeRead = runtime.admittedIds();
        var sessions = store.findOpen();
        runtime.retainLoaded(sessions.stream().map(session -> session.id()).collect(Collectors.toSet()), beforeRead);
        var results = new ArrayList<BoardingTrackingStore.Applied>();
        for (var session : sessions) {
            try {
                if (!now().isBefore(session.expiresAt())) {
                    store.expire(session);
                    continue;
                }
                BoardingTrackingDecision decision;
                if (session.requiresConfirmation()) {
                    continue;
                } else if (!metadata.matches(session)) {
                    decision = BoardingTrackingDecision.held(BoardingStateReason.METADATA_CHANGED);
                } else {
                    BoardingVehicleTracker.Input input;
                    try {
                        input = tracker.observe(session, cycle);
                    } catch (TransitProviderClientException exception) {
                        input = BoardingVehicleTracker.Input.held(BoardingStateReason.TEMPORARY_PROVIDER_FAILURE);
                    }
                    decision = input.reason() != null ? BoardingTrackingDecision.held(input.reason())
                            : evaluator.evaluate(session, input.observation(), now());
                    if (decision.reason() != null && decision.reason().allowsAutomaticRevalidation()
                            && evaluator.continuityExpired(session, now())) {
                        decision = BoardingTrackingDecision.held(BoardingStateReason.CONTINUITY_GAP_EXCEEDED);
                    }
                }
                results.add(store.apply(session, decision));
            } catch (RuntimeException exception) {
                // No immediate Provider refetch/replay after a database failure. Next cycle reads a new snapshot.
                log.warn("Boarding polling failed: failureType={}", exception.getClass().getSimpleName());
            }
        }
        return List.copyOf(results);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
