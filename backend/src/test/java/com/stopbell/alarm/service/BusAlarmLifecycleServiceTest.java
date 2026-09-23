package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusAlarmLifecycleServiceTest {

    private final AlarmRepository alarmRepository = mock(AlarmRepository.class);
    private final BusAlarmLifecycleService lifecycleService = new BusAlarmLifecycleService(alarmRepository);

    @Test
    @DisplayName("현재 activation cycle의 ARRIVED는 after option에 따라 FOLLOW_UP을 시작한다")
    void applies_arrival_to_current_cycle() {
        Alarm alarm = activeAlarm();
        AlarmEvaluationKey key = new AlarmEvaluationKey(1L, alarm.getActivationGeneration());
        when(alarmRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(alarm));

        boolean applied = lifecycleService.applyIfCurrent(
                key, AlarmStatus.ACTIVE, result(TransitEventType.ARRIVED), Instant.parse("2026-09-23T00:00:00Z")
        );

        assertThat(applied).isTrue();
        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.FOLLOW_UP);
        assertThat(alarm.getFollowUpVehicleTrackingId()).isEqualTo("vehicle-1");
        assertThat(alarm.getFollowUpExpiresAt()).isEqualTo(alarm.getFollowUpStartedAt().plusMinutes(5));
    }

    @Test
    @DisplayName("generation 또는 lifecycle이 달라진 polling 결과는 lifecycle을 변경하지 않는다")
    void discards_stale_result() {
        Alarm alarm = activeAlarm();
        when(alarmRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(alarm));

        boolean applied = lifecycleService.applyIfCurrent(
                new AlarmEvaluationKey(1L, alarm.getActivationGeneration() - 1),
                AlarmStatus.ACTIVE,
                result(TransitEventType.ARRIVED),
                Instant.parse("2026-09-23T00:00:00Z")
        );

        assertThat(applied).isFalse();
        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        verify(alarmRepository).findByIdForUpdate(1L);
    }

    private static Alarm activeAlarm() {
        BusAlarmTarget target = new BusAlarmTarget(
                TransitProvider.SEOUL_BUS, "route-1", "stop-1", 3, "143", "서울역",
                new BigDecimal("37.5547000"), new BigDecimal("126.9707000"), null,
                null, new AdjacentStopSnapshot("successor-stop", 4)
        );
        Alarm alarm = new Alarm(null, target);
        alarm.activate();
        return alarm;
    }

    private static BusAlarmEvaluationResult result(TransitEventType eventType) {
        TransitEvent event = new TransitEvent(
                eventType, "vehicle-1", UUID.randomUUID(), Instant.parse("2026-09-23T00:00:00Z"),
                "stop-1", null, 3, null, null, null, null
        );
        return new BusAlarmEvaluationResult(List.of(event), BusAlarmEvaluationState.initial(), false);
    }
}
