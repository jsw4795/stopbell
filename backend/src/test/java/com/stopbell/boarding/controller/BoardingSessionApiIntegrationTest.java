package com.stopbell.boarding.controller;

import static com.stopbell.boarding.BoardingSessionFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.stopbell.boarding.dto.CreateBoardingSessionRequest;
import com.stopbell.boarding.dto.ReconfirmBoardingSessionRequest;
import com.stopbell.boarding.dto.BoardingSessionResponse;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.entity.BoardingCheckpoint;
import com.stopbell.boarding.entity.BoardingTraversalSnapshot;
import com.stopbell.boarding.entity.BoardingSessionStatus;
import com.stopbell.boarding.service.VerifiedBoardingStart;
import com.stopbell.boarding.service.BoardingTrackingRuntime;
import com.stopbell.boarding.entity.BoardingStateReason;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.boarding.service.BoardingSessionService;
import com.stopbell.boarding.service.BoardingSessionStartValidator;
import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.boarding.service.BoardingTrackingStore;
import com.stopbell.notification.entity.*;
import com.stopbell.notification.repository.*;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
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
    @MockitoSpyBean private com.stopbell.boarding.service.BoardingNotificationService notifications;
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwt;
    @Autowired private UserRepository users;
    @Autowired private BusRouteRepository routes;
    @Autowired private BusStopRepository stops;
    @Autowired private BusRouteStopOccurrenceRepository occurrences;
    @Autowired private BoardingSessionRepository sessions;
    @Autowired private BoardingSessionService service;
    @Autowired private BoardingTrackingRuntime runtime;
    @Autowired private com.stopbell.boarding.service.BoardingSessionPollingService polling;
    @Autowired private BoardingCandidateMetadataService metadata;
    @Autowired private BoardingCandidateService candidates;
    @Autowired private com.stopbell.boarding.service.BoardingSessionProperties properties;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeviceRepository devices;
    @Autowired private BoardingTrackingStore trackingStore;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @MockitoSpyBean private NotificationEventRepository events;
    @MockitoSpyBean private NotificationDeliveryRepository deliveries;
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
        jdbc.update("delete from notification_deliveries");
        jdbc.update("delete from notification_events");
        devices.deleteAll();
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
    @DisplayName("반복 Stop의 첫 방문과 목적지 재방문은 실제 후보·저장·이동 polling에서도 정확한 occurrence로 구분한다")
    void repeated_destination_occurrence_across_real_candidate_and_polling() {
        target = occurrences.saveAndFlush(new BusRouteStopOccurrence(route, first.getStop(), 40));
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(progress("s1", 10, 0));
        var created = service.create(owner.getId(), new CreateBoardingSessionRequest(REQUEST_ID, candidateRef(), null, 1));
        assertThat(created.response().targetName()).isEqualTo("s1");
        assertThat(created.response().targetOrder()).isEqualTo(40);
        assertThat(created.response().remainingOccurrenceGap()).isEqualTo(3);
        assertThat(created.response().status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isZero();
        time.set(NOW.plusSeconds(20));
        when(seoul.fetchVehicleLocations(any())).thenAnswer(invocation -> roster(new SeoulBusVehicleLocationItem(
                "vehicle-id", NUMBER, 999, "coarse", 0, liveDataTime(), null, null, null, null, null)));
        when(detail.fetchVehicleDetail("vehicle-id")).thenAnswer(invocation -> liveProgress("s3", 30, 0));
        polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
        assertThat(service.find(owner.getId(), created.response().id()).remainingOccurrenceGap()).isEqualTo(1);
        time.set(NOW.plusSeconds(40));
        when(detail.fetchVehicleDetail("vehicle-id")).thenAnswer(invocation -> liveProgress("s1", 40, 0));
        polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
        assertThat(service.find(owner.getId(), created.response().id()).status()).isEqualTo(BoardingSessionStatus.COMPLETED);
        assertThat(jdbc.queryForList("select boarding_event_type from notification_events", String.class)).containsExactly("PREPARE_ALIGHT");
    }

    @Test
    @DisplayName("등록 근거의 준비 TTL이 이미 지났으면 기회를 소비하지 않고 이후 새 이동 관측에서 대체 알림을 생성한다")
    void initial_expired_preparation_keeps_later_opportunity() {
        supportFixture();
        time.set(NOW.plusSeconds(31));
        var created = service.create(owner.getId(), request(REQUEST_ID, "ref"));
        assertThat(created.response().preparationNotificationStatus()).isEqualTo("NOT_CREATED");
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isZero();
        time.set(NOW.plusSeconds(40));
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster(new SeoulBusVehicleLocationItem(
                "vehicle-id", NUMBER, 999, "coarse", 0, liveDataTime(), null, null, null, null, null)));
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(liveProgress("s2", 20, 0));
        polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
        assertThat(jdbc.queryForList("select boarding_event_type from notification_events", String.class)).containsExactly("PREPARE_ALIGHT");
        assertThat(service.find(owner.getId(), created.response().id()).preparationNotificationStatus()).isEqualTo("NO_RECIPIENT");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,0", "1,1", "2,0", "2,1"})
    @DisplayName("등록 시 이미 목적지 한 occurrence 전이면 최신 이동·정차 근거로 실제 목적지 준비 알림을 즉시 한 번 생성한다")
    void initial_registration_already_in_notification_window(int offset, int flag) {
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(progress("s2", 20, flag));
        var request = new CreateBoardingSessionRequest(REQUEST_ID, candidateRef(), null, offset);
        var created = service.create(owner.getId(), request);
        assertThat(created.response().evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.READY);
        assertThat(created.response().remainingOccurrenceGap()).isEqualTo(1);
        assertThat(created.response().targetName()).isEqualTo("목적지");
        assertThat(created.response().preparationNotificationStatus()).isEqualTo("NO_RECIPIENT");
        service.create(owner.getId(), request);
        polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
        assertThat(jdbc.queryForList("select boarding_event_type from notification_events", String.class)).containsExactly("PREPARE_ALIGHT");
        assertThat(jdbc.queryForObject("select current_stop_external_id from notification_events", String.class)).isEqualTo("s2");
        assertThat(sessions.findById(created.response().id()).orElseThrow().getTraversal().targetIndex()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notification_deliveries", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    @DisplayName("실제 Validator 생성은 알림 시점을 저장하고 UUID의 설정 변경을 거부한다")
    void real_creation_preserves_notification_choice(int offset) throws Exception {
        String ref = candidateRef();
        String content = body(REQUEST_ID, ref).replace("}", ",\"notificationOffsetStops\":" + offset + "}");
        mvc.perform(auth(postSession(REQUEST_ID, ref).content(content), owner)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.notificationOffsetStops").value(offset))
                .andExpect(jsonPath("$.preparationNotificationStatus").value(offset == 2 ? "NO_RECIPIENT" : "NOT_CREATED"));
        assertThat(sessions.findAll()).singleElement().extracting(BoardingSession::getNotificationOffsetStops).isEqualTo(offset);
        runtime.forget(sessions.findAll().getFirst().getId());
        mvc.perform(auth(postSession(REQUEST_ID, ref).content(content), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationOffsetStops").value(offset));
        mvc.perform(auth(postSession(REQUEST_ID, ref).content(content.replace(":" + offset + "}", ":" + (3 - offset) + "}")), owner))
                .andExpect(status().isConflict()).andExpect(apiError(ErrorCode.BOARDING_REQUEST_CONFLICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "3", "1.0", "true", "\"1\"", "{}", "2147483649"})
    @DisplayName("알림 시점은 생략하거나 정수 1과 2만 허용한다")
    void reject_invalid_preparation_choice(String value) throws Exception {
        mvc.perform(auth(postSession(REQUEST_ID, "ref").content(body(REQUEST_ID, "ref")
                .replace("}", ",\"notificationOffsetStops\":" + value + "}")), owner))
                .andExpect(status().isBadRequest());
        assertThat(sessions.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("기존 ON/OFF 요청은 저장된 세션 재시도만 허용하고 새 세션 정책으로 해석하지 않는다")
    void legacy_request_only_reconciles_existing_session(boolean enabled) throws Exception {
        String ref = "legacy-ref";
        String content = body(REQUEST_ID, ref).replace("}", ",\"prepareNotificationEnabled\":" + enabled + "}");
        mvc.perform(auth(postSession(REQUEST_ID, ref).content(content), owner)).andExpect(status().isBadRequest());
        var start = verified(metadata());
        var legacy = new CreateBoardingSessionRequest(REQUEST_ID, ref, NUMBER, enabled);
        sessions.saveAndFlush(new BoardingSession(owner, REQUEST_ID, legacy.fingerprint(), "vehicle-id", NUMBER,
                "7000", "목적지", start.traversal(), start.checkpoint(), UTC_NOW, UTC_NOW.plusHours(4), enabled));
        mvc.perform(auth(postSession(REQUEST_ID, ref).content(content), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationOffsetStops").isEmpty())
                .andExpect(jsonPath("$.prepareNotificationEnabled").value(enabled));
        verifyNoInteractions(seoul, detail);
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
    @DisplayName("최신 검증 입력은 201 TRACKING과 현재 READY 및 고정 수명을 반환하고 준비 Event를 즉시 생성한다")
    void create_verified_session() throws Exception {
        supportFixture();
        var response = mvc.perform(auth(postSession(REQUEST_ID, "ref"), owner)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TRACKING"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.routeNumber").value("7000"))
                .andExpect(jsonPath("$.notificationOffsetStops").value(2))
                .andExpect(jsonPath("$.vehicleNumber").value(NUMBER))
                .andExpect(jsonPath("$.targetOrder").value(30))
                .andExpect(jsonPath("$.remainingOccurrenceGap").value(2))
                .andExpect(jsonPath("$.evaluationStatus").value("READY"))
                .andExpect(jsonPath("$.checkpointObservedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.expiresAt").value(NOW.plusSeconds(14400).toString()))
                .andExpect(jsonPath("$.vehicleTrackingId").doesNotExist())
                .andExpect(jsonPath("$.traversal").doesNotExist())
                .andExpect(jsonPath("$.traversalFingerprint").doesNotExist()).andReturn();
        long id = json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        assertThat(sessions.findById(id).orElseThrow().getCheckpoint().getIndex()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from alarms", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(1);
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
                .andExpect(apiError(ErrorCode.BOARDING_REQUEST_CONFLICT));
        mvc.perform(auth(postSession(REQUEST_ID, "ref").content(body(REQUEST_ID, "ref").replace(NUMBER, NUMBER + " ")), owner))
                .andExpect(status().isConflict()).andExpect(apiError(ErrorCode.BOARDING_REQUEST_CONFLICT));
        mvc.perform(auth(postSession(UUID.randomUUID().toString(), "ref"), owner)).andExpect(status().isConflict())
                .andExpect(apiError(ErrorCode.BOARDING_SESSION_CONFLICT));
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
        mvc.perform(auth(get(path), other)).andExpect(status().isNotFound()).andExpect(apiError(ErrorCode.BOARDING_SESSION_NOT_FOUND));
        mvc.perform(auth(get("/api/v1/boarding-sessions/999999"), owner)).andExpect(status().isNotFound()).andExpect(apiError(ErrorCode.BOARDING_SESSION_NOT_FOUND));
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
                .andExpect(status().isConflict()).andExpect(apiError(ErrorCode.BOARDING_REVISION_CONFLICT));
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
                .andExpect(apiError(ErrorCode.INVALID_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedRevision\":null}", "{\"expectedRevision\":-1}",
            "{\"expectedRevision\":0.5}", "{\"expectedRevision\":\"0\"}", "{\"expectedRevision\":9223372036854775808}"})
    @DisplayName("취소 revision은 생략할 수 없는 0 이상의 정수다")
    void invalid_revision_body(String body) throws Exception {
        mvc.perform(auth(post("/api/v1/boarding-sessions/1/cancel").contentType("application/json").content(body), owner))
                .andExpect(status().isBadRequest()).andExpect(apiError(ErrorCode.INVALID_REQUEST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"clientRequestId\":\"bad\",\"candidateRef\":\"ref\",\"confirmedVehicleNumber\":\"number\"}",
            "{\"clientRequestId\":\"aaaaaaaa-1111-4111-8111-111111111111\",\"candidateRef\":123,\"confirmedVehicleNumber\":\"number\"}"})
    @DisplayName("생성 body는 canonical UUID와 문자열 reference 및 전체 번호를 요구한다")
    void invalid_create_body(String body) throws Exception {
        mvc.perform(auth(post("/api/v1/boarding-sessions").contentType("application/json").content(body), owner))
                .andExpect(status().isBadRequest()).andExpect(apiError(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("타 User와 만료 reference는 새 세션 없이 거부한다")
    void reject_invalid_reference_and_number() throws Exception {
        String reference = candidateRef();
        mvc.perform(auth(postSession(REQUEST_ID, reference), other)).andExpect(status().isConflict())
                .andExpect(apiError(ErrorCode.BOARDING_CANDIDATE_CHANGED));
        time.set(NOW.plusSeconds(61));
        mvc.perform(auth(postSession(REQUEST_ID, reference), owner)).andExpect(status().isConflict())
                .andExpect(apiError(ErrorCode.BOARDING_CANDIDATE_CHANGED));
        assertThat(sessions.count()).isZero();
    }

    @Test
    @DisplayName("실제 Validator는 하차 가능 근거 없이도 검증된 서울 차량과 정확한 목적 occurrence의 세션을 생성한다")
    void real_validator_creates_session_with_unknown_access() throws Exception {
        String reference = candidateRef();
        mvc.perform(auth(postSession(REQUEST_ID, reference), owner)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TRACKING"));
        assertThat(sessions.findAll()).singleElement().satisfies(value -> {
            assertThat(value.getTraversal().targetAlightAvailability()).isEqualTo(BoardingTraversalSnapshot.AlightAvailability.UNKNOWN);
            assertThat(value.getTraversal().occurrences().get(value.getTraversal().targetIndex()).occurrenceId()).isEqualTo(target.getId());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("정차 뒤 이동 중 등록은 최신 검증 기준점과 선택 시점을 보존하고 알림 구간이면 즉시 생성한다")
    void create_from_stopped_baseline_while_moving(boolean prepare) throws Exception {
        var lookup = mvc.perform(auth(get("/api/v1/bus-routes/" + route.getId() + "/boarding-candidates")
                        .param("targetStopOccurrenceId", target.getId().toString())
                        .param("boardingStopOccurrenceId", first.getId().toString()), owner))
                .andExpect(status().isOk()).andReturn();
        String reference = json.readTree(lookup.getResponse().getContentAsString())
                .get("candidates").get(0).get("candidateRef").asString();
        time.set(NOW.plusSeconds(10));
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", null), new SeoulBusVehicleDetailResponse.Body(List.of(
                new SeoulBusVehicleDetailItem("vehicle-id", NUMBER, "s1", 10, 0, "20261020120010", null, null)))));
        String content = body(REQUEST_ID, reference).replace("}", ",\"notificationOffsetStops\":" + (prepare ? 2 : 1) + "}");

        mvc.perform(auth(postSession(REQUEST_ID, reference).content(content), owner))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("TRACKING"))
                .andExpect(jsonPath("$.checkpointObservedAt").value(NOW.plusSeconds(10).toString()))
                .andExpect(jsonPath("$.providerDataTime").value(NOW.plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()))
                .andExpect(jsonPath("$.evaluationStatus").value("READY"))
                .andExpect(jsonPath("$.remainingOccurrenceGap").value(2))
                .andExpect(jsonPath("$.notificationOffsetStops").value(prepare ? 2 : 1));
        assertThat(sessions.findAll()).singleElement().satisfies(session -> {
            assertThat(session.getCheckpoint().getObservedAt()).isEqualTo(UTC_NOW.plusSeconds(10));
            assertThat(session.getCheckpoint().getProviderDataTime())
                    .isEqualTo(UTC_NOW.plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            assertThat(session.getCheckpoint().getIndex()).isZero();
        });
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(prepare ? 1 : 0);
        assertThat(jdbc.queryForObject("select count(*) from notification_deliveries", Long.class)).isZero();
        time.set(NOW.plusSeconds(61));
        mvc.perform(auth(postSession(REQUEST_ID, reference).content(content), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointObservedAt").value(NOW.plusSeconds(10).toString()));
        assertThat(sessions.count()).isEqualTo(1);
        verify(seoul, times(2)).fetchVehicleLocations(any());
        verify(detail, times(2)).fetchVehicleDetail("vehicle-id");
        verifyNoInteractions(tago);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_BASELINE", "CHANGED_REFERENCE", "TARGET_REACHED", "EXPIRED"})
    @DisplayName("이동 차량과 목적지 현재 차량도 즉시 추적하고 만료 후보는 거부한다")
    void reject_unsupported_moving_creation(String issue) throws Exception {
        if (issue.equals("NO_BASELINE")) {
            when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(progress("s1", 10, 0));
        }
        String reference = candidateRef();
        time.set(NOW.plusSeconds(issue.equals("EXPIRED") ? 61 : 10));
        String stop = issue.equals("TARGET_REACHED") ? "s3" : issue.equals("CHANGED_REFERENCE") ? "s2" : "s1";
        int order = stop.equals("s3") ? 30 : stop.equals("s2") ? 20 : 10;
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", null), new SeoulBusVehicleDetailResponse.Body(List.of(
                new SeoulBusVehicleDetailItem("vehicle-id", NUMBER, stop, order, 0, "20261020120010", null, null)))));

        if (!issue.equals("EXPIRED")) {
            String content = "{\"clientRequestId\":\"" + REQUEST_ID + "\",\"candidateRef\":\"" + reference + "\"}";
            mvc.perform(auth(postSession(REQUEST_ID, reference).content(content), owner))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("TRACKING"))
                    .andExpect(jsonPath("$.evaluationStatus").value(issue.equals("TARGET_REACHED") ? "VERIFICATION_PENDING" : "READY"))
                    .andExpect(jsonPath("$.remainingOccurrenceGap").value(issue.equals("TARGET_REACHED") ? null : issue.equals("CHANGED_REFERENCE") ? 1 : 2))
                    .andExpect(jsonPath("$.checkpointObservedAt").value(issue.equals("TARGET_REACHED") ? null : NOW.plusSeconds(10).toString()));
            mvc.perform(auth(postSession(REQUEST_ID, reference).content(content), owner))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TRACKING"));
            assertThat(sessions.count()).isEqualTo(1);
            assertThat(service.findCurrent(owner.getId())).hasSize(1);
        } else {
            expectError(reference, "BOARDING_CANDIDATE_CHANGED", 409);
            assertThat(sessions.count()).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(issue.equals("EXPIRED") || issue.equals("TARGET_REACHED") ? 0 : 1);
        assertThat(jdbc.queryForObject("select count(*) from notification_deliveries", Long.class)).isZero();
        verify(detail, times(issue.equals("EXPIRED") ? 1 : 2)).fetchVehicleDetail("vehicle-id");
    }

    @Test
    @DisplayName("같은 제공시각의 occurrence 변경은 실제 시작 검증에서 세션과 알림을 저장하지 않는다")
    void conflicting_progress_does_not_create_session() throws Exception {
        String reference = candidateRef();
        time.set(NOW.plusSeconds(1));
        doReturn(progress("s2", 20, 1)).when(detail).fetchVehicleDetail("vehicle-id");
        expectError(reference, "BOARDING_CANDIDATE_CHANGED", 409);
        assertThat(sessions.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_deliveries", Long.class)).isZero();
    }

    @Test
    @DisplayName("최신 roster가 운행 종료를 명시하면 실제 시작 검증에서 세션과 알림을 저장하지 않는다")
    void known_run_end_does_not_create_session() throws Exception {
        String reference = candidateRef();
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster(new SeoulBusVehicleLocationItem(
                "vehicle-id", NUMBER, 999, "coarse", 1, "20261020120000", null, null, "unused-coarse-stop", "0", null)));
        expectError(reference, "BOARDING_TRACKING_UNSUPPORTED", 409);
        assertThat(sessions.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_deliveries", Long.class)).isZero();
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
    @DisplayName("Provider 실패와 metadata 구조 변경을 거부하고 목적지 현재는 알림 없이 추적한다")
    void provider_metadata_and_target_failures() throws Exception {
        String reference = candidateRef();
        when(detail.fetchVehicleDetail("vehicle-id")).thenThrow(TransitProviderClientException.http(TransitProvider.SEOUL_BUS, "detail", 503, null));
        expectError(reference, "TRANSIT_TEMPORARILY_UNAVAILABLE", 503);
        time.set(NOW.plusSeconds(10));
        doReturn(liveProgress("s3", 30, 1)).when(detail).fetchVehicleDetail("vehicle-id");
        var created = service.create(owner.getId(), new CreateBoardingSessionRequest(REQUEST_ID, reference, null));
        assertThat(created.response().status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(created.response().evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.VERIFICATION_PENDING);
        service.cancel(owner.getId(), created.response().id(), created.response().revision());
        sessions.deleteAll();
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
            assertThat(first.response()).usingRecursiveComparison()
                    .ignoringFields("evaluationStatus", "remainingOccurrenceGap").isEqualTo(second.response());
            assertThat(first.response().preparationNotificationStatus()).isEqualTo("NO_RECIPIENT");
            assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(1);
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
            assertThat(jdbc.queryForObject("select count(*) from notification_events where boarding_session_id=?",
                    Long.class, result.response().id())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("재확인은 JWT와 소유권을 먼저 검증하고 타 사용자와 없는 세션을 같은 404로 반환한다")
    void reconfirm_authentication_and_ownership() throws Exception {
        long id = suspendedFixture();
        clearInvocations(validator);
        mvc.perform(reconfirmRequest(id, 1, "ref")).andExpect(status().isUnauthorized());
        mvc.perform(auth(reconfirmRequest(id, 1, "ref"), other)).andExpect(status().isNotFound())
                .andExpect(apiError(ErrorCode.BOARDING_SESSION_NOT_FOUND));
        mvc.perform(auth(reconfirmRequest(999999L, 1, "ref"), owner)).andExpect(status().isNotFound())
                .andExpect(apiError(ErrorCode.BOARDING_SESSION_NOT_FOUND));
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
                .andExpect(apiError(ErrorCode.BOARDING_REVISION_CONFLICT));
        assertThat(sessions.findById(id).orElseThrow().getCheckpoint()).isEqualTo(recovered.getCheckpoint());
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("실제 Validator의 재확인은 하차 분류와 무관하게 같은 차량과 경로의 검증된 기준점으로 재개한다")
    void reconfirm_real_validator_without_access_gate() throws Exception {
        long id = suspendedFixture();
        doCallRealMethod().when(validator).validate(anyLong(), anyString(), anyString());
        mvc.perform(auth(reconfirmRequest(id, 1, candidateRef()), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRACKING"));
        assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo(BoardingSessionStatus.TRACKING);
    }

    @Test
    @DisplayName("만료되거나 사라진 candidateRef는 재확인 세션을 변경하지 않는다")
    void reconfirm_expired_reference() throws Exception {
        long id = suspendedFixture();
        doCallRealMethod().when(validator).validate(anyLong(), anyString(), anyString());
        String reference = candidateRef();
        time.set(NOW.plusSeconds(61));
        mvc.perform(auth(reconfirmRequest(id, 1, reference), owner)).andExpect(status().isConflict())
                .andExpect(apiError(ErrorCode.BOARDING_CANDIDATE_CHANGED));
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
                .andExpect(apiError(ErrorCode.BOARDING_CANDIDATE_CHANGED));
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
                .andExpect(apiError(ErrorCode.BOARDING_RECONFIRM_REQUIRED));
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
                .andExpect(apiError(ErrorCode.BOARDING_CANDIDATE_CHANGED));
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

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2,1,1", "2,2,1", "1,1,0", "1,2,1", "2,0,0"})
    @DisplayName("AUD-08 실제 Validator 재확인은 현재 offset 구간에서만 준비 Event와 eligible Device별 Delivery를 원자 생성한다")
    void aud08_reconfirmation_window(int offset, int index, int count) {
        long id = aud08Suspended(offset);
        device(owner);
        device(owner);
        device(other);
        liveObservation(index);
        var response = service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, candidateRef(), null));
        assertThat(response.status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(response.revision()).isEqualTo(2);
        assertThat(response.evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.READY);
        assertThat(response.remainingOccurrenceGap()).isEqualTo(3 - index);
        assertThat(events.count()).isEqualTo(count);
        assertThat(deliveries.count()).isEqualTo(count * 2L);
        if (count == 1) {
            var event = events.findAll().getFirst();
            assertThat(event.getBoardingEventType()).isEqualTo(BoardingNotificationEventType.PREPARE_ALIGHT);
            assertThat(event.getObservedAt()).isEqualTo(LocalDateTime.ofInstant(time.get(), ZoneOffset.UTC));
        }
        // Same source data after reconfirmation is not new progress and must not duplicate the Event.
        polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
        assertThat(events.count()).isEqualTo(count);
        time.set(time.get().plusSeconds(5));
        liveObservation(2);
        polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
        assertThat(events.count()).isEqualTo(1);
        assertThat(deliveries.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("AUD-08 같은 위치·시각도 재확인한 유효 baseline이면 새 물리적 진행 없이 원래 TTL로 즉시 판단한다")
    void aud08_revalidated_baseline_without_new_progress() {
        long id = aud08Suspended(2);
        var start = aud08Start(1);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var session = sessions.findForUpdate(id).orElseThrow();
            session.reconfirm(1, start.checkpoint(), UTC_NOW.plusSeconds(20));
            session.suspend(2, BoardingStateReason.VEHICLE_MISSING, UTC_NOW.plusSeconds(20));
        });
        time.set(NOW.plusSeconds(25));
        doReturn(start).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        var response = service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(3, "ref", null));
        assertThat(response.evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.READY);
        var event = events.findAll().getFirst();
        assertThat(event.getObservedAt()).isEqualTo(UTC_NOW.plusSeconds(20));
        assertThat(event.getExpiresAt()).isEqualTo(UTC_NOW.plusSeconds(50));
        assertThat(event.getProviderDataTime()).isEqualTo(start.checkpoint().getProviderDataTime());
        assertThat(events.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "occurrence", "stale", "provider-regression", "same-provider", "same-received", "run-end"})
    @DisplayName("AUD-08 실제 후보 재검증의 식별·occurrence·freshness·제공/수신시각·운행 모순은 재확인 알림 근거가 되지 않는다")
    void aud08_real_validator_rejects_unsafe_evidence(String scenario) {
        long id = aud08Suspended(2);
        liveObservation(1);
        String originalDataTime = liveDataTime();
        String ref = candidateRef();
        if (!scenario.equals("same-received")) {
            time.set(time.get().plusSeconds(scenario.equals("stale") ? 61 : 5));
        }
        liveObservation(2);
        if (scenario.equals("run-end")) {
            when(seoul.fetchVehicleLocations(any())).thenReturn(roster(new SeoulBusVehicleLocationItem(
                    "vehicle-id", NUMBER, 999, "coarse", 1, liveDataTime(), null, null, null, "0", null)));
        } else {
            String dataTime = scenario.equals("provider-regression") ? "20261020120019"
                    : scenario.equals("same-provider") || scenario.equals("stale") ? originalDataTime : liveDataTime();
            when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(new SeoulBusVehicleDetailResponse(
                    new SeoulBusVehicleDetailResponse.Header("0", null), new SeoulBusVehicleDetailResponse.Body(List.of(
                            new SeoulBusVehicleDetailItem("vehicle-id", scenario.equals("identity") ? "다른번호" : NUMBER,
                                    scenario.equals("occurrence") ? "unknown-stop" : "s3", 30, 0, dataTime, null, null)))));
        }
        assertThatThrownBy(() -> service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, ref, null)))
                .isInstanceOf(ApiException.class);
        assertThat(sessions.findById(id).orElseThrow().getRevision()).isEqualTo(1);
        assertThat(events.count()).isZero();
        assertThat(deliveries.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    @DisplayName("AUD-08 목적지 도달·통과 위치는 기존 미검증 재개 정책을 유지하며 준비 알림을 소급 생성하지 않는다")
    void aud08_destination_is_not_late_preparation(int index) {
        long id = aud08Suspended(2);
        liveObservation(1);
        String ref = candidateRef();
        time.set(time.get().plusSeconds(5));
        liveObservation(index);
        var response = service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, ref, null));
        assertThat(response.status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(response.evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.VERIFICATION_PENDING);
        assertThat(events.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"old-received", "old-provider", "same-received-conflict", "same-provider-conflict",
            "regressed-index", "direction-conflict", "unverified", "stale", "future"})
    @DisplayName("AUD-08 과거 checkpoint와 충돌하거나 미검증·오래된 재확인 근거로 즉시 알림을 만들지 않는다")
    void aud08_conflicting_evidence_does_not_notify(String scenario) {
        long id = aud08Suspended(2);
        var start = aud08Start(1);
        var at = LocalDateTime.ofInstant(time.get(), ZoneOffset.UTC);
        var received = scenario.equals("old-received") ? UTC_NOW.minusSeconds(1)
                : scenario.equals("same-received-conflict") ? UTC_NOW
                : scenario.equals("stale") ? at.minusSeconds(61)
                : scenario.equals("future") ? at.plusSeconds(1) : at;
        var provider = scenario.equals("old-provider") ? UTC_NOW.minusSeconds(2)
                : scenario.equals("same-provider-conflict") ? UTC_NOW.minusSeconds(1)
                : received.minusSeconds(1);
        if (scenario.equals("regressed-index")) {
            var checkpoint = aud08Start(2).checkpoint();
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                var session = sessions.findForUpdate(id).orElseThrow();
                session.reconfirm(1, checkpoint, at);
                session.suspend(2, BoardingStateReason.PROGRESS_REGRESSION, at);
            });
            start = aud08Start(1);
        }
        var cp = new BoardingCheckpoint(start.checkpoint().getStopExternalId(), start.checkpoint().getStopOrder(),
                start.checkpoint().getIndex(), scenario.equals("unverified") ? BoardingCheckpoint.Evidence.UNVERIFIED_REFERENCE
                        : BoardingCheckpoint.Evidence.ID_ORDER,
                scenario.equals("direction-conflict") ? "conflicting-direction" : null, received, provider);
        var changed = new VerifiedBoardingStart(start.metadata(), start.traversal(), cp, start.referenceExpiresAt(),
                start.vehicleTrackingId(), NUMBER, start.routeNumber(), start.targetName());
        doReturn(changed).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        long revision = sessions.findById(id).orElseThrow().getRevision();
        var request = new ReconfirmBoardingSessionRequest(revision, "ref", null);
        if (scenario.equals("stale") || scenario.equals("future")) {
            assertThatThrownBy(() -> service.reconfirm(owner.getId(), id, request)).isInstanceOf(ApiException.class);
        } else {
            service.reconfirm(owner.getId(), id, request);
        }
        assertThat(events.count()).isZero();
        assertThat(deliveries.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("AUD-08 최초 INSERT 전 준비 TTL·Session 수명이 끝나면 Event 기회를 소비하지 않는다")
    void aud08_expiry_before_event_insert(boolean sessionExpiry) {
        long id = aud08Suspended(2);
        var start = aud08Start(1);
        if (sessionExpiry) {
            jdbc.update("update boarding_sessions set expires_at=? where id=?", UTC_NOW.plusSeconds(21), id);
        }
        doReturn(start).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        // Advance after checkpoint/revision flush and before the first Event INSERT.
        com.stopbell.boarding.service.BoardingNotificationService targetNotifications =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(notifications);
        doAnswer(invocation -> {
            time.set(sessionExpiry ? NOW.plusSeconds(21) : NOW.plusSeconds(50));
            return invocation.callRealMethod();
        }).when(targetNotifications)
                .recordReconfirmedPreparation(any(BoardingSession.class), any(BoardingTrackingSnapshot.class));
        service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, "ref", null));
        assertThat(events.count()).isZero();
        assertThat(deliveries.count()).isZero();
        if (!sessionExpiry) {
            liveObservation(2);
            polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
            assertThat(events.count()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("AUD-08 Event 저장 뒤 FK 오류·두 번째 Delivery UNIQUE 오류는 checkpoint·revision·전체 fan-out을 rollback한다")
    void aud08_atomic_failure(boolean eventFailure) {
        long id = aud08Suspended(2);
        device(owner);
        device(owner);
        doReturn(aud08Start(1)).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        if (eventFailure) {
            doAnswer(invocation -> {
                NotificationEvent event = invocation.getArgument(0);
                entityManager.persist(event);
                entityManager.flush();
                jdbc.update("update notification_events set user_id=-1 where id=?", event.getId());
                return event;
            }).when(events).saveAndFlush(any(NotificationEvent.class));
        } else {
            var firstDelivery = new AtomicReference<NotificationDelivery>();
            doAnswer(invocation -> {
                NotificationDelivery delivery = invocation.getArgument(0);
                if (firstDelivery.get() == null) {
                    firstDelivery.set(delivery);
                    entityManager.persist(delivery);
                    entityManager.flush();
                    return delivery;
                }
                var duplicate = firstDelivery.get();
                return deliveries.saveAndFlush(new NotificationDelivery(duplicate.getNotificationEvent(),
                        duplicate.getDevice(), duplicate.getRecipientOwnershipGeneration(), duplicate.getExpiresAt(),
                        LocalDateTime.ofInstant(time.get(), ZoneOffset.UTC)));
            }).when(deliveries).save(any(NotificationDelivery.class));
        }
        assertThatThrownBy(() -> service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, "ref", null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(events.count()).isZero();
        assertThat(deliveries.count()).isZero();
        var current = sessions.findById(id).orElseThrow();
        assertThat(current.getRevision()).isEqualTo(1);
        assertThat(current.getCheckpoint().getObservedAt()).isEqualTo(UTC_NOW);
        assertThat(runtime.isReady(BoardingTrackingSnapshot.from(current), UTC_NOW.plusSeconds(20))).isFalse();
    }

    @Test
    @DisplayName("AUD-08 재확인 0 recipients Event는 소비되며 나중 Device에 소급 Delivery를 만들지 않는다")
    void aud08_no_recipient_is_consumed() {
        long id = aud08Suspended(2);
        doReturn(aud08Start(1)).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        assertThat(service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, "ref", null))
                .preparationNotificationStatus()).isEqualTo("NO_RECIPIENT");
        device(owner);
        new TransactionTemplate(manager).executeWithoutResult(status -> sessions.findForUpdate(id).orElseThrow()
                .suspend(2, BoardingStateReason.VEHICLE_MISSING, UTC_NOW.plusSeconds(20)));
        time.set(NOW.plusSeconds(25));
        doReturn(aud08Start(2)).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(3, "ref", null));
        assertThat(events.count()).isEqualTo(1);
        assertThat(deliveries.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("AUD-08 commit 응답 유실은 정확한 checkpoint·Event를 확인하며 rollback된 Event·Delivery를 재적용하지 않는다")
    void aud08_commit_reconciliation(boolean persisted) {
        long id = aud08Suspended(2);
        device(owner);
        doReturn(aud08Start(1)).when(validator).validate(anyLong(), anyString(), nullable(String.class));
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
        var request = new ReconfirmBoardingSessionRequest(1, "ref", null);
        if (persisted) {
            assertThat(tested.reconfirm(owner.getId(), id, request).preparationNotificationStatus()).isEqualTo("PENDING");
            assertThatThrownBy(() -> service.reconfirm(owner.getId(), id, request))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BOARDING_REVISION_CONFLICT));
        } else {
            assertThatThrownBy(() -> tested.reconfirm(owner.getId(), id, request)).isInstanceOf(TransactionSystemException.class);
        }
        assertThat(events.count()).isEqualTo(persisted ? 1 : 0);
        assertThat(deliveries.count()).isEqualTo(persisted ? 1 : 0);
        var current = sessions.findById(id).orElseThrow();
        assertThat(current.getRevision()).isEqualTo(persisted ? 2 : 1);
        assertThat(runtime.isReady(BoardingTrackingSnapshot.from(current), UTC_NOW.plusSeconds(20))).isEqualTo(persisted);
    }

    @Test
    @DisplayName("AUD-08 두 재확인과 늦은 Polling 결과가 경합해도 새 revision의 Event·Delivery만 한 번 저장한다")
    void aud08_concurrent_reconfirmation_and_polling() throws Exception {
        long id = aud08Suspended(2);
        device(owner);
        var suspended = BoardingTrackingSnapshot.from(sessions.findById(id).orElseThrow());
        var start = aud08Start(1);
        var both = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            both.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return start;
        }).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<String> reconfirm = () -> {
                try { service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, "ref", null)); return "SUCCESS"; }
                catch (ApiException exception) { return exception.getErrorCode().name(); }
            };
            var a = pool.submit(reconfirm);
            var b = pool.submit(reconfirm);
            assertThat(both.await(5, TimeUnit.SECONDS)).isTrue();
            // A cycle while Provider revalidation is in flight must preserve the suspended revision.
            polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
            release.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SUCCESS", "BOARDING_REVISION_CONFLICT");
            assertThat(trackingStore.apply(suspended,
                    com.stopbell.boarding.domain.BoardingTrackingDecision.held(suspended.reason())).accepted()).isFalse();
        } finally { release.countDown(); }
        assertThat(events.count()).isEqualTo(1);
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(sessions.findById(id).orElseThrow().getRevision()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("AUD-08 재확인 Provider I/O 중 취소·Session 만료는 새 Event·Delivery를 생성하지 않는다")
    void aud08_terminal_during_reconfirmation(boolean expired) {
        long id = aud08Suspended(2);
        var start = aud08Start(1);
        doAnswer(invocation -> {
            if (expired) { time.set(NOW.plusSeconds(14400)); }
            else { service.cancel(owner.getId(), id, 1); }
            return start;
        }).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        assertThatThrownBy(() -> service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, "ref", null)))
                .isInstanceOf(ApiException.class);
        assertThat(events.count()).isZero();
        assertThat(deliveries.count()).isZero();
        assertThat(sessions.findById(id).orElseThrow().getStatus())
                .isEqualTo(expired ? BoardingSessionStatus.EXPIRED : BoardingSessionStatus.CANCELLED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("AUD-08 NULL offset의 기존 준비 ON/OFF·소비·Delivery와 목적지 Event를 보존한다")
    void aud08_legacy_policy(boolean prepare) {
        long id = aud08Suspended(null);
        jdbc.update("update boarding_sessions set prepare_notification_enabled=? where id=?", prepare, id);
        device(owner);
        liveObservation(1);
        service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, candidateRef(), null));
        assertThat(events.count()).isZero(); // No baseline notification for the legacy policy.
        time.set(time.get().plusSeconds(5));
        liveObservation(2);
        polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
        assertThat(events.count()).isEqualTo(prepare ? 1 : 0);
        Long preparationId = prepare ? events.findAll().getFirst().getId() : null;
        Long deliveryId = prepare ? deliveries.findAll().getFirst().getId() : null;
        long revision = sessions.findById(id).orElseThrow().getRevision();
        new TransactionTemplate(manager).executeWithoutResult(status -> sessions.findForUpdate(id).orElseThrow()
                .suspend(revision, BoardingStateReason.VEHICLE_MISSING, UTC_NOW.plusSeconds(25)));
        time.set(time.get().plusSeconds(5));
        service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(revision + 1, candidateRef(), null));
        assertThat(events.count()).isEqualTo(prepare ? 1 : 0);
        if (prepare) {
            assertThat(events.findById(preparationId)).isPresent();
            assertThat(deliveries.findById(deliveryId)).isPresent();
        }
        time.set(time.get().plusSeconds(5));
        liveObservation(3);
        polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
        assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo(BoardingSessionStatus.COMPLETED);
        assertThat(jdbc.queryForList("select boarding_event_type from notification_events order by id", String.class))
                .containsExactlyElementsOf(prepare ? List.of("PREPARE_ALIGHT", "DESTINATION_REACHED")
                        : List.of("DESTINATION_REACHED"));
        assertThat(deliveries.count()).isEqualTo(prepare ? 2 : 1);
    }

    @Test
    @DisplayName("AUD-08 checkpoint·Event·Delivery 저장 중에는 gate를 닫고 commit 확인 후 같은 revision만 READY로 공개한다")
    void aud08_gate_after_atomic_commit() {
        long id = aud08Suspended(2);
        device(owner);
        doReturn(aud08Start(1)).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        var checked = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            NotificationEvent event = invocation.getArgument(0);
            entityManager.persist(event);
            entityManager.flush();
            var snapshot = BoardingTrackingSnapshot.from(sessions.findForUpdate(id).orElseThrow());
            assertThat(snapshot.revision()).isEqualTo(2);
            assertThat(runtime.isReady(snapshot, UTC_NOW.plusSeconds(20))).isFalse();
            TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            assertThat(events.count()).isEqualTo(1);
                            assertThat(deliveries.count()).isEqualTo(1);
                            assertThat(runtime.isReady(snapshot, UTC_NOW.plusSeconds(20))).isFalse();
                        }

                        @Override
                        public void afterCommit() {
                            assertThat(runtime.isReady(snapshot, UTC_NOW.plusSeconds(20))).isFalse();
                            checked.set(true);
                        }
                    });
            return event;
        }).when(events).saveAndFlush(any(NotificationEvent.class));
        var response = service.reconfirm(owner.getId(), id, new ReconfirmBoardingSessionRequest(1, "ref", null));
        assertThat(checked).isTrue();
        assertThat(response.evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.READY);
        assertThat(response.preparationNotificationStatus()).isEqualTo("PENDING");
    }

    private long aud08Suspended(Integer offset) {
        target = occurrence("s4", 40);
        occurrence("s5", 50);
        var start = verified(metadata.read(route.getId(), target.getId(), first.getId()));
        var session = new BoardingSession(owner, REQUEST_ID, "a".repeat(64), start.vehicleTrackingId(), NUMBER,
                start.routeNumber(), start.targetName(), start.traversal(), start.checkpoint(), UTC_NOW, UTC_NOW.plusHours(4));
        if (offset != null) {
            session = new BoardingSession(owner, REQUEST_ID, "a".repeat(64), start.vehicleTrackingId(), NUMBER,
                    start.routeNumber(), start.targetName(), start.traversal(), start.checkpoint(), UTC_NOW,
                    UTC_NOW.plusHours(4), offset);
        }
        session.suspend(0, BoardingStateReason.CONTINUITY_GAP_EXCEEDED, UTC_NOW);
        long id = sessions.saveAndFlush(session).getId();
        runtime.committed(id, 1, false);
        time.set(NOW.plusSeconds(20));
        return id;
    }

    private VerifiedBoardingStart aud08Start(int index) {
        var start = freshStart();
        var occurrence = start.traversal().occurrences().get(index);
        var at = LocalDateTime.ofInstant(time.get(), ZoneOffset.UTC);
        return new VerifiedBoardingStart(start.metadata(), start.traversal(),
                new BoardingCheckpoint(occurrence.externalStopId(), occurrence.order(), index,
                        BoardingCheckpoint.Evidence.ID_ORDER, null, at, at.minusSeconds(1)), start.referenceExpiresAt(),
                start.vehicleTrackingId(), NUMBER, start.routeNumber(), start.targetName());
    }

    private void liveObservation(int index) {
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster(new SeoulBusVehicleLocationItem(
                "vehicle-id", NUMBER, 999, "coarse", 1, liveDataTime(), null, null, null, null, null)));
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(liveProgress("s" + (index + 1), (index + 1) * 10, 0));
    }

    private void device(User user) {
        devices.saveAndFlush(new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS,
                UUID.randomUUID().toString(), 0, UTC_NOW));
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

    @Test
    @DisplayName("위치 없는 차량은 번호 입력 없이 대기로 등록하고 다른 요청 UUID의 중복 등록을 차단하며 취소할 수 있다")
    void pending_unknown_position_is_registered_and_cancellable() throws Exception {
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(progress(null, 10, 0));
        String reference = candidateRef();
        var request = new CreateBoardingSessionRequest(REQUEST_ID, reference, null);
        var created = service.create(owner.getId(), request);
        assertThat(created.response().status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(created.response().checkpointObservedAt()).isNull();
        assertThat(service.create(owner.getId(), request).created()).isFalse();
        assertThatThrownBy(() -> service.create(owner.getId(),
                new CreateBoardingSessionRequest(UUID.randomUUID().toString(), reference, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BOARDING_SESSION_CONFLICT));
        var stored = sessions.findById(created.response().id()).orElseThrow();
        assertThat(stored.getCheckpoint().getIndex()).isEqualTo(-1);
        assertThat(stored.getCheckpoint().getStopExternalId()).isNull();
        assertThat(service.cancel(owner.getId(), stored.getId(), stored.getRevision()).status())
                .isEqualTo(BoardingSessionStatus.CANCELLED);
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isZero();
    }

    @Test
    @DisplayName("버스가 승차 정류장 이전에서 이동 중이어도 번호 입력 없이 알림을 사전 등록한다")
    void register_before_boarding_stop_arrival() {
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(progress("s1", 10, 0));
        var candidate = candidates.find(owner.getId(), route.getId(), target.getId(), target.getId())
                .candidates().getFirst();
        assertThat(candidate.boardingOccurrenceGap()).isEqualTo(2);
        assertThat(candidate.progress().kind()).isEqualTo(
                com.stopbell.transit.dto.BoardingCandidatesResponse.ProgressKind.MOVING_REFERENCE);
        assertThat(candidate.selectionKey()).isNotBlank().doesNotContain("vehicle-id", NUMBER);
        var result = service.create(owner.getId(), new CreateBoardingSessionRequest(REQUEST_ID, candidate.candidateRef(), null));
        assertThat(result.response().status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(result.response().evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.READY);
        assertThat(result.response().remainingOccurrenceGap()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,true,3,0", "0,true,4,0", "0,false,3,0", "0,false,4,0", "1,true,3,0", "1,true,3,1", "-1,true,3,0"})
    @DisplayName("정차·이동 등록과 위치 부족·재시작에서도 준비 알림 한 번과 목적지 완료를 유지한다")
    void immediate_tracking_observes_then_notifies(int initialFlag, boolean prepare, int endStop, int unavailableFlag) {
        occurrence("s4", 40);
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(initialFlag < 0
                ? new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("4", null), null)
                : progress("s1", 10, initialFlag));
        String reference = candidateRef();
        var created = service.create(owner.getId(), new CreateBoardingSessionRequest(REQUEST_ID, reference, null, prepare ? 2 : 1));
        Long id = created.response().id();
        assertThat(created.response().status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(created.response().evaluationStatus()).isEqualTo(initialFlag < 0
                ? BoardingSessionResponse.EvaluationStatus.VERIFICATION_PENDING : BoardingSessionResponse.EvaluationStatus.READY);
        try {
            for (int seconds : List.of(20, 40)) {
                time.set(NOW.plusSeconds(seconds));
                when(seoul.fetchVehicleLocations(any())).thenReturn(roster(new SeoulBusVehicleLocationItem(
                        "vehicle-id", NUMBER, 999, "coarse", 1, liveDataTime(), null, null, null, null, null)));
                // Identity is valid while location fields are temporarily unavailable.
                when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(liveProgress(null, null, unavailableFlag));
                polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
                assertThat(service.find(owner.getId(), id).status()).isEqualTo(BoardingSessionStatus.TRACKING);
                assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(initialFlag >= 0 && prepare ? 1 : 0);
                runtime.forget(id); // Restart gate is default closed; DB identity/checkpoint remain.
            }
            time.set(NOW.plusSeconds(60));
            when(seoul.fetchVehicleLocations(any())).thenReturn(roster(new SeoulBusVehicleLocationItem(
                    "vehicle-id", NUMBER, 999, "coarse", 1, liveDataTime(), null, null, null, null, null)));
            when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(liveProgress("s2", 20, 1));
            polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
            assertThat(service.find(owner.getId(), id).evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.READY);
            assertThat(jdbc.queryForObject("select count(*) from notification_events where boarding_event_type='PREPARE_ALIGHT'", Long.class))
                    .isEqualTo(1L);
            time.set(NOW.plusSeconds(80));
            when(seoul.fetchVehicleLocations(any())).thenReturn(roster(new SeoulBusVehicleLocationItem(
                    "vehicle-id", NUMBER, 999, "coarse", 1, liveDataTime(), null, null, null, null, null)));
            when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(liveProgress("s" + endStop, endStop * 10, 1));
            for (int repeat = 0; repeat < 2; repeat++) {
                polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
            }
            var completed = service.find(owner.getId(), id);
            assertThat(completed.status()).isEqualTo(BoardingSessionStatus.COMPLETED);
            assertThat(completed.reasonCode()).isEqualTo(endStop == 3 ? BoardingStateReason.ARRIVAL_OBSERVED : BoardingStateReason.PASSED_OBSERVED);
            assertThat(jdbc.queryForObject("select count(*) from notification_events where boarding_event_type='DESTINATION_REACHED'", Long.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isEqualTo(1L);
        } finally {
            jdbc.update("delete from notification_deliveries");
            jdbc.update("delete from notification_events");
        }
    }

    @Test
    @DisplayName("서울 143번 기록의 이동→정차→이동→목적지 관측을 재생하면 즉시 추적부터 알림까지 이어진다")
    void replay_seoul_143_recorded_observations() {
        // docs/research/TASK-908-provider-evidence.md §24.3 vehicle A, orders 7..10.
        // This is an offline replay with an isolated metadata subset, not a new live/E2E result.
        occurrences.deleteAll();
        stops.deleteAll();
        first = occurrence("107000029", 7);
        occurrence("107000031", 8);
        occurrence("107000005", 9);
        target = occurrence("107000007", 10);
        replayObservation("2026-10-28T10:54:52Z", "20261028195440", "107000029", 7, 0);
        var candidate = candidateRef();
        var created = service.create(owner.getId(), new CreateBoardingSessionRequest(REQUEST_ID, candidate, null));
        assertThat(created.response().status()).isEqualTo(BoardingSessionStatus.TRACKING);
        assertThat(created.response().evaluationStatus()).isEqualTo(BoardingSessionResponse.EvaluationStatus.READY);
        try {
            replayObservation("2026-10-28T10:55:12Z", "20261028195503", "107000031", 8, 1);
            polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
            assertThat(service.find(owner.getId(), created.response().id()).remainingOccurrenceGap()).isEqualTo(2);
            replayObservation("2026-10-28T10:55:53Z", "20261028195538", "107000031", 8, 0);
            polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
            assertThat(service.find(owner.getId(), created.response().id()).remainingOccurrenceGap()).isEqualTo(2);
            replayObservation("2026-10-28T10:56:33Z", "20261028195615", "107000005", 9, 1);
            polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
            replayObservation("2026-10-28T10:57:54Z", "20261028195736", "107000007", 10, 1);
            polling.poll(new com.stopbell.transit.service.VehiclePollingCycle(tago, seoul, detail, clock));
            assertThat(service.find(owner.getId(), created.response().id()).reasonCode()).isEqualTo(BoardingStateReason.ARRIVAL_OBSERVED);
            assertThat(jdbc.queryForList("select boarding_event_type from notification_events order by id", String.class))
                    .containsExactly("PREPARE_ALIGHT");
        } finally {
            jdbc.update("delete from notification_deliveries");
            jdbc.update("delete from notification_events");
        }
    }

    private void replayObservation(String receivedAt, String dataTime, String stop, int order, int flag) {
        time.set(Instant.parse(receivedAt));
        when(seoul.fetchVehicleLocations(any())).thenReturn(roster(new SeoulBusVehicleLocationItem(
                "vehicle-id", NUMBER, order, "coarse", 1, dataTime, null, null, null, null, null)));
        when(detail.fetchVehicleDetail("vehicle-id")).thenReturn(new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", null), new SeoulBusVehicleDetailResponse.Body(List.of(
                        new SeoulBusVehicleDetailItem("vehicle-id", NUMBER, stop, order, flag, dataTime, null, null)))));
    }

    private String liveDataTime() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneId.of("Asia/Seoul"))
                .format(time.get());
    }

    private SeoulBusVehicleDetailResponse liveProgress(String stop, Integer order, int flag) {
        return new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("0", null),
                new SeoulBusVehicleDetailResponse.Body(List.of(new SeoulBusVehicleDetailItem(
                        "vehicle-id", NUMBER, stop, order, flag, liveDataTime(), null, null))));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("재확인 HTTP의 실제 Event FK 실패는 안전한 JSON 500과 Session·Event rollback을 보장한다")
    void report_database_failure_over_http_safely(CapturedOutput output) throws Exception {
        long id = aud08Suspended(2);
        device(owner);
        device(owner);
        var before = BoardingTrackingSnapshot.from(sessions.findById(id).orElseThrow());
        var completed = new AtomicReference<Integer>();
        doReturn(aud08Start(1)).when(validator).validate(anyLong(), anyString(), nullable(String.class));
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completed.set(status);
                }
            });
            NotificationEvent event = invocation.getArgument(0);
            entityManager.persist(event);
            entityManager.flush();
            jdbc.update("update notification_events set user_id=-1 where id=?", event.getId());
            return event;
        }).when(events).saveAndFlush(any(NotificationEvent.class));

        var result = mvc.perform(auth(reconfirmRequest(id, 1, "ref"), owner)).andReturn();

        assertSafePersistenceFailure(result, output, "DataIntegrityViolationException");
        assertThat(completed.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(before.matches(sessions.findById(id).orElseThrow())).isTrue();
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(events.count()).isZero();
        assertThat(deliveries.count()).isZero();
        assertThat(runtime.isReady(before, UTC_NOW.plusSeconds(20))).isFalse();
        for (var device : devices.findAll()) {
            assertThat(output.getAll()).doesNotContain(device.getInstallationId(), device.getCurrentPushTargetId(),
                    device.getInstallationCredentialHash());
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("조회 HTTP의 TransactionException은 원문과 cause 없이 같은 JSON 500을 반환한다")
    void report_transaction_failure_over_http_safely(CapturedOutput output) throws Exception {
        long id = aud08Suspended(2);
        var before = BoardingTrackingSnapshot.from(sessions.findById(id).orElseThrow());
        String privateDetail = "SQL SELECT private-table SQLState=HY000 constraint=private-constraint "
                + "installation=private-installation FID=private-target credential=private-credential "
                + "userId=private-user JWT=private-jwt refresh=private-refresh";
        com.stopbell.boarding.service.BoardingNotificationService targetNotifications =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(notifications);
        doThrow(new TransactionSystemException(privateDetail, new IllegalStateException("private-cause")))
                .when(targetNotifications).preparationStatus(id);

        var result = mvc.perform(auth(get("/api/v1/boarding-sessions/" + id), owner)).andReturn();

        assertSafePersistenceFailure(result, output, "TransactionSystemException");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(privateDetail, "private-cause");
        assertThat(output.getAll()).doesNotContain(privateDetail, "private-cause", "private-table",
                "private-constraint", "private-installation", "private-target", "private-credential",
                "private-user", "private-jwt", "private-refresh");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(before.matches(sessions.findById(id).orElseThrow())).isTrue();
    }

    private void assertSafePersistenceFailure(MvcResult result, CapturedOutput output, String exceptionType) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
        String body = result.getResponse().getContentAsString();
        var error = json.readTree(body);
        assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(error.get("code").asString()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(error.get("message").asString()).isEqualTo("An internal server error occurred.");
        assertThat(body).doesNotContain("SQLState", "SQLSTATE", "stackTrace", exceptionType,
                "fk_notification_events_user_id", "uk_notification_deliveries_event_device");
        assertThat(output.getAll()).contains("Boarding persistence failed: " + exceptionType)
                .doesNotContain("fk_notification_events_user_id", "uk_notification_deliveries_event_device",
                        "update notification_events set user_id=-1");
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
                .andExpect(apiError(ErrorCode.valueOf(code)));
        assertThat(sessions.count()).isZero();
    }

    private ResultMatcher apiError(ErrorCode code) {
        return result -> {
            assertThat(result.getResponse().getStatus()).isEqualTo(code.getHttpStatus().value());
            assertThat(result.getResponse().getContentType()).isEqualTo("application/json");
            var error = json.readTree(result.getResponse().getContentAsString());
            assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message");
            assertThat(error.get("code").asString()).isEqualTo(code.name());
            assertThat(error.get("message").asString()).isEqualTo(code.getMessage());
        };
    }
}
