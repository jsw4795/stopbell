package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusAlarmPollingServiceTest {

    private final AlarmRepository alarmRepository = mock(AlarmRepository.class);
    private final BusAlarmPollingService pollingService = new BusAlarmPollingService(alarmRepository);

    @Test
    @DisplayName("ACTIVE와 FOLLOW_UP BUS Alarm을 Provider Route request context별로 묶는다")
    void groups_monitoring_alarms_by_provider_route_request_context() {
        User firstUser = new User(AuthProvider.GOOGLE, "first-user");
        User secondUser = new User(AuthProvider.KAKAO, "second-user");
        Alarm tagoActive = activeAlarm(firstUser, TransitProvider.TAGO, "tago-route", "31010", "stop-a");
        Alarm tagoFollowUp = followUpAlarm(secondUser, TransitProvider.TAGO, "tago-route", "31010", "stop-b");
        Alarm tagoDifferentCity = activeAlarm(TransitProvider.TAGO, "tago-route", "31020", "stop-c");
        Alarm seoulActive = activeAlarm(TransitProvider.SEOUL_BUS, "shared-route", null, "stop-d");
        Alarm seoulFollowUp = followUpAlarm(TransitProvider.SEOUL_BUS, "shared-route", null, "stop-e");
        Alarm tagoSameRouteText = activeAlarm(TransitProvider.TAGO, "shared-route", "31010", "stop-f");
        Alarm otherRoute = activeAlarm(TransitProvider.TAGO, "other-route", "31010", "stop-g");

        when(alarmRepository.findAllByTransitTypeAndStatusInOrderByIdAsc(
                TransitType.BUS,
                List.of(AlarmStatus.ACTIVE, AlarmStatus.FOLLOW_UP)
        )).thenReturn(List.of(
                tagoActive,
                tagoFollowUp,
                tagoDifferentCity,
                seoulActive,
                seoulFollowUp,
                tagoSameRouteText,
                otherRoute
        ));

        List<BusAlarmPollingGroup> groups = pollingService.findMonitoringGroups();

        assertThat(groups).hasSize(5);
        assertThat(groups.get(0).key()).isEqualTo(new BusPollingKey(TransitProvider.TAGO, "tago-route", "31010"));
        assertThat(groups.get(0).alarms()).containsExactly(tagoActive, tagoFollowUp);
        assertThat(groups.get(1).key()).isEqualTo(new BusPollingKey(TransitProvider.TAGO, "tago-route", "31020"));
        assertThat(groups.get(1).alarms()).containsExactly(tagoDifferentCity);
        assertThat(groups.get(2).key()).isEqualTo(new BusPollingKey(TransitProvider.SEOUL_BUS, "shared-route", null));
        assertThat(groups.get(2).alarms()).containsExactly(seoulActive, seoulFollowUp);
        assertThat(groups.get(3).key()).isEqualTo(new BusPollingKey(TransitProvider.TAGO, "shared-route", "31010"));
        assertThat(groups.get(4).key()).isEqualTo(new BusPollingKey(TransitProvider.TAGO, "other-route", "31010"));
    }

    @Test
    @DisplayName("monitoring 조회는 BUS의 ACTIVE와 FOLLOW_UP 상태만 요청한다")
    void queries_only_bus_alarms_with_monitoring_statuses() {
        when(alarmRepository.findAllByTransitTypeAndStatusInOrderByIdAsc(
                TransitType.BUS,
                List.of(AlarmStatus.ACTIVE, AlarmStatus.FOLLOW_UP)
        )).thenReturn(List.of());

        assertThat(pollingService.findMonitoringGroups()).isEmpty();
        verify(alarmRepository).findAllByTransitTypeAndStatusInOrderByIdAsc(
                TransitType.BUS,
                List.of(AlarmStatus.ACTIVE, AlarmStatus.FOLLOW_UP)
        );
    }

    @Test
    @DisplayName("BusAlarmTarget이 없는 monitoring Alarm을 조용히 제외하지 않는다")
    void rejects_targetless_monitoring_alarm() {
        Alarm targetlessAlarm = mock(Alarm.class);
        when(targetlessAlarm.getId()).thenReturn(99L);
        when(alarmRepository.findAllByTransitTypeAndStatusInOrderByIdAsc(
                TransitType.BUS,
                List.of(AlarmStatus.ACTIVE, AlarmStatus.FOLLOW_UP)
        )).thenReturn(List.of(targetlessAlarm));

        assertThatThrownBy(pollingService::findMonitoringGroups)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BusAlarmTarget");
    }

    @Test
    @DisplayName("깨진 TAGO 또는 서울 Provider request context를 조용히 보정하지 않는다")
    void rejects_invalid_provider_request_context() {
        Alarm invalidTago = alarmWithTarget(mockTarget(TransitProvider.TAGO, "route", null));
        Alarm invalidSeoul = alarmWithTarget(mockTarget(TransitProvider.SEOUL_BUS, "route", "11000"));
        when(alarmRepository.findAllByTransitTypeAndStatusInOrderByIdAsc(
                TransitType.BUS,
                List.of(AlarmStatus.ACTIVE, AlarmStatus.FOLLOW_UP)
        )).thenReturn(List.of(invalidTago));

        assertThatThrownBy(pollingService::findMonitoringGroups)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TAGO city code");

        when(alarmRepository.findAllByTransitTypeAndStatusInOrderByIdAsc(
                TransitType.BUS,
                List.of(AlarmStatus.ACTIVE, AlarmStatus.FOLLOW_UP)
        )).thenReturn(List.of(invalidSeoul));

        assertThatThrownBy(pollingService::findMonitoringGroups)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Seoul Bus polling key");
    }

    @Test
    @DisplayName("polling key는 Provider별 request context 불변 조건을 검증한다")
    void validates_polling_key_context() {
        assertThatThrownBy(() -> new BusPollingKey(null, "route", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusPollingKey(TransitProvider.TAGO, " ", "31010"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusPollingKey(TransitProvider.TAGO, "route", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BusPollingKey(TransitProvider.SEOUL_BUS, "route", "11000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Alarm activeAlarm(
            TransitProvider provider,
            String externalRouteId,
            String cityCode,
            String externalStopId
    ) {
        return activeAlarm(null, provider, externalRouteId, cityCode, externalStopId);
    }

    private static Alarm activeAlarm(
            User user,
            TransitProvider provider,
            String externalRouteId,
            String cityCode,
            String externalStopId
    ) {
        Alarm alarm = alarmWithTarget(user, target(provider, externalRouteId, cityCode, externalStopId, null));
        alarm.activate();
        return alarm;
    }

    private static Alarm followUpAlarm(
            TransitProvider provider,
            String externalRouteId,
            String cityCode,
            String externalStopId
    ) {
        return followUpAlarm(null, provider, externalRouteId, cityCode, externalStopId);
    }

    private static Alarm followUpAlarm(
            User user,
            TransitProvider provider,
            String externalRouteId,
            String cityCode,
            String externalStopId
    ) {
        Alarm alarm = alarmWithTarget(user, target(
                provider,
                externalRouteId,
                cityCode,
                externalStopId,
                new AdjacentStopSnapshot("successor-" + externalStopId, 2)
        ));
        alarm.activate();
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 23, 12, 0);
        alarm.startFollowUp("vehicle-" + externalStopId, startedAt, startedAt.plusMinutes(10));
        return alarm;
    }

    private static Alarm alarmWithTarget(BusAlarmTarget target) {
        return alarmWithTarget(null, target);
    }

    private static Alarm alarmWithTarget(User user, BusAlarmTarget target) {
        return new Alarm(user, target);
    }

    private static BusAlarmTarget target(
            TransitProvider provider,
            String externalRouteId,
            String cityCode,
            String externalStopId,
            AdjacentStopSnapshot successor
    ) {
        return new BusAlarmTarget(
                provider,
                externalRouteId,
                externalStopId,
                1,
                "7000",
                "Target Stop",
                new BigDecimal("37.5547000"),
                new BigDecimal("126.9707000"),
                cityCode,
                null,
                successor
        );
    }

    private static BusAlarmTarget mockTarget(TransitProvider provider, String routeId, String cityCode) {
        BusAlarmTarget target = mock(BusAlarmTarget.class);
        when(target.getProvider()).thenReturn(provider);
        when(target.getExternalRouteId()).thenReturn(routeId);
        when(target.getCityCode()).thenReturn(cityCode);
        return target;
    }
}
