package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.client.TransitProviderClientFailureKind;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationResponse;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;
import com.stopbell.transit.mapper.SeoulBusTransitObservationMapper;
import com.stopbell.transit.mapper.TagoTransitObservationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
            clock,
            Duration.ZERO
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
                new TagoTransitObservationMapper(clock), new SeoulBusTransitObservationMapper(clock), clock,
                Duration.ZERO
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

    @ParameterizedTest
    @EnumSource(TransitProviderClientFailureKind.class)
    @DisplayName("TAGO Provider 실패 종류와 무관하게 성공 Route를 먼저 처리하고 실패 Route만 한 번 재시도한다")
    void tago_failure_retries_only_failed_route_after_success(TransitProviderClientFailureKind kind) {
        BusAlarmMonitoringScheduler monitored = spy(scheduler);
        Alarm failedAlarm = tagoActiveAlarm(false);
        Alarm successfulAlarm = tagoActiveAlarm(false);
        when(successfulAlarm.getId()).thenReturn(2L);
        BusAlarmPollingGroup failedGroup = new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(failedAlarm));
        BusAlarmPollingGroup successfulGroup = new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.TAGO, "route-2", "31010"), List.of(successfulAlarm));
        when(successfulAlarm.getBusAlarmTarget().getExternalRouteId()).thenReturn("route-2");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(failedGroup, successfulGroup));
        when(tagoClient.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            com.stopbell.transit.client.VehicleLocationRequest<?> request = invocation.getArgument(0);
            if (request.externalRouteId().equals("route-1") && invocation.getMock() == tagoClient) {
                throw providerFailure(kind);
            }
            return emptyTagoResponse();
        });
        BusAlarmEvaluationResult result = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any())).thenReturn(true);

        monitored.pollMonitoringAlarms();

        verify(evaluator).evaluate(eq(successfulAlarm), any(), eq(Set.of()), eq(List.of()), any());
        verify(evaluator, never()).evaluate(eq(failedAlarm), any(), anySet(), anyList(), any());
        verify(monitored).waitBeforeRetry();
        verify(tagoClient, times(3)).fetchVehicleLocations(any());
    }

    @Test
    @DisplayName("서울 roster 실패는 다른 Route 정상 empty 평가 뒤 해당 Route만 재시도한다")
    void seoul_roster_failure_does_not_block_other_route() {
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        BusAlarmMonitoringScheduler monitored = spy(seoulScheduler(rosterClient, detailClient));
        Alarm failedAlarm = seoulActiveAlarm(1L, "route-1");
        Alarm successfulAlarm = seoulActiveAlarm(2L, "route-2");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(
                seoulGroup(failedAlarm, "route-1"), seoulGroup(successfulAlarm, "route-2")));
        when(rosterClient.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            com.stopbell.transit.client.VehicleLocationRequest<?> request = invocation.getArgument(0);
            if (request.externalRouteId().equals("route-1")) {
                throw providerFailure(TransitProviderClientFailureKind.HTTP);
            }
            return emptySeoulRoster();
        });
        BusAlarmEvaluationResult result = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any())).thenReturn(true);

        monitored.pollMonitoringAlarms();

        verify(evaluator).evaluate(eq(successfulAlarm), any(), eq(Set.of()), eq(List.of()), any());
        verify(evaluator, never()).evaluate(eq(failedAlarm), any(), anySet(), anyList(), any());
        verify(monitored).waitBeforeRetry();
        verify(rosterClient, times(3)).fetchVehicleLocations(any());
        verify(detailClient, never()).fetchVehicleDetail(any());
    }

    @Test
    @DisplayName("서울 detail 부분 실패는 성공 차량을 즉시 적용하고 실패 차량만 재시도한다")
    void seoul_detail_partial_failure_applies_success_before_retry() {
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        BusAlarmMonitoringScheduler monitored = spy(seoulScheduler(rosterClient, detailClient));
        Alarm alarm = seoulActiveAlarm(1L, "route-1");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("A", "B", "C", "D"));
        when(detailClient.fetchVehicleDetail("A")).thenReturn(seoulDetail("A"));
        when(detailClient.fetchVehicleDetail("B"))
                .thenThrow(providerFailure(TransitProviderClientFailureKind.TRANSPORT))
                .thenReturn(seoulDetail("B"));
        when(detailClient.fetchVehicleDetail("C")).thenReturn(seoulDetail("C"));
        when(detailClient.fetchVehicleDetail("D")).thenReturn(seoulDetail("D"));
        BusAlarmEvaluationState firstState = new BusAlarmEvaluationState(true, Map.of(), Map.of(), Map.of());
        BusAlarmEvaluationResult first = new BusAlarmEvaluationResult(List.of(), firstState, false);
        BusAlarmEvaluationResult retried = new BusAlarmEvaluationResult(List.of(), firstState, false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(first);
        when(evaluator.evaluatePartial(any(), any(), anySet(), anyList(), any())).thenReturn(retried);
        when(lifecycleService.applyIfCurrent(any(), any(), any())).thenReturn(true);

        monitored.pollMonitoringAlarms();

        InOrder order = inOrder(detailClient, evaluator, lifecycleService, monitored);
        order.verify(detailClient).fetchVehicleDetail("A");
        order.verify(detailClient).fetchVehicleDetail("B");
        order.verify(detailClient).fetchVehicleDetail("C");
        order.verify(detailClient).fetchVehicleDetail("D");
        order.verify(evaluator).evaluate(eq(alarm), any(), eq(Set.of("A", "B", "C", "D")),
                anyList(), any());
        order.verify(lifecycleService).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(first));
        order.verify(monitored).waitBeforeRetry();
        order.verify(detailClient).fetchVehicleDetail("B");
        order.verify(evaluator).evaluatePartial(eq(alarm), eq(firstState), eq(Set.of("B")), anyList(), any());
        verify(rosterClient).fetchVehicleLocations(any());
        verify(detailClient, times(2)).fetchVehicleDetail("B");
        ArgumentCaptor<List<TransitObservation>> observations = observationCaptor();
        verify(evaluator).evaluate(eq(alarm), any(), anySet(), observations.capture(), any());
        assertThat(observations.getValue()).extracting(TransitObservation::vehicleTrackingId)
                .containsExactly("A", "C", "D");
    }

    @Test
    @DisplayName("TAGO 재시도 성공은 지연 뒤 실제 응답으로 평가한다")
    void tago_retry_success_evaluates_actual_response() {
        BusAlarmMonitoringScheduler monitored = spy(scheduler);
        Alarm alarm = tagoActiveAlarm(false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(alarm))));
        when(tagoClient.fetchVehicleLocations(any()))
                .thenThrow(providerFailure(TransitProviderClientFailureKind.PROTOCOL))
                .thenReturn(emptyTagoResponse());
        BusAlarmEvaluationResult result = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any())).thenReturn(true);

        monitored.pollMonitoringAlarms();

        InOrder order = inOrder(tagoClient, monitored, evaluator);
        order.verify(tagoClient).fetchVehicleLocations(any());
        order.verify(monitored).waitBeforeRetry();
        order.verify(tagoClient).fetchVehicleLocations(any());
        order.verify(evaluator).evaluate(eq(alarm), any(), eq(Set.of()), eq(List.of()), any());
    }

    @Test
    @DisplayName("서울 roster 재시도에서 새로 실패한 detail은 같은 cycle에서 다시 재시도하지 않는다")
    void seoul_roster_retry_does_not_retry_new_detail_failure() {
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        BusAlarmMonitoringScheduler monitored = spy(seoulScheduler(rosterClient, detailClient));
        Alarm alarm = seoulActiveAlarm(1L, "route-1");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any()))
                .thenThrow(providerFailure(TransitProviderClientFailureKind.HTTP))
                .thenReturn(seoulRoster("A", "B"));
        when(detailClient.fetchVehicleDetail("A")).thenReturn(seoulDetail("A"));
        when(detailClient.fetchVehicleDetail("B"))
                .thenThrow(providerFailure(TransitProviderClientFailureKind.TRANSPORT));
        BusAlarmEvaluationResult result = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any())).thenReturn(true);

        monitored.pollMonitoringAlarms();

        verify(monitored).waitBeforeRetry();
        verify(rosterClient, times(2)).fetchVehicleLocations(any());
        verify(detailClient).fetchVehicleDetail("A");
        verify(detailClient).fetchVehicleDetail("B");
        verify(evaluator).evaluate(eq(alarm), any(), eq(Set.of("A", "B")), anyList(), any());
    }

    @Test
    @DisplayName("예상 밖 내부 오류는 Provider 재시도 대상으로 취급하지 않는다")
    void unexpected_error_is_not_provider_retry() {
        BusAlarmMonitoringScheduler monitored = spy(scheduler);
        Alarm alarm = tagoActiveAlarm(false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(alarm))));
        when(tagoClient.fetchVehicleLocations(any())).thenThrow(new IllegalStateException("invalid internal state"));

        monitored.pollMonitoringAlarms();

        verify(monitored, never()).waitBeforeRetry();
        verify(evaluator, never()).evaluate(any(), any(), anySet(), anyList(), any());
    }

    @Test
    @DisplayName("detail 재시도 결과가 stale이면 첫 평가 state를 덮어쓰지 않는다")
    void stale_detail_retry_keeps_first_applied_state() {
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        BusAlarmMonitoringScheduler monitored = seoulScheduler(rosterClient, detailClient);
        Alarm alarm = seoulActiveAlarm(1L, "route-1");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("B"), emptySeoulRoster());
        when(detailClient.fetchVehicleDetail("B"))
                .thenThrow(providerFailure(TransitProviderClientFailureKind.TRANSPORT))
                .thenReturn(seoulDetail("B"));
        BusAlarmEvaluationState firstState = new BusAlarmEvaluationState(true, Map.of(), Map.of(), Map.of());
        BusAlarmEvaluationResult first = new BusAlarmEvaluationResult(List.of(), firstState, false);
        BusAlarmEvaluationResult stale = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(first, first);
        when(evaluator.evaluatePartial(any(), any(), anySet(), anyList(), any())).thenReturn(stale);
        when(lifecycleService.applyIfCurrent(any(), any(), any())).thenReturn(true, false, true);

        monitored.pollMonitoringAlarms();
        monitored.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationState> states = ArgumentCaptor.forClass(BusAlarmEvaluationState.class);
        verify(evaluator, times(2)).evaluate(eq(alarm), states.capture(), anySet(), anyList(), any());
        assertThat(states.getAllValues().get(1)).isEqualTo(firstState);
        verify(evaluator).evaluatePartial(eq(alarm), eq(firstState), eq(Set.of("B")), anyList(), any());
        verify(lifecycleService).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(stale));
    }

    @Test
    @DisplayName("여러 Route 실패도 첫 요청을 모두 마친 뒤 한 번만 지연한다")
    void multiple_failed_routes_share_one_retry_delay() {
        BusAlarmMonitoringScheduler monitored = spy(scheduler);
        Alarm first = tagoActiveAlarm(false);
        Alarm second = tagoActiveAlarm(false);
        when(second.getId()).thenReturn(2L);
        when(second.getBusAlarmTarget().getExternalRouteId()).thenReturn("route-2");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(
                new BusAlarmPollingGroup(new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(first)),
                new BusAlarmPollingGroup(new BusPollingKey(TransitProvider.TAGO, "route-2", "31010"), List.of(second))));
        when(tagoClient.fetchVehicleLocations(any()))
                .thenThrow(providerFailure(TransitProviderClientFailureKind.HTTP));

        monitored.pollMonitoringAlarms();

        InOrder order = inOrder(tagoClient, monitored);
        order.verify(tagoClient, times(2)).fetchVehicleLocations(any());
        order.verify(monitored).waitBeforeRetry();
        order.verify(tagoClient, times(2)).fetchVehicleLocations(any());
        verify(monitored).waitBeforeRetry();
        verify(evaluator, never()).evaluate(any(), any(), anySet(), anyList(), any());
    }

    @Test
    @DisplayName("detail 재시도 정상 empty는 위치 평가를 추가하지 않는다")
    void retried_detail_normal_empty_keeps_first_presence_only() {
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        BusAlarmMonitoringScheduler monitored = seoulScheduler(rosterClient, detailClient);
        Alarm alarm = seoulActiveAlarm(1L, "route-1");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("B"));
        when(detailClient.fetchVehicleDetail("B"))
                .thenThrow(providerFailure(TransitProviderClientFailureKind.HTTP))
                .thenReturn(new SeoulBusVehicleDetailResponse(
                        new SeoulBusVehicleDetailResponse.Header("4", "empty"), null));
        BusAlarmEvaluationResult result = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any())).thenReturn(true);

        monitored.pollMonitoringAlarms();

        verify(evaluator).evaluate(eq(alarm), any(), eq(Set.of("B")), eq(List.of()), any());
        verify(evaluator, never()).evaluatePartial(any(), any(), anySet(), anyList(), any());
        verify(detailClient, times(2)).fetchVehicleDetail("B");
    }

    @Test
    @DisplayName("평가 중 내부 오류가 나면 해당 group의 수집된 detail 실패도 재시도하지 않는다")
    void evaluator_error_discards_group_retry_targets() {
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        BusAlarmMonitoringScheduler monitored = spy(seoulScheduler(rosterClient, detailClient));
        Alarm alarm = seoulActiveAlarm(1L, "route-1");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("B"));
        when(detailClient.fetchVehicleDetail("B"))
                .thenThrow(providerFailure(TransitProviderClientFailureKind.PROTOCOL));
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any()))
                .thenThrow(new IllegalStateException("invalid evaluation state"));

        monitored.pollMonitoringAlarms();

        verify(detailClient).fetchVehicleDetail("B");
        verify(monitored, never()).waitBeforeRetry();
    }

    private BusAlarmMonitoringScheduler seoulScheduler(SeoulBusVehicleLocationClient rosterClient,
            SeoulBusVehicleDetailClient detailClient) {
        return new BusAlarmMonitoringScheduler(pollingService, evaluator, lifecycleService, tagoClient,
                rosterClient, detailClient, new TagoTransitObservationMapper(clock),
                new SeoulBusTransitObservationMapper(clock), clock, Duration.ZERO);
    }

    private static Alarm seoulActiveAlarm(long id, String routeId) {
        Alarm alarm = mock(Alarm.class);
        BusAlarmTarget target = mock(BusAlarmTarget.class);
        when(alarm.getId()).thenReturn(id);
        when(alarm.getActivationGeneration()).thenReturn(1L);
        when(alarm.getStatus()).thenReturn(AlarmStatus.ACTIVE);
        when(alarm.getBusAlarmTarget()).thenReturn(target);
        when(target.getProvider()).thenReturn(TransitProvider.SEOUL_BUS);
        when(target.getExternalRouteId()).thenReturn(routeId);
        return alarm;
    }

    private static BusAlarmPollingGroup seoulGroup(Alarm alarm, String routeId) {
        return new BusAlarmPollingGroup(new BusPollingKey(TransitProvider.SEOUL_BUS, routeId, null), List.of(alarm));
    }

    private static SeoulBusVehicleLocationResponse emptySeoulRoster() {
        return seoulRoster();
    }

    private static SeoulBusVehicleLocationResponse seoulRoster(String... vehicleIds) {
        return new SeoulBusVehicleLocationResponse(new SeoulBusVehicleLocationResponse.Header("0", "OK"),
                new SeoulBusVehicleLocationResponse.Body(java.util.Arrays.stream(vehicleIds)
                        .map(id -> new SeoulBusVehicleLocationItem(id, null, null, null, null, null,
                                null, null, null, null, null)).toList()));
    }

    private static SeoulBusVehicleDetailResponse seoulDetail(String vehicleId) {
        return new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("0", "OK"),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem(
                        vehicleId, null, "stop-1", 1, 0, null, null, null))));
    }

    private static TransitProviderClientException providerFailure(TransitProviderClientFailureKind kind) {
        return switch (kind) {
            case TRANSPORT -> TransitProviderClientException.transport(TransitProvider.TAGO, "test", null);
            case HTTP -> TransitProviderClientException.http(TransitProvider.TAGO, "test", 503, null);
            case PROVIDER -> TransitProviderClientException.provider(TransitProvider.TAGO, "test", "error");
            case PROTOCOL -> TransitProviderClientException.protocol(TransitProvider.TAGO, "test", null);
        };
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
