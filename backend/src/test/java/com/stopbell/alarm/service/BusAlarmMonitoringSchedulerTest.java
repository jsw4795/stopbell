package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.domain.TransitProvider;
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
        when(evaluator.evaluate(eq(alarm), any(BusAlarmEvaluationState.class), anyList(), any(Instant.class)))
                .thenReturn(result);
        when(lifecycleService.applyIfCurrent(any(), eq(AlarmStatus.ACTIVE), eq(result), any(Instant.class)))
                .thenReturn(true);

        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();

        ArgumentCaptor<BusAlarmEvaluationState> states = ArgumentCaptor.forClass(BusAlarmEvaluationState.class);
        verify(evaluator, times(2)).evaluate(eq(alarm), states.capture(), anyList(), any(Instant.class));
        assertThat(states.getAllValues().getFirst().baselineEstablished()).isFalse();
        assertThat(states.getAllValues().get(1).baselineEstablished()).isTrue();
        verify(tagoClient, times(2)).fetchVehicleLocations(any());
    }

    private static TagoVehicleLocationResponse emptyTagoResponse() {
        return new TagoVehicleLocationResponse(new TagoVehicleLocationResponse.Response(
                new TagoVehicleLocationResponse.Header("00", "NORMAL SERVICE."),
                new TagoVehicleLocationResponse.Body(null, 0, 1, 10)
        ));
    }
}
