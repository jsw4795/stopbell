package com.stopbell.alarm.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.stopbell.transit.domain.TransitProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlarmTest {

    @Test
    @DisplayName("새 버스 알람은 비활성 상태로 생성된다")
    void create_bus_alarm_as_inactive() {
        Alarm alarm = createBusAlarm(null, null);

        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
    }

    @Test
    @DisplayName("비활성 알람을 활성화하면 활성 상태가 된다")
    void activate_when_alarm_is_inactive() {
        Alarm alarm = createBusAlarm(null, null);

        alarm.activate();

        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
    }

    @Test
    @DisplayName("이미 활성화된 알람을 다시 활성화해도 활성 상태를 유지한다")
    void activate_when_alarm_is_already_active() {
        Alarm alarm = createBusAlarm(null, null);
        alarm.activate();

        alarm.activate();

        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
    }

    @Test
    @DisplayName("한 정거장 후 옵션이 있는 활성 알람은 차량과 만료 문맥으로 후속 추적을 시작한다")
    void start_follow_up_with_runtime_context() {
        Alarm alarm = createBusAlarm(null, new AdjacentStopSnapshot("successor-stop", 13));
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 10, 12, 0);
        LocalDateTime expiresAt = startedAt.plusMinutes(10);
        alarm.activate();

        alarm.startFollowUp("vehicle-123", startedAt, expiresAt);

        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.FOLLOW_UP);
        assertThat(alarm.getFollowUpVehicleTrackingId()).isEqualTo("vehicle-123");
        assertThat(alarm.getFollowUpStartedAt()).isEqualTo(startedAt);
        assertThat(alarm.getFollowUpExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("후속 추적 중 다시 활성화하면 이전 후속 문맥을 지우고 새 활성 주기를 시작한다")
    void activate_when_follow_up_clears_runtime() {
        Alarm alarm = followUpAlarm();

        alarm.activate();

        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertFollowUpRuntimeIsCleared(alarm);
    }

    @Test
    @DisplayName("활성 알람을 비활성화하면 비활성 상태가 된다")
    void deactivate_when_alarm_is_active() {
        Alarm alarm = createBusAlarm(null, null);
        alarm.activate();

        alarm.deactivate();

        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertFollowUpRuntimeIsCleared(alarm);
    }

    @Test
    @DisplayName("이미 비활성화된 알람을 다시 비활성화해도 비활성 상태를 유지한다")
    void deactivate_when_alarm_is_already_inactive() {
        Alarm alarm = createBusAlarm(null, null);

        alarm.deactivate();

        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertFollowUpRuntimeIsCleared(alarm);
    }

    @Test
    @DisplayName("후속 추적 알람을 비활성화하면 후속 문맥을 지우고 비활성 상태가 된다")
    void deactivate_when_alarm_is_follow_up() {
        Alarm alarm = followUpAlarm();

        alarm.deactivate();

        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertFollowUpRuntimeIsCleared(alarm);
    }

    @Test
    @DisplayName("후속 추적을 완료하면 후속 문맥을 지우고 비활성 상태가 된다")
    void complete_follow_up_clears_runtime() {
        Alarm alarm = followUpAlarm();

        alarm.completeFollowUp();

        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertFollowUpRuntimeIsCleared(alarm);
    }

    @Test
    @DisplayName("한 정거장 후 옵션이 없으면 후속 추적을 시작할 수 없다")
    void start_follow_up_without_after_option_fails() {
        Alarm alarm = createBusAlarm(null, null);
        alarm.activate();

        assertThatThrownBy(() -> alarm.startFollowUp(
                "vehicle-123",
                LocalDateTime.of(2026, 9, 10, 12, 0),
                LocalDateTime.of(2026, 9, 10, 12, 10)
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("비활성 알람은 후속 추적을 시작할 수 없다")
    void start_follow_up_when_inactive_fails() {
        Alarm alarm = createBusAlarm(null, new AdjacentStopSnapshot("successor-stop", 13));

        assertThatThrownBy(() -> alarm.startFollowUp(
                "vehicle-123",
                LocalDateTime.of(2026, 9, 10, 12, 0),
                LocalDateTime.of(2026, 9, 10, 12, 10)
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("차량 식별자나 만료 시각이 없으면 후속 추적을 시작할 수 없다")
    void start_follow_up_without_runtime_fails() {
        Alarm alarm = createBusAlarm(null, new AdjacentStopSnapshot("successor-stop", 13));
        alarm.activate();
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 10, 12, 0);

        assertThatThrownBy(() -> alarm.startFollowUp(null, startedAt, startedAt.plusMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> alarm.startFollowUp("vehicle-123", startedAt, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("후속 추적 시작 시각보다 늦지 않은 만료 시각은 허용하지 않는다")
    void start_follow_up_with_invalid_expiry_fails() {
        Alarm alarm = createBusAlarm(null, new AdjacentStopSnapshot("successor-stop", 13));
        alarm.activate();
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 10, 12, 0);

        assertThatThrownBy(() -> alarm.startFollowUp("vehicle-123", startedAt, startedAt))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("버스 Target 없이 버스 알람을 생성할 수 없다")
    void create_bus_alarm_without_target_fails() {
        assertThatThrownBy(() -> new Alarm(null, TransitType.BUS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TAGO Target은 cityCode가 필요하다")
    void create_tago_target_without_city_code_fails() {
        assertThatThrownBy(() -> new BusAlarmTarget(
                TransitProvider.TAGO,
                "route-1",
                "stop-1",
                12,
                "143",
                "서울역",
                null,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("서울 Target에는 TAGO cityCode를 저장할 수 없다")
    void create_seoul_target_with_city_code_fails() {
        assertThatThrownBy(() -> new BusAlarmTarget(
                TransitProvider.SEOUL_BUS,
                "route-1",
                "stop-1",
                12,
                "143",
                "서울역",
                null,
                null,
                "11",
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Target 좌표는 위도와 경도가 함께 있어야 한다")
    void create_target_with_partial_coordinates_fails() {
        assertThatThrownBy(() -> new BusAlarmTarget(
                TransitProvider.SEOUL_BUS,
                "route-1",
                "stop-1",
                12,
                "143",
                "서울역",
                new BigDecimal("37.5547000"),
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private Alarm followUpAlarm() {
        Alarm alarm = createBusAlarm(null, new AdjacentStopSnapshot("successor-stop", 13));
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 10, 12, 0);
        alarm.activate();
        alarm.startFollowUp("vehicle-123", startedAt, startedAt.plusMinutes(10));
        return alarm;
    }

    private Alarm createBusAlarm(AdjacentStopSnapshot predecessor, AdjacentStopSnapshot successor) {
        BusAlarmTarget target = new BusAlarmTarget(
                TransitProvider.SEOUL_BUS,
                "route-1",
                "stop-1",
                12,
                "143",
                "서울역",
                new BigDecimal("37.5547000"),
                new BigDecimal("126.9707000"),
                null,
                predecessor,
                successor
        );
        return new Alarm(null, target);
    }

    private void assertFollowUpRuntimeIsCleared(Alarm alarm) {
        assertThat(alarm.getFollowUpVehicleTrackingId()).isNull();
        assertThat(alarm.getFollowUpStartedAt()).isNull();
        assertThat(alarm.getFollowUpExpiresAt()).isNull();
    }
}
