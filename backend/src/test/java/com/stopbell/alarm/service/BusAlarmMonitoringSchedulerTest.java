package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationResponse;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;
import com.stopbell.transit.mapper.SeoulBusTransitObservationMapper;
import com.stopbell.transit.mapper.TagoTransitObservationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BusAlarmMonitoringSchedulerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
    private final BusAlarmPollingService pollingService = mock(BusAlarmPollingService.class);
    private final BusAlarmEvaluator evaluator = mock(BusAlarmEvaluator.class);
    private final BusAlarmLifecycleService lifecycleService = mock(BusAlarmLifecycleService.class);
    private final TagoVehicleLocationClient tagoClient = mock(TagoVehicleLocationClient.class);

    private final BusAlarmMonitoringScheduler scheduler = new BusAlarmMonitoringScheduler(
            pollingService,
            evaluator,
            lifecycleService,
            tagoClient,
            mock(SeoulBusVehicleLocationClient.class),
            mock(SeoulBusVehicleDetailClient.class),
            new TagoTransitObservationMapper(clock),
            new SeoulBusTransitObservationMapper(clock),
            clock
    );

    @Test
    @DisplayName("현재 lifecycle에 적용된 결과만 다음 polling cycle의 memory state로 사용한다")
    void retains_state_only_after_current_lifecycle_verification() {
        Alarm alarm = mock(Alarm.class);
        BusAlarmTarget target = mock(BusAlarmTarget.class);
        when(alarm.getId()).thenReturn(1L);
        when(alarm.getActivationGeneration()).thenReturn(3L);
        when(alarm.getStatus()).thenReturn(AlarmStatus.ACTIVE);
        when(alarm.getBusAlarmTarget()).thenReturn(target);
        when(target.getProvider()).thenReturn(TransitProvider.TAGO);
        when(target.getExternalRouteId()).thenReturn("route-1");
        when(target.getCityCode()).thenReturn("31010");

        BusAlarmPollingGroup group = new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(alarm)
        );
        BusAlarmEvaluationState nextState = new BusAlarmEvaluationState(true, java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        BusAlarmEvaluationResult result = new BusAlarmEvaluationResult(List.of(), nextState, false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(group));
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(emptyTagoResponse());
        when(evaluator.evaluate(eq(alarm), any(BusAlarmEvaluationState.class), anySet(), anyList(), any(Instant.class)))
                .thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(result)))
                .thenReturn(true);

        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationState> states = ArgumentCaptor.forClass(BusAlarmEvaluationState.class);
        verify(evaluator, times(2)).evaluate(eq(alarm), states.capture(), anySet(), anyList(), any(Instant.class));
        assertThat(states.getAllValues().getFirst().baselineEstablished()).isFalse();
        assertThat(states.getAllValues().get(1).baselineEstablished()).isTrue();
        verify(tagoClient, times(2)).fetchVehicleLocations(any());
    }

    @Test
    @DisplayName("서울 roster vehicle은 detail 정상 empty여도 evaluator의 present vehicle로 전달한다")
    void forwards_seoul_roster_presence_separately_from_empty_detail_observations() {
        SeoulBusVehicleLocationClient seoulLocationClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient seoulDetailClient = mock(SeoulBusVehicleDetailClient.class);
        BusAlarmMonitoringScheduler seoulScheduler = new BusAlarmMonitoringScheduler(
                pollingService, evaluator, lifecycleService, tagoClient, seoulLocationClient, seoulDetailClient,
                new TagoTransitObservationMapper(clock), new SeoulBusTransitObservationMapper(clock), clock
        );
        Alarm alarm = mock(Alarm.class);
        BusAlarmTarget target = mock(BusAlarmTarget.class);
        when(alarm.getId()).thenReturn(1L);
        when(alarm.getActivationGeneration()).thenReturn(3L);
        when(alarm.getStatus()).thenReturn(AlarmStatus.ACTIVE);
        when(alarm.getBusAlarmTarget()).thenReturn(target);
        when(target.getProvider()).thenReturn(TransitProvider.SEOUL_BUS);
        when(target.getExternalRouteId()).thenReturn("route-1");
        BusAlarmPollingGroup group = new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.SEOUL_BUS, "route-1", null), List.of(alarm)
        );
        BusAlarmEvaluationResult result = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(group));
        when(seoulLocationClient.fetchVehicleLocations(any())).thenReturn(new SeoulBusVehicleLocationResponse(
                new SeoulBusVehicleLocationResponse.Header("0", "OK"),
                new SeoulBusVehicleLocationResponse.Body(List.of(new SeoulBusVehicleLocationItem(
                        "vehicle-1", null, null, null, null, null, null, null, null, null, null
                )))
        ));
        when(seoulDetailClient.fetchVehicleDetail("vehicle-1")).thenReturn(new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("4", "empty"), null
        ));
        when(evaluator.evaluate(eq(alarm), any(BusAlarmEvaluationState.class), anySet(), anyList(), any(Instant.class)))
                .thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(result))).thenReturn(true);

        seoulScheduler.pollMonitoringAlarms();

        ArgumentCaptor<Set<String>> presentVehicleIds = setCaptor();
        ArgumentCaptor<List<TransitObservation>> observations = observationCaptor();
        verify(evaluator).evaluate(eq(alarm), any(BusAlarmEvaluationState.class), presentVehicleIds.capture(),
                observations.capture(), any(Instant.class));
        assertThat(presentVehicleIds.getValue()).containsExactly("vehicle-1");
        assertThat(observations.getValue()).isEmpty();
    }

    @Test
    @DisplayName("current ARRIVED after-on 결과만 같은 cycle ID의 FOLLOW_UP memory state로 전환한다")
    void moves_only_selected_arrived_vehicle_to_follow_up_after_current_apply() {
        Alarm alarm = tagoActiveAlarm(true);
        BusAlarmPollingGroup group = new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(alarm)
        );
        TransitObservation observation = observation("vehicle-a");
        VehicleTrackingState tracking = VehicleTrackingState.beginBeforeTarget(observation, clock.instant())
                .emit(TransitEventType.ARRIVED, observation, clock.instant());
        TransitEvent event = new TransitEvent(TransitEventType.ARRIVED, "vehicle-a", tracking.trackingCycleId(),
                clock.instant(), "target", null, 20, null, null, null, null);
        BusAlarmEvaluationResult arrival = new BusAlarmEvaluationResult(List.of(event), new BusAlarmEvaluationState(
                true, Map.of("vehicle-a", tracking, "vehicle-b", VehicleTrackingState.begin(observation("vehicle-b"), clock.instant())),
                Map.of("baseline-after", clock.instant()), Map.of()
        ), false);
        BusAlarmEvaluationResult noEvent = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(group));
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(emptyTagoResponse());
        when(evaluator.evaluate(eq(alarm), any(BusAlarmEvaluationState.class), anySet(), anyList(), any(Instant.class)))
                .thenReturn(arrival, noEvent);
        when(lifecycleService.applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), any(BusAlarmEvaluationResult.class))).thenReturn(true);

        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationState> states = ArgumentCaptor.forClass(BusAlarmEvaluationState.class);
        verify(evaluator, times(2)).evaluate(eq(alarm), states.capture(), anySet(), anyList(), any(Instant.class));
        BusAlarmEvaluationState followUpState = states.getAllValues().get(1);
        assertThat(followUpState.trackedVehicles()).isEmpty();
        assertThat(followUpState.baselineAfterVehicles()).isEmpty();
        assertThat(followUpState.followUpVehicles()).containsOnlyKeys("vehicle-a");
        assertThat(followUpState.followUpVehicles().get("vehicle-a").trackingCycleId())
                .isEqualTo(tracking.trackingCycleId());
    }

    @Test
    @DisplayName("stale ARRIVED 결과는 FOLLOW_UP memory state로 전환하지 않는다")
    void does_not_create_follow_up_memory_state_for_stale_arrival() {
        Alarm alarm = tagoActiveAlarm(true);
        BusAlarmPollingGroup group = new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(alarm)
        );
        TransitObservation observation = observation("vehicle-a");
        VehicleTrackingState tracking = VehicleTrackingState.begin(observation, clock.instant());
        TransitEvent event = new TransitEvent(TransitEventType.ARRIVED, "vehicle-a", tracking.trackingCycleId(),
                clock.instant(), "target", null, 20, null, null, null, null);
        BusAlarmEvaluationResult arrival = new BusAlarmEvaluationResult(List.of(event), new BusAlarmEvaluationState(
                true, Map.of("vehicle-a", tracking), Map.of(), Map.of()
        ), false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(group));
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(emptyTagoResponse());
        when(evaluator.evaluate(eq(alarm), any(BusAlarmEvaluationState.class), anySet(), anyList(), any(Instant.class)))
                .thenReturn(arrival);
        when(lifecycleService.applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), any(BusAlarmEvaluationResult.class))).thenReturn(false);

        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationState> states = ArgumentCaptor.forClass(BusAlarmEvaluationState.class);
        verify(evaluator, times(2)).evaluate(eq(alarm), states.capture(), anySet(), anyList(), any(Instant.class));
        assertThat(states.getAllValues().get(1)).isEqualTo(BusAlarmEvaluationState.initial());
    }

    private static Alarm tagoActiveAlarm(boolean notifyOneStopAfter) {
        Alarm alarm = mock(Alarm.class);
        BusAlarmTarget target = mock(BusAlarmTarget.class);
        when(alarm.getId()).thenReturn(1L);
        when(alarm.getActivationGeneration()).thenReturn(3L);
        when(alarm.getStatus()).thenReturn(AlarmStatus.ACTIVE);
        when(alarm.getBusAlarmTarget()).thenReturn(target);
        when(target.getProvider()).thenReturn(TransitProvider.TAGO);
        when(target.getExternalRouteId()).thenReturn("route-1");
        when(target.getCityCode()).thenReturn("31010");
        when(target.isNotifyOneStopAfter()).thenReturn(notifyOneStopAfter);
        return alarm;
    }

    private TransitObservation observation(String vehicleId) {
        return new TransitObservation(TransitProvider.TAGO, "route-1", vehicleId, "target", 20, null, null, null,
                new BigDecimal("37.0000000"), new BigDecimal("127.0000000"), clock.instant(), null,
                ArrivalEvidence.UNAVAILABLE);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Set<String>> setCaptor() {
        return (ArgumentCaptor<Set<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Set.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<TransitObservation>> observationCaptor() {
        return (ArgumentCaptor<List<TransitObservation>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
    }

    private static TagoVehicleLocationResponse emptyTagoResponse() {
        return new TagoVehicleLocationResponse(new TagoVehicleLocationResponse.Response(
                new TagoVehicleLocationResponse.Header("00", "NORMAL SERVICE."),
                new TagoVehicleLocationResponse.Body(null, 0, 1, 10)
        ));
    }
}
