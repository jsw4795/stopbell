package com.stopbell.boarding.controller;

import static com.stopbell.boarding.BoardingSessionFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.stopbell.boarding.dto.CreateBoardingSessionRequest;
import com.stopbell.boarding.dto.ReconfirmBoardingSessionRequest;
import com.stopbell.boarding.dto.BoardingSessionResponse;
import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.boarding.service.VerifiedBoardingStart;
import com.stopbell.boarding.service.BoardingTrackingRuntime;
import com.stopbell.boarding.entity.BoardingStateReason;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.boarding.service.BoardingSessionService;
import com.stopbell.boarding.service.BoardingSessionStartValidator;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.client.*;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.*;
import com.stopbell.transit.entity.*;
import com.stopbell.transit.repository.*;
import com.stopbell.transit.service.BoardingCandidateMetadataService;
import com.stopbell.transit.service.BoardingCandidateService;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class BoardingSessionApiIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");
    @Autowired private com.stopbell.boarding.service.BoardingNotificationService notifications;
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwt;
    @Autowired private UserRepository users;
    @Autowired private BusRouteRepository routes;
    @Autowired private BusStopRepository stops;
    @Autowired private BusRouteStopOccurrenceRepository occurrences;
    @Autowired private BoardingSessionRepository sessions;
    @Autowired private BoardingSessionService service;
    @Autowired private BoardingTrackingRuntime runtime;
    @Autowired private BoardingCandidateMetadataService metadata;
    @Autowired private BoardingCandidateService candidates;
    @Autowired private com.stopbell.boarding.service.BoardingSessionProperties properties;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private BoardingSessionStartValidator validator;
    @MockitoBean(name = "transitMetadataClock") private Clock clock;
    @MockitoBean private SeoulBusVehicleLocationClient seoul;
    @MockitoBean private SeoulBusVehicleDetailClient detail;
    @MockitoBean private TagoVehicleLocationClient tago;
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    private final JsonMapper json = JsonMapper.builder().build();
    private User owner;
    private User other;
    private BusRoute route;
    private BusRouteStopOccurrence target;
    private BusRouteStopOccurrence first;

    @BeforeEach
    void set_up() {
        sessions.deleteAll();
        occurrences.deleteAll();
        routes.deleteAll();
        stops.deleteAll();
        users.deleteAll();
        owner = users.saveAndFlush(new User(AuthProvider.GOOGLE, "boarding-owner"));
        other = users.saveAndFlush(new User(AuthProvider.GOOGLE, "boarding-other"));
        route = routes.saveAndFlush(new BusRoute(TransitProvider.SEOUL_BUS, "route-id", "7000", null));
        first = occurrence("s1", 10);
        occurrence("s2", 20);
        target = occurrence("s3", 30);
        time.set(NOW);
        when(clock.instant()).thenAnswer(invocation -> time.get());
        when(seoul.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return roster(new SeoulBusVehicleLocationItem("vehicle-id", NUMBER, 999, "coarse", 1,
                    "20261020120000", null, null, "unused-coarse-stop", null, null));
        });
        when(detail.fetchVehicleDetail("vehicle-id")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return progress("s1", 10, 1);
        });
    }

    @Test
    @DisplayName("모든 세션 API는 JWT 인증을 요구한다")
    void require_jwt() throws Exception {
        for (var request : List.of(post("/api/v1/boarding-sessions").contentType("application/json").content(body(REQUEST_ID, "ref")),
                get("/api/v1/boarding-sessions/1"), get("/api/v1/boarding-sessions?scope=current"),
                post("/api/v1/boarding-sessions/1/cancel").contentType("application/json").content("{\"expectedRevision\":0}"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/boarding-sessions?scope=current").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("검증된 입력의 생성은 201 TRACKING과 고정 수명을 반환하고 과거 gap이나 알림 성공을 노출하지 않는다")
    void create_verified_session() throws Exception {
        supportFixture();
        var response = mvc.perform(auth(postSession(REQUEST_ID, "ref"), owner)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TRACKING"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.routeNumber").value("7000"))
                .andExpect(jsonPath("$.vehicleNumber").value(NUMBER))
                .andExpect(jsonPath("$.targetOrder").value(30))
                .andExpect(jsonPath("$.remainingOccurrenceGap").isEmpty())
                .andExpect(jsonPath("$.evaluationStatus").value("RECOVERY_PENDING"))
                .andExpect(jsonPath("$.checkpointObservedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.expiresAt").value(NOW.plusSeconds(14400).toString()))
                .andExpect(jsonPath("$.vehicleTrackingId").doesNotExist())
                .andExpect(jsonPath("$.traversal").doesNotExist())
                .andExpect(jsonPath("$.traversalFingerprint").doesNotExist()).andReturn();
        long id = json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        assertThat(sessions.findById(id).orElseThrow().getCheckpoint().getIndex()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alarms", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isZero();
    }

    @Test
    @DisplayName("응답 유실 후 UUID 재전송은 reference 만료나 소실과 무관하게 원래 기한과 checkpoint를 유지한다")
    void idempotent_after_reference_loss() throws Exception {
        supportFixture();
        var original = service.create(owner.getId(), request(REQUEST_ID, "lost-reference")).response();
        time.set(NOW.plusSeconds(120));
        doThrow(new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED)).when(validator).validate(anyLong(), anyString(), anyString());
        mvc.perform(auth(postSession(REQUEST_ID.toUpperCase(), "lost-reference"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(original.id()))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.checkpointObservedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.expiresAt").value(original.expiresAt().toString()));
        verify(validator, times(1)).validate(anyLong(), anyString(), anyString());
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 UUID의 다른 요청과 이미 열린 세션에 대한 다른 생성은 각각 409다")
    void request_and_session_conflicts() throws Exception {
        supportFixture();
        service.create(owner.getId(), request(REQUEST_ID, "ref"));
        mvc.perform(auth(postSession(REQUEST_ID, "changed-ref"), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_REQUEST_CONFLICT"));
        mvc.perform(auth(postSession(REQUEST_ID, "ref").content(body(REQUEST_ID, "ref").replace(NUMBER, NUMBER + " ")), owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOARDING_REQUEST_CONFLICT"));
        mvc.perform(auth(postSession(UUID.randomUUID().toString(), "ref"), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_SESSION_CONFLICT"));
    }

    @Test
    @DisplayName("상세와 current 및 요청 UUID 조회는 소유권을 지키며 종료한 원래 요청도 복구한다")
    void query_and_cancel_with_ownership() throws Exception {
        supportFixture();
        var original = service.create(owner.getId(), request(REQUEST_ID, "ref")).response();
        String path = "/api/v1/boarding-sessions/" + original.id();
        mvc.perform(auth(get(path), owner)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(original.id()));
        mvc.perform(auth(get("/api/v1/boarding-sessions?scope=current"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(auth(get(path), other)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOARDING_SESSION_NOT_FOUND"));
        mvc.perform(auth(get("/api/v1/boarding-sessions/999999"), owner)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOARDING_SESSION_NOT_FOUND"));
        mvc.perform(auth(post(path + "/cancel").contentType("application/json").content("{\"expectedRevision\":0}"), other))
                .andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/boarding-sessions?clientRequestId=" + REQUEST_ID), other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(post(path + "/cancel").contentType("application/json").content("{\"expectedRevision\":0}"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.reasonCode").value("USER_CANCELLED"))
                .andExpect(jsonPath("$.endedAt").value(NOW.toString()));
        mvc.perform(auth(post(path + "/cancel").contentType("application/json").content("{\"expectedRevision\":0}"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(auth(get("/api/v1/boarding-sessions?scope=current"), owner)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(get("/api/v1/boarding-sessions?clientRequestId=" + REQUEST_ID), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
        assertThat(service.create(owner.getId(), request(REQUEST_ID, "ref")).response().status().name()).isEqualTo("CANCELLED");
        assertThat(service.create(owner.getId(), request(UUID.randomUUID().toString(), "ref")).created()).isTrue();
    }

    @Test
    @DisplayName("SUSPENDED 세션 취소는 current revision을 요구하며 오래된 변경 결과가 취소를 되살리지 못한다")
    void cancel_requires_current_revision() throws Exception {
        supportFixture();
        var original = service.create(owner.getId(), request(REQUEST_ID, "ref")).response();
        new TransactionTemplate(manager).executeWithoutResult(status -> sessions.findOwnedForUpdate(original.id(), owner.getId())
                .orElseThrow().suspend(0, BoardingStateReason.VEHICLE_MISSING, UTC_NOW));
        String path = "/api/v1/boarding-sessions/" + original.id() + "/cancel";
        mvc.perform(auth(post(path).contentType("application/json").content("{\"expectedRevision\":0}"), owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOARDING_REVISION_CONFLICT"));
        mvc.perform(auth(post(path).contentType("application/json").content("{\"expectedRevision\":1}"), owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED")).andExpect(jsonPath("$.revision").value(2));
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
                sessions.findOwnedForUpdate(original.id(), owner.getId()).orElseThrow()
                        .suspend(1, BoardingStateReason.STALE_PROGRESS, UTC_NOW))).isInstanceOf(IllegalStateException.class);
        assertThat(sessions.findById(original.id()).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?scope=all", "?scope=current&clientRequestId=aaaaaaaa-1111-4111-8111-111111111111",
            "?scope=current&scope=current", "?clientRequestId=bad", "?scope=current&extra=x", "?clientRequestId="})
    @DisplayName("누락되거나 중복되거나 지원하지 않는 조회 query는 400이다")
    void invalid_query(String query) throws Exception {
        mvc.perform(auth(get("/api/v1/boarding-sessions" + query), owner)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedRevision\":null}", "{\"expectedRevision\":-1}",
            "{\"expectedRevision\":0.5}", "{\"expectedRevision\":\"0\"}", "{\"expectedRevision\":9223372036854775808}"})
    @DisplayName("취소 revision은 생략할 수 없는 0 이상의 정수다")
    void invalid_revision_body(String body) throws Exception {
        mvc.perform(auth(post("/api/v1/boarding-sessions/1/cancel").contentType("application/json").content(body), owner))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"clientRequestId\":\"bad\",\"candidateRef\":\"ref\",\"confirmedVehicleNumber\":\"number\"}",
            "{\"clientRequestId\":\"aaaaaaaa-1111-4111-8111-111111111111\",\"candidateRef\":123,\"confirmedVehicleNumber\":\"number\"}"})
    @DisplayName("생성 body는 canonical UUID와 문자열 reference 및 전체 번호를 요구한다")
    void invalid_create_body(String body) throws Exception {
        mvc.perform(auth(post("/api/v1/boarding-sessions").contentType("application/json").content(body), owner))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("타 User와 만료 reference 및 전체 번호 불일치는 새 세션 없이 거부한다")
    void reject_invalid_reference_and_number() throws Exception {
        String reference = candidateRef();
        mvc.perform(auth(postSession(REQUEST_ID, reference), other)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_CANDIDATE_CHANGED"));
        mvc.perform(auth(postSession(REQUEST_ID, reference).content(body(REQUEST_ID, reference).replace(NUMBER, "1234")), owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOARDING_CANDIDATE_CHANGED"));
        time.set(NOW.plusSeconds(61));
        mvc.perform(auth(postSession(REQUEST_ID, reference), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_CANDIDATE_CHANGED"));
        assertThat(sessions.count()).isZero();
    }

    @Test
    @DisplayName("서울의 exact direct 도착 진행도 목적지의 하차 가능 근거 없이는 지원 성공으로 바꾸지 않는다")
    void real_validator_preserves_unsupported_boundary() throws Exception {
        String reference = candidateRef();
        mvc.perform(auth(postSession(REQUEST_ID, reference), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_TRACKING_UNSUPPORTED"));
        assertThat(sessions.count()).isZero();
    }

    @Test
    @DisplayName("차량 소실과 ID 또는 번호 충돌 및 detail 번호 변경은 확정을 거부한다")
    void reject_identity_changes() throws Exception {
        String reference = candidateRef();
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster());
        expectError(reference, "BOARDING_CANDIDATE_CHANGED", 409);
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster(
                new SeoulBusVehicleLocationItem("vehicle-id", "different-number", 1, "x", 1, "20261020120000", null, null, null, null, null)));
        expectError(reference, "BOARDING_CANDIDATE_CHANGED", 409);
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster(
                new SeoulBusVehicleLocationItem("vehicle-id", NUMBER, 1, "x", 1, "20261020120000", null, null, null, null, null),
                new SeoulBusVehicleLocationItem("another-id", NUMBER, 1, "x", 1, "20261020120000", null, null, null, null, null)));
        expectError(reference, "BOARDING_CANDIDATE_CHANGED", 409);
    }

    @Test
    @DisplayName("Provider 실패는 503이고 metadata 구조 변경과 목적지 이후 진행은 계약된 오류로 거부한다")
    void provider_metadata_and_target_failures() throws Exception {
        String reference = candidateRef();
        when(detail.fetchVehicleDetail("vehicle-id")).thenThrow(TransitProviderClientException.http(TransitProvider.SEOUL_BUS, "detail", 503, null));
        expectError(reference, "TRANSIT_TEMPORARILY_UNAVAILABLE", 503);
        doReturn(progress("s3", 30, 1)).when(detail).fetchVehicleDetail("vehicle-id");
        expectError(reference, "BOARDING_TARGET_NOT_AHEAD", 409);
        jdbc.update("update bus_route_stop_occurrences set stop_order=11 where id=?", first.getId());
        expectError(reference, "BOARDING_CANDIDATE_CHANGED", 409);
    }

    @Test
    @DisplayName("저장 transaction에서도 metadata를 재확인하고 검증 관측이 오래됐으면 INSERT하지 않는다")
    void recheck_metadata_and_freshness_before_insert() throws Exception {
        var start = verified(metadata.read(route.getId(), target.getId(), first.getId()));
        doReturn(start).when(validator).validate(anyLong(), anyString(), anyString());
        jdbc.update("update bus_route_stop_occurrences set stop_order=11 where id=?", first.getId());
        expectError("ref", "BOARDING_CANDIDATE_CHANGED", 409);
        jdbc.update("update bus_route_stop_occurrences set stop_order=10 where id=?", first.getId());
        time.set(NOW.plusSeconds(61));
        expectError("ref", "BOARDING_CANDIDATE_CHANGED", 409);
    }

    @Test
    @DisplayName("만료 세션 조회는 EXPIRED로 종료하고 current 점유를 해제한다")
    void expire_on_access() throws Exception {
        supportFixture();
        var original = service.create(owner.getId(), request(REQUEST_ID, "ref")).response();
        time.set(original.expiresAt());
        mvc.perform(auth(get("/api/v1/boarding-sessions/" + original.id()), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.reasonCode").value("SESSION_LIFETIME_EXPIRED"));
        mvc.perform(auth(get("/api/v1/boarding-sessions?scope=current"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("서로 다른 동시 생성 요청은 User 잠금과 UNIQUE로 하나만 성공한다")
    void concurrent_distinct_creates() throws Exception {
        supportConcurrent();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> createOutcome(REQUEST_ID));
            var b = pool.submit(() -> createOutcome(UUID.randomUUID().toString()));
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "BOARDING_SESSION_CONFLICT");
        }
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 UUID의 동시 생성은 하나의 세션을 반환하며 checkpoint와 기한을 초기화하지 않는다")
    void concurrent_same_request() throws Exception {
        supportConcurrent();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> service.create(owner.getId(), request(REQUEST_ID, "ref")));
            var b = pool.submit(() -> service.create(owner.getId(), request(REQUEST_ID, "ref")));
            var first = a.get(10, TimeUnit.SECONDS);
            var second = b.get(10, TimeUnit.SECONDS);
            assertThat(first.response()).isEqualTo(second.response());
            assertThat(first.created()).isNotEqualTo(second.created());
        }
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("생성 commit 응답 유실과 rollback은 원래 검증 입력으로 한 번 재확인한다")
    void lost_commit_response_and_rollback() {
        supportFixture();
        for (boolean committed : List.of(true, false)) {
            sessions.deleteAll();
            var count = new java.util.concurrent.atomic.AtomicInteger();
            PlatformTransactionManager failing = new PlatformTransactionManager() {
                public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
                public void commit(TransactionStatus status) {
                    if (count.incrementAndGet() == 2) {
                        if (committed) { manager.commit(status); } else { manager.rollback(status); }
                        throw new TransactionSystemException("controlled commit response failure");
                    }
                    manager.commit(status);
                }
                public void rollback(TransactionStatus status) { manager.rollback(status); }
            };
            var tested = new BoardingSessionService(sessions, users, validator, metadata, properties, failing, clock, new com.stopbell.boarding.service.BoardingTrackingRuntime(), notifications);
            var result = tested.create(owner.getId(), request(REQUEST_ID, "ref"));
            assertThat(sessions.count()).isEqualTo(1);
            assertThat(result.created()).isEqualTo(!committed);
            assertThat(result.response().checkpointObservedAt()).isEqualTo(NOW);
            assertThat(result.response().expiresAt()).isEqualTo(NOW.plusSeconds(14400));
        }
    }

    @Test
    @DisplayName("재확인은 JWT와 소유권을 먼저 검증하고 타 사용자와 없는 세션을 같은 404로 반환한다")
    void reconfirm_authentication_and_ownership() throws Exception {
        long id = suspendedFixture();
        clearInvocations(validator);
        mvc.perform(reconfirmRequest(id, 1, "ref")).andExpect(status().isUnauthorized());
        mvc.perform(auth(reconfirmRequest(id, 1, "ref"), other)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARDING_SESSION_NOT_FOUND"));
        mvc.perform(auth(reconfirmRequest(999999L, 1, "ref"), owner)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARDING_SESSION_NOT_FOUND"));
        verifyNoInteractions(validator);
    }

    @Test
    @DisplayName("검증 fixture 재확인은 같은 세션과 최초 기한을 유지하며 새 checkpoint commit 뒤 READY로 재개한다")
    void reconfirm_success_and_lost_response_retry() throws Exception {
        long id = suspendedFixture();
        var original = sessions.findById(id).orElseThrow();
        freshReconfirmationFixture();
        mvc.perform(auth(reconfirmRequest(id, 1, "ref"), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.status").value("TRACKING"))
                .andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.evaluationStatus").value("READY"))
                .andExpect(jsonPath("$.remainingOccurrenceGap").value(2))
                .andExpect(jsonPath("$.checkpointObservedAt").value(time.get().toString()))
                .andExpect(jsonPath("$.expiresAt").value(NOW.plusSeconds(14400).toString()));
        var recovered = sessions.findById(id).orElseThrow();
        assertThat(recovered.getTraversal()).isEqualTo(original.getTraversal());
        assertThat(recovered.getCreatedAt()).isEqualTo(original.getCreatedAt());
        mvc.perform(auth(reconfirmRequest(id, 1, "ref"), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_REVISION_CONFLICT"));
        assertThat(sessions.findById(id).orElseThrow().getCheckpoint()).isEqualTo(recovered.getCheckpoint());
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isZero();
    }

    @Test
    @DisplayName("실제 Provider의 미검증 하차 가능 근거는 재확인에서도 성공으로 우회하지 않는다")
    void reconfirm_real_provider_remains_unsupported() throws Exception {
        long id = suspendedFixture();
        doCallRealMethod().when(validator).validate(anyLong(), anyString(), anyString());
        mvc.perform(auth(reconfirmRequest(id, 1, candidateRef()), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_TRACKING_UNSUPPORTED"));
        assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo(BoardingSessionStatus.SUSPENDED);
    }

    @Test
    @DisplayName("만료되거나 사라진 candidateRef는 재확인 세션을 변경하지 않는다")
    void reconfirm_expired_reference() throws Exception {
        long id = suspendedFixture();
        doCallRealMethod().when(validator).validate(anyLong(), anyString(), anyString());
        String reference = candidateRef();
        time.set(NOW.plusSeconds(61));
        mvc.perform(auth(reconfirmRequest(id, 1, reference), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_CANDIDATE_CHANGED"));
        assertThat(sessions.findById(id).orElseThrow().getRevision()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    @DisplayName("재확인으로 차량 ID·전체 번호·목적 occurrence·Route·Provider를 변경할 수 없다")
    void reconfirm_identity_and_target_change(int scenario) throws Exception {
        long id = suspendedFixture();
        var context = metadata.read(route.getId(), target.getId(), first.getId());
        if (scenario == 2) {
            var middle = occurrences.findAllByRouteOrderByStopOrderAsc(route).get(1);
            context = metadata.read(route.getId(), middle.getId(), first.getId());
        } else if (scenario == 3 || scenario == 4) {
            context = new com.stopbell.transit.service.BoardingRouteSnapshot(context.routeId(),
                    scenario == 4 ? TransitProvider.TAGO : context.provider(),
                    scenario == 3 ? "other-route" : context.externalRouteId(), scenario == 4 ? "31010" : null,
                    context.targetOccurrenceId(), context.boardingOccurrenceId(), context.traversal(), context.routeNumber());
        }
        var original = verified(context);
        var changed = new VerifiedBoardingStart(context, original.traversal(), original.checkpoint(), original.referenceExpiresAt(),
                scenario == 0 ? "other-vehicle" : original.vehicleTrackingId(), scenario == 1 ? "다른번호1234" : NUMBER,
                original.routeNumber(), original.targetName());
        doReturn(changed).when(validator).validate(anyLong(), anyString(), anyString());
        mvc.perform(auth(reconfirmRequest(id, 1, "ref"), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_CANDIDATE_CHANGED"));
        assertThat(sessions.findById(id).orElseThrow().getRevision()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRACKING", "CANCELLED", "COMPLETED", "EXPIRED"})
    @DisplayName("보류 이외 상태와 terminal 세션은 재확인으로 활성화할 수 없다")
    void reconfirm_requires_suspension(String state) throws Exception {
        long id = suspendedFixture();
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            var session = sessions.findForUpdate(id).orElseThrow();
            switch (state) {
                case "TRACKING" -> session.reconfirm(1, session.getCheckpoint(), com.stopbell.boarding.BoardingSessionFixtures.UTC_NOW.plusSeconds(20));
                case "CANCELLED" -> session.cancel(1, com.stopbell.boarding.BoardingSessionFixtures.UTC_NOW.plusSeconds(20));
                case "EXPIRED" -> session.expire(session.getExpiresAt());
                default -> {
                    session.reconfirm(1, session.getCheckpoint(), com.stopbell.boarding.BoardingSessionFixtures.UTC_NOW.plusSeconds(20));
                    var t = session.getTraversal().occurrences().get(2);
                    session.track(2, new BoardingCheckpoint(t.externalStopId(), t.order(), 2, BoardingCheckpoint.Evidence.ID_ORDER,
                            null, UTC_NOW.plusSeconds(20), UTC_NOW.plusSeconds(19)), BoardingStateReason.ARRIVAL_OBSERVED,
                            UTC_NOW.plusSeconds(20));
                }
            }
        });
        long revision = sessions.findById(id).orElseThrow().getRevision();
        mvc.perform(auth(reconfirmRequest(id, revision, "ref"), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_RECONFIRM_REQUIRED"));
        assertThat(sessions.findById(id).orElseThrow().getStatus().name()).isEqualTo(state);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("재확인 Provider I/O 중 취소·만료가 되면 checkpoint를 저장하거나 terminal을 되살리지 않는다")
    void reconfirm_terminal_during_io(boolean expired) throws Exception {
        long id = suspendedFixture();
        freshReconfirmationFixture();
        var start = freshStart();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return start;
        }).when(validator).validate(anyLong(), anyString(), anyString());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = pool.submit(() -> {
                try { service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, "ref", NUMBER)); return "SUCCESS"; }
                catch (ApiException exception) { return exception.getErrorCode().name(); }
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            if (expired) { time.set(NOW.plusSeconds(14400)); }
            else { service.cancel(owner.getId(), id, 1); }
            release.countDown();
            assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("BOARDING_RECONFIRM_REQUIRED");
        } finally { release.countDown(); }
        var ended = sessions.findById(id).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(expired ? BoardingSessionStatus.EXPIRED : BoardingSessionStatus.CANCELLED);
        assertThat(ended.getCheckpoint().getObservedAt()).isEqualTo(UTC_NOW);
        assertThat(runtime.isReady(com.stopbell.boarding.domain.BoardingTrackingSnapshot.from(ended), UTC_NOW.plusSeconds(20))).isFalse();
    }

    @Test
    @DisplayName("재확인 검증 후 metadata가 바뀌면 원래 snapshot을 보존하고 반영하지 않는다")
    void reconfirm_metadata_race() throws Exception {
        long id = suspendedFixture();
        freshReconfirmationFixture();
        var start = freshStart();
        doAnswer(invocation -> {
            occurrence("added", 40);
            return start;
        }).when(validator).validate(anyLong(), anyString(), anyString());
        mvc.perform(auth(reconfirmRequest(id, 1, "ref"), owner)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOARDING_CANDIDATE_CHANGED"));
        assertThat(sessions.findById(id).orElseThrow().getRevision()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("재확인 commit 응답 유실은 새 transaction에서 같은 baseline을 확인하고 rollback은 재적용하지 않는다")
    void reconfirm_commit_reconciliation(boolean persisted) {
        long id = suspendedFixture();
        freshReconfirmationFixture();
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        PlatformTransactionManager uncertain = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) { return manager.getTransaction(definition); }
            public void commit(TransactionStatus status) {
                if (counter.incrementAndGet() == 2) {
                    if (persisted) { manager.commit(status); } else { manager.rollback(status); }
                    throw new TransactionSystemException("controlled acknowledgement loss");
                }
                manager.commit(status);
            }
            public void rollback(TransactionStatus status) { manager.rollback(status); }
        };
        var tested = new BoardingSessionService(sessions, users, validator, metadata, properties, uncertain, clock, runtime, notifications);
        var request = new ReconfirmBoardingSessionRequest(1, "ref", NUMBER);
        if (persisted) {
            assertThat(tested.reconfirm(owner.getId(), id, request).evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.READY);
        } else {
            assertThatThrownBy(() -> tested.reconfirm(owner.getId(), id, request)).isInstanceOf(TransactionSystemException.class);
        }
        var current = sessions.findById(id).orElseThrow();
        assertThat(current.getRevision()).isEqualTo(persisted ? 2 : 1);
        assertThat(current.getExpiresAt()).isEqualTo(UTC_NOW.plusHours(4));
    }

    @Test
    @DisplayName("동일 revision의 재확인 두 요청은 MySQL lock 아래 한 번만 적용되며 다른 결과는 409다")
    void concurrent_reconfirmation() throws Exception {
        long id = suspendedFixture();
        freshReconfirmationFixture();
        var start = freshStart();
        var both = new CountDownLatch(2);
        doAnswer(invocation -> {
            both.countDown();
            assertThat(both.await(10, TimeUnit.SECONDS)).isTrue();
            return start;
        }).when(validator).validate(anyLong(), anyString(), anyString());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<String> call = () -> {
                try { service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, "ref", NUMBER)); return "SUCCESS"; }
                catch (ApiException exception) { return exception.getErrorCode().name(); }
            };
            var a = pool.submit(call);
            var b = pool.submit(call);
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "BOARDING_REVISION_CONFLICT");
        }
        assertThat(sessions.findById(id).orElseThrow().getRevision()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedRevision\":1}",
            "{\"expectedRevision\":1.5,\"candidateRef\":\"ref\",\"confirmedVehicleNumber\":\"number\"}",
            "{\"expectedRevision\":-1,\"candidateRef\":\"ref\",\"confirmedVehicleNumber\":\"number\"}",
            "{\"expectedRevision\":1,\"candidateRef\":42,\"confirmedVehicleNumber\":\"number\"}"})
    @DisplayName("재확인 body는 음수가 아닌 정수 revision과 문자열 reference·전체 번호를 요구한다")
    void reconfirm_invalid_body(String body) throws Exception {
        mvc.perform(auth(post("/api/v1/boarding-sessions/1/reconfirm").contentType("application/json").content(body), owner))
                .andExpect(status().isBadRequest());
    }

    private long suspendedFixture() {
        supportFixture();
        long id = service.create(owner.getId(), request(REQUEST_ID, "ref")).response().id();
        new TransactionTemplate(manager).executeWithoutResult(status -> sessions.findForUpdate(id).orElseThrow()
                .suspend(0, BoardingStateReason.CONTINUITY_GAP_EXCEEDED, UTC_NOW));
        runtime.committed(id, 1, false);
        return id;
    }

    private VerifiedBoardingStart freshStart() {
        var start = verified(metadata.read(route.getId(), target.getId(), first.getId()));
        var at = java.time.LocalDateTime.ofInstant(time.get(), java.time.ZoneOffset.UTC);
        var checkpoint = new BoardingCheckpoint("s1", 10, 0, BoardingCheckpoint.Evidence.ID_ORDER, null, at, at.minusSeconds(1));
        return new VerifiedBoardingStart(start.metadata(), start.traversal(), checkpoint, time.get().plusSeconds(60),
                start.vehicleTrackingId(), NUMBER, start.routeNumber(), start.targetName());
    }

    private void freshReconfirmationFixture() {
        time.set(NOW.plusSeconds(20));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return freshStart();
        }).when(validator).validate(anyLong(), anyString(), anyString());
    }

    private static MockHttpServletRequestBuilder reconfirmRequest(long id, long revision, String reference) {
        return post("/api/v1/boarding-sessions/" + id + "/reconfirm").contentType("application/json")
                .content("{\"expectedRevision\":" + revision + ",\"candidateRef\":\"" + reference
                        + "\",\"confirmedVehicleNumber\":\"" + NUMBER + "\"}");
    }

    private void supportFixture() {
        // Isolate the persistence/API boundary with explicitly verified fixture evidence.
        // The real validator's production support refusal is tested separately above.
        var start = verified(metadata.read(route.getId(), target.getId(), first.getId()));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return start;
        }).when(validator).validate(anyLong(), anyString(), anyString());
    }

    private void supportConcurrent() {
        var start = verified(metadata.read(route.getId(), target.getId(), first.getId()));
        var both = new CountDownLatch(2);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            both.countDown();
            assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
            return start;
        }).when(validator).validate(anyLong(), anyString(), anyString());
    }

    private String createOutcome(String id) {
        try { service.create(owner.getId(), request(id, "ref")); return "CREATED"; }
        catch (ApiException exception) { return exception.getErrorCode().name(); }
    }

    private String candidateRef() {
        return candidates.find(owner.getId(), route.getId(), target.getId(), first.getId()).candidates().getFirst().candidateRef();
    }

    private BusRouteStopOccurrence occurrence(String id, int order) {
        return occurrences.saveAndFlush(new BusRouteStopOccurrence(route,
                stops.saveAndFlush(new BusStop(TransitProvider.SEOUL_BUS, id, id.equals("s3") ? "목적지" : id, null, null)), order));
    }

    private static SeoulBusVehicleLocationResponse roster(SeoulBusVehicleLocationItem... items) {
        return new SeoulBusVehicleLocationResponse(new SeoulBusVehicleLocationResponse.Header("0", null),
                new SeoulBusVehicleLocationResponse.Body(List.of(items)));
    }

    private static SeoulBusVehicleDetailResponse progress(String stop, int order, int flag) {
        return new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("0", null),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem("vehicle-id", NUMBER,
                        stop, order, flag, "20261020120000", null, null))));
    }

    private static CreateBoardingSessionRequest request(String key, String reference) {
        return new CreateBoardingSessionRequest(key, reference, NUMBER);
    }

    private static String body(String key, String reference) {
        return "{\"clientRequestId\":\"" + key + "\",\"candidateRef\":\"" + reference + "\",\"confirmedVehicleNumber\":\"" + NUMBER + "\"}";
    }

    private static MockHttpServletRequestBuilder postSession(String key, String reference) {
        return post("/api/v1/boarding-sessions").contentType("application/json").content(body(key, reference));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(user.getId()));
    }

    private void expectError(String reference, String code, int status) throws Exception {
        mvc.perform(auth(postSession(REQUEST_ID, reference), owner)).andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));
        assertThat(sessions.count()).isZero();
    }
}
