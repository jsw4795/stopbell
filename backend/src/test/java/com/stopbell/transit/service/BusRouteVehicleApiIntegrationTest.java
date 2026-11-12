package com.stopbell.transit.service;

import static com.stopbell.transit.service.BoardingCandidateTestFixtures.tago;
import static com.stopbell.transit.service.BoardingCandidateTestFixtures.tagoResponse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stopbell.support.LatestSchemaIntegrationTest;
import com.stopbell.transit.client.GbisBusLocationClient;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class BusRouteVehicleApiIntegrationTest extends LatestSchemaIntegrationTest {
    @Autowired private BusRouteRepository routes;
    @Autowired private BusStopRepository stops;
    @Autowired private BusRouteStopOccurrenceRepository occurrences;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private com.stopbell.user.repository.UserRepository users;
    @Autowired private MockMvc mockMvc;
    @MockitoBean private TagoVehicleLocationClient tagoClient;
    @MockitoBean private SeoulBusVehicleLocationClient seoulClient;
    @MockitoBean private GbisBusLocationClient gbisClient;

    @Test
    @DisplayName("차량 위치 API는 인증을 요구하고 Provider 원본 ID 없이 위치를 반환한다")
    void returns_vehicles_for_authenticated_user() throws Exception {
        var route = routes.saveAndFlush(new BusRoute(TransitProvider.TAGO, "GGB-vehicle-api", "7000", "41110"));
        var stop = stops.saveAndFlush(new BusStop(TransitProvider.TAGO, "api-s1", "첫 정류장", null, null));
        var occurrence = occurrences.saveAndFlush(new BusRouteStopOccurrence(route, stop, 1));
        when(tagoClient.fetchVehicleLocations(any())).thenReturn(tagoResponse(tago("경기74아3491", "api-s1", 1)));

        mockMvc.perform(get("/api/v1/bus-routes/{id}/vehicles", route.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/bus-routes/{id}/vehicles", route.getId()).header("Authorization", authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.observedAt").exists())
                .andExpect(jsonPath("$.vehicles[0].vehicleNumber").value("경기74아3491"))
                .andExpect(jsonPath("$.vehicles[0].occurrenceId").value(occurrence.getId()))
                .andExpect(jsonPath("$.vehicles[0].seat.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.vehicles[0].seat.remaining").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.vehicles[0].vehicleId").doesNotExist())
                .andExpect(jsonPath("$.vehicles[0].gpsX").doesNotExist());
    }

    @Test
    @DisplayName("없는 Route는 404, 잘못된 id는 400, Provider 실패와 캐시 없음은 503을 반환한다")
    void error_responses() throws Exception {
        var auth = authorization();
        mockMvc.perform(get("/api/v1/bus-routes/{id}/vehicles", 999999L).header("Authorization", auth))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bus-routes/{id}/vehicles", 0L).header("Authorization", auth))
                .andExpect(status().isBadRequest());
        var route = routes.saveAndFlush(new BusRoute(TransitProvider.TAGO, "vehicle-api-fail", "7001", "41110"));
        var stop = stops.saveAndFlush(new BusStop(TransitProvider.TAGO, "api-s2", "정류장", null, null));
        occurrences.saveAndFlush(new BusRouteStopOccurrence(route, stop, 1));
        when(tagoClient.fetchVehicleLocations(any()))
                .thenThrow(TransitProviderClientException.transport(TransitProvider.TAGO, "op", null));
        mockMvc.perform(get("/api/v1/bus-routes/{id}/vehicles", route.getId()).header("Authorization", auth))
                .andExpect(status().isServiceUnavailable());
    }

    private String authorization() {
        var user = users.saveAndFlush(new com.stopbell.user.entity.User(com.stopbell.user.entity.AuthProvider.GOOGLE,
                java.util.UUID.randomUUID().toString()));
        return "Bearer " + jwtTokenService.createAccessToken(user.getId());
    }
}
