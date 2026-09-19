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
import java.util.UUID;

import com.stopbell.alarm.dto.AlarmResponse;
import com.stopbell.alarm.dto.CreateAlarmRequest;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.notification.entity.NotificationHistory;
import com.stopbell.notification.entity.NotificationStatus;
import com.stopbell.notification.repository.NotificationHistoryRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class AlarmServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private AlarmService alarmService;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private NotificationHistoryRepository notificationHistoryRepository;

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
                alarm.getId(), TransitType.BUS, AlarmStatus.INACTIVE, "7000", "Stop B", true, true
        ));
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
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThat(alarmRepository.count()).isEqualTo(alarmCount);
    }

    @Test
    @DisplayName("마지막 occurrence에서 after option을 요청하면 Alarm을 생성하지 않는다")
    void reject_after_on_last_occurrence() {
        User user = user();
        RouteFixture route = route();
        long alarmCount = alarmRepository.count();

        assertThatThrownBy(() -> alarmService.create(user.getId(), new CreateAlarmRequest(route.lastId(), false, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
        assertThat(alarmRepository.count()).isEqualTo(alarmCount);
    }

    @Test
    @DisplayName("없는 occurrence와 null target은 명확히 거부하고 Alarm을 생성하지 않는다")
    void reject_missing_or_null_target() {
        User user = user();
        long alarmCount = alarmRepository.count();

        assertThatThrownBy(() -> alarmService.create(user.getId(), new CreateAlarmRequest(Long.MAX_VALUE, false, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        assertThatThrownBy(() -> alarmService.create(user.getId(), new CreateAlarmRequest(null, false, false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
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
                .startFollowUp("vehicle-1", startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
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
        alarm.startFollowUp("vehicle-1", startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
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
        alarm.startFollowUp("vehicle-1", startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
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
        alarm.startFollowUp("vehicle-1", startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
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
        alarm.startFollowUp("vehicle-1", startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
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
        notificationHistoryRepository.saveAndFlush(new NotificationHistory(
                alarmRepository.findById(alarmId).orElseThrow(), NotificationStatus.SUCCESS, null
        ));
        notificationHistoryRepository.saveAndFlush(new NotificationHistory(
                alarmRepository.findById(alarmId).orElseThrow(), NotificationStatus.FAILURE, "provider error"
        ));
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
        assertThat(notificationHistoryRepository.count()).isZero();
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
        Long historyId = notificationHistoryRepository.saveAndFlush(new NotificationHistory(
                alarmRepository.findById(alarmId).orElseThrow(), NotificationStatus.SUCCESS, null
        )).getId();

        mockMvc.perform(delete("/api/v1/alarms/{alarmId}", alarmId)
                        .header("Authorization", "Bearer " + jwtTokenService.createAccessToken(other.getId())))
                .andExpect(status().isNotFound());
        entityManager.flush();
        entityManager.clear();

        assertThat(alarmRepository.existsById(alarmId)).isTrue();
        assertThat(entityManager.find(BusAlarmTarget.class, alarmId)).isNotNull();
        assertThat(notificationHistoryRepository.existsById(historyId)).isTrue();
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
        alarm.startFollowUp("vehicle-1", startedAt, startedAt.plus(10, ChronoUnit.MINUTES));
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
    @DisplayName("Alarm 목록과 모든 BusAlarmTarget을 한 쿼리로 조회한다")
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
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(originallyEnabled);
        }
    }

    @Test
    @DisplayName("Alarm 상세와 BusAlarmTarget을 한 쿼리로 조회한다")
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
                    alarmId, TransitType.BUS, AlarmStatus.INACTIVE, "7000", "Stop B", false, false
            ));
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(originallyEnabled);
        }
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
                externalRouteId,
                firstOccurrence.getId(), middleOccurrence.getId(), lastOccurrence.getId(),
                first.getExternalStopId(), middle.getExternalStopId(), last.getExternalStopId()
        );
    }

    private record RouteFixture(
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
