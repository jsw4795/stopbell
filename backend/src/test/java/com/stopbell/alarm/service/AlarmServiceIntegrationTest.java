package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import com.stopbell.alarm.dto.AlarmResponse;
import com.stopbell.alarm.dto.CreateAlarmRequest;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.repository.AlarmRepository;
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
