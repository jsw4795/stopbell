package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationResponse;
import com.stopbell.transit.dto.tago.TagoVehicleLocationItem;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;
import com.stopbell.transit.mapper.SeoulBusTransitObservationMapper;
import com.stopbell.transit.mapper.TagoTransitObservationMapper;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Verifies the Phase 5 monitoring path with real DB, mapping, evaluation, and lifecycle components. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TransitMonitoringIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private BusAlarmPollingService pollingService;

    @Autowired
    private BusAlarmEvaluator evaluator;

    @Autowired
    private BusAlarmLifecycleService lifecycleService;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    @AfterEach
    void clearTestData() {
        alarmRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("TAGO target 도착 raw 응답은 mapper와 lifecycle을 거쳐 Alarm을 종료한다")
    void tago_arrival_deactivates_alarm_through_full_monitoring_flow() {
        Alarm alarm = activeAlarm(tagoTarget("route-tago", false));
        TagoVehicleLocationClient tagoClient = mock(TagoVehicleLocationClient.class);
        when(tagoClient.fetchVehicleLocations(any())).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return tagoResponse(new TagoVehicleLocationItem("vehicle-1", "target", 20,
                    new BigDecimal("37.5000000"), new BigDecimal("127.1000000")));
        });

        scheduler(tagoClient, mock(SeoulBusVehicleLocationClient.class), mock(SeoulBusVehicleDetailClient.class))
                .pollMonitoringAlarms();

        assertThat(reload(alarm).getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(reload(alarm).getActivationGeneration()).isEqualTo(1);
        verify(tagoClient).fetchVehicleLocations(any());
    }

    @Test
    @DisplayName("서울 target ARRIVED 뒤 같은 차량의 successor detail은 FOLLOW_UP을 완료한다")
    void seoul_arrival_then_same_vehicle_one_stop_after_completes_follow_up() {
        Alarm alarm = activeAlarm(seoulTarget("route-seoul", true));
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(roster("vehicle-1"));
        when(detailClient.fetchVehicleDetail("vehicle-1"))
                .thenReturn(detail("vehicle-1", "target", 20, 1), detail("vehicle-1", "successor", 30, 0));

        BusAlarmMonitoringScheduler scheduler = scheduler(mock(TagoVehicleLocationClient.class), rosterClient, detailClient);
        scheduler.pollMonitoringAlarms();
        Alarm followUp = reload(alarm);
        assertThat(followUp.getStatus()).isEqualTo(AlarmStatus.FOLLOW_UP);
        assertThat(followUp.getFollowUpVehicleTrackingId()).isEqualTo("vehicle-1");
        assertThat(followUp.getActivationGeneration()).isEqualTo(1);

        scheduler.pollMonitoringAlarms();

        assertThat(reload(alarm).getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(reload(alarm).getActivationGeneration()).isEqualTo(1);
        verify(rosterClient, times(2)).fetchVehicleLocations(any());
        verify(detailClient, times(2)).fetchVehicleDetail("vehicle-1");
    }

    @Test
    @DisplayName("TAGO target 이전 차량이 이후 위치로 진행해도 PASSED는 Alarm을 종료하지 않는다")
    void tago_passed_keeps_alarm_active_and_tracking_can_continue() {
        Alarm alarm = activeAlarm(tagoTarget("route-passed", false));
        TagoVehicleLocationClient tagoClient = mock(TagoVehicleLocationClient.class);
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(
                tagoResponse(new TagoVehicleLocationItem("vehicle-1", "before", 10,
                        new BigDecimal("37.4000000"), new BigDecimal("127.0000000"))),
                tagoResponse(new TagoVehicleLocationItem("vehicle-1", "after", 30,
                        new BigDecimal("37.6000000"), new BigDecimal("127.2000000")))
        );

        BusAlarmMonitoringScheduler scheduler = scheduler(
                tagoClient, mock(SeoulBusVehicleLocationClient.class), mock(SeoulBusVehicleDetailClient.class)
        );
        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();

        assertThat(reload(alarm).getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(reload(alarm).getActivationGeneration()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 TAGO Route를 쓰는 여러 Alarm은 한 polling cycle에서 Route 요청을 공유한다")
    void tago_route_request_is_shared_by_alarms_in_same_polling_group() {
        activeAlarm(tagoTarget("route-shared", false));
        activeAlarm(tagoTarget("route-shared", false));
        TagoVehicleLocationClient tagoClient = mock(TagoVehicleLocationClient.class);
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(emptyTagoResponse());

        scheduler(tagoClient, mock(SeoulBusVehicleLocationClient.class), mock(SeoulBusVehicleDetailClient.class))
                .pollMonitoringAlarms();

        verify(tagoClient, times(1)).fetchVehicleLocations(any());
    }

    @Test
    @DisplayName("서울 detail 일부 실패는 성공 차량을 먼저 반영하고 실패 차량만 한 번 재시도한다")
    void seoul_detail_failure_retries_only_failed_vehicle_after_successful_details() {
        activeAlarm(seoulTarget("route-partial", false));
        SeoulBusVehicleLocationClient rosterClient = mock(SeoulBusVehicleLocationClient.class);
        SeoulBusVehicleDetailClient detailClient = mock(SeoulBusVehicleDetailClient.class);
        when(rosterClient.fetchVehicleLocations(any())).thenReturn(roster("vehicle-a", "vehicle-b", "vehicle-c"));
        when(detailClient.fetchVehicleDetail("vehicle-a")).thenReturn(detail("vehicle-a", "before", 10, 0));
        when(detailClient.fetchVehicleDetail("vehicle-b")).thenThrow(transportFailure())
                .thenReturn(detail("vehicle-b", "before", 10, 0));
        when(detailClient.fetchVehicleDetail("vehicle-c")).thenReturn(detail("vehicle-c", "before", 10, 0));

        scheduler(mock(TagoVehicleLocationClient.class), rosterClient, detailClient).pollMonitoringAlarms();

        InOrder order = inOrder(detailClient);
        order.verify(detailClient).fetchVehicleDetail("vehicle-a");
        order.verify(detailClient).fetchVehicleDetail("vehicle-b");
        order.verify(detailClient).fetchVehicleDetail("vehicle-c");
        order.verify(detailClient).fetchVehicleDetail("vehicle-b");
        verify(rosterClient, times(1)).fetchVehicleLocations(any());
        verify(detailClient, times(1)).fetchVehicleDetail("vehicle-a");
        verify(detailClient, times(2)).fetchVehicleDetail("vehicle-b");
        verify(detailClient, times(1)).fetchVehicleDetail("vehicle-c");
    }

    @Test
    @DisplayName("TAGO Provider 최종 실패는 기존 tracking과 Alarm lifecycle을 보존한다")
    void final_tago_provider_failure_preserves_existing_state() {
        Alarm alarm = activeAlarm(tagoTarget("route-failure", false));
        TagoVehicleLocationClient tagoClient = mock(TagoVehicleLocationClient.class);
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(
                tagoResponse(new TagoVehicleLocationItem("vehicle-1", "before", 10,
                        new BigDecimal("37.4000000"), new BigDecimal("127.0000000")))
        ).thenThrow(transportFailure(TransitProvider.TAGO, "route-location"))
                .thenThrow(transportFailure(TransitProvider.TAGO, "route-location"));
        BusAlarmMonitoringScheduler scheduler = scheduler(
                tagoClient, mock(SeoulBusVehicleLocationClient.class), mock(SeoulBusVehicleDetailClient.class)
        );

        scheduler.pollMonitoringAlarms();
        scheduler.pollMonitoringAlarms();

        assertThat(reload(alarm).getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(reload(alarm).getActivationGeneration()).isEqualTo(1);
        verify(tagoClient, times(3)).fetchVehicleLocations(any());
    }

    private BusAlarmMonitoringScheduler scheduler(
            TagoVehicleLocationClient tagoClient,
            SeoulBusVehicleLocationClient rosterClient,
            SeoulBusVehicleDetailClient detailClient
    ) {
        return new BusAlarmMonitoringScheduler(
                pollingService, evaluator, lifecycleService, tagoClient, rosterClient, detailClient,
                new TagoTransitObservationMapper(CLOCK), new SeoulBusTransitObservationMapper(CLOCK), CLOCK, Duration.ZERO
        );
    }

    private Alarm activeAlarm(BusAlarmTarget target) {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, UUID.randomUUID().toString()));
        Alarm alarm = new Alarm(user, target);
        alarm.activate();
        return alarmRepository.saveAndFlush(alarm);
    }

    private Alarm reload(Alarm alarm) {
        return alarmRepository.findById(alarm.getId()).orElseThrow();
    }

    private static BusAlarmTarget tagoTarget(String routeId, boolean after) {
        return new BusAlarmTarget(
                TransitProvider.TAGO, routeId, "target", 20, "7000", "Target",
                new BigDecimal("37.5000000"), new BigDecimal("127.1000000"), "41110", null,
                after ? new AdjacentStopSnapshot("successor", 30) : null
        );
    }

    private static BusAlarmTarget seoulTarget(String routeId, boolean after) {
        return new BusAlarmTarget(
                TransitProvider.SEOUL_BUS, routeId, "target", 20, "701", "Target",
                null, null, null, null, after ? new AdjacentStopSnapshot("successor", 30) : null
        );
    }

    private static TagoVehicleLocationResponse tagoResponse(TagoVehicleLocationItem... items) {
        return new TagoVehicleLocationResponse(new TagoVehicleLocationResponse.Response(
                new TagoVehicleLocationResponse.Header("00", "NORMAL"),
                new TagoVehicleLocationResponse.Body(new TagoVehicleLocationResponse.Items(List.of(items)), items.length, 1, 100)
        ));
    }

    private static TagoVehicleLocationResponse emptyTagoResponse() {
        return tagoResponse();
    }

    private static SeoulBusVehicleLocationResponse roster(String... vehicleIds) {
        return new SeoulBusVehicleLocationResponse(
                new SeoulBusVehicleLocationResponse.Header("0", "OK"),
                new SeoulBusVehicleLocationResponse.Body(java.util.Arrays.stream(vehicleIds)
                        .map(id -> new SeoulBusVehicleLocationItem(id, null, 999, "ignored", 0,
                                "20260923090000", null, null, "ignored", null, null))
                        .toList())
        );
    }

    private static SeoulBusVehicleDetailResponse detail(String vehicleId, String stopId, int stopOrder, int stopFlag) {
        return new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", "OK"),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem(
                        vehicleId, null, stopId, stopOrder, stopFlag, "20260923090000", null, null
                )))
        );
    }

    private static TransitProviderClientException transportFailure() {
        return transportFailure(TransitProvider.SEOUL_BUS, "vehicle-detail");
    }

    private static TransitProviderClientException transportFailure(TransitProvider provider, String operation) {
        return TransitProviderClientException.transport(provider, operation,
                new IllegalStateException("test transport failure"));
    }
}
