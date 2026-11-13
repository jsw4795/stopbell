package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.stopbell.alarm.dto.AlarmResponse;
import com.stopbell.alarm.dto.CreateAlarmRequest;
import com.stopbell.alarm.dto.UpdateAlarmOptionsRequest;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.support.LatestSchemaIntegrationTest;
import com.stopbell.transit.domain.TransitProvider;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Transactional
class AlarmServiceIntegrationTest extends LatestSchemaIntegrationTest {

    private static final UUID TRACKING_CYCLE_ID = UUID.fromString("a1234567-1234-4123-8123-123456789abc");

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private BusAlarmPollingService busAlarmPollingService;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BusRouteRepository routeRepository;

    @Autowired
    private BusStopRepository stopRepository;

    @Autowired
    private BusRouteStopOccurrenceRepository occurrenceRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private com.stopbell.transit.repository.TransitSourceMetadataRepository sourceMetadataRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("실제 traversal 인접 occurrence와 current metadata를 Bus Alarm snapshot으로 저장한다")
    void create_bus_alarm_with_actual_adjacent_snapshots() {
        User user = user();
        RouteFixture route = route();

        AlarmResponse response = alarmService.create(user.getId(), new CreateAlarmRequest(route.middleId(), true, true));
        entityManager.flush();
        entityManager.clear();

        Alarm alarm = alarmRepository.findById(response.id()).orElseThrow();
        BusAlarmTarget target = alarm.getBusAlarmTarget();
        assertThat(alarm.getUser().getId()).isEqualTo(user.getId());
        assertThat(alarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(alarm.getTransitType()).isEqualTo(TransitType.BUS);
        assertThat(target.getAlarmId()).isEqualTo(alarm.getId());
        assertThat(target.getProvider()).isEqualTo(TransitProvider.TAGO);
        assertThat(target.getExternalRouteId()).isEqualTo(route.externalRouteId());
        assertThat(target.getExternalStopId()).isEqualTo(route.middleExternalStopId());
        assertThat(target.getTargetStopOrder()).isEqualTo(3);
        assertThat(target.getRouteNumber()).isEqualTo("7000");
        assertThat(target.getStopName()).isEqualTo("Stop B");
        assertThat(target.getTargetStopLatitude()).isEqualByComparingTo("37.1234567");
        assertThat(target.getTargetStopLongitude()).isEqualByComparingTo("127.1234567");
        assertThat(target.getCityCode()).isEqualTo("41110");
        assertThat(target.isNotifyOneStopBefore()).isTrue();
        assertThat(target.getPredecessorExternalStopId()).isEqualTo(route.firstExternalStopId());
        assertThat(target.getPredecessorStopOrder()).isEqualTo(1);
        assertThat(target.isNotifyOneStopAfter()).isTrue();
        assertThat(target.getSuccessorExternalStopId()).isEqualTo(route.lastExternalStopId());
        assertThat(target.getSuccessorStopOrder()).isEqualTo(7);
        assertThat(response).isEqualTo(new AlarmResponse(
                alarm.getId(), TransitType.BUS, AlarmStatus.INACTIVE, "7000", "Stop B", true, true, null, null,
                TransitProvider.TAGO, null, true, true, route.routeId(), route.middleId()
        ));
    }

    @Test
    @DisplayName("대상 occurrence가 현재 traversal에 없으면 차량 조회용 reference를 제공하지 않는다")
    void omit_vehicle_references_when_target_occurrence_is_missing() {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(),
                new CreateAlarmRequest(route.middleId(), false, false)).id();
        occurrenceRepository.deleteById(route.middleId());
        entityManager.flush();
        entityManager.clear();

        AlarmResponse response = alarmService.findById(owner.getId(), alarmId);
        assertThat(response.routeId()).isNull();
        assertThat(response.targetStopOccurrenceId()).isNull();
    }

    @Test
    @DisplayName("양쪽 occurrence가 존재해도 꺼진 option의 snapshot은 저장하지 않는다")
    void omit_disabled_adjacent_snapshots() {
        User user = user();
        RouteFixture route = route();

        AlarmResponse response = alarmService.create(user.getId(), new CreateAlarmRequest(route.middleId(), false, false));
        entityManager.flush();
        entityManager.clear();

        BusAlarmTarget target = alarmRepository.findById(response.id()).orElseThrow().getBusAlarmTarget();
        assertThat(target.isNotifyOneStopBefore()).isFalse();
        assertThat(target.getPredecessorExternalStopId()).isNull();
        assertThat(target.getPredecessorStopOrder()).isNull();
        assertThat(target.isNotifyOneStopAfter()).isFalse();
        assertThat(target.getSuccessorExternalStopId()).isNull();
        assertThat(target.getSuccessorStopOrder()).isNull();
    }

    @Test
    @DisplayName("첫 occurrence에서 before option을 요청하면 Alarm을 생성하지 않는다")
    void reject_before_on_first_occurrence() {
        User user = user();
        RouteFixture route = route();
        long alarmCount = alarmRepository.count();

        assertThatThrownBy(() -> alarmService.create(user.getId(), new CreateAlarmRequest(route.firstId(), true, false)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ALARM_REQUEST));
        assertThat(alarmRepository.count()).isEqualTo(alarmCount);
    }

    @Test
    @DisplayName("마지막 occurrence에서 after option을 요청하면 Alarm을 생성하지 않는다")
    void reject_after_on_last_occurrence() {
        User user = user();
        RouteFixture route = route();
        long alarmCount = alarmRepository.count();

        assertThatThrownBy(() -> alarmService.create(user.getId(), new CreateAlarmRequest(route.lastId(), false, true)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ALARM_REQUEST));
        assertThat(alarmRepository.count()).isEqualTo(alarmCount);
    }

    @Test
    @DisplayName("없는 occurrence와 null target은 명확히 거부하고 Alarm을 생성하지 않는다")
    void reject_missing_or_null_target() {
        User user = user();
        long alarmCount = alarmRepository.count();

        assertThatThrownBy(() -> alarmService.create(user.getId(), new CreateAlarmRequest(Long.MAX_VALUE, false, false)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TARGET_STOP_OCCURRENCE_NOT_FOUND));
        assertThatThrownBy(() -> alarmService.create(user.getId(), new CreateAlarmRequest(null, false, false)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThat(alarmRepository.count()).isEqualTo(alarmCount);
    }

    @Test
    @DisplayName("JWT의 User가 삭제된 경우 Alarm을 생성하지 않는다")
    void reject_missing_authenticated_user() {
        RouteFixture route = route();
        long alarmCount = alarmRepository.count();

        assertThatThrownBy(() -> alarmService.create(Long.MAX_VALUE, new CreateAlarmRequest(route.middleId(), false, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
        assertThat(alarmRepository.count()).isEqualTo(alarmCount);
    }

    @Test
    @DisplayName("인증된 POST는 201과 AlarmResponse를 반환하고 JWT User에게 Alarm을 소유시킨다")
    void authenticated_post_creates_alarm() throws Exception {
        User user = user();
        RouteFixture route = route();

        mockMvc.perform(post("/api/v1/alarms")
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(user.getId()))
                        .contentType("application/json")
                        .content("{\"targetStopOccurrenceId\":" + route.middleId()
                                + ",\"notifyOneStopBefore\":true,\"notifyOneStopAfter\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.transitType").value("BUS"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.routeNumber").value("7000"))
                .andExpect(jsonPath("$.stopName").value("Stop B"))
                .andExpect(jsonPath("$.notifyOneStopBefore").value(true))
                .andExpect(jsonPath("$.notifyOneStopAfter").value(false));

        Alarm alarm = alarmRepository.findAll().getFirst();
        assertThat(alarm.getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("Access Token 없이 Alarm API 목록을 조회하면 401을 반환한다")
    void reject_unauthenticated_alarm_api_request() throws Exception {
        mockMvc.perform(get("/api/v1/alarms"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Alarm 생성 요청의 target occurrence ID 검증 오류는 INVALID_REQUEST를 반환한다")
    void reject_invalid_target_stop_occurrence_id() throws Exception {
        User user = user();

        for (String requestBody : List.of(
                "{\"targetStopOccurrenceId\":null}",
                "{\"targetStopOccurrenceId\":0}",
                "{\"targetStopOccurrenceId\":-1}"
        )) {
            mockMvc.perform(post("/api/v1/alarms")
                            .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(user.getId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Request is invalid."));
        }
    }

    @Test
    @DisplayName("선택 option 없이 Alarm을 생성하면 두 option은 false가 된다")
    void create_alarm_with_id_only() throws Exception {
        User user = user();
        RouteFixture route = route();

        mockMvc.perform(post("/api/v1/alarms")
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(user.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStopOccurrenceId\":" + route.middleId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notifyOneStopBefore").value(false))
                .andExpect(jsonPath("$.notifyOneStopAfter").value(false));
    }

    @Test
    @DisplayName("존재하지 않는 target occurrence는 구조화된 404 오류를 반환한다")
    void return_target_stop_occurrence_not_found() throws Exception {
        User user = user();

        mockMvc.perform(post("/api/v1/alarms")
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(user.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStopOccurrenceId\":999999999}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("TARGET_STOP_OCCURRENCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Target stop occurrence was not found."));
    }

    @Test
    @DisplayName("인접 occurrence가 없는 option 요청은 구조화된 400 오류를 반환한다")
    void reject_boundary_alarm_request() throws Exception {
        User user = user();
        RouteFixture route = route();
        String authorization = "Bearer " + jwtTokenService.createAccessToken(user.getId());

        mockMvc.perform(post("/api/v1/alarms")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStopOccurrenceId\":" + route.firstId()
                                + ",\"notifyOneStopBefore\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ALARM_REQUEST"))
                .andExpect(jsonPath("$.message").value("Target stop has no predecessor stop."));

        mockMvc.perform(post("/api/v1/alarms")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStopOccurrenceId\":" + route.lastId()
                                + ",\"notifyOneStopAfter\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ALARM_REQUEST"))
                .andExpect(jsonPath("$.message").value("Target stop has no successor stop."));
    }

    @Test
    @DisplayName("읽을 수 없는 Alarm 생성 요청은 INVALID_REQUEST를 반환한다")
    void reject_missing_or_malformed_request_body() throws Exception {
        User user = user();
        String authorization = "Bearer " + jwtTokenService.createAccessToken(user.getId());

        for (String requestBody : List.of("", "{\"targetStopOccurrenceId\":")) {
            mockMvc.perform(post("/api/v1/alarms")
                            .header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Request is invalid."));
        }
    }

    @Test
    @DisplayName("Alarm 소유권 조회 실패는 모든 변경 API에서 ALARM_NOT_FOUND를 반환한다")
    void return_alarm_not_found_for_missing_or_other_user_alarm() throws Exception {
        User owner = user();
        User other = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)).id();
        String authorization = "Bearer " + jwtTokenService.createAccessToken(other.getId());

        mockMvc.perform(get("/api/v1/alarms/{alarmId}", alarmId)
                        .header("Authorization", authorization))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ALARM_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Alarm was not found."));

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/activate", Long.MAX_VALUE)
                        .header("Authorization", authorization))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ALARM_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Alarm was not found."));

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/deactivate", Long.MAX_VALUE)
                        .header("Authorization", authorization))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ALARM_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Alarm was not found."));

        mockMvc.perform(delete("/api/v1/alarms/{alarmId}", Long.MAX_VALUE)
                        .header("Authorization", authorization))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ALARM_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Alarm was not found."));
    }

    @Test
    @DisplayName("인증된 User의 모든 상태 Alarm만 최근 생성순으로 반환한다")
    void authenticated_get_lists_owned_alarms_newest_first() throws Exception {
        User owner = user();
        User other = user();
        RouteFixture route = route();
        Long oldestId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), true, false)).id();
        Long middleId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)).id();
        Long newestId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, true)).id();
        alarmService.create(other.getId(), new CreateAlarmRequest(route.middleId(), false, false));

        alarmRepository.findById(middleId).orElseThrow().activate();
        alarmRepository.findById(newestId).orElseThrow().activate();
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        alarmRepository.findById(newestId).orElseThrow()
                .startFollowUp("vehicle-1", TRACKING_CYCLE_ID, startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
        entityManager.flush();
        setCreatedAt(oldestId, LocalDateTime.of(2025, 1, 1, 0, 0));
        setCreatedAt(middleId, LocalDateTime.of(2025, 1, 2, 0, 0));
        setCreatedAt(newestId, LocalDateTime.of(2025, 1, 3, 0, 0));
        entityManager.clear();

        mockMvc.perform(get("/api/v1/alarms")
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(newestId))
                .andExpect(jsonPath("$[0].transitType").value("BUS"))
                .andExpect(jsonPath("$[0].status").value("FOLLOW_UP"))
                .andExpect(jsonPath("$[0].routeNumber").value("7000"))
                .andExpect(jsonPath("$[0].stopName").value("Stop B"))
                .andExpect(jsonPath("$[0].notifyOneStopBefore").value(false))
                .andExpect(jsonPath("$[0].notifyOneStopAfter").value(true))
                .andExpect(jsonPath("$[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(middleId))
                .andExpect(jsonPath("$[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$[2].id").value(oldestId))
                .andExpect(jsonPath("$[2].status").value("INACTIVE"));
    }

    @Test
    @DisplayName("Alarm이 없는 User의 목록은 200과 빈 배열을 반환한다")
    void authenticated_get_returns_empty_array() throws Exception {
        User owner = user();

        mockMvc.perform(get("/api/v1/alarms")
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("인증된 User는 소유한 Alarm 상세와 현재 상태를 AlarmResponse로 조회한다")
    void authenticated_get_returns_owned_alarm_detail() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, true)).id();
        Alarm alarm = alarmRepository.findById(alarmId).orElseThrow();
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        alarm.activate();
        alarm.startFollowUp("vehicle-1", TRACKING_CYCLE_ID, startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/alarms/{alarmId}", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alarmId))
                .andExpect(jsonPath("$.transitType").value("BUS"))
                .andExpect(jsonPath("$.status").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.routeNumber").value("7000"))
                .andExpect(jsonPath("$.stopName").value("Stop B"))
                .andExpect(jsonPath("$.notifyOneStopBefore").value(false))
                .andExpect(jsonPath("$.notifyOneStopAfter").value(true));
    }

    @Test
    @DisplayName("다른 User가 소유한 Alarm 상세 조회는 404를 반환한다")
    void authenticated_get_returns_not_found_for_other_users_alarm() throws Exception {
        User owner = user();
        User other = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)).id();

        mockMvc.perform(get("/api/v1/alarms/{alarmId}", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(other.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 Alarm 상세 조회는 404를 반환한다")
    void authenticated_get_returns_not_found_for_missing_alarm() throws Exception {
        User owner = user();

        mockMvc.perform(get("/api/v1/alarms/{alarmId}", Long.MAX_VALUE)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("인증된 User는 비활성 Alarm을 활성화하고 AlarmResponse를 받는다")
    void authenticated_post_activates_inactive_alarm() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), true, true)).id();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/activate", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alarmId))
                .andExpect(jsonPath("$.transitType").value("BUS"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.routeNumber").value("7000"))
                .andExpect(jsonPath("$.stopName").value("Stop B"))
                .andExpect(jsonPath("$.notifyOneStopBefore").value(true))
                .andExpect(jsonPath("$.notifyOneStopAfter").value(true));
        entityManager.flush();
        entityManager.clear();

        assertThat(alarmRepository.findById(alarmId).orElseThrow().getStatus()).isEqualTo(AlarmStatus.ACTIVE);
    }

    @Test
    @DisplayName("이미 활성화된 Alarm을 다시 활성화해도 설정을 유지하고 성공한다")
    void authenticated_post_keeps_active_alarm_active() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), true, true)).id();
        Alarm alarm = alarmRepository.findById(alarmId).orElseThrow();
        alarm.activate();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/activate", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.notifyOneStopBefore").value(true))
                .andExpect(jsonPath("$.notifyOneStopAfter").value(true));
        entityManager.flush();
        entityManager.clear();

        Alarm persistedAlarm = alarmRepository.findById(alarmId).orElseThrow();
        assertThat(persistedAlarm.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(persistedAlarm.getBusAlarmTarget().isNotifyOneStopBefore()).isTrue();
        assertThat(persistedAlarm.getBusAlarmTarget().isNotifyOneStopAfter()).isTrue();
    }

    @Test
    @DisplayName("FOLLOW_UP Alarm을 다시 활성화하면 runtime을 지우고 활성 상태로 저장한다")
    void authenticated_post_activates_follow_up_alarm_and_clears_runtime() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, true)).id();
        Alarm alarm = alarmRepository.findById(alarmId).orElseThrow();
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        alarm.activate();
        alarm.startFollowUp("vehicle-1", TRACKING_CYCLE_ID, startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
        entityManager.flush();
        entityManager.clear();

        Alarm followUpAlarm = alarmRepository.findById(alarmId).orElseThrow();
        assertThat(followUpAlarm.getStatus()).isEqualTo(AlarmStatus.FOLLOW_UP);
        assertThat(followUpAlarm.getFollowUpVehicleTrackingId()).isNotNull();
        assertThat(followUpAlarm.getFollowUpStartedAt()).isNotNull();
        assertThat(followUpAlarm.getFollowUpExpiresAt()).isNotNull();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/activate", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        entityManager.flush();
        entityManager.clear();

        Alarm persistedAlarm = alarmRepository.findById(alarmId).orElseThrow();
        assertThat(persistedAlarm.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(persistedAlarm.getFollowUpVehicleTrackingId()).isNull();
        assertThat(persistedAlarm.getFollowUpStartedAt()).isNull();
        assertThat(persistedAlarm.getFollowUpExpiresAt()).isNull();
    }

    @Test
    @DisplayName("다른 User의 Alarm 활성화 요청은 404이며 상태를 변경하지 않는다")
    void authenticated_post_activate_returns_not_found_for_other_users_alarm() throws Exception {
        User owner = user();
        User other = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)).id();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/activate", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(other.getId())))
                .andExpect(status().isNotFound());
        entityManager.flush();
        entityManager.clear();

        assertThat(alarmRepository.findById(alarmId).orElseThrow().getStatus()).isEqualTo(AlarmStatus.INACTIVE);
    }

    @Test
    @DisplayName("존재하지 않는 Alarm 활성화 요청은 404를 반환한다")
    void authenticated_post_activate_returns_not_found_for_missing_alarm() throws Exception {
        User owner = user();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/activate", Long.MAX_VALUE)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("인증된 User는 ACTIVE Alarm을 비활성화하고 AlarmResponse를 받는다")
    void authenticated_post_deactivates_active_alarm() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), true, true)).id();
        alarmRepository.findById(alarmId).orElseThrow().activate();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/deactivate", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alarmId))
                .andExpect(jsonPath("$.transitType").value("BUS"))
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.routeNumber").value("7000"))
                .andExpect(jsonPath("$.stopName").value("Stop B"))
                .andExpect(jsonPath("$.notifyOneStopBefore").value(true))
                .andExpect(jsonPath("$.notifyOneStopAfter").value(true));
        entityManager.flush();
        entityManager.clear();

        assertThat(alarmRepository.findById(alarmId).orElseThrow().getStatus()).isEqualTo(AlarmStatus.INACTIVE);
    }

    @Test
    @DisplayName("이미 INACTIVE인 Alarm을 다시 비활성화해도 설정을 유지하고 성공한다")
    void authenticated_post_keeps_inactive_alarm_inactive() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), true, false)).id();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/deactivate", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.notifyOneStopBefore").value(true))
                .andExpect(jsonPath("$.notifyOneStopAfter").value(false));
        entityManager.flush();
        entityManager.clear();

        Alarm persistedAlarm = alarmRepository.findById(alarmId).orElseThrow();
        assertThat(persistedAlarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(persistedAlarm.getBusAlarmTarget().isNotifyOneStopBefore()).isTrue();
        assertThat(persistedAlarm.getBusAlarmTarget().isNotifyOneStopAfter()).isFalse();
    }

    @Test
    @DisplayName("FOLLOW_UP Alarm을 비활성화하면 runtime을 지우고 비활성 상태로 저장한다")
    void authenticated_post_deactivates_follow_up_alarm_and_clears_runtime() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, true)).id();
        Alarm alarm = alarmRepository.findById(alarmId).orElseThrow();
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        alarm.activate();
        alarm.startFollowUp("vehicle-1", TRACKING_CYCLE_ID, startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
        entityManager.flush();
        entityManager.clear();

        Alarm followUpAlarm = alarmRepository.findById(alarmId).orElseThrow();
        assertThat(followUpAlarm.getStatus()).isEqualTo(AlarmStatus.FOLLOW_UP);
        assertThat(followUpAlarm.getFollowUpVehicleTrackingId()).isNotNull();
        assertThat(followUpAlarm.getFollowUpStartedAt()).isNotNull();
        assertThat(followUpAlarm.getFollowUpExpiresAt()).isNotNull();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/deactivate", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        entityManager.flush();
        entityManager.clear();

        Alarm persistedAlarm = alarmRepository.findById(alarmId).orElseThrow();
        assertThat(persistedAlarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(persistedAlarm.getFollowUpVehicleTrackingId()).isNull();
        assertThat(persistedAlarm.getFollowUpStartedAt()).isNull();
        assertThat(persistedAlarm.getFollowUpExpiresAt()).isNull();
    }

    @Test
    @DisplayName("다른 User의 ACTIVE Alarm 비활성화 요청은 404이며 상태를 변경하지 않는다")
    void authenticated_post_deactivate_returns_not_found_for_other_users_active_alarm() throws Exception {
        User owner = user();
        User other = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)).id();
        alarmRepository.findById(alarmId).orElseThrow().activate();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/deactivate", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(other.getId())))
                .andExpect(status().isNotFound());
        entityManager.flush();
        entityManager.clear();

        assertThat(alarmRepository.findById(alarmId).orElseThrow().getStatus()).isEqualTo(AlarmStatus.ACTIVE);
    }

    @Test
    @DisplayName("다른 User의 FOLLOW_UP Alarm 비활성화 요청은 404이며 runtime을 유지한다")
    void authenticated_post_deactivate_returns_not_found_for_other_users_follow_up_alarm() throws Exception {
        User owner = user();
        User other = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, true)).id();
        Alarm alarm = alarmRepository.findById(alarmId).orElseThrow();
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        alarm.activate();
        alarm.startFollowUp("vehicle-1", TRACKING_CYCLE_ID, startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/deactivate", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(other.getId())))
                .andExpect(status().isNotFound());
        entityManager.flush();
        entityManager.clear();

        Alarm persistedAlarm = alarmRepository.findById(alarmId).orElseThrow();
        assertThat(persistedAlarm.getStatus()).isEqualTo(AlarmStatus.FOLLOW_UP);
        assertThat(persistedAlarm.getFollowUpVehicleTrackingId()).isEqualTo("vehicle-1");
        assertThat(persistedAlarm.getFollowUpStartedAt()).isEqualTo(startedAt);
        assertThat(persistedAlarm.getFollowUpExpiresAt()).isEqualTo(startedAt.plus(10, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("존재하지 않는 Alarm 비활성화 요청은 404를 반환한다")
    void authenticated_post_deactivate_returns_not_found_for_missing_alarm() throws Exception {
        User owner = user();

        mockMvc.perform(post("/api/v1/alarms/{alarmId}/deactivate", Long.MAX_VALUE)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("인증된 User는 Alarm과 종속 데이터를 삭제하고 transit metadata는 보존한다")
    void authenticated_delete_removes_owned_alarm_and_dependent_data() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)).id();
        long routeCount = routeRepository.count();
        long stopCount = stopRepository.count();
        long occurrenceCount = occurrenceRepository.count();
        entityManager.clear();

        mockMvc.perform(delete("/api/v1/alarms/{alarmId}", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        entityManager.flush();
        entityManager.clear();

        assertThat(alarmRepository.existsById(alarmId)).isFalse();
        assertThat(entityManager.find(BusAlarmTarget.class, alarmId)).isNull();
        assertThat(routeRepository.count()).isEqualTo(routeCount);
        assertThat(stopRepository.count()).isEqualTo(stopCount);
        assertThat(occurrenceRepository.count()).isEqualTo(occurrenceCount);

        mockMvc.perform(get("/api/v1/alarms/{alarmId}", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("다른 User의 Alarm 삭제 요청은 404이며 종속 데이터를 유지한다")
    void authenticated_delete_rejects_other_users_alarm() throws Exception {
        User owner = user();
        User other = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)).id();

        mockMvc.perform(delete("/api/v1/alarms/{alarmId}", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(other.getId())))
                .andExpect(status().isNotFound());
        entityManager.flush();
        entityManager.clear();

        assertThat(alarmRepository.existsById(alarmId)).isTrue();
        assertThat(entityManager.find(BusAlarmTarget.class, alarmId)).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 Alarm 삭제 요청은 404를 반환한다")
    void authenticated_delete_returns_not_found_for_missing_alarm() throws Exception {
        User owner = user();

        mockMvc.perform(delete("/api/v1/alarms/{alarmId}", Long.MAX_VALUE)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ACTIVE Alarm은 상태 전이 없이 삭제할 수 있다")
    void authenticated_delete_removes_active_alarm() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)).id();
        alarmRepository.findById(alarmId).orElseThrow().activate();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(delete("/api/v1/alarms/{alarmId}", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        assertThat(alarmRepository.existsById(alarmId)).isFalse();
        assertThat(entityManager.find(BusAlarmTarget.class, alarmId)).isNull();
    }

    @Test
    @DisplayName("FOLLOW_UP Alarm은 상태 전이 없이 삭제할 수 있다")
    void authenticated_delete_removes_follow_up_alarm() throws Exception {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, true)).id();
        Alarm alarm = alarmRepository.findById(alarmId).orElseThrow();
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        alarm.activate();
        alarm.startFollowUp("vehicle-1", TRACKING_CYCLE_ID, startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(delete("/api/v1/alarms/{alarmId}", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();

        assertThat(alarmRepository.existsById(alarmId)).isFalse();
        assertThat(entityManager.find(BusAlarmTarget.class, alarmId)).isNull();
    }

    @Test
    @DisplayName("Alarm 목록과 모든 BusAlarmTarget을 한 쿼리로 조회하고 기종점은 Alarm 수와 무관한 쿼리로 조회한다")
    void find_all_fetches_bus_targets_in_one_query() {
        User owner = user();
        RouteFixture route = route();
        for (int index = 0; index < 3; index++) {
            alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false));
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertThat(alarmService.findAll(owner.getId())).hasSize(3);
            // Alarm·BusAlarmTarget 1회 + 표시용 현재 노선 1회 + TAGO 노선 원본 1회
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        } finally {
            statistics.setStatisticsEnabled(originallyEnabled);
        }
    }

    @Test
    @DisplayName("monitoring Alarm과 BusAlarmTarget을 한 쿼리로 조회해 polling group을 구성한다")
    void find_monitoring_groups_fetches_bus_targets_in_one_query() {
        User owner = user();
        RouteFixture route = route();
        Long activeAlarmId = alarmService.create(
                owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)
        ).id();
        Long followUpAlarmId = alarmService.create(
                owner.getId(), new CreateAlarmRequest(route.middleId(), false, true)
        ).id();
        alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false));
        alarmRepository.findById(activeAlarmId).orElseThrow().activate();
        Alarm followUpAlarm = alarmRepository.findById(followUpAlarmId).orElseThrow();
        followUpAlarm.activate();
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 23, 12, 0);
        followUpAlarm.startFollowUp("vehicle-1", TRACKING_CYCLE_ID, startedAt, startedAt.plusMinutes(10));
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();

            List<BusAlarmPollingGroup> groups = busAlarmPollingService.findMonitoringGroups();

            assertThat(groups).hasSize(1);
            assertThat(groups.getFirst().key()).isEqualTo(new BusPollingKey(
                    TransitProvider.TAGO, route.externalRouteId(), "41110"
            ));
            assertThat(groups.getFirst().alarms()).extracting(Alarm::getId)
                    .containsExactly(activeAlarmId, followUpAlarmId);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(originallyEnabled);
        }
    }

    @Test
    @DisplayName("Alarm 상세와 BusAlarmTarget을 한 쿼리로 조회하고 기종점은 고정된 추가 쿼리로 조회한다")
    void find_by_id_fetches_bus_target_in_one_query() {
        User owner = user();
        RouteFixture route = route();
        Long alarmId = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false)).id();
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertThat(alarmService.findById(owner.getId(), alarmId)).isEqualTo(new AlarmResponse(
                    alarmId, TransitType.BUS, AlarmStatus.INACTIVE, "7000", "Stop B", false, false, null, null,
                    TransitProvider.TAGO, null, true, true, route.routeId(), route.middleId()
            ));
            // Alarm·BusAlarmTarget 1회 + 표시용 현재 노선 1회 + TAGO 노선 원본 1회
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        } finally {
            statistics.setStatisticsEnabled(originallyEnabled);
        }
    }

    @Test
    @DisplayName("Alarm 목록은 생성 당시 노선과 일치하는 공식 기종점과 노선 유형만 고정된 쿼리 수로 제공한다")
    void list_alarms_with_official_route_endpoints() throws Exception {
        User owner = user();
        RouteFixture verified = route();
        RouteFixture renumbered = route();
        RouteFixture unknown = route();
        rawRoute(verified.externalRouteId(), """
                {"routeid":"%s","routeno":7000,"startnodenm":"평내신명.대주","endnodenm":"상봉역"}
                """.formatted(verified.externalRouteId()));
        rawRoute(renumbered.externalRouteId(), """
                {"routeid":"%s","routeno":7000,"startnodenm":"기점","endnodenm":"종점"}
                """.formatted(renumbered.externalRouteId()));
        Long verifiedId = alarmService.create(owner.getId(), new CreateAlarmRequest(verified.middleId(), false, false)).id();
        alarmService.create(owner.getId(), new CreateAlarmRequest(verified.middleId(), true, false));
        Long renumberedId = alarmService.create(owner.getId(), new CreateAlarmRequest(renumbered.middleId(), false, false)).id();
        Long unknownId = alarmService.create(owner.getId(), new CreateAlarmRequest(unknown.middleId(), false, false)).id();
        BusRoute changed = routeRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, renumbered.externalRouteId())
                .orElseThrow();
        changed.updateMetadata("7000A", changed.getCityCode());
        BusRoute typed = routeRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, verified.externalRouteId())
                .orElseThrow();
        typed.updateRouteType(com.stopbell.transit.domain.BusRouteType.CITY_LOCAL);
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            List<AlarmResponse> alarms = alarmService.findAll(owner.getId());
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
            assertThat(alarms).hasSize(4);
            assertThat(alarms).filteredOn(alarm -> alarm.id().equals(verifiedId)).singleElement()
                    .satisfies(alarm -> {
                        assertThat(alarm.startStopName()).isEqualTo("평내신명.대주");
                        assertThat(alarm.endStopName()).isEqualTo("상봉역");
                        assertThat(alarm.provider()).isEqualTo(TransitProvider.TAGO);
                        assertThat(alarm.routeType()).isEqualTo(com.stopbell.transit.domain.BusRouteType.CITY_LOCAL);
                    });
            assertThat(alarms).filteredOn(alarm -> alarm.id().equals(renumberedId) || alarm.id().equals(unknownId))
                    .hasSize(2)
                    .allSatisfy(alarm -> {
                        assertThat(alarm.startStopName()).isNull();
                        assertThat(alarm.endStopName()).isNull();
                    });
            assertThat(alarms).filteredOn(alarm -> alarm.id().equals(renumberedId)).singleElement()
                    .satisfies(alarm -> {
                        assertThat(alarm.provider()).isNull();
                        assertThat(alarm.routeType()).isNull();
                        assertThat(alarm.routeId()).isNull();
                        assertThat(alarm.targetStopOccurrenceId()).isNull();
                    });
        } finally {
            statistics.setStatisticsEnabled(originallyEnabled);
        }

        mockMvc.perform(get("/api/v1/alarms/{alarmId}", verifiedId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startStopName").value("평내신명.대주"))
                .andExpect(jsonPath("$.endStopName").value("상봉역"))
                .andExpect(jsonPath("$.provider").value("TAGO"))
                .andExpect(jsonPath("$.routeType").value("CITY_LOCAL"))
                .andExpect(jsonPath("$.externalRouteId").doesNotExist());
    }

    @Test
    @DisplayName("활성 알람의 한 정류장 전/후 옵션을 바꾸면 snapshot이 함께 갱신된다")
    void update_options_on_active_alarm() {
        User user = user();
        RouteFixture route = route();
        AlarmResponse created = alarmService.create(user.getId(), new CreateAlarmRequest(route.middleId(), false, false));
        alarmService.activate(user.getId(), created.id());

        AlarmResponse on = alarmService.updateOptions(user.getId(), created.id(),
                new UpdateAlarmOptionsRequest(true, true));
        BusAlarmTarget target = alarmRepository.findById(created.id()).orElseThrow().getBusAlarmTarget();
        assertThat(on.notifyOneStopBefore()).isTrue();
        assertThat(on.notifyOneStopAfter()).isTrue();
        assertThat(target.getPredecessorExternalStopId()).isEqualTo(route.firstExternalStopId());
        assertThat(target.getSuccessorExternalStopId()).isEqualTo(route.lastExternalStopId());

        AlarmResponse off = alarmService.updateOptions(user.getId(), created.id(),
                new UpdateAlarmOptionsRequest(false, false));
        assertThat(off.notifyOneStopBefore()).isFalse();
        assertThat(off.notifyOneStopAfter()).isFalse();
        assertThat(target.getPredecessorExternalStopId()).isNull();
        assertThat(target.getSuccessorStopOrder()).isNull();
    }

    @Test
    @DisplayName("비활성 알람의 옵션 수정은 409로 거부한다")
    void reject_update_options_on_inactive_alarm() {
        User user = user();
        RouteFixture route = route();
        AlarmResponse created = alarmService.create(user.getId(), new CreateAlarmRequest(route.middleId(), false, false));

        assertThatThrownBy(() -> alarmService.updateOptions(user.getId(), created.id(),
                new UpdateAlarmOptionsRequest(true, false)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALARM_NOT_ACTIVE));
    }

    @Test
    @DisplayName("첫 occurrence의 before, 마지막 occurrence의 after 옵션 수정은 400으로 거부한다")
    void reject_update_options_without_adjacent_stop() {
        User user = user();
        RouteFixture route = route();
        AlarmResponse first = alarmService.create(user.getId(), new CreateAlarmRequest(route.firstId(), false, false));
        AlarmResponse last = alarmService.create(user.getId(), new CreateAlarmRequest(route.lastId(), false, false));
        alarmService.activate(user.getId(), first.id());

        assertThatThrownBy(() -> alarmService.updateOptions(user.getId(), first.id(),
                new UpdateAlarmOptionsRequest(true, false)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ALARM_REQUEST));
        alarmService.deactivate(user.getId(), first.id());
        alarmService.activate(user.getId(), last.id());
        assertThatThrownBy(() -> alarmService.updateOptions(user.getId(), last.id(),
                new UpdateAlarmOptionsRequest(false, true)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_ALARM_REQUEST));
    }

    @Test
    @DisplayName("사용자당 활성 도착 알람은 1개이며 중복 활성화는 멱등이고 다른 사용자와 독립이다")
    void limit_one_active_alarm_per_user() {
        User user = user();
        User other = user();
        RouteFixture route = route();
        AlarmResponse first = alarmService.create(user.getId(), new CreateAlarmRequest(route.middleId(), false, false));
        AlarmResponse second = alarmService.create(user.getId(), new CreateAlarmRequest(route.middleId(), false, true));
        AlarmResponse third = alarmService.create(user.getId(), new CreateAlarmRequest(route.middleId(), true, false));
        AlarmResponse others = alarmService.create(other.getId(), new CreateAlarmRequest(route.middleId(), false, false));

        alarmService.activate(user.getId(), first.id());
        assertThat(alarmService.activate(user.getId(), first.id()).status()).isEqualTo(AlarmStatus.ACTIVE);
        assertThatThrownBy(() -> alarmService.activate(user.getId(), second.id()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALARM_ACTIVE_LIMIT_EXCEEDED));
        assertThat(alarmService.activate(other.getId(), others.id()).status()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(alarmService.findById(user.getId(), second.id()).status()).isEqualTo(AlarmStatus.INACTIVE);

        // 기존 알람을 끄면 다른 알람을 켤 수 있고, FOLLOW_UP도 활성 슬롯을 점유한다.
        alarmService.deactivate(user.getId(), first.id());
        alarmService.activate(user.getId(), second.id());
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        alarmRepository.findById(second.id()).orElseThrow()
                .startFollowUp("vehicle-1", TRACKING_CYCLE_ID, startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
        entityManager.flush();
        assertThatThrownBy(() -> alarmService.activate(user.getId(), third.id()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALARM_ACTIVE_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("알람 응답은 첫·마지막 occurrence에서 불가능한 전/후 옵션을 표시한다")
    void response_reports_possible_adjacent_options() {
        User user = user();
        RouteFixture route = route();
        AlarmResponse first = alarmService.create(user.getId(), new CreateAlarmRequest(route.firstId(), false, false));
        AlarmResponse last = alarmService.create(user.getId(), new CreateAlarmRequest(route.lastId(), false, false));

        assertThat(first.canNotifyOneStopBefore()).isFalse();
        assertThat(first.canNotifyOneStopAfter()).isTrue();
        assertThat(last.canNotifyOneStopBefore()).isTrue();
        assertThat(last.canNotifyOneStopAfter()).isFalse();
    }

    @Test
    @DisplayName("다른 사용자의 알람 옵션 수정은 404로 거부한다")
    void reject_update_options_of_other_user() {
        User owner = user();
        User other = user();
        RouteFixture route = route();
        AlarmResponse created = alarmService.create(owner.getId(), new CreateAlarmRequest(route.middleId(), false, false));
        alarmService.activate(owner.getId(), created.id());

        assertThatThrownBy(() -> alarmService.updateOptions(other.getId(), created.id(),
                new UpdateAlarmOptionsRequest(true, false)))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ALARM_NOT_FOUND));
    }

    private void rawRoute(String externalRouteId, String payload) {
        BusRoute route = routeRepository.findByProviderAndExternalRouteId(TransitProvider.TAGO, externalRouteId)
                .orElseThrow();
        java.time.Instant now = java.time.Instant.now();
        sourceMetadataRepository.upsert("TAGO", "ROUTE", externalRouteId, route.getId(), null, null,
                payload, null, now, now);
    }

    private void setCreatedAt(Long alarmId, LocalDateTime createdAt) {
        entityManager.createNativeQuery("UPDATE alarms SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", alarmId)
                .executeUpdate();
    }

    private User user() {
        return userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, UUID.randomUUID().toString()));
    }

    private RouteFixture route() {
        String suffix = UUID.randomUUID().toString();
        String externalRouteId = "route-" + suffix;
        BusRoute route = routeRepository.saveAndFlush(
                new BusRoute(TransitProvider.TAGO, externalRouteId, "7000", "41110")
        );
        BusStop first = stopRepository.saveAndFlush(new BusStop(
                TransitProvider.TAGO, "stop-a-" + suffix, "Stop A", null, null
        ));
        BusStop middle = stopRepository.saveAndFlush(new BusStop(
                TransitProvider.TAGO, "stop-b-" + suffix, "Stop B",
                new BigDecimal("37.1234567"), new BigDecimal("127.1234567")
        ));
        BusStop last = stopRepository.saveAndFlush(new BusStop(
                TransitProvider.TAGO, "stop-c-" + suffix, "Stop C", null, null
        ));
        BusRouteStopOccurrence firstOccurrence = occurrenceRepository.saveAndFlush(
                new BusRouteStopOccurrence(route, first, 1)
        );
        BusRouteStopOccurrence middleOccurrence = occurrenceRepository.saveAndFlush(
                new BusRouteStopOccurrence(route, middle, 3)
        );
        BusRouteStopOccurrence lastOccurrence = occurrenceRepository.saveAndFlush(
                new BusRouteStopOccurrence(route, last, 7)
        );
        return new RouteFixture(
                route.getId(), externalRouteId,
                firstOccurrence.getId(), middleOccurrence.getId(), lastOccurrence.getId(),
                first.getExternalStopId(), middle.getExternalStopId(), last.getExternalStopId()
        );
    }

    private record RouteFixture(
            Long routeId,
            String externalRouteId,
            Long firstId,
            Long middleId,
            Long lastId,
            String firstExternalStopId,
            String middleExternalStopId,
            String lastExternalStopId
    ) {
    }
}
