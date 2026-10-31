package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.sql.DataSource;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.alarm.repository.AlarmCreationRequestRepository;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.repository.BusStopRepository;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AlarmCreationIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withCommand("--log-bin-trust-function-creators=1");
    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired BusRouteRepository routes;
    @Autowired BusStopRepository stops;
    @Autowired BusRouteStopOccurrenceRepository occurrences;
    @Autowired JdbcTemplate jdbc;
    @Autowired AlarmCreationCleanup cleanup;
    @Autowired DataSource dataSource;
    @MockitoSpyBean AlarmCreationRequestRepository requestRepository;
    @MockitoSpyBean AlarmService alarmService;
    private User user;
    private long occurrence;
    private String auth;
    private String requestId;

    @BeforeEach
    void setup() {
        jdbc.update("delete from alarm_creation_requests where expires_at <= UTC_TIMESTAMP(6)");
        String suffix = UUID.randomUUID().toString();
        user = users.saveAndFlush(new User(AuthProvider.GOOGLE, suffix));
        var route = routes.saveAndFlush(new BusRoute(TransitProvider.TAGO, "route-" + suffix, "7000", "41110"));
        var stop = stops.saveAndFlush(new BusStop(TransitProvider.TAGO, "stop-" + suffix, "Stop", null, null));
        occurrence = occurrences.saveAndFlush(new BusRouteStopOccurrence(route, stop, 1)).getId();
        auth = "Bearer " + tokens.createAccessToken(user.getId());
        requestId = UUID.randomUUID().toString();
    }

    private String body(String id) {
        return "{\"clientRequestId\":\"" + id + "\",\"targetStopOccurrenceId\":" + occurrence + "}";
    }

    private int postBody(String body, String authorization) throws Exception {
        var result = mvc.perform(post("/api/v1/alarms").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        return result.getResponse().getStatus();
    }

    private long alarmId() {
        return jdbc.queryForObject("select alarm_id from alarm_creation_requests where user_id=? and client_request_id=?",
                Long.class, user.getId(), requestId);
    }

    @Test @DisplayName("동일 요청을 현재 상태로 복구하고 내용 충돌과 삭제된 결과를 구분한다")
    void replay_conflict_and_tombstone() throws Exception {
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        long id = alarmId();
        mvc.perform(post("/api/v1/alarms/{id}/activate", id).header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/alarms").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"notifyOneStopAfter\":false,\"targetStopOccurrenceId\":" + occurrence + ",\"clientRequestId\":\"" + requestId + "\",\"notifyOneStopBefore\":false}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/alarms").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(body(requestId).replace("}", ",\"notifyOneStopBefore\":null,\"notifyOneStopAfter\":null}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isEqualTo(1);
        mvc.perform(post("/api/v1/alarms").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(body(requestId).replace("}", ",\"notifyOneStopAfter\":true}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALARM_CREATION_REQUEST_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Alarm creation request conflicts with the original request."))
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(postBody(body(requestId).replace("\"targetStopOccurrenceId\":" + occurrence,
                "\"targetStopOccurrenceId\":" + Long.MAX_VALUE), auth)).isEqualTo(409);
        mvc.perform(delete("/api/v1/alarms/{id}", id).header("Authorization", auth)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId).header("Authorization", auth))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ALARM_CREATION_RESULT_GONE"))
                .andExpect(jsonPath("$.message").value("Alarm created by this request is no longer available."))
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(postBody(body(requestId), auth)).isEqualTo(410);
        assertThat(postBody(body(requestId).replace("}", ",\"notifyOneStopAfter\":true}"), auth)).isEqualTo(409);
    }

    @Test @DisplayName("누락 ID는 레거시이고 명시적 null과 잘못된 UUID는 거부한다")
    void legacy_and_uuid_validation() throws Exception {
        assertThat(postBody("{\"targetStopOccurrenceId\":" + occurrence + "}", auth)).isEqualTo(201);
        assertThat(postBody("{\"clientRequestId\":null,\"targetStopOccurrenceId\":" + occurrence + "}", auth)).isEqualTo(400);
        for (String invalid : List.of("", requestId.toUpperCase(), "12345678-1234-1234-8234-123456789abc",
                "12345678-1234-3234-8234-123456789abc", "12345678-1234-5234-8234-123456789abc",
                "12345678-1234-4234-7234-123456789abc", "not-a-uuid")) {
            assertThat(postBody(body(invalid), auth)).isEqualTo(400);
        }
        mvc.perform(get("/api/v1/alarm-creation-requests/{id}", "invalid").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId).header("Authorization", auth))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ALARM_CREATION_REQUEST_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Alarm creation request was not found."))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId)).andExpect(status().isUnauthorized());
    }

    @Test @DisplayName("같은 UUID의 동시 요청은 하나의 Alarm과 요청 기록만 만든다")
    void concurrent_same_request() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(); return postBody(body(requestId), auth); });
            var b = executor.submit(() -> { barrier.await(); return postBody(body(requestId), auth); });
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 201);
        }
        assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where user_id=?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test @DisplayName("만료된 기록도 삭제 전까지 복구되며 cleanup 후 새 생성이 가능하다")
    void cleanup_retention_and_reuse() throws Exception {
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        long first = alarmId();
        cleanup.cleanup();
        assertThat(alarmId()).isEqualTo(first);
        LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusDays(31);
        jdbc.update("update alarm_creation_requests set created_at=?, expires_at=? where user_id=? and client_request_id=?",
                old, old.plusDays(30), user.getId(), requestId);
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        assertThat(alarmId()).isEqualTo(first);
        cleanup.cleanup();
        mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId).header("Authorization", auth)).andExpect(status().isNotFound());
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        assertThat(alarmId()).isNotEqualTo(first);
    }

    @Test @DisplayName("DB는 버전과 variant가 맞는 소문자 UUID 및 유일성을 강제한다")
    void database_uuid_and_unique_constraints() {
        String sql = "insert into alarm_creation_requests (user_id,client_request_id,request_fingerprint,alarm_id,created_at,expires_at) values (?, ?, ?, 1, '2026-01-01', '2026-02-01')";
        byte[] hash = new byte[32];
        jdbc.update(sql, user.getId(), requestId, hash);
        assertThatThrownBy(() -> jdbc.update(sql, user.getId(), requestId, hash)).isInstanceOf(DataIntegrityViolationException.class);
        for (String invalid : List.of("12345678-1234-1234-8234-123456789abc", "12345678-1234-4234-7234-123456789abc", "ABCDEFAB-1234-4234-8234-123456789ABC")) {
            assertThatThrownBy(() -> jdbc.update(sql, user.getId(), invalid, hash))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class).hasMessageContaining("ck_alarm_creation_requests_uuid");
        }
        assertThatThrownBy(() -> jdbc.update("insert into alarm_creation_requests (user_id,client_request_id,request_fingerprint,alarm_id,created_at,expires_at) values (?, ?, ?, 1, '2026-01-01', '2026-01-01')", user.getId(), UUID.randomUUID().toString(), hash))
                .hasRootCauseInstanceOf(java.sql.SQLException.class).hasMessageContaining("ck_alarm_creation_requests_lifetime");
    }

    @Test @DisplayName("다른 사용자는 같은 UUID를 독립적으로 사용하고 서로의 기록을 볼 수 없다")
    void isolates_users_and_recovers_after_metadata_removal() throws Exception {
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        long first = alarmId();
        var other = users.saveAndFlush(new User(AuthProvider.GOOGLE, UUID.randomUUID().toString()));
        String otherAuth = "Bearer " + tokens.createAccessToken(other.getId());
        mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId).header("Authorization", otherAuth))
                .andExpect(status().isNotFound());
        assertThat(postBody(body(requestId), otherAuth)).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where client_request_id=?", Integer.class, requestId)).isEqualTo(2);
        jdbc.update("delete from bus_route_stop_occurrences where id=?", occurrence);
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        assertThat(alarmId()).isEqualTo(first);
    }

    @Test @DisplayName("요청 기록 저장 실패는 Alarm도 롤백하고 동일 ID 재시도는 새로 생성한다")
    void request_insert_failure_rolls_back_alarm() throws Exception {
        jdbc.execute("create trigger reject_alarm before insert on alarms for each row signal sqlstate '45000' set message_text = 'injected alarm failure'");
        try {
            assertThat(postBody(body(requestId), auth)).isEqualTo(500);
            assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where user_id=?", Integer.class, user.getId())).isZero();
        } finally {
            jdbc.execute("drop trigger reject_alarm");
        }
        jdbc.execute("create trigger reject_alarm_creation before insert on alarm_creation_requests for each row signal sqlstate '45000' set message_text = 'injected failure'");
        try {
            assertThat(postBody(body(requestId), auth)).isEqualTo(500);
            assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where user_id=?", Integer.class, user.getId())).isZero();
        } finally {
            jdbc.execute("drop trigger reject_alarm_creation");
        }
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
    }

    @Test @DisplayName("다른 내용의 동일 ID 동시 요청은 한쪽만 생성하고 다른 쪽은 충돌한다")
    void concurrent_different_request() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Long routeId = jdbc.queryForObject("select route_id from bus_route_stop_occurrences where id=?", Long.class, occurrence);
        var route = routes.findById(routeId).orElseThrow();
        var stop = stops.saveAndFlush(new BusStop(TransitProvider.TAGO, "next-" + suffix, "Next", null, null));
        occurrences.saveAndFlush(new BusRouteStopOccurrence(route, stop, 2));
        String other = body(requestId).replace("}", ",\"notifyOneStopAfter\":true}");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(); return postBody(body(requestId), auth); });
            var b = executor.submit(() -> { barrier.await(); return postBody(other, auth); });
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test @DisplayName("응답을 수신하지 못한 커밋 결과는 같은 ID로 확인할 수 있다")
    void recover_after_committed_response_is_discarded() throws Exception {
        mvc.perform(post("/api/v1/alarms").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(body(requestId))).andReturn(); // Simulate discarding the already committed response, not a network failure.
        long id = alarmId();
        mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId).header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test @DisplayName("만료 기록 정리와 재전송 경합은 기존 결과 또는 정리 뒤 신규 결과를 반환한다")
    void cleanup_races_with_replay() throws Exception {
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusDays(31);
        jdbc.update("update alarm_creation_requests set created_at=?, expires_at=? where user_id=? and client_request_id=?",
                old, old.plusDays(30), user.getId(), requestId);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(); cleanup.cleanup(); return true; });
            var b = executor.submit(() -> { barrier.await(); return postBody(body(requestId), auth); });
            assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where user_id=?", Integer.class, user.getId())).isBetween(0, 1);
        assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isBetween(1, 2);
    }

    @Test @DisplayName("FOLLOW_UP 재전송은 추적과 generation 및 알림 기록을 변경하지 않는다")
    void replay_preserves_follow_up_runtime() throws Exception {
        Long routeId = jdbc.queryForObject("select route_id from bus_route_stop_occurrences where id=?", Long.class, occurrence);
        var stop = stops.saveAndFlush(new BusStop(TransitProvider.TAGO, "next-" + UUID.randomUUID(), "Next", null, null));
        occurrences.saveAndFlush(new BusRouteStopOccurrence(routes.findById(routeId).orElseThrow(), stop, 2));
        String afterBody = body(requestId).replace("}", ",\"notifyOneStopAfter\":true}");
        assertThat(postBody(afterBody, auth)).isEqualTo(201);
        long id = alarmId();
        mvc.perform(post("/api/v1/alarms/{id}/activate", id).header("Authorization", auth)).andExpect(status().isOk());
        String cycle = UUID.randomUUID().toString();
        jdbc.update("update alarms set status='FOLLOW_UP', follow_up_vehicle_tracking_id='vehicle', follow_up_tracking_cycle_id=?, follow_up_started_at='2026-01-01 00:00:00', follow_up_expires_at='2026-01-01 01:00:00' where id=?", cycle, id);
        jdbc.update("insert into devices (user_id,installation_id,installation_credential_hash,platform,current_push_target_id,registration_revision,ownership_generation,enabled,last_registered_at,created_at,updated_at) values (?, ?, ?, 'IOS', ?, 0, 0, true, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                user.getId(), UUID.randomUUID().toString(), "a".repeat(64), "target-" + UUID.randomUUID());
        long device = jdbc.queryForObject("select id from devices where user_id=?", Long.class, user.getId());
        jdbc.update("insert into notification_events (source_type,alarm_id,user_id,activation_generation,tracking_cycle_id,event_type,observed_at,event_detected_at,created_at) values ('ALARM', ?, ?, 1, ?, 'ARRIVED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id, user.getId(), cycle);
        long event = jdbc.queryForObject("select id from notification_events where alarm_id=?", Long.class, id);
        jdbc.update("insert into notification_deliveries (notification_event_id,device_id,recipient_ownership_generation,status,next_attempt_at,expires_at,created_at,updated_at) values (?, ?, 0, 'PENDING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 1 DAY, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", event, device);
        var eventBefore = jdbc.queryForMap("select * from notification_events where id=?", event);
        var deliveryBefore = jdbc.queryForMap("select * from notification_deliveries where notification_event_id=?", event);
        var before = jdbc.queryForMap("select status, activation_generation, follow_up_vehicle_tracking_id, follow_up_tracking_cycle_id, follow_up_started_at, follow_up_expires_at, updated_at from alarms where id=?", id);
        var events = jdbc.queryForObject("select count(*) from notification_events", Long.class);
        var deliveries = jdbc.queryForObject("select count(*) from notification_deliveries", Long.class);
        mvc.perform(post("/api/v1/alarms").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(afterBody))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("FOLLOW_UP"));
        assertThat(jdbc.queryForMap("select status, activation_generation, follow_up_vehicle_tracking_id, follow_up_tracking_cycle_id, follow_up_started_at, follow_up_expires_at, updated_at from alarms where id=?", id)).isEqualTo(before);
        assertThat(jdbc.queryForMap("select * from notification_events where id=?", event)).isEqualTo(eventBefore);
        assertThat(jdbc.queryForMap("select * from notification_deliveries where notification_event_id=?", event)).isEqualTo(deliveryBefore);
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(events);
        assertThat(jdbc.queryForObject("select count(*) from notification_deliveries", Long.class)).isEqualTo(deliveries);
        mvc.perform(delete("/api/v1/alarms/{id}", id).header("Authorization", auth)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForMap("select * from notification_events where id=?", event)).isEqualTo(eventBefore);
        assertThat(jdbc.queryForObject("select status from notification_deliveries where notification_event_id=?", String.class, event)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select last_failure_code from notification_deliveries where notification_event_id=?", String.class, event)).isEqualTo("DISPATCH_NOT_ALLOWED");
        mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId).header("Authorization", auth)).andExpect(status().isGone());
    }

    @Test @DisplayName("만료 기록 cleanup과 결과 조회 경합은 200 또는 404를 반환한다")
    void cleanup_races_with_lookup() throws Exception {
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusDays(31);
        jdbc.update("update alarm_creation_requests set created_at=?, expires_at=? where user_id=? and client_request_id=?",
                old, old.plusDays(30), user.getId(), requestId);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(); cleanup.cleanup(); return true; });
            var b = executor.submit(() -> { barrier.await(); return mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId)
                    .header("Authorization", auth)).andReturn().getResponse().getStatus(); });
            assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(20, TimeUnit.SECONDS)).isIn(200, 404);
        }
        assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test @DisplayName("cleanup은 만료 기록을 500개로 제한하고 미만료 기록을 보존한다")
    void cleanup_limits_batch_and_preserves_unexpired() {
        LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusDays(31);
        String sql = "insert into alarm_creation_requests (user_id,client_request_id,request_fingerprint,alarm_id,created_at,expires_at) values (?, ?, ?, 1, ?, ?)";
        for (int i = 0; i < 501; i++) {
            jdbc.update(sql, user.getId(), UUID.randomUUID().toString(), new byte[32], old, old.plusDays(30));
        }
        jdbc.update(sql, user.getId(), requestId, new byte[32], old.plusDays(2), old.plusDays(32));
        cleanup.cleanup();
        assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where user_id=?", Integer.class, user.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where user_id=? and client_request_id=?", Integer.class, user.getId(), requestId)).isEqualTo(1);
    }

    @Test @DisplayName("실제 UNIQUE 충돌은 실패한 생성 전체를 롤백하고 새 트랜잭션에서 승자 결과를 조회한다")
    void unique_collision_reloads_after_rollback() throws Exception {
        assertThat(postBody("{\"targetStopOccurrenceId\":" + occurrence + "}", auth)).isEqualTo(201);
        long winner = jdbc.queryForObject("select id from alarms where user_id=?", Long.class, user.getId());
        byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(("v1:" + occurrence + ":0:0").getBytes(StandardCharsets.UTF_8));
        org.mockito.Mockito.doAnswer(invocation -> {
            // Simulate an external writer that bypasses the user lock, then exercise MySQL's real UNIQUE.
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("set foreign_key_checks=0");
                try (var insert = connection.prepareStatement("insert into alarm_creation_requests (user_id,client_request_id,request_fingerprint,alarm_id,created_at,expires_at) values (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 30 DAY)")) {
                    insert.setLong(1, user.getId());
                    insert.setString(2, requestId);
                    insert.setBytes(3, fingerprint);
                    insert.setLong(4, winner);
                    insert.executeUpdate();
                } finally {
                    statement.execute("set foreign_key_checks=1");
                }
            }
            var saved = requestRepository.save(invocation.getArgument(0));
            requestRepository.flush();
            return saved;
        }).when(requestRepository).saveAndFlush(org.mockito.ArgumentMatchers.any());
        mvc.perform(post("/api/v1/alarms").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body(requestId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(winner));
        assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where user_id=?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test @DisplayName("cleanup과 Alarm 삭제는 각각의 기록만 안전하게 삭제한다")
    void cleanup_races_with_alarm_deletion() throws Exception {
        assertThat(postBody(body(requestId), auth)).isEqualTo(201);
        long id = alarmId();
        LocalDateTime old = LocalDateTime.now(ZoneOffset.UTC).minusDays(31);
        jdbc.update("update alarm_creation_requests set created_at=?, expires_at=? where user_id=? and client_request_id=?",
                old, old.plusDays(30), user.getId(), requestId);
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(); cleanup.cleanup(); return true; });
            var b = executor.submit(() -> { barrier.await(); return mvc.perform(delete("/api/v1/alarms/{id}", id)
                    .header("Authorization", auth)).andReturn().getResponse().getStatus(); });
            assertThat(a.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(204);
        }
        assertThat(jdbc.queryForObject("select count(*) from alarms where id=?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where user_id=?", Integer.class, user.getId())).isZero();
    }

    @Test @DisplayName("다른 사용자의 같은 UUID 동시 요청은 각각 독립 생성된다")
    void concurrent_same_id_for_different_users() throws Exception {
        var other = users.saveAndFlush(new User(AuthProvider.GOOGLE, UUID.randomUUID().toString()));
        String otherAuth = "Bearer " + tokens.createAccessToken(other.getId());
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(); return postBody(body(requestId), auth); });
            var b = executor.submit(() -> { barrier.await(); return postBody(body(requestId), otherAuth); });
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 201);
        }
        assertThat(jdbc.queryForObject("select count(*) from alarm_creation_requests where client_request_id=?", Integer.class, requestId)).isEqualTo(2);
    }

    @Test @DisplayName("최초 생성 중 metadata가 삭제돼도 같은 요청의 경합은 커밋된 결과를 복구한다")
    void concurrent_replay_after_metadata_changes() throws Exception {
        var saved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            var response = invocation.callRealMethod();
            saved.countDown();
            if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return response;
        }).when(alarmService).create(org.mockito.ArgumentMatchers.eq(user.getId()), org.mockito.ArgumentMatchers.any());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> postBody(body(requestId), auth));
            try {
                assertThat(saved.await(20, TimeUnit.SECONDS)).isTrue();
                mvc.perform(get("/api/v1/alarm-creation-requests/{id}", requestId).header("Authorization", auth))
                        .andExpect(status().isNotFound());
                jdbc.update("delete from bus_route_stop_occurrences where id=?", occurrence);
                var secondStarted = new CountDownLatch(1);
                var second = executor.submit(() -> { secondStarted.countDown(); return postBody(body(requestId), auth); });
                assertThat(secondStarted.await(20, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 201);
            } finally {
                release.countDown();
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from alarms where user_id=?", Integer.class, user.getId())).isEqualTo(1);
    }
}
