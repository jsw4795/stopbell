package com.stopbell.notification.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.service.*;
import com.stopbell.notification.entity.*;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.notification.service.*;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Existing isolated MySQL/Flyway test setup; FCM is a mock using the real payload builder only. */
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "notification.dispatch.enabled=false",
        "notification.fcm.enabled=false", "transit.monitoring.enabled=false"})
@ActiveProfiles("test")
@Testcontainers
class PassedNotificationDispatchIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00.123456Z");
    private static final LocalDateTime UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BusAlarmLifecycleService lifecycle;
    @Autowired private NotificationDeliveryRepository deliveries;
    @Autowired private DeviceRepository devices;
    @Autowired private PlatformTransactionManager manager;
    @MockitoBean(name = "transitMetadataClock") private Clock clock;
    private TransactionTemplate tx;

    @BeforeEach
    void set_up() {
        tx = new TransactionTemplate(manager);
        when(clock.instant()).thenReturn(NOW);
        jdbc.update("delete from notification_deliveries");
        jdbc.update("delete from notification_events");
    }

    @ParameterizedTest
    @CsvSource(value = {"강남역,3", "강남역,NULL", "NULL,3", "NULL,NULL"}, nullValues = "NULL")
    @DisplayName("PASSED 결정 commit 후 새 영속성 컨텍스트의 Worker가 원래 위치를 두 FCM body까지 전달한다")
    void persisted_event_reaches_worker_payload(String name, Integer stops) {
        Fixture fixture = fixture(name, stops);
        var eventRow = jdbc.queryForMap("select * from notification_events where id=?", fixture.eventId());
        assertThat(eventRow).containsEntry("current_stop_name", name).containsEntry("stops_past_target", stops)
                .containsEntry("current_stop_external_id", "observed-stop").containsEntry("current_stop_order", 90)
                .containsEntry("event_type", "PASSED").containsEntry("tracking_cycle_id", fixture.cycle().toString());
        tx.executeWithoutResult(status -> {
            entityManager.clear();
            NotificationEvent event = entityManager.find(NotificationEvent.class, fixture.eventId());
            assertThat(event.getCurrentStopName()).isEqualTo(name);
            assertThat(event.getStopsPastTarget()).isEqualTo(stops);
            assertThat(event.getProviderDataTime()).isEqualTo(UTC.minusSeconds(1));
            assertThat(event.getTrackingCycleId()).isEqualTo(fixture.cycle());
            assertThat(event.getActivationGeneration()).isOne();
            assertThat(deliveries.findById(fixture.deliveryId()).orElseThrow().getNotificationEvent().getId())
                    .isEqualTo(fixture.eventId());
            entityManager.clear();
        });
        var provider = mock(FcmProviderClient.class);
        when(provider.send(any())).thenAnswer(call -> {
            assertNoTransaction();
            NotificationSendRequest request = call.getArgument(0);
            assertThat(request.passedContext()).isEqualTo(new PassedNotificationContext(name, stops));
            assertPayload(request, fixture, expectedBody(name, stops));
            return new NotificationSendResult(NotificationProviderResult.ACCEPTED, null, null, NOW, 1, true);
        });
        new NotificationDispatchWorker(new NotificationDispatchProperties(true, 50, Duration.ofSeconds(1)),
                deliveries, attempts(), provider, clock).dispatchPending();
        verify(provider, times(1)).send(any());
        assertThat(jdbc.queryForMap("select status, attempt_count from notification_deliveries where id=?", fixture.deliveryId()))
                .containsEntry("status", "ACCEPTED").containsEntry("attempt_count", 1);
        assertActive(fixture);
    }

    @Test
    @DisplayName("전송 경계를 재생성한 retry도 새 tracking cycle과 섞이지 않고 원래 Event 위치와 최신 FID를 사용한다")
    void retry_restores_original_event_snapshot() {
        Fixture fixture = fixture("원래 확인 정류장", 3);
        var service = attempts();
        var first = service.beginAttempt(fixture.deliveryId()).orElseThrow();
        assertNoTransaction();
        String originalPayload = FcmProviderClient.payload(first.target().request());
        service.completeAttempt(first, new NotificationSendResult(NotificationProviderResult.RETRYABLE,
                "UNAVAILABLE", Duration.ofSeconds(10), NOW, 1, true));
        LocalDateTime due = deliveries.findById(fixture.deliveryId()).orElseThrow().getNextAttemptAt();
        when(clock.instant()).thenReturn(due.toInstant(ZoneOffset.UTC));
        tx.executeWithoutResult(status -> {
            entityManager.clear();
            Device device = devices.findByIdForUpdate(fixture.deviceId()).orElseThrow();
            device.register("rotated-synthetic-fid", 1, due);
        });
        // A later Event belongs to another vehicle cycle; it must not supply this Delivery's evidence.
        commitPassed(fixture.alarmId(), UUID.randomUUID(), "다른 차량 정류장", 1, clock.instant());
        var retry = attempts().beginAttempt(fixture.deliveryId()).orElseThrow();
        assertNoTransaction();
        assertThat(retry.attemptCount()).isEqualTo(2);
        assertThat(retry.target().passedContext()).isEqualTo(first.target().passedContext());
        assertThat(retry.target().registrationRevision()).isOne();
        assertThat(retry.target().fid()).isEqualTo("rotated-synthetic-fid");
        assertPayload(retry.target().request(), fixture, expectedBody("원래 확인 정류장", 3));
        var original = JsonParser.parseString(originalPayload).getAsJsonObject().getAsJsonObject("message");
        var restored = JsonParser.parseString(FcmProviderClient.payload(retry.target().request()))
                .getAsJsonObject().getAsJsonObject("message");
        assertThat(restored.get("notification")).isEqualTo(original.get("notification"));
        assertThat(restored.get("data")).isEqualTo(original.get("data"));
        assertThat(restored.get("apns")).isEqualTo(original.get("apns"));
        assertActive(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "disabled", "owner-changed", "generation-changed"})
    @DisplayName("저장된 PASSED 위치가 있어도 만료·비활성·소유자·generation 변경 Delivery는 전송하지 않는다")
    void location_does_not_bypass_eligibility(String rejection) {
        Fixture fixture = fixture("강남역", 3);
        if (rejection.equals("expired")) {
            when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        } else {
            tx.executeWithoutResult(status -> {
                Device device = devices.findByIdForUpdate(fixture.deviceId()).orElseThrow();
                if (rejection.equals("disabled")) {
                    device.disable(1);
                } else {
                    User original = device.getUser();
                    User next = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
                    entityManager.persist(next);
                    device.transferOwnership(next, "other-owner-fid", 1, UTC);
                    if (rejection.equals("generation-changed")) {
                        device.transferOwnership(original, "returned-owner-fid", 2, UTC);
                    }
                }
            });
        }
        var provider = mock(FcmProviderClient.class);
        new NotificationDispatchWorker(new NotificationDispatchProperties(true, 50, Duration.ofSeconds(1)),
                deliveries, attempts(), provider, clock).dispatchPending();
        verifyNoInteractions(provider);
        var row = deliveries.findById(fixture.deliveryId()).orElseThrow();
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getStatus()).isEqualTo(rejection.equals("expired")
                ? NotificationDeliveryStatus.EXPIRED : NotificationDeliveryStatus.FAILED);
        assertActive(fixture);
    }

    private NotificationDispatchAttemptService attempts() {
        var retry = new NotificationRetryPolicy(new NotificationRetryProperties(3, Duration.ofSeconds(10),
                Duration.ofSeconds(60), Duration.ZERO, Duration.ofSeconds(10)),
                new FcmClientProperties(false, "", "", Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(15)));
        return new NotificationDispatchAttemptService(deliveries, new NotificationRecipientPreflight(devices),
                new DurableNotificationAttemptHandler(retry, devices, clock), manager, clock);
    }

    private Fixture fixture(String name, Integer stops) {
        long[] identity = tx.execute(status -> {
            User user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
            entityManager.persist(user);
            Alarm alarm = new Alarm(user, new BusAlarmTarget(TransitProvider.SEOUL_BUS, "route", "target", 20,
                    "143", "목표 정류장", null, null, null, null, null));
            alarm.activate();
            entityManager.persist(alarm);
            Device device = new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS,
                    UUID.randomUUID().toString(), 0, UTC);
            entityManager.persist(device);
            entityManager.flush();
            return new long[] {alarm.getId(), device.getId()};
        });
        UUID cycle = UUID.randomUUID();
        commitPassed(identity[0], cycle, name, stops, NOW);
        long eventId = jdbc.queryForObject("select id from notification_events where alarm_id=?", Long.class, identity[0]);
        long deliveryId = jdbc.queryForObject("select id from notification_deliveries where notification_event_id=?", Long.class, eventId);
        return new Fixture(identity[0], identity[1], eventId, deliveryId, cycle);
    }

    private void commitPassed(long alarmId, UUID cycle, String name, Integer stops, Instant observedAt) {
        var transit = new TransitEvent(TransitEventType.PASSED, "vehicle-" + cycle, cycle, observedAt,
                "observed-stop", name, 90, null, null, observedAt.minusSeconds(1), stops);
        var result = lifecycle.applyIfCurrent(new AlarmEvaluationKey(alarmId, 1), AlarmStatus.ACTIVE,
                new BusAlarmEvaluationResult(List.of(transit), BusAlarmEvaluationState.initial(), false), observedAt);
        assertThat(result.outcome()).isEqualTo(BusAlarmDecisionResult.Outcome.COMMITTED);
    }

    private void assertPayload(NotificationSendRequest request, Fixture fixture, String body) {
        var message = JsonParser.parseString(FcmProviderClient.payload(request)).getAsJsonObject().getAsJsonObject("message");
        assertThat(message.getAsJsonObject("notification").get("body").getAsString()).isEqualTo(body);
        assertThat(message.getAsJsonObject("apns").getAsJsonObject("payload").getAsJsonObject("aps").get("alert"))
                .isEqualTo(message.get("notification"));
        var data = message.getAsJsonObject("data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("type", "alarmId", "eventType", "notificationEventId");
        assertThat(data.get("type").getAsString()).isEqualTo("ALARM_EVENT");
        assertThat(data.get("alarmId").getAsLong()).isEqualTo(fixture.alarmId());
        assertThat(data.get("eventType").getAsString()).isEqualTo("PASSED");
        assertThat(data.get("notificationEventId").getAsLong()).isEqualTo(fixture.eventId());
    }

    private void assertActive(Fixture fixture) {
        assertThat(jdbc.queryForObject("select status from alarms where id=?", String.class, fixture.alarmId()))
                .isEqualTo("ACTIVE");
    }

    private void assertNoTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    private String expectedBody(String name, Integer stops) {
        String body = "버스가 목표 정류장을 지나갔어요.";
        if (name != null) return body + "\n최근 확인된 정류장: " + name
                + (stops == null ? "" : " (목표 정류장 이후 " + stops + "정거장)");
        return stops == null ? body : body + "\n목표 정류장 이후 " + stops + "정거장 이동한 것으로 확인됐어요.";
    }

    private record Fixture(long alarmId, long deviceId, long eventId, long deliveryId, UUID cycle) { }
}
