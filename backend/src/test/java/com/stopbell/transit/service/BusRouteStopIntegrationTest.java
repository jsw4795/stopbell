package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.repository.BusStopRepository;
import com.stopbell.user.auth.service.JwtTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class BusRouteStopIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private BusRouteRepository busRouteRepository;

    @Autowired
    private BusStopRepository busStopRepository;

    @Autowired
    private BusRouteStopOccurrenceRepository occurrenceRepository;

    @Autowired
    private BusRouteStopService busRouteStopService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Route Stop occurrence를 traversal 순서로 반환하고 실제 인접 occurrence 기준 option을 제공한다")
    void return_occurrences_in_traversal_order_with_actual_adjacency() throws Exception {
        BusRoute route = route("route-7000");
        BusStop firstStop = stop("stop-first", "첫 정류장");
        BusStop repeatedStop = stop("stop-repeated", "재방문 정류장");
        BusRouteStopOccurrence last = occurrence(route, repeatedStop, 7);
        BusRouteStopOccurrence first = occurrence(route, firstStop, 1);
        BusRouteStopOccurrence middle = occurrence(route, repeatedStop, 3);

        mockMvc.perform(get("/api/v1/bus-routes/{routeId}/stops", route.getId())
                        .header("Authorization", authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(first.getId()))
                .andExpect(jsonPath("$[0].name").value("첫 정류장"))
                .andExpect(jsonPath("$[0].order").value(1))
                .andExpect(jsonPath("$[0].canNotifyOneStopBefore").value(false))
                .andExpect(jsonPath("$[0].canNotifyOneStopAfter").value(true))
                .andExpect(jsonPath("$[1].id").value(middle.getId()))
                .andExpect(jsonPath("$[1].name").value("재방문 정류장"))
                .andExpect(jsonPath("$[1].order").value(3))
                .andExpect(jsonPath("$[1].canNotifyOneStopBefore").value(true))
                .andExpect(jsonPath("$[1].canNotifyOneStopAfter").value(true))
                .andExpect(jsonPath("$[2].id").value(last.getId()))
                .andExpect(jsonPath("$[2].name").value("재방문 정류장"))
                .andExpect(jsonPath("$[2].order").value(7))
                .andExpect(jsonPath("$[2].canNotifyOneStopBefore").value(true))
                .andExpect(jsonPath("$[2].canNotifyOneStopAfter").value(false))
                .andExpect(jsonPath("$[0].provider").doesNotExist())
                .andExpect(jsonPath("$[0].externalRouteId").doesNotExist())
                .andExpect(jsonPath("$[0].externalStopId").doesNotExist())
                .andExpect(jsonPath("$[0].cityCode").doesNotExist())
                .andExpect(jsonPath("$[0].stopId").doesNotExist())
                .andExpect(jsonPath("$[0].latitude").doesNotExist())
                .andExpect(jsonPath("$[0].longitude").doesNotExist());
    }

    @Test
    @DisplayName("없는 Route는 BUS_ROUTE_NOT_FOUND 오류를 반환한다")
    void return_bus_route_not_found() throws Exception {
        mockMvc.perform(get("/api/v1/bus-routes/{routeId}/stops", 999999999L)
                        .header("Authorization", authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUS_ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Bus route was not found."));
    }

    @Test
    @DisplayName("형식이 잘못되었거나 양수가 아닌 Route ID는 INVALID_REQUEST 오류를 반환한다")
    void reject_invalid_route_id() throws Exception {
        mockMvc.perform(get("/api/v1/bus-routes/abc/stops").header("Authorization", authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is invalid."));

        mockMvc.perform(get("/api/v1/bus-routes/0/stops").header("Authorization", authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is invalid."));
    }

    @Test
    @DisplayName("Bus Stop 조회 API는 Access Token 없이는 접근할 수 없다")
    void require_authentication() throws Exception {
        mockMvc.perform(get("/api/v1/bus-routes/1/stops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("occurrence가 없는 Route는 metadata invariant 위반으로 실패한다")
    void reject_route_without_occurrences() {
        BusRoute route = route("route-without-occurrences");

        assertThatThrownBy(() -> busRouteStopService.findAll(route.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bus route metadata must contain at least one stop occurrence");
    }

    private BusRoute route(String externalRouteId) {
        return busRouteRepository.saveAndFlush(new BusRoute(
                TransitProvider.TAGO, externalRouteId, "7000", "41110"
        ));
    }

    private BusStop stop(String externalStopId, String name) {
        return busStopRepository.saveAndFlush(new BusStop(
                TransitProvider.TAGO, externalStopId, name, null, null
        ));
    }

    private BusRouteStopOccurrence occurrence(BusRoute route, BusStop stop, int order) {
        return occurrenceRepository.saveAndFlush(new BusRouteStopOccurrence(route, stop, order));
    }

    private String authorization() {
        return "Bearer " + jwtTokenService.createAccessToken(1L);
    }
}
