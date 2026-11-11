package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.stopbell.support.LatestSchemaIntegrationTest;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BusRouteSearchResponse;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.TransitSourceMetadataRepository;
import com.stopbell.user.auth.service.JwtTokenService;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional
class BusRouteSearchIntegrationTest extends LatestSchemaIntegrationTest {

    @Autowired
    private BusRouteRepository busRouteRepository;

    @Autowired
    private BusRouteSearchService busRouteSearchService;

    @Autowired
    private TransitSourceMetadataRepository sourceMetadataRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenService jwtTokenService;
    @Autowired private com.stopbell.user.repository.UserRepository users;

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
    @DisplayName("선행 영문자를 생략한 숫자 prefix로 검색하되 중간 substring은 검색하지 않는다")
    void search_by_prefix_after_leading_letters() {
        route(TransitProvider.SEOUL_BUS, "route-m2352", "M2352", null);
        route(TransitProvider.SEOUL_BUS, "route-n26", "N26", null);
        route(TransitProvider.TAGO, "route-g6000", "G6000", "31010");
        route(TransitProvider.SEOUL_BUS, "route-ab1234", "AB1234", null);

        for (String query : List.of("M23", "m2352", "2352", "235", "23")) {
            assertThat(busRouteSearchService.search(query)).extracting(BusRouteSearchResponse::routeNumber)
                    .containsExactly("M2352");
        }
        assertThat(busRouteSearchService.search("26")).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactly("N26");
        assertThat(busRouteSearchService.search("6000")).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactly("G6000");
        assertThat(busRouteSearchService.search("123")).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactly("AB1234");
        assertThat(busRouteSearchService.search("352")).isEmpty();
        assertThat(busRouteSearchService.search("6")).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactly("G6000");
    }

    @Test
    @DisplayName("숫자 노선과 선행 영문자 노선이 모두 일치하면 두 후보를 노선번호 순으로 반환한다")
    void return_direct_and_prefixed_route_candidates() {
        BusRoute prefixed = route(TransitProvider.SEOUL_BUS, "route-m2352", "M2352", null);
        BusRoute direct = route(TransitProvider.TAGO, "route-2352", "2352", "31010");

        assertThat(busRouteSearchService.search("2352")).extracting(BusRouteSearchResponse::id)
                .containsExactly(direct.getId(), prefixed.getId());
    }

    @Test
    @DisplayName("동일 노선번호 후보를 내부 ID와 지역명으로 각각 반환하고 ID 오름차순으로 정렬한다")
    void return_duplicate_route_number_candidates_in_id_order() throws Exception {
        BusRoute suwon = route(TransitProvider.TAGO, "route-suwon", "7000", "31010");
        suwon.updateRouteType(com.stopbell.transit.domain.BusRouteType.CITY_LOCAL);
        busRouteRepository.saveAndFlush(suwon);
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
                .andExpect(jsonPath("$[0].provider").value("TAGO"))
                .andExpect(jsonPath("$[0].routeType").value("CITY_LOCAL"))
                .andExpect(jsonPath("$[0].regionCategory").value("경기"))
                .andExpect(jsonPath("$[0].startStopName").value(Matchers.nullValue()))
                .andExpect(jsonPath("$[0].endStopName").value(Matchers.nullValue()))
                .andExpect(jsonPath("$[0].externalRouteId").doesNotExist())
                .andExpect(jsonPath("$[0].cityCode").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(gimpo.getId()))
                .andExpect(jsonPath("$[1].regionName").value("김포시"))
                .andExpect(jsonPath("$[2].id").value(gapyeong.getId()))
                .andExpect(jsonPath("$[2].regionName").value("가평군"));
    }

    @Test
    @DisplayName("TAGO 원본의 공식 기종점을 표시하고 반복 방문·복귀 정류장과 destinationName을 사용하지 않는다")
    void read_linked_tago_endpoints_for_round_trip() throws Exception {
        var tago = route(TransitProvider.TAGO, "GGB200000006", "300", "31010");
        var seoul = route(TransitProvider.SEOUL_BUS, "seoul-300", "300", null);
        var start = stop("start", "수월암리공단");
        var turn = stop("turn", "롯데백화점.범계역");
        var depot = stop("depot", "수월암차고지");
        entityManager.persist(new BusRouteStopOccurrence(tago, start, 1, "방향 표시용 이름"));
        entityManager.persist(new BusRouteStopOccurrence(tago, turn, 82, depot.getStopName()));
        entityManager.persist(new BusRouteStopOccurrence(tago, start, 162, depot.getStopName()));
        entityManager.persist(new BusRouteStopOccurrence(tago, depot, 163, depot.getStopName()));
        rawRoute("TAGO", "GGB200000006", tago.getId(), """
                {"routeid":"GGB200000006","routeno":300,
                 "startnodenm":"수월암리공단","endnodenm":"롯데백화점.범계역"}
                """);

        mockMvc.perform(get("/api/v1/bus-routes").param("query", "300")
                        .header("Authorization", authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(tago.getId()))
                .andExpect(jsonPath("$[0].regionCategory").value("경기"))
                .andExpect(jsonPath("$[0].startStopName").value("수월암리공단"))
                .andExpect(jsonPath("$[0].endStopName").value("롯데백화점.범계역"))
                .andExpect(jsonPath("$[1].id").value(seoul.getId()))
                .andExpect(jsonPath("$[1].regionCategory").value("서울"))
                .andExpect(jsonPath("$[1].startStopName").value(Matchers.nullValue()))
                .andExpect(jsonPath("$[1].endStopName").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("동일 명칭·누락·잘못 연결된 기종점은 추측하지 않는다")
    void suppress_unverified_or_indistinguishable_endpoints() {
        var sameName = route(TransitProvider.TAGO, "same", "165", "31130");
        var partial = route(TransitProvider.TAGO, "partial", "165", "31230");
        var unlinked = route(TransitProvider.TAGO, "unlinked", "165", "31010");
        var stale = route(TransitProvider.TAGO, "stale", "165", "31010");
        rawRoute("TAGO", sameName.getExternalRouteId(), sameName.getId(), """
                {"routeid":"same","routeno":165,"startnodenm":"순환기점","endnodenm":"순환기점"}
                """);
        var start = stop("loop", "순환기점");
        entityManager.persist(new BusRouteStopOccurrence(sameName, start, 1, "회차 안내"));
        entityManager.persist(new BusRouteStopOccurrence(sameName, stop("mid", "중간 정류장"), 2, "순환기점"));
        entityManager.persist(new BusRouteStopOccurrence(sameName, start, 3, "순환기점"));
        rawRoute("TAGO", partial.getExternalRouteId(), partial.getId(), """
                {"routeid":"partial","routeno":165,"startnodenm":"기점","endnodenm":null}
                """);
        rawRoute("TAGO", unlinked.getExternalRouteId(), null, """
                {"routeid":"unlinked","routeno":165,"startnodenm":"기점","endnodenm":"종점"}
                """);
        rawRoute("TAGO", stale.getExternalRouteId(), stale.getId(), """
                {"routeid":"stale","routeno":"165A","startnodenm":"기점","endnodenm":"종점"}
                """);

        assertThat(busRouteSearchService.search("165")).hasSize(4).allSatisfy(result -> {
            assertThat(result.regionCategory()).isEqualTo("경기");
            assertThat(result.startStopName()).isNull();
            assertThat(result.endStopName()).isNull();
        });
    }

    @Test
    @DisplayName("서울 공식 표시 구간은 연결된 노선에만 제공하고 같은 이름의 순환 구간은 보류한다")
    void return_seoul_endpoints_for_exact_route_identity() throws Exception {
        var route = route(TransitProvider.SEOUL_BUS, "100100022", "143", null);
        var other = route(TransitProvider.SEOUL_BUS, "100100023", "143", null);
        var stale = route(TransitProvider.SEOUL_BUS, "100100024", "143", null);
        var loop = route(TransitProvider.SEOUL_BUS, "100100001", "01A", null);
        seoulEndpoints(route, "100100022", "143", "정릉", "개포동");
        seoulEndpoints(other, "100100022", "143", "정릉", "개포동");
        seoulEndpoints(stale, "100100024", "144", "정릉", "개포동");
        seoulEndpoints(loop, "100100001", "01A", "예장주차장", "예장주차장");

        mockMvc.perform(get("/api/v1/bus-routes").param("query", "143")
                        .header("Authorization", authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(route.getId()))
                .andExpect(jsonPath("$[0].provider").value("SEOUL_BUS"))
                .andExpect(jsonPath("$[0].regionCategory").value("서울"))
                .andExpect(jsonPath("$[0].startStopName").value("정릉"))
                .andExpect(jsonPath("$[0].endStopName").value("개포동"))
                .andExpect(jsonPath("$[1].id").value(other.getId()))
                .andExpect(jsonPath("$[1].startStopName").value(Matchers.nullValue()))
                .andExpect(jsonPath("$[2].startStopName").value(Matchers.nullValue()));
        assertThat(busRouteSearchService.search("01A")).singleElement().satisfies(result -> {
            assertThat(result.startStopName()).isNull();
            assertThat(result.endStopName()).isNull();
        });
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

        var statistics = entityManager.unwrap(Session.class).getSessionFactory().getStatistics();
        long queryCount = statistics.getQueryExecutionCount();
        List<BusRouteSearchResponse> response = busRouteSearchService.search("77");

        assertThat(statistics.getQueryExecutionCount() - queryCount).isEqualTo(2);
        assertThat(response).hasSize(50);
        assertThat(response).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 50)
                        .mapToObj(index -> "77" + String.format("%02d", index))
                        .toList());
        assertThat(busRouteSearchService.search("7799")).extracting(BusRouteSearchResponse::id)
                .containsExactly(firstSameNumber.getId(), secondSameNumber.getId());
    }

    @Test
    @DisplayName("영문 prefix를 생략한 검색 결과도 최대 50건으로 제한한다")
    void limit_search_results_after_leading_letters() {
        for (int index = 50; index >= 0; index--) {
            route(TransitProvider.SEOUL_BUS, "route-m-" + index, "M88" + String.format("%02d", index), null);
        }

        var statistics = entityManager.unwrap(Session.class).getSessionFactory().getStatistics();
        long queryCount = statistics.getQueryExecutionCount();
        var results = busRouteSearchService.search("88");
        assertThat(statistics.getQueryExecutionCount() - queryCount).isEqualTo(2);
        assertThat(results).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 50)
                        .mapToObj(index -> "M88" + String.format("%02d", index))
                        .toList());
    }

    @Test
    @DisplayName("LIKE wildcard 문자는 literal prefix로 처리한다")
    void treat_like_wildcards_as_literals() {
        route(TransitProvider.TAGO, "route-percent", "70%1", "31010");
        route(TransitProvider.TAGO, "route-plain", "701", "31010");
        route(TransitProvider.TAGO, "route-underscore", "70A1", "31010");
        route(TransitProvider.SEOUL_BUS, "route-prefixed-percent", "M70%1", null);
        route(TransitProvider.SEOUL_BUS, "route-prefixed-plain", "M701", null);
        route(TransitProvider.SEOUL_BUS, "route-prefixed-underscore", "M70A1", null);

        assertThat(busRouteSearchService.search("70%")).extracting(BusRouteSearchResponse::routeNumber)
                .containsExactly("70%1", "M70%1");
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

    private BusStop stop(String externalStopId, String name) {
        var stop = new BusStop(TransitProvider.TAGO, externalStopId, name,
                new BigDecimal("37.5"), new BigDecimal("127.0"));
        entityManager.persist(stop);
        return stop;
    }

    private void rawRoute(String source, String sourceKey, Long routeId, String payload) {
        Instant now = Instant.now();
        sourceMetadataRepository.upsert(source, "ROUTE", sourceKey, routeId, null, null,
                payload, null, now, now);
    }

    private void seoulEndpoints(BusRoute route, String externalId, String number, String start, String end) {
        jdbc.update("""
                INSERT INTO seoul_route_endpoints VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), REPEAT('a', 64), 1)
                """, route.getId(), externalId, number, start, end);
    }

    private String authorization() {
        var user = users.saveAndFlush(new com.stopbell.user.entity.User(com.stopbell.user.entity.AuthProvider.GOOGLE,
                java.util.UUID.randomUUID().toString()));
        return "Bearer " + jwtTokenService.createAccessToken(user.getId());
    }
}
