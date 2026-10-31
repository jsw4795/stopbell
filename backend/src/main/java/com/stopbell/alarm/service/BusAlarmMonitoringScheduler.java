package com.stopbell.alarm.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.stopbell.boarding.service.BoardingSessionPollingService;
import com.stopbell.common.observability.OperationalMetrics;
import com.stopbell.transit.service.VehiclePollingCycle;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationRequestContext;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationRequestContext;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.mapper.SeoulBusTransitObservationMapper;
import com.stopbell.transit.mapper.TagoTransitObservationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single-instance, synchronous fixed-delay polling orchestration for Bus Alarms.
 */
@Component
@ConditionalOnProperty(prefix = "transit.monitoring", name = "enabled", havingValue = "true")
public class BusAlarmMonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(BusAlarmMonitoringScheduler.class);

    private final BusAlarmPollingService pollingService;
    private final BoardingSessionPollingService boardingPolling;
    private final BusAlarmEvaluator evaluator;
    private final BusAlarmLifecycleService lifecycleService;
    private final TagoVehicleLocationClient tagoVehicleLocationClient;
    private final SeoulBusVehicleLocationClient seoulBusVehicleLocationClient;
    private final SeoulBusVehicleDetailClient seoulBusVehicleDetailClient;
    private final TagoTransitObservationMapper tagoMapper;
    private final SeoulBusTransitObservationMapper seoulMapper;
    private final Clock clock;
    private final Duration failureRetryDelay;
    private final Map<AlarmEvaluationKey, BusAlarmEvaluationState> evaluationStates = new HashMap<>();
    private final Map<AlarmEvaluationKey, PendingDecision> pendingDecisions = new HashMap<>();
    private volatile boolean stopping;
    private OperationalMetrics metrics;

    @Autowired(required = false)
    void setOperationalMetrics(OperationalMetrics metrics) {
        this.metrics = metrics;
    }

    public BusAlarmMonitoringScheduler(
            BusAlarmPollingService pollingService,
            BusAlarmEvaluator evaluator,
            BusAlarmLifecycleService lifecycleService,
            TagoVehicleLocationClient tagoVehicleLocationClient,
            SeoulBusVehicleLocationClient seoulBusVehicleLocationClient,
            SeoulBusVehicleDetailClient seoulBusVehicleDetailClient,
            TagoTransitObservationMapper tagoMapper,
            SeoulBusTransitObservationMapper seoulMapper,
            Clock transitMetadataClock,
            @Value("${transit.monitoring.failure-retry-delay:PT5S}") Duration failureRetryDelay,
            BoardingSessionPollingService boardingPolling
    ) {
        this.pollingService = pollingService;
        this.boardingPolling = boardingPolling;
        this.evaluator = evaluator;
        this.lifecycleService = lifecycleService;
        this.tagoVehicleLocationClient = tagoVehicleLocationClient;
        this.seoulBusVehicleLocationClient = seoulBusVehicleLocationClient;
        this.seoulBusVehicleDetailClient = seoulBusVehicleDetailClient;
        this.tagoMapper = tagoMapper;
        this.seoulMapper = seoulMapper;
        this.clock = transitMetadataClock;
        if (failureRetryDelay.isNegative()) {
            throw new IllegalArgumentException("Failure retry delay must not be negative");
        }
        this.failureRetryDelay = failureRetryDelay;
    }

    @Scheduled(scheduler = "transitMonitoringScheduler", fixedDelayString = "${transit.monitoring.fixed-delay:PT20S}")
    public synchronized void pollMonitoringAlarms() {
        if (stopping || Thread.currentThread().isInterrupted()) {
            return;
        }
        long started = System.nanoTime();
        OperationalMetrics.CycleOutcome outcome = OperationalMetrics.CycleOutcome.FAILED;
        try {
            pollCycle();
            outcome = !stopping && !Thread.currentThread().isInterrupted()
                    ? OperationalMetrics.CycleOutcome.COMPLETED : OperationalMetrics.CycleOutcome.INTERRUPTED;
        } finally {
            if (metrics != null) metrics.monitoringCycle(System.nanoTime() - started, outcome);
        }
    }

    private void pollCycle() {
        VehiclePollingCycle cycle = new VehiclePollingCycle(tagoVehicleLocationClient, seoulBusVehicleLocationClient,
                seoulBusVehicleDetailClient, clock, metrics);
        // Reconcile before loading monitoring groups: an ambiguous ARRIVED/after commit may already be INACTIVE.
        Set<AlarmEvaluationKey> replayed = new LinkedHashSet<>(pendingDecisions.keySet());
        for (AlarmEvaluationKey key : replayed) {
            applyDecision(key, pendingDecisions.get(key));
        }
        List<BusAlarmPollingGroup> groups = pollingService.findMonitoringGroups();
        retainCurrentEvaluationStates(groups);
        List<RetryTarget> retryTargets = new ArrayList<>();
        for (BusAlarmPollingGroup group : groups) {
            if (stopping || Thread.currentThread().isInterrupted()) {
                return;
            }
            List<RetryTarget> groupRetries = new ArrayList<>();
            try {
                pollGroup(group, groupRetries, cycle);
                retryTargets.addAll(groupRetries);
            } catch (TransitProviderClientException exception) {
                logProviderFailure(group, null, exception, false);
                retryTargets.add(new RetryTarget(group, null));
            } catch (RuntimeException exception) {
                log.warn("Bus Alarm polling group failed: provider={}, failureType={}",
                        group.key().provider(), exception.getClass().getSimpleName());
            }
        }
        if (stopping || Thread.currentThread().isInterrupted()) {
            return;
        }
        try {
            boardingPolling.poll(cycle);
        } catch (RuntimeException exception) {
            log.warn("Boarding polling cycle failed: failureType={}", exception.getClass().getSimpleName());
        }
        if (stopping || retryTargets.isEmpty() || !waitBeforeRetry()) {
            return;
        }
        VehiclePollingCycle retryCycle = new VehiclePollingCycle(tagoVehicleLocationClient,
                seoulBusVehicleLocationClient, seoulBusVehicleDetailClient, clock, metrics);
        for (RetryTarget target : retryTargets) {
            if (stopping || Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                if (target.vehicleId() == null) {
                    pollGroup(target.group(), null, retryCycle);
                } else {
                    retrySeoulDetail(target, retryCycle);
                }
            } catch (TransitProviderClientException exception) {
                logProviderFailure(target.group(), target.vehicleId(), exception, true);
            } catch (RuntimeException exception) {
                log.warn("Bus Alarm polling retry failed: provider={}, failureType={}",
                        target.group().key().provider(), exception.getClass().getSimpleName());
            }
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        stopping = true;
    }

    boolean waitBeforeRetry() {
        try {
            Thread.sleep(failureRetryDelay);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Bus Alarm polling retry interrupted");
            return false;
        }
    }

    private void pollGroup(BusAlarmPollingGroup group, List<RetryTarget> retryTargets, VehiclePollingCycle cycle) {
        PollSnapshot snapshot = switch (group.key().provider()) {
            case TAGO -> pollTago(group, cycle);
            case SEOUL_BUS -> pollSeoul(group, retryTargets, cycle);
        };
        Instant now = clock.instant();
        for (Alarm alarm : group.alarms()) {
            evaluateAndApply(alarm, snapshot, now);
        }
    }

    private PollSnapshot pollTago(BusAlarmPollingGroup group, VehiclePollingCycle cycle) {
        VehicleLocationRequest<TagoVehicleLocationRequestContext> request = new VehicleLocationRequest<>(
                group.key().externalRouteId(), new TagoVehicleLocationRequestContext(group.key().cityCode())
        );
        List<TransitObservation> observations = tagoMapper.map(request, cycle.tago(group.key().externalRouteId(), group.key().cityCode()).response());
        return new PollSnapshot(observations, vehicleIds(observations));
    }

    private PollSnapshot pollSeoul(BusAlarmPollingGroup group, List<RetryTarget> retryTargets, VehiclePollingCycle cycle) {
        VehicleLocationRequest<SeoulBusVehicleLocationRequestContext> request = new VehicleLocationRequest<>(
                group.key().externalRouteId(), new SeoulBusVehicleLocationRequestContext()
        );
        List<SeoulBusVehicleLocationItem> roster = rosterItems(
                cycle.roster(group.key().externalRouteId()).response()
        );
        Set<String> presentVehicleIds = rosterVehicleIds(roster);
        Set<String> vehicleIds = selectSeoulDetailVehicleIds(group.alarms(), presentVehicleIds);
        List<TransitObservation> observations = new ArrayList<>();
        for (String vehicleId : vehicleIds) {
            try {
                observations.addAll(seoulMapper.map(request, vehicleId, cycle.detail(vehicleId).response()));
            } catch (TransitProviderClientException exception) {
                logProviderFailure(group, vehicleId, exception, retryTargets == null);
                if (retryTargets != null) {
                    retryTargets.add(new RetryTarget(group, vehicleId));
                }
            }
        }
        return new PollSnapshot(observations, presentVehicleIds);
    }

    private void retrySeoulDetail(RetryTarget target, VehiclePollingCycle cycle) {
        VehicleLocationRequest<SeoulBusVehicleLocationRequestContext> request = new VehicleLocationRequest<>(
                target.group().key().externalRouteId(), new SeoulBusVehicleLocationRequestContext()
        );
        List<TransitObservation> observations = seoulMapper.map(
                request, target.vehicleId(), cycle.detail(target.vehicleId()).response()
        );
        if (observations.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (Alarm alarm : target.group().alarms()) {
            evaluateAndApply(alarm, new PollSnapshot(observations, vehicleIds(observations)), now, true);
        }
    }

    private void logProviderFailure(BusAlarmPollingGroup group, String vehicleId,
            TransitProviderClientException exception, boolean retry) {
        log.warn("Bus Alarm provider failure: provider={}, operation={}, failureKind={}, route={}, vehicle={}, attempt={}",
                exception.provider(), exception.operation(), exception.failureKind(),
                group.key().externalRouteId(), vehicleId, retry ? "retry-final" : "first-retry-pending");
    }

    private Set<String> selectSeoulDetailVehicleIds(List<Alarm> alarms, Set<String> rosterVehicleIds) {
        Set<String> detailVehicleIds = new LinkedHashSet<>();
        for (Alarm alarm : alarms) {
            AlarmEvaluationKey key = evaluationKey(alarm);
            BusAlarmEvaluationState state = evaluationStates.getOrDefault(key, BusAlarmEvaluationState.initial());
            if (alarm.getStatus() == AlarmStatus.ACTIVE) {
                if (!state.baselineEstablished()) {
                    detailVehicleIds.addAll(rosterVehicleIds);
                    continue;
                }
                for (String vehicleId : state.trackedVehicles().keySet()) {
                    if (rosterVehicleIds.contains(vehicleId)) {
                        detailVehicleIds.add(vehicleId);
                    }
                }
                for (String vehicleId : rosterVehicleIds) {
                    if (!state.trackedVehicles().containsKey(vehicleId)
                            && !state.baselineAfterVehicles().containsKey(vehicleId)) {
                        detailVehicleIds.add(vehicleId);
                    }
                }
            }
            if (alarm.getStatus() == AlarmStatus.FOLLOW_UP
                    && alarm.getFollowUpVehicleTrackingId() != null
                    && !alarm.getFollowUpVehicleTrackingId().isBlank()) {
                detailVehicleIds.add(alarm.getFollowUpVehicleTrackingId());
            }
        }
        return detailVehicleIds;
    }

    private void evaluateAndApply(Alarm alarm, PollSnapshot snapshot, Instant now) {
        evaluateAndApply(alarm, snapshot, now, false);
    }

    private void evaluateAndApply(Alarm alarm, PollSnapshot snapshot, Instant now, boolean partialPresence) {
        AlarmEvaluationKey key = evaluationKey(alarm);
        if (pendingDecisions.containsKey(key)) {
            return;
        }
        BusAlarmEvaluationState currentState = evaluationStates.getOrDefault(key, BusAlarmEvaluationState.initial());
        BusAlarmEvaluationResult result = partialPresence
                ? evaluator.evaluatePartial(alarm, currentState, snapshot.presentVehicleIds(), snapshot.observations(), now)
                : evaluator.evaluate(alarm, currentState, snapshot.presentVehicleIds(), snapshot.observations(), now);
        Optional<TransitEvent> selectedEvent = BusAlarmLifecycleService.selectEvent(
                result.eventCandidates(), alarm.getStatus()
        );
        if (metrics != null) metrics.evaluation(selectedEvent.isPresent() ? OperationalMetrics.Evaluation.EVENT
                : result.followUpExpired() ? OperationalMetrics.Evaluation.FOLLOW_UP_EXPIRED
                : OperationalMetrics.Evaluation.NO_EVENT);
        BusAlarmEvaluationResult selectedResult = new BusAlarmEvaluationResult(
                selectedEvent.map(List::of).orElseGet(List::of), result.nextState(), result.followUpExpired()
        );
        PendingDecision decision = new PendingDecision(alarm.getStatus(), selectedResult, now);
        pendingDecisions.put(key, decision);
        applyDecision(key, decision);
    }

    private void applyDecision(AlarmEvaluationKey key, PendingDecision decision) {
        BusAlarmDecisionResult applied;
        try {
            applied = lifecycleService.applyIfCurrent(key, decision.expectedStatus(), decision.result(), decision.detectedAt());
        } catch (RuntimeException exception) {
            // Preserve the original immutable candidate even when commit acknowledgement was lost.
            log.warn("Notification decision failed: failureType={}", exception.getClass().getSimpleName());
            return;
        }
        if (applied.outcome() == BusAlarmDecisionResult.Outcome.RETRY_REQUIRED) {
            return;
        }
        pendingDecisions.remove(key);
        TransitEvent event = decision.result().eventCandidates().stream().findFirst().orElse(null);
        if (!applied.permitsMemory(key, decision.expectedStatus(), event)) {
            return;
        }
        if (applied.status() == AlarmStatus.INACTIVE) {
            evaluationStates.remove(key);
            return;
        }
        BusAlarmEvaluationState nextState = decision.result().nextState();
        if (applied.status() == AlarmStatus.FOLLOW_UP && event != null && event.type() == TransitEventType.ARRIVED) {
            VehicleTrackingState tracking = nextState.trackedVehicles().get(applied.followUpVehicleId());
            if (tracking == null || !tracking.trackingCycleId().equals(applied.followUpTrackingCycleId())) {
                return; // Next poll can restore from persisted runtime; never publish a different cycle.
            }
            nextState = nextState.forFollowUp(applied.followUpVehicleId());
        }
        evaluationStates.put(key, nextState);
    }

    private void retainCurrentEvaluationStates(List<BusAlarmPollingGroup> groups) {
        Set<AlarmEvaluationKey> currentKeys = new LinkedHashSet<>();
        for (BusAlarmPollingGroup group : groups) {
            for (Alarm alarm : group.alarms()) {
                currentKeys.add(evaluationKey(alarm));
            }
        }
        evaluationStates.keySet().retainAll(currentKeys);
    }

    private static List<SeoulBusVehicleLocationItem> rosterItems(
            com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationResponse response
    ) {
        if (response.body() == null || response.body().itemList() == null) {
            return List.of();
        }
        return response.body().itemList();
    }

    private static Set<String> vehicleIds(List<TransitObservation> observations) {
        Set<String> vehicleIds = new LinkedHashSet<>();
        for (TransitObservation observation : observations) {
            vehicleIds.add(observation.vehicleTrackingId());
        }
        return vehicleIds;
    }

    private static Set<String> rosterVehicleIds(List<SeoulBusVehicleLocationItem> roster) {
        Set<String> vehicleIds = new LinkedHashSet<>();
        for (SeoulBusVehicleLocationItem item : roster) {
            if (item.vehId() != null && !item.vehId().isBlank()) {
                vehicleIds.add(item.vehId());
            }
        }
        return vehicleIds;
    }

    private record PendingDecision(AlarmStatus expectedStatus, BusAlarmEvaluationResult result, Instant detectedAt) {
    }

    private record PollSnapshot(List<TransitObservation> observations, Set<String> presentVehicleIds) {
    }

    private record RetryTarget(BusAlarmPollingGroup group, String vehicleId) {
    }

    private static AlarmEvaluationKey evaluationKey(Alarm alarm) {
        return new AlarmEvaluationKey(alarm.getId(), alarm.getActivationGeneration());
    }
}
