package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.repository.BusStopRepository;
import com.stopbell.user.auth.service.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class BoardingCandidateApiIntegrationTest {
    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwt;
    @Autowired private BusRouteRepository routes;
    @Autowired private BusStopRepository stops;
    @Autowired private BusRouteStopOccurrenceRepository occurrences;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private TagoVehicleLocationClient tago;
    @MockitoBean private SeoulBusVehicleLocationClient seoul;
    @MockitoBean private SeoulBusVehicleDetailClient detail;
    private BusRoute route;
    private BusRouteStopOccurrence target;

    @BeforeEach
    void set_up() {
        occurrences.deleteAll();
        routes.deleteAll();
        stops.deleteAll();
        route = routes.saveAndFlush(new BusRoute(TransitProvider.TAGO, "tago-route", "7000", "31010"));
        target = occurrences.saveAndFlush(new BusRouteStopOccurrence(route,
                stops.saveAndFlush(new BusStop(TransitProvider.TAGO, "s1", "목적지", null, null)), 10));
    }

    @Test
    @DisplayName("후보 API는 실제 JWT 인증이 필수이며 미인증과 잘못된 토큰은 401이다")
    void require_valid_jwt() throws Exception {
        mvc.perform(request()).andExpect(status().isUnauthorized());
        mvc.perform(request().header("Authorization", "Bearer invalid-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(tago, seoul, detail);
    }

    @Test
    @DisplayName("정상 후보는 raw 식별자와 GPS 없이 반환하며 Provider 호출은 transaction 밖에서 수행한다")
    void normal_candidates_without_side_effects() throws Exception {
        when(tago.fetchVehicleLocations(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return BoardingCandidateTestFixtures.tagoResponse(BoardingCandidateTestFixtures.tago("경기70바5770", "s1", 10));
        });
        mvc.perform(auth(request())).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateSetStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.observedAt").isNotEmpty())
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].vehicleNumber").value("경기70바5770"))
                .andExpect(jsonPath("$.candidates[0].candidateRef").isNotEmpty())
                .andExpect(jsonPath("$.candidates[0].confirmationStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.candidates[0].trackingSupport").value("HELD"))
                .andExpect(jsonPath("$.candidates[0].progress.occurrenceId").value(target.getId()))
                .andExpect(jsonPath("$.candidates[0].vehicleId").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.externalRouteId").doesNotExist())
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.selectedCandidateRef").doesNotExist());
        assertThat(jdbc.queryForObject("select count(*) from alarms", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_events", Long.class)).isZero();
        verify(tago).fetchVehicleLocations(argThat(req -> req.externalRouteId().equals("tago-route")
                && req.context().cityCode().equals("31010")));
        verifyNoInteractions(seoul, detail);
    }

    @Test
    @DisplayName("정상 empty는 200 EMPTY로 응답한다")
    void empty_response() throws Exception {
        when(tago.fetchVehicleLocations(any())).thenReturn(BoardingCandidateTestFixtures.tagoResponse());
        mvc.perform(auth(request())).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateSetStatus").value("EMPTY"))
                .andExpect(jsonPath("$.candidates").isEmpty());
    }

    @Test
    @DisplayName("식별 불가 항목은 정상 후보와 함께 200 PARTIAL로 응답한다")
    void partial_response() throws Exception {
        when(tago.fetchVehicleLocations(any())).thenReturn(BoardingCandidateTestFixtures.tagoResponse(
                BoardingCandidateTestFixtures.tago("A", "s1", 10), BoardingCandidateTestFixtures.tago(null, null, null)));
        mvc.perform(auth(request())).andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateSetStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[1].confirmationStatus").value("HELD"))
                .andExpect(jsonPath("$.candidates[1].candidateRef").isEmpty());
    }

    @Test
    @DisplayName("Provider protocol 실패는 원문 없이 503으로 응답한다")
    void provider_error_response() throws Exception {
        when(tago.fetchVehicleLocations(any())).thenThrow(
                TransitProviderClientException.protocol(TransitProvider.TAGO, "operation", new RuntimeException("secret")));
        mvc.perform(auth(request())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRANSIT_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Transit is temporarily unavailable."));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("인증된 후보 요청의 서울 Provider 401은 Client 401이 아닌 503이며 안전한 발생 지점만 기록한다")
    void upstream_unauthorized_is_not_client_unauthorized(CapturedOutput output) throws Exception {
        route = routes.saveAndFlush(new BusRoute(TransitProvider.SEOUL_BUS, "seoul-route", "143", null));
        target = occurrences.saveAndFlush(new BusRouteStopOccurrence(route,
                stops.saveAndFlush(new BusStop(TransitProvider.SEOUL_BUS, "seoul-stop", "목적지", null, null)), 10));
        when(seoul.fetchVehicleLocations(any())).thenThrow(TransitProviderClientException.http(
                TransitProvider.SEOUL_BUS, "getBusPosByRtidList", 401,
                new RuntimeException("serviceKey=upstream-key-secret upstream-body-secret")));
        String token = jwt.createAccessToken(7L);

        mvc.perform(request().header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRANSIT_TEMPORARILY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Transit is temporarily unavailable."));

        verify(seoul, times(1)).fetchVehicleLocations(any());
        verifyNoInteractions(tago, detail);
        assertThat(output.getAll()).contains("stage=provider-roster provider=SEOUL_BUS",
                        "operation=getBusPosByRtidList kind=HTTP upstreamStatus=401 responseStatus=503")
                .doesNotContain(token, "upstream-key-secret", "upstream-body-secret");
    }

    @Test
    @DisplayName("필수 목적지 누락과 양수가 아닌 ID 및 형식 오류는 400이다")
    void invalid_ids() throws Exception {
        for (String path : new String[] {
                "/api/v1/bus-routes/0/boarding-candidates?targetStopOccurrenceId=1",
                "/api/v1/bus-routes/abc/boarding-candidates?targetStopOccurrenceId=1",
                "/api/v1/bus-routes/1/boarding-candidates",
                "/api/v1/bus-routes/1/boarding-candidates?targetStopOccurrenceId=0",
                "/api/v1/bus-routes/1/boarding-candidates?targetStopOccurrenceId=abc",
                "/api/v1/bus-routes/1/boarding-candidates?targetStopOccurrenceId=1&boardingStopOccurrenceId=-1"
        }) {
            mvc.perform(auth(get(path))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(tago, seoul, detail);
    }

    @Test
    @DisplayName("없는 Route와 occurrence는 기존 404 오류를 반환한다")
    void missing_metadata() throws Exception {
        mvc.perform(auth(get("/api/v1/bus-routes/999999999/boarding-candidates")
                        .param("targetStopOccurrenceId", target.getId().toString())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BUS_ROUTE_NOT_FOUND"));
        mvc.perform(auth(get("/api/v1/bus-routes/{id}/boarding-candidates", route.getId())
                        .param("targetStopOccurrenceId", "999999999")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TARGET_STOP_OCCURRENCE_NOT_FOUND"));
        mvc.perform(auth(request().param("boardingStopOccurrenceId", "999999999")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TARGET_STOP_OCCURRENCE_NOT_FOUND"));
        verifyNoInteractions(tago, seoul, detail);
    }

    @Test
    @DisplayName("같은 노선번호의 다른 Provider Route에 속한 목적지나 승차 occurrence는 400이다")
    void reject_other_route_occurrences() throws Exception {
        BusRoute otherRoute = routes.saveAndFlush(new BusRoute(TransitProvider.SEOUL_BUS, "seoul-route", "7000", null));
        BusRouteStopOccurrence other = occurrences.saveAndFlush(new BusRouteStopOccurrence(otherRoute,
                stops.saveAndFlush(new BusStop(TransitProvider.SEOUL_BUS, "seoul-stop", "같은 이름", null, null)), 10));
        mvc.perform(auth(get("/api/v1/bus-routes/{id}/boarding-candidates", route.getId())
                        .param("targetStopOccurrenceId", other.getId().toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(auth(request().param("boardingStopOccurrenceId", other.getId().toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(tago, seoul, detail);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/api/v1/bus-routes/{id}/boarding-candidates", route.getId())
                .param("targetStopOccurrenceId", target.getId().toString());
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(7L));
    }
}
