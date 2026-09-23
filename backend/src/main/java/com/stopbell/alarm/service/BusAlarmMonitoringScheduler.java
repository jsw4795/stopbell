package com.stopbell.alarm.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationRequestContext;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationRequestContext;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.mapper.SeoulBusTransitObservationMapper;
import com.stopbell.transit.mapper.TagoTransitObservationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private final BusAlarmEvaluator evaluator;
    private final BusAlarmLifecycleService lifecycleService;
    private final TagoVehicleLocationClient tagoVehicleLocationClient;
    private final SeoulBusVehicleLocationClient seoulBusVehicleLocationClient;
    private final SeoulBusVehicleDetailClient seoulBusVehicleDetailClient;
    private final TagoTransitObservationMapper tagoMapper;
    private final SeoulBusTransitObservationMapper seoulMapper;
    private final Clock clock;
    private final Map<AlarmEvaluationKey, BusAlarmEvaluationState> evaluationStates = new HashMap<>();

    public BusAlarmMonitoringScheduler(
            BusAlarmPollingService pollingService,
            BusAlarmEvaluator evaluator,
            BusAlarmLifecycleService lifecycleService,
            TagoVehicleLocationClient tagoVehicleLocationClient,
            SeoulBusVehicleLocationClient seoulBusVehicleLocationClient,
            SeoulBusVehicleDetailClient seoulBusVehicleDetailClient,
            TagoTransitObservationMapper tagoMapper,
            SeoulBusTransitObservationMapper seoulMapper,
            Clock transitMetadataClock
    ) {
        this.pollingService = pollingService;
        this.evaluator = evaluator;
        this.lifecycleService = lifecycleService;
        this.tagoVehicleLocationClient = tagoVehicleLocationClient;
        this.seoulBusVehicleLocationClient = seoulBusVehicleLocationClient;
        this.seoulBusVehicleDetailClient = seoulBusVehicleDetailClient;
        this.tagoMapper = tagoMapper;
        this.seoulMapper = seoulMapper;
        this.clock = transitMetadataClock;
    }

    @Scheduled(fixedDelayString = "${transit.monitoring.fixed-delay:PT20S}")
    public void pollMonitoringAlarms() {
        List<BusAlarmPollingGroup> groups = pollingService.findMonitoringGroups();
        retainCurrentEvaluationStates(groups);
        for (BusAlarmPollingGroup group : groups) {
            try {
                pollGroup(group);
            } catch (RuntimeException exception) {
                log.warn("Bus Alarm polling group failed: provider={}, route={}",
                        group.key().provider(), group.key().externalRouteId(), exception);
            }
        }
    }

    private void pollGroup(BusAlarmPollingGroup group) {
        List<TransitObservation> observations = switch (group.key().provider()) {
            case TAGO -> pollTago(group);
            case SEOUL_BUS -> pollSeoul(group);
        };
        Instant now = clock.instant();
        for (Alarm alarm : group.alarms()) {
            evaluateAndApply(alarm, observations, now);
        }
    }

    private List<TransitObservation> pollTago(BusAlarmPollingGroup group) {
        VehicleLocationRequest<TagoVehicleLocationRequestContext> request = new VehicleLocationRequest<>(
                group.key().externalRouteId(), new TagoVehicleLocationRequestContext(group.key().cityCode())
        );
        return tagoMapper.map(request, tagoVehicleLocationClient.fetchVehicleLocations(request));
    }

    private List<TransitObservation> pollSeoul(BusAlarmPollingGroup group) {
        VehicleLocationRequest<SeoulBusVehicleLocationRequestContext> request = new VehicleLocationRequest<>(
                group.key().externalRouteId(), new SeoulBusVehicleLocationRequestContext()
        );
        List<SeoulBusVehicleLocationItem> roster = rosterItems(
                seoulBusVehicleLocationClient.fetchVehicleLocations(request)
        );
        Set<String> vehicleIds = selectSeoulDetailVehicleIds(group.alarms(), roster);
        List<TransitObservation> observations = new ArrayList<>();
        for (String vehicleId : vehicleIds) {
            observations.addAll(seoulMapper.map(request, seoulBusVehicleDetailClient.fetchVehicleDetail(vehicleId)));
        }
        return observations;
    }

    private Set<String> selectSeoulDetailVehicleIds(List<Alarm> alarms, List<SeoulBusVehicleLocationItem> roster) {
        Set<String> rosterVehicleIds = new LinkedHashSet<>();
        for (SeoulBusVehicleLocationItem item : roster) {
            if (item.vehId() != null && !item.vehId().isBlank()) {
                rosterVehicleIds.add(item.vehId());
            }
        }

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

    private void evaluateAndApply(Alarm alarm, List<TransitObservation> observations, Instant now) {
        AlarmEvaluationKey key = evaluationKey(alarm);
        BusAlarmEvaluationState currentState = evaluationStates.getOrDefault(key, BusAlarmEvaluationState.initial());
        BusAlarmEvaluationResult result = evaluator.evaluate(alarm, currentState, observations, now);
        if (lifecycleService.applyIfCurrent(key, alarm.getStatus(), result, now)) {
            evaluationStates.put(key, result.nextState());
        }
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

    private static AlarmEvaluationKey evaluationKey(Alarm alarm) {
        return new AlarmEvaluationKey(alarm.getId(), alarm.getActivationGeneration());
    }
}
