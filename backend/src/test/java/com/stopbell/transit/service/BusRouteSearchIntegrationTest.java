package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BusRouteSearchResponse;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.repository.BusRouteRepository;
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
class BusRouteSearchIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private BusRouteRepository busRouteRepository;

    @Autowired
    private BusRouteSearchService busRouteSearchService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("노선번호를 trim한 대소문자 무시 prefix로 검색하고 관계없는 노선은 제외한다")
    void search_by_trimmed_case_insensitive_prefix() {
        route(TransitProvider.TAGO, "route-7000", "7000", "31010");
        route(TransitProvider.TAGO, "route-701", "701", "31230");
        route(TransitProvider.TAGO, "route-170", "170", "31370");
        route(TransitProvider.SEOUL_BUS, "route-n26", "N26", null);

        List<BusRouteSearchResponse> seventyRoutes = busRouteSearchService.search(" 70 ");
        List<BusRouteSearchResponse> nightRoutes = busRouteSearchService.search("n2");

        assertThat(seventyRoutes).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactly("7000", "701");
        assertThat(nightRoutes).extracting(BusRouteSearchResponse::routeNumber).containsExactly("N26");
    }

    @Test
    @DisplayName("동일 노선번호 후보를 내부 ID와 지역명으로 각각 반환하고 ID 오름차순으로 정렬한다")
    void return_duplicate_route_number_candidates_in_id_order() throws Exception {
        BusRoute suwon = route(TransitProvider.TAGO, "route-suwon", "7000", "31010");
        BusRoute gimpo = route(TransitProvider.TAGO, "route-gimpo", "7000", "31230");
        BusRoute gapyeong = route(TransitProvider.TAGO, "route-gapyeong", "7000", "31370");

        mockMvc.perform(get("/api/v1/bus-routes")
                        .param("query", "7000")
                        .header("Authorization", authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(suwon.getId()))
                .andExpect(jsonPath("$[0].routeNumber").value("7000"))
                .andExpect(jsonPath("$[0].regionName").value("수원시"))
                .andExpect(jsonPath("$[0].provider").doesNotExist())
                .andExpect(jsonPath("$[0].externalRouteId").doesNotExist())
                .andExpect(jsonPath("$[0].cityCode").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(gimpo.getId()))
                .andExpect(jsonPath("$[1].regionName").value("김포시"))
                .andExpect(jsonPath("$[2].id").value(gapyeong.getId()))
                .andExpect(jsonPath("$[2].regionName").value("가평군"));
    }

    @Test
    @DisplayName("검색 결과는 노선번호와 같은 번호의 내부 ID 순으로 최대 50건만 반환한다")
    void limit_and_order_search_results() {
        for (int index = 50; index >= 0; index--) {
            String routeNumber = "77" + String.format("%02d", index);
            route(TransitProvider.TAGO, "route-" + index, routeNumber, "31010");
        }
        BusRoute firstSameNumber = route(TransitProvider.TAGO, "route-same-first", "7799", "31010");
        BusRoute secondSameNumber = route(TransitProvider.TAGO, "route-same-second", "7799", "31230");

        List<BusRouteSearchResponse> response = busRouteSearchService.search("77");

        assertThat(response).hasSize(50);
        assertThat(response).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 50)
                        .mapToObj(index -> "77" + String.format("%02d", index))
                        .toList());
        assertThat(busRouteSearchService.search("7799")).extracting(BusRouteSearchResponse::id)
                .containsExactly(firstSameNumber.getId(), secondSameNumber.getId());
    }

    @Test
    @DisplayName("LIKE wildcard 문자는 literal prefix로 처리한다")
    void treat_like_wildcards_as_literals() {
        route(TransitProvider.TAGO, "route-percent", "70%1", "31010");
        route(TransitProvider.TAGO, "route-plain", "701", "31010");
        route(TransitProvider.TAGO, "route-underscore", "70A1", "31010");

        assertThat(busRouteSearchService.search("70%")).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactly("70%1");
        assertThat(busRouteSearchService.search("70_")).isEmpty();
    }

    @Test
    @DisplayName("일치하는 노선이 없으면 인증된 요청에 200과 빈 배열을 반환한다")
    void return_empty_array_when_no_routes_match() throws Exception {
        mockMvc.perform(get("/api/v1/bus-routes")
                        .param("query", "999")
                        .header("Authorization", authorization()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("빈 query와 누락 query는 구조화된 INVALID_REQUEST 오류를 반환한다")
    void reject_missing_or_blank_query() throws Exception {
        for (String query : List.of("", "   ")) {
            mockMvc.perform(get("/api/v1/bus-routes")
                            .param("query", query)
                            .header("Authorization", authorization()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Request is invalid."));
        }

        mockMvc.perform(get("/api/v1/bus-routes").header("Authorization", authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is invalid."));
    }

    @Test
    @DisplayName("Bus Route 검색 API는 Access Token 없이는 접근할 수 없고 인증 요청은 처리한다")
    void require_authentication() throws Exception {
        mockMvc.perform(get("/api/v1/bus-routes").param("query", "70"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/bus-routes")
                        .param("query", "70")
                        .header("Authorization", authorization()))
                .andExpect(status().isOk());
    }

    private BusRoute route(TransitProvider provider, String externalRouteId, String routeNumber, String cityCode) {
        return busRouteRepository.saveAndFlush(new BusRoute(provider, externalRouteId, routeNumber, cityCode));
    }

    private String authorization() {
        return "Bearer " + jwtTokenService.createAccessToken(1L);
    }
}
