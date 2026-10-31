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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BusAlarmMonitoringSchedulerTest {

    private static org.mockito.stubbing.Answer<BusAlarmDecisionResult> applied() {
        return invocation -> {
            AlarmEvaluationKey key = invocation.getArgument(0);
            AlarmStatus expected = invocation.getArgument(1);
            BusAlarmEvaluationResult result = invocation.getArgument(2);
            TransitEvent event = result.eventCandidates().stream().findFirst().orElse(null);
            boolean arrival = event != null && event.type() == TransitEventType.ARRIVED;
            return new BusAlarmDecisionResult(BusAlarmDecisionResult.Outcome.COMMITTED,
                    key.activationGeneration(), arrival ? AlarmStatus.FOLLOW_UP : expected,
                    arrival ? event.vehicleTrackingId() : null, arrival ? event.trackingCycleId() : null);
        };
    }

    private static BusAlarmDecisionResult stale() {
        return new BusAlarmDecisionResult(BusAlarmDecisionResult.Outcome.STALE, -1, null, null, null);
    }

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
            Duration.ZERO, mock(com.stopbell.boarding.service.BoardingSessionPollingService.class)
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
        when(lifecycleService.applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(result), any(Instant.class)))
                .thenAnswer(applied());

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
                Duration.ZERO, mock(com.stopbell.boarding.service.BoardingSessionPollingService.class)
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
        when(lifecycleService.applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(result), any(Instant.class))).thenAnswer(applied());

        seoulScheduler.pollMonitoringAlarms();

        ArgumentCaptor<Set<String>> presentVehicleIds = setCaptor();
        ArgumentCaptor<List<TransitObservation>> observations = observationCaptor();
        verify(evaluator).evaluate(eq(alarm), any(BusAlarmEvaluationState.class), presentVehicleIds.capture(),
                observations.capture(), any(Instant.class));
        assertThat(presentVehicleIds.getValue()).containsExactly("vehicle-1");
        assertThat(observations.getValue()).isEmpty();
        verify(seoulDetailClient).fetchVehicleDetail("vehicle-1");
        verify(evaluator, never()).evaluatePartial(any(), any(), anySet(), anyList(), any());
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
        when(lifecycleService.applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), any(BusAlarmEvaluationResult.class), any(Instant.class))).thenAnswer(applied());

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
        when(lifecycleService.applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), any(BusAlarmEvaluationResult.class), any(Instant.class))).thenReturn(stale());

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
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any(Instant.class))).thenAnswer(applied());

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
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any(Instant.class))).thenAnswer(applied());

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
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any(Instant.class))).thenAnswer(applied());

        monitored.pollMonitoringAlarms();

        InOrder order = inOrder(detailClient, evaluator, lifecycleService, monitored);
        order.verify(detailClient).fetchVehicleDetail("A");
        order.verify(detailClient).fetchVehicleDetail("B");
        order.verify(detailClient).fetchVehicleDetail("C");
        order.verify(detailClient).fetchVehicleDetail("D");
        order.verify(evaluator).evaluate(eq(alarm), any(), eq(Set.of("A", "B", "C", "D")),
                anyList(), any());
        order.verify(lifecycleService).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(first), any(Instant.class));
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
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any(Instant.class))).thenAnswer(applied());

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
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any(Instant.class))).thenAnswer(applied());

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpected_polling_failures_do_not_log_exception_details(boolean onRetry) {
        Alarm alarm = tagoActiveAlarm(false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(new BusAlarmPollingGroup(
                new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(alarm))));
        var failure = new IllegalStateException("secret-marker", new RuntimeException("cause-marker"));
        if (onRetry) {
            when(tagoClient.fetchVehicleLocations(any()))
                    .thenThrow(providerFailure(TransitProviderClientFailureKind.HTTP)).thenThrow(failure);
        } else {
            when(tagoClient.fetchVehicleLocations(any())).thenThrow(failure);
        }
        Logger logger = (Logger) LoggerFactory.getLogger(BusAlarmMonitoringScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler.pollMonitoringAlarms();
            var event = appender.list.stream()
                    .filter(item -> item.getFormattedMessage().contains(onRetry
                            ? "polling retry failed" : "polling group failed"))
                    .findFirst().orElseThrow();
            assertThat(event.getFormattedMessage()).contains("failureType=IllegalStateException")
                    .doesNotContain("secret-marker", "cause-marker", "route-1");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
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
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any(Instant.class))).thenAnswer(applied()).thenReturn(stale()).thenAnswer(applied());

        monitored.pollMonitoringAlarms();
        monitored.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationState> states = ArgumentCaptor.forClass(BusAlarmEvaluationState.class);
        verify(evaluator, times(2)).evaluate(eq(alarm), states.capture(), anySet(), anyList(), any());
        assertThat(states.getAllValues().get(1)).isEqualTo(firstState);
        verify(evaluator).evaluatePartial(eq(alarm), eq(firstState), eq(Set.of("B")), anyList(), any());
        verify(lifecycleService).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(stale), any(Instant.class));
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
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any(Instant.class))).thenAnswer(applied());

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

    @Test
    @DisplayName("DB 재시도와 commit 응답 유실은 원래 candidate·UUID·detectedAt을 유지하고 안전한 duplicate 뒤에만 memory를 반영한다")
    void replays_original_decision_until_commit_is_confirmed() {
        Alarm alarm = tagoActiveAlarm(true);
        BusAlarmPollingGroup group = new BusAlarmPollingGroup(new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(alarm));
        VehicleTrackingState tracking = VehicleTrackingState.begin(observation("vehicle-a"), clock.instant());
        TransitEvent event = new TransitEvent(TransitEventType.ARRIVED, "vehicle-a", tracking.trackingCycleId(),
                clock.instant(), "target", null, 20, null, null, null, null);
        BusAlarmEvaluationResult arrival = new BusAlarmEvaluationResult(List.of(event), new BusAlarmEvaluationState(
                true, Map.of("vehicle-a", tracking), Map.of(), Map.of()), false);
        BusAlarmEvaluationResult empty = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(group));
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(emptyTagoResponse());
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(arrival, empty);
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any()))
                .thenReturn(new BusAlarmDecisionResult(BusAlarmDecisionResult.Outcome.RETRY_REQUIRED, -1, null, null, null))
                .thenThrow(new org.springframework.transaction.TransactionSystemException("commit acknowledgement lost"))
                .thenReturn(new BusAlarmDecisionResult(BusAlarmDecisionResult.Outcome.DUPLICATE, 3, AlarmStatus.FOLLOW_UP,
                        "vehicle-a", tracking.trackingCycleId()))
                .thenAnswer(applied());
        scheduler.pollMonitoringAlarms();
        when(alarm.getStatus()).thenReturn(AlarmStatus.FOLLOW_UP);
        scheduler.pollMonitoringAlarms();
        verify(evaluator, times(1)).evaluate(any(), any(), anySet(), anyList(), any());
        scheduler.pollMonitoringAlarms();
        ArgumentCaptor<BusAlarmEvaluationState> states = ArgumentCaptor.forClass(BusAlarmEvaluationState.class);
        verify(evaluator, times(2)).evaluate(any(), states.capture(), anySet(), anyList(), any());
        assertThat(states.getAllValues().get(1).followUpVehicles().get("vehicle-a").trackingCycleId())
                .isEqualTo(tracking.trackingCycleId());
        ArgumentCaptor<BusAlarmEvaluationResult> results = ArgumentCaptor.forClass(BusAlarmEvaluationResult.class);
        verify(lifecycleService, times(3)).applyIfCurrent(eq(new AlarmEvaluationKey(1L, 3)), eq(AlarmStatus.ACTIVE),
                results.capture(), eq(clock.instant()));
        assertThat(results.getAllValues().get(0)).isSameAs(results.getAllValues().get(1)).isSameAs(results.getAllValues().get(2));
        assertThat(results.getValue().eventCandidates()).containsExactly(event);
    }

    @Test
    @DisplayName("commit 응답 유실 뒤 Alarm이 monitoring에서 사라져도 원래 ARRIVED를 duplicate로 확인한다")
    void reconciles_pending_decision_before_loading_monitoring_groups() {
        Alarm alarm = tagoActiveAlarm(false);
        BusAlarmPollingGroup group = new BusAlarmPollingGroup(new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(alarm));
        TransitEvent event = new TransitEvent(TransitEventType.ARRIVED, "vehicle-a", java.util.UUID.randomUUID(),
                clock.instant(), "target", null, 20, null, null, null, null);
        BusAlarmEvaluationResult result = new BusAlarmEvaluationResult(List.of(event), BusAlarmEvaluationState.initial(), false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(group), List.of());
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(emptyTagoResponse());
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any()))
                .thenThrow(new org.springframework.transaction.TransactionSystemException("commit acknowledgement lost"))
                .thenReturn(new BusAlarmDecisionResult(BusAlarmDecisionResult.Outcome.DUPLICATE, 3, AlarmStatus.INACTIVE, null, null));
        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();
        verify(lifecycleService, times(2)).applyIfCurrent(eq(new AlarmEvaluationKey(1L, 3)), eq(AlarmStatus.ACTIVE), any(), eq(clock.instant()));
        verify(evaluator, times(1)).evaluate(any(), any(), anySet(), anyList(), any());
    }

    @Test
    @DisplayName("duplicate라도 current FOLLOW_UP cycle 또는 generation이 다르면 오래된 memory를 반영하지 않는다")
    void refuses_memory_for_incompatible_duplicate() {
        Alarm alarm = tagoActiveAlarm(true);
        BusAlarmPollingGroup group = new BusAlarmPollingGroup(new BusPollingKey(TransitProvider.TAGO, "route-1", "31010"), List.of(alarm));
        VehicleTrackingState tracking = VehicleTrackingState.begin(observation("vehicle-a"), clock.instant());
        TransitEvent event = new TransitEvent(TransitEventType.ARRIVED, "vehicle-a", tracking.trackingCycleId(),
                clock.instant(), "target", null, 20, null, null, null, null);
        BusAlarmEvaluationResult result = new BusAlarmEvaluationResult(List.of(event), new BusAlarmEvaluationState(
                true, Map.of("vehicle-a", tracking), Map.of(), Map.of()), false);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(group));
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(emptyTagoResponse());
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any()))
                .thenReturn(new BusAlarmDecisionResult(BusAlarmDecisionResult.Outcome.DUPLICATE, 3, AlarmStatus.FOLLOW_UP,
                        "vehicle-a", java.util.UUID.randomUUID()))
                .thenReturn(new BusAlarmDecisionResult(BusAlarmDecisionResult.Outcome.DUPLICATE, 4, AlarmStatus.ACTIVE, null, null));
        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();
        ArgumentCaptor<BusAlarmEvaluationState> states = ArgumentCaptor.forClass(BusAlarmEvaluationState.class);
        verify(evaluator, times(3)).evaluate(any(), states.capture(), anySet(), anyList(), any());
        assertThat(states.getAllValues()).containsOnly(BusAlarmEvaluationState.initial());
    }

    @Test
    @DisplayName("여러 Route에 겹친 서울 차량의 detail 실패와 한 번 재시도도 고유 차량 ID로 중복 제거한다")
    void shares_detail_failure_and_retry_by_vehicle_id() {
        var rosterClient = mock(SeoulBusVehicleLocationClient.class);
        var detailClient = mock(SeoulBusVehicleDetailClient.class);
        var first = seoulActiveAlarm(1L, "route-1");
        var second = seoulActiveAlarm(2L, "route-2");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(first, "route-1"), seoulGroup(second, "route-2")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("same-vehicle"));
        when(detailClient.fetchVehicleDetail("same-vehicle"))
                .thenThrow(TransitProviderClientException.transport(TransitProvider.SEOUL_BUS, "detail", null))
                .thenReturn(seoulDetail("same-vehicle"));
        var result = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(evaluator.evaluatePartial(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any())).thenAnswer(applied());

        seoulScheduler(rosterClient, detailClient).pollMonitoringAlarms();

        verify(rosterClient, times(2)).fetchVehicleLocations(any());
        verify(detailClient, times(2)).fetchVehicleDetail("same-vehicle");
        verify(evaluator, times(2)).evaluate(any(), any(), eq(Set.of("same-vehicle")), eq(List.of()), any());
        verify(evaluator, times(2)).evaluatePartial(any(), any(), anySet(), anyList(), any());
    }

    @Test
    @DisplayName("실제 평가기는 서울 detail 실패 후 오래된 재시도에서 이벤트를 차단하고 다음 정상 관측으로 같은 cycle의 도착을 판단한다")
    void rejects_old_detail_retry_and_recovers_with_real_evaluator() {
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        BusAlarmEvaluator realEvaluator = new BusAlarmEvaluator(mock(BusRouteTraversalService.class));
        BusAlarmMonitoringScheduler monitored = new BusAlarmMonitoringScheduler(
                pollingService, realEvaluator, lifecycleService, tagoClient, rosterClient, detailClient,
                new TagoTransitObservationMapper(clock), new SeoulBusTransitObservationMapper(clock), clock,
                Duration.ZERO, mock(com.stopbell.boarding.service.BoardingSessionPollingService.class));
        Alarm alarm = seoulActiveAlarm(1L, "route-1");
        when(alarm.getBusAlarmTarget().getExternalStopId()).thenReturn("target");
        when(alarm.getBusAlarmTarget().getTargetStopOrder()).thenReturn(20);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("vehicle"));
        SeoulBusVehicleDetailResponse before = new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", "OK"),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem(
                        "vehicle", null, "predecessor", 10, 0, "20260923085940", null, null))));
        SeoulBusVehicleDetailResponse olderArrival = new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", "OK"),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem(
                        "vehicle", null, "target", 20, 1, "20260923085935", null, null))));
        SeoulBusVehicleDetailResponse validArrival = new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", "OK"),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem(
                        "vehicle", null, "target", 20, 1, "20260923085950", null, null))));
        when(detailClient.fetchVehicleDetail("vehicle")).thenReturn(before)
                .thenThrow(providerFailure(TransitProviderClientFailureKind.TRANSPORT))
                .thenReturn(olderArrival, validArrival);
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any(Instant.class))).thenAnswer(applied());

        monitored.pollMonitoringAlarms();
        monitored.pollMonitoringAlarms();
        monitored.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationResult> results = ArgumentCaptor.forClass(BusAlarmEvaluationResult.class);
        verify(lifecycleService, times(4)).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), results.capture(), any());
        List<BusAlarmEvaluationResult> appliedResults = results.getAllValues();
        VehicleTrackingState original = appliedResults.getFirst().nextState().trackedVehicles().get("vehicle");
        assertThat(appliedResults.subList(0, 3)).allSatisfy(result -> {
            assertThat(result.eventCandidates()).isEmpty();
            assertThat(result.nextState().trackedVehicles().get("vehicle")).isEqualTo(original);
        });
        assertThat(appliedResults.getLast().eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TransitEventType.ARRIVED);
            assertThat(event.trackingCycleId()).isEqualTo(original.trackingCycleId());
            assertThat(event.providerDataTime()).isEqualTo(clock.instant().minusSeconds(10));
        });
        verify(detailClient, times(4)).fetchVehicleDetail("vehicle");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "B", "C"})
    @DisplayName("서울 첫째·중간·마지막 차량의 날짜 오류는 정상 차량 평가를 보존하고 실패 차량만 재시도한다")
    void seoul_mapping_failure_isolates_vehicle(String failedVehicle) {
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        BusAlarmMonitoringScheduler monitored = spy(seoulScheduler(rosterClient, detailClient));
        Alarm alarm = seoulActiveAlarm(1L, "route-1");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("A", "B", "C"));
        for (String vehicle : List.of("A", "B", "C")) {
            if (vehicle.equals(failedVehicle)) {
                when(detailClient.fetchVehicleDetail(vehicle))
                        .thenReturn(timedSeoulDetail(vehicle, "invalid", "target", 20, 1), seoulDetail(vehicle));
            } else {
                when(detailClient.fetchVehicleDetail(vehicle)).thenReturn(seoulDetail(vehicle));
            }
        }
        BusAlarmEvaluationState firstState = new BusAlarmEvaluationState(true, Map.of(), Map.of(), Map.of());
        var result = new BusAlarmEvaluationResult(List.of(), firstState, false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(evaluator.evaluatePartial(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any())).thenAnswer(applied());

        monitored.pollMonitoringAlarms();

        ArgumentCaptor<List<TransitObservation>> observations = observationCaptor();
        InOrder order = inOrder(evaluator, lifecycleService, monitored);
        order.verify(evaluator).evaluate(eq(alarm), any(), eq(Set.of("A", "B", "C")),
                observations.capture(), any());
        assertThat(observations.getValue()).extracting(TransitObservation::vehicleTrackingId)
                .containsExactlyElementsOf(List.of("A", "B", "C").stream()
                        .filter(vehicle -> !vehicle.equals(failedVehicle)).toList());
        order.verify(lifecycleService).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(result), any());
        order.verify(monitored).waitBeforeRetry();
        order.verify(evaluator).evaluatePartial(eq(alarm), eq(firstState), eq(Set.of(failedVehicle)),
                observations.capture(), any());
        assertThat(observations.getValue()).extracting(TransitObservation::vehicleTrackingId)
                .containsExactly(failedVehicle);
        verify(rosterClient).fetchVehicleLocations(any());
        for (String vehicle : List.of("A", "B", "C")) {
            verify(detailClient, times(vehicle.equals(failedVehicle) ? 2 : 1)).fetchVehicleDetail(vehicle);
        }
        verify(monitored).waitBeforeRetry();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-date", "mismatched-id", "missing-id", "null-item"})
    @DisplayName("상세 응답 일부 item의 오류도 해당 차량 전체를 거부하고 다른 차량 관측은 보존한다")
    void seoul_rejects_entire_invalid_detail(String invalidKind) {
        var rosterClient = mock(SeoulBusVehicleLocationClient.class);
        var detailClient = mock(SeoulBusVehicleDetailClient.class);
        var monitored = spy(seoulScheduler(rosterClient, detailClient));
        Alarm alarm = seoulActiveAlarm(1L, "route-1");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("A", "B", "C"));
        when(detailClient.fetchVehicleDetail("A")).thenReturn(seoulDetail("A"));
        when(detailClient.fetchVehicleDetail("C")).thenReturn(seoulDetail("C"));
        SeoulBusVehicleDetailItem invalid = switch (invalidKind) {
            case "invalid-date" -> new SeoulBusVehicleDetailItem("B", null, "target", 20, 1,
                    "20260230090000", null, null);
            case "mismatched-id" -> new SeoulBusVehicleDetailItem("A", null, "target", 20, 1, null, null, null);
            case "missing-id" -> new SeoulBusVehicleDetailItem(null, null, "target", 20, 1, null, null, null);
            case "null-item" -> null;
            default -> throw new IllegalArgumentException(invalidKind);
        };
        var response = new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("0", "OK"),
                new SeoulBusVehicleDetailResponse.Body(java.util.Arrays.asList(
                        seoulDetail("B").body().itemList().getFirst(), invalid)));
        when(detailClient.fetchVehicleDetail("B")).thenReturn(response);
        var result = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any())).thenAnswer(applied());

        monitored.pollMonitoringAlarms();

        var observations = observationCaptor();
        verify(evaluator).evaluate(eq(alarm), any(), eq(Set.of("A", "B", "C")), observations.capture(), any());
        assertThat(observations.getValue()).extracting(TransitObservation::vehicleTrackingId)
                .containsExactly("A", "C");
        verify(evaluator, never()).evaluatePartial(any(), any(), anySet(), anyList(), any());
        verify(detailClient).fetchVehicleDetail("A");
        verify(detailClient).fetchVehicleDetail("C");
        verify(detailClient, times(2)).fetchVehicleDetail("B");
        verify(rosterClient).fetchVehicleLocations(any());
        verify(monitored).waitBeforeRetry();
    }

    @Test
    @DisplayName("반복 Mapping 실패 중에도 실제 정상 이벤트와 위치 진행은 반영하고 오류 차량의 검증 위치·cycle과 presence를 보존한다")
    void repeated_mapping_failure_preserves_tracking_and_normal_events() {
        var rosterClient = mock(SeoulBusVehicleLocationClient.class);
        var detailClient = mock(SeoulBusVehicleDetailClient.class);
        var now = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenAnswer(invocation -> now.get());
        BusAlarmEvaluator realEvaluator = spy(new BusAlarmEvaluator(mock(BusRouteTraversalService.class)));
        var monitored = realSeoulScheduler(rosterClient, detailClient, realEvaluator, advancingClock);
        Alarm alarm = seoulEvaluationAlarm();
        when(alarm.getBusAlarmTarget().isNotifyOneStopBefore()).thenReturn(true);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("A", "B", "C"));
        when(detailClient.fetchVehicleDetail("A")).thenReturn(
                timedSeoulDetail("A", "20260923085940", "stop-1", 1, 0),
                timedSeoulDetail("A", "20260923085950", "predecessor", 10, 0),
                timedSeoulDetail("A", "20260923090000", "predecessor", 10, 0));
        when(detailClient.fetchVehicleDetail("B")).thenReturn(
                timedSeoulDetail("B", "20260923085940", "stop-1", 1, 0),
                timedSeoulDetail("B", "20260931090000", "target", 20, 1),
                timedSeoulDetail("B", "20260931090000", "target", 20, 1),
                timedSeoulDetail("B", "20260923090000", "stop-2", 2, 0));
        when(detailClient.fetchVehicleDetail("C")).thenReturn(
                timedSeoulDetail("C", "20260923085940", "stop-1", 1, 0),
                timedSeoulDetail("C", "20260923085950", "stop-2", 2, 0),
                timedSeoulDetail("C", "20260923090000", "stop-3", 3, 0));
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any())).thenAnswer(applied());

        monitored.pollMonitoringAlarms();
        now.set(clock.instant().plusSeconds(10));
        monitored.pollMonitoringAlarms();
        now.set(clock.instant().plusSeconds(20));
        monitored.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationResult> results = ArgumentCaptor.forClass(BusAlarmEvaluationResult.class);
        verify(lifecycleService, times(3)).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), results.capture(), any());
        var baseline = results.getAllValues().getFirst();
        var failed = results.getAllValues().get(1);
        var recovered = results.getAllValues().getLast();
        VehicleTrackingState original = baseline.nextState().trackedVehicles().get("B");
        VehicleTrackingState preserved = failed.nextState().trackedVehicles().get("B");
        assertThat(preserved.lastObservation()).isEqualTo(original.lastObservation());
        assertThat(preserved.lastKnownProviderDataTime()).isEqualTo(original.lastKnownProviderDataTime());
        assertThat(preserved.trackingCycleId()).isEqualTo(original.trackingCycleId());
        assertThat(preserved.emittedEventTypes()).isEqualTo(original.emittedEventTypes());
        assertThat(preserved.hasObservedBeforeTarget()).isTrue();
        assertThat(preserved.lastSeenAt()).isEqualTo(clock.instant().plusSeconds(10));
        assertThat(failed.nextState().trackedVehicles().get("C").lastObservation().currentStopOrder()).isEqualTo(2);
        assertThat(failed.eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TransitEventType.ONE_STOP_BEFORE);
            assertThat(event.vehicleTrackingId()).isEqualTo("A");
        });
        assertThat(baseline.eventCandidates()).isEmpty();
        assertThat(recovered.eventCandidates()).isEmpty();
        assertThat(recovered.nextState().trackedVehicles().get("B").trackingCycleId())
                .isEqualTo(original.trackingCycleId());
        assertThat(recovered.nextState().trackedVehicles().get("B").lastObservation().currentStopOrder()).isEqualTo(2);
        verify(realEvaluator, never()).evaluatePartial(any(), any(), anySet(), anyList(), any());
        verify(detailClient, times(3)).fetchVehicleDetail("A");
        verify(detailClient, times(3)).fetchVehicleDetail("C");
        verify(detailClient, times(4)).fetchVehicleDetail("B");
        verify(rosterClient, times(3)).fetchVehicleLocations(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"20260923085935", "20260923085940"})
    @DisplayName("Mapping 실패 뒤 같은 시각·역행 시각의 재시도는 실제 평가기에서 거부하고 이후 정상 도착은 같은 cycle로 반영한다")
    void mapping_retry_preserves_provider_time_validation(String retryTime) {
        var rosterClient = mock(SeoulBusVehicleLocationClient.class);
        var detailClient = mock(SeoulBusVehicleDetailClient.class);
        var realEvaluator = spy(new BusAlarmEvaluator(mock(BusRouteTraversalService.class)));
        var monitored = realSeoulScheduler(rosterClient, detailClient, realEvaluator, clock);
        Alarm alarm = seoulEvaluationAlarm();
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("B"));
        when(detailClient.fetchVehicleDetail("B")).thenReturn(
                timedSeoulDetail("B", "20260923085940", "predecessor", 10, 0),
                timedSeoulDetail("B", "invalid", "target", 20, 1),
                timedSeoulDetail("B", retryTime, "target", 20, 1),
                timedSeoulDetail("B", "20260923085950", "target", 20, 1));
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any())).thenAnswer(applied());

        monitored.pollMonitoringAlarms();
        monitored.pollMonitoringAlarms();
        monitored.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationResult> results = ArgumentCaptor.forClass(BusAlarmEvaluationResult.class);
        verify(lifecycleService, times(4)).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), results.capture(), any());
        var original = results.getAllValues().getFirst().nextState().trackedVehicles().get("B");
        assertThat(results.getAllValues().subList(0, 3)).allSatisfy(result -> {
            assertThat(result.eventCandidates()).isEmpty();
            assertThat(result.nextState().trackedVehicles().get("B")).isEqualTo(original);
        });
        assertThat(results.getAllValues().getLast().eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(TransitEventType.ARRIVED);
            assertThat(event.trackingCycleId()).isEqualTo(original.trackingCycleId());
        });
        verify(realEvaluator).evaluatePartial(eq(alarm), any(), eq(Set.of("B")), anyList(), any());
        verify(detailClient, times(4)).fetchVehicleDetail("B");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("여러 Alarm·Route에 공유된 Mapping 실패는 정상 Route를 먼저 평가하고 차량별 재조회는 성공·실패 모두 한 번으로 제한한다")
    void shares_mapping_failure_retry_across_routes(boolean retrySuccess) {
        var rosterClient = mock(SeoulBusVehicleLocationClient.class);
        var detailClient = mock(SeoulBusVehicleDetailClient.class);
        var monitored = spy(seoulScheduler(rosterClient, detailClient));
        Alarm first = seoulActiveAlarm(1L, "route-1");
        Alarm sameRoute = seoulActiveAlarm(2L, "route-1");
        Alarm second = seoulActiveAlarm(3L, "route-2");
        Alarm healthy = seoulActiveAlarm(4L, "route-3");
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(
                new BusAlarmPollingGroup(new BusPollingKey(TransitProvider.SEOUL_BUS, "route-1", null),
                        List.of(first, sameRoute)), seoulGroup(second, "route-2"), seoulGroup(healthy, "route-3")));
        when(rosterClient.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            com.stopbell.transit.client.VehicleLocationRequest<?> request = invocation.getArgument(0);
            return switch (request.externalRouteId()) {
                case "route-1" -> seoulRoster("A", "B");
                case "route-2" -> seoulRoster("B", "C");
                default -> seoulRoster("D");
            };
        });
        for (String vehicle : List.of("A", "C", "D")) {
            when(detailClient.fetchVehicleDetail(vehicle)).thenReturn(seoulDetail(vehicle));
        }
        var invalid = timedSeoulDetail("B", "invalid", "target", 20, 1);
        when(detailClient.fetchVehicleDetail("B")).thenReturn(invalid, retrySuccess ? seoulDetail("B") : invalid);
        var result = new BusAlarmEvaluationResult(List.of(), BusAlarmEvaluationState.initial(), false);
        when(evaluator.evaluate(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(evaluator.evaluatePartial(any(), any(), anySet(), anyList(), any())).thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any())).thenAnswer(applied());

        monitored.pollMonitoringAlarms();

        InOrder order = inOrder(evaluator, lifecycleService, monitored);
        for (Alarm alarm : List.of(first, sameRoute, second, healthy)) {
            var observations = observationCaptor();
            order.verify(evaluator).evaluate(eq(alarm), any(), anySet(), observations.capture(), any());
            String expectedVehicle = alarm == second ? "C" : alarm == healthy ? "D" : "A";
            assertThat(observations.getValue()).singleElement().satisfies(observation -> {
                assertThat(observation.vehicleTrackingId()).isEqualTo(expectedVehicle);
                assertThat(observation.externalRouteId()).isEqualTo(alarm.getBusAlarmTarget().getExternalRouteId());
            });
            order.verify(lifecycleService).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(result), any());
        }
        order.verify(monitored).waitBeforeRetry();
        verify(evaluator, times(retrySuccess ? 3 : 0)).evaluatePartial(any(), any(), eq(Set.of("B")), anyList(), any());
        verify(rosterClient, times(3)).fetchVehicleLocations(any());
        verify(detailClient, times(2)).fetchVehicleDetail("B");
        for (String vehicle : List.of("A", "C", "D")) {
            verify(detailClient).fetchVehicleDetail(vehicle);
        }
        verify(monitored).waitBeforeRetry();
    }

    @Test
    @DisplayName("같은 차량의 충돌 중복 관측은 실제 평가기의 UNKNOWN 정책을 유지하고 정상 차량 이벤트만 반영한다")
    void duplicate_detail_keeps_existing_ambiguity_policy() {
        var rosterClient = mock(SeoulBusVehicleLocationClient.class);
        var detailClient = mock(SeoulBusVehicleDetailClient.class);
        var realEvaluator = new BusAlarmEvaluator(mock(BusRouteTraversalService.class));
        var monitored = spy(realSeoulScheduler(rosterClient, detailClient, realEvaluator, clock));
        Alarm alarm = seoulEvaluationAlarm();
        when(alarm.getBusAlarmTarget().isNotifyOneStopBefore()).thenReturn(true);
        when(pollingService.findMonitoringGroups()).thenReturn(List.of(seoulGroup(alarm, "route-1")));
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(seoulRoster("A", "B"));
        when(detailClient.fetchVehicleDetail("A"))
                .thenReturn(timedSeoulDetail("A", "20260923085950", "predecessor", 10, 0));
        when(detailClient.fetchVehicleDetail("B")).thenReturn(new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", "OK"), new SeoulBusVehicleDetailResponse.Body(List.of(
                new SeoulBusVehicleDetailItem("B", null, "predecessor", 10, 0, "20260923085950", null, null),
                new SeoulBusVehicleDetailItem("B", null, "target", 20, 1, "20260923085950", null, null)))));
        when(lifecycleService.applyIfCurrent(any(), any(), any(), any())).thenAnswer(applied());

        monitored.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationResult> result = ArgumentCaptor.forClass(BusAlarmEvaluationResult.class);
        verify(lifecycleService).applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), result.capture(), any());
        assertThat(result.getValue().eventCandidates()).singleElement().satisfies(event -> {
            assertThat(event.vehicleTrackingId()).isEqualTo("A");
            assertThat(event.type()).isEqualTo(TransitEventType.ONE_STOP_BEFORE);
        });
        assertThat(result.getValue().nextState().trackedVehicles()).containsOnlyKeys("A");
        verify(monitored, never()).waitBeforeRetry();
        verify(detailClient).fetchVehicleDetail("B");
    }

    private static Alarm seoulEvaluationAlarm() {
        Alarm alarm = seoulActiveAlarm(1L, "route-1");
        when(alarm.getBusAlarmTarget().getExternalStopId()).thenReturn("target");
        when(alarm.getBusAlarmTarget().getTargetStopOrder()).thenReturn(20);
        when(alarm.getBusAlarmTarget().getPredecessorExternalStopId()).thenReturn("predecessor");
        when(alarm.getBusAlarmTarget().getPredecessorStopOrder()).thenReturn(10);
        return alarm;
    }

    private BusAlarmMonitoringScheduler realSeoulScheduler(SeoulBusVehicleLocationClient rosterClient,
            SeoulBusVehicleDetailClient detailClient, BusAlarmEvaluator realEvaluator, Clock pollingClock) {
        return new BusAlarmMonitoringScheduler(pollingService, realEvaluator, lifecycleService, tagoClient,
                rosterClient, detailClient, new TagoTransitObservationMapper(pollingClock),
                new SeoulBusTransitObservationMapper(pollingClock), pollingClock, Duration.ZERO,
                mock(com.stopbell.boarding.service.BoardingSessionPollingService.class));
    }

    private static SeoulBusVehicleDetailResponse timedSeoulDetail(
            String vehicleId, String dataTm, String stopId, int stopOrder, int stopFlag) {
        return new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("0", "OK"),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem(
                        vehicleId, null, stopId, stopOrder, stopFlag, dataTm, null, null))));
    }

    private BusAlarmMonitoringScheduler seoulScheduler(SeoulBusVehicleLocationClient rosterClient,
            SeoulBusVehicleDetailClient detailClient) {
        return new BusAlarmMonitoringScheduler(pollingService, evaluator, lifecycleService, tagoClient,
                rosterClient, detailClient, new TagoTransitObservationMapper(clock),
                new SeoulBusTransitObservationMapper(clock), clock, Duration.ZERO, mock(com.stopbell.boarding.service.BoardingSessionPollingService.class));
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
