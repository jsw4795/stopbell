package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import com.stopbell.transit.service.CompleteBusMetadataSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

class SeoulCsvMetadataSourceTest {

    @Test
    @DisplayName("UTF-8 BOM 서울 T Data fixture를 실제 한글 header와 join 계약으로 파싱한다")
    void parse_utf8_bom_fixture_with_actual_headers_and_joins() {
        SeoulCsvMetadataSource.CompleteSourceSnapshot snapshot = source(ROUTES, ROUTE_STOPS, STOPS)
                .fetchCompleteSourceSnapshot();

        SeoulCsvMetadataSource.SourceRouteSnapshot route7016 = route(snapshot, "100100447");
        assertThat(snapshot.routes()).hasSize(3);
        assertThat(route7016.routeNumber()).isEqualTo("7016");
        assertThat(route7016.occurrences()).extracting("externalStopId")
                .containsExactly("111000907", "111000225", "113000022");
        assertThat(route7016.occurrences()).extracting("stopName")
                .containsExactly("은평공영차고지", "덕은교.은평차고지앞", "DMC첨단산업센터");
        assertThat(route7016.occurrences().get(2).longitude()).isEqualByComparingTo("126.884787");
        assertThat(route7016.occurrences().get(2).latitude()).isEqualByComparingTo("37.585598");

        assertThat(route(snapshot, "102900001").occurrences())
                .extracting("externalStopId", "stopOrder")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("102900097", 15),
                        org.assertj.core.groups.Tuple.tuple("102900097", 23)
                );
        assertThat(route(snapshot, "241006973").routeType()).isEqualTo("경기");
        assertThat(route(snapshot, "241006973").occurrences()).isEmpty();
    }

    @Test
    @DisplayName("노선-정류장 행이 없는 노선을 참조하면 complete snapshot 생성을 거부한다")
    void reject_missing_route_join() {
        String routeStops = ROUTE_STOPS.replaceFirst("100100447,111000907", "missing-route,111000907");

        assertThatThrownBy(() -> source(ROUTES, routeStops, STOPS).fetchCompleteSourceSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown route");
    }

    @Test
    @DisplayName("노선-정류장 행이 없는 정류장을 참조하면 complete snapshot 생성을 거부한다")
    void reject_missing_stop_join() {
        String routeStops = ROUTE_STOPS.replaceFirst("100100447,111000907", "100100447,missing-stop");

        assertThatThrownBy(() -> source(ROUTES, routeStops, STOPS).fetchCompleteSourceSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown stop");
    }

    @Test
    @DisplayName("같은 노선의 중복 정류장 순번을 거부한다")
    void reject_duplicate_stop_order() {
        String routeStops = ROUTE_STOPS.replace("102900001,102900097,168.0,23", "102900001,102900097,168.0,15");

        assertThatThrownBy(() -> source(ROUTES, routeStops, STOPS).fetchCompleteSourceSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate stop order");
    }

    @Test
    @DisplayName("유효 범위를 벗어난 X 좌표를 거부한다")
    void reject_invalid_coordinate() {
        String stops = STOPS.replace("126.884787,37.585598", "181,37.585598");

        assertThatThrownBy(() -> source(ROUTES, ROUTE_STOPS, stops).fetchCompleteSourceSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    @DisplayName("필수 서울 CSV source가 없으면 complete snapshot 생성을 거부한다")
    void reject_missing_required_source() {
        Resource missing = new FileSystemResource("/private/tmp/stopbell-missing-seoul-stop-master.csv");

        assertThatThrownBy(() -> new SeoulCsvMetadataSource(resource(ROUTES), resource(ROUTE_STOPS), missing)
                .fetchCompleteSourceSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source is missing");
    }

    @Test
    @DisplayName("계약과 다른 필수 header를 거부한다")
    void reject_unexpected_required_header() {
        String routes = ROUTES.replace("노선명", "노선번호");

        assertThatThrownBy(() -> source(routes, ROUTE_STOPS, STOPS).fetchCompleteSourceSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("headers do not match");
    }

    @Test
    @DisplayName("공식 노선유형과 null 노선의 정류장유형으로 SEOUL_BUS complete snapshot 범위를 결정한다")
    void create_complete_snapshot_with_explicit_route_scope() {
        CompleteBusMetadataSnapshot snapshot = source(SCOPED_ROUTES, SCOPED_ROUTE_STOPS, SCOPED_STOPS)
                .fetchCompleteSnapshot();

        assertThat(snapshot.routes()).extracting("externalRouteId")
                .containsExactlyInAnyOrder("100100447", "102900001", "100100586");
        assertThat(snapshot.routes()).extracting("routeNumber").contains("N26");
        assertThat(snapshot.routes()).extracting("externalRouteId")
                .doesNotContain("241006973", "300000001", "123000021");
    }

    @Test
    @DisplayName("null 노선유형 Route에 선착장과 버스 정류장이 섞이면 complete snapshot 생성을 거부한다")
    void reject_null_route_type_with_mixed_stop_types() {
        String routeStops = SCOPED_ROUTE_STOPS + "100100586,123000689,100.0,2\n";

        assertThatThrownBy(() -> source(SCOPED_ROUTES, routeStops, SCOPED_STOPS).fetchCompleteSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mixed stop types");
    }

    @Test
    @DisplayName("null 노선유형 Route에 occurrence가 없으면 complete snapshot 생성을 거부한다")
    void reject_null_route_type_without_occurrences() {
        String routeStops = SCOPED_ROUTE_STOPS.replace("100100586,115000321,0.0,1\n", "");

        assertThatThrownBy(() -> source(SCOPED_ROUTES, routeStops, SCOPED_STOPS).fetchCompleteSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no occurrences");
    }

    @Test
    @DisplayName("미확인 정류장유형이 있으면 Route scope를 추측하지 않고 complete snapshot 생성을 거부한다")
    void reject_unknown_stop_type() {
        String stops = SCOPED_STOPS.replaceFirst("일반차로", "미확인유형");

        assertThatThrownBy(() -> source(SCOPED_ROUTES, SCOPED_ROUTE_STOPS, stops).fetchCompleteSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown stop type");
    }

    private SeoulCsvMetadataSource source(String routes, String routeStops, String stops) {
        return new SeoulCsvMetadataSource(resource(routes), resource(routeStops), resource(stops));
    }

    private Resource resource(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }

    private SeoulCsvMetadataSource.SourceRouteSnapshot route(
            SeoulCsvMetadataSource.CompleteSourceSnapshot snapshot,
            String routeId
    ) {
        return snapshot.routes().stream()
                .filter(route -> route.externalRouteId().equals(routeId))
                .findFirst()
                .orElseThrow();
    }

    private static final String ROUTES = "\uFEFF" + """
            노선ID,노선명,노선유형,거리
            100100447,7016,지선,50.0
            102900001,마포01,마을,5.9
            241006973,8455안성,경기,
            """;

    private static final String ROUTE_STOPS = "\uFEFF" + """
            노선ID,노드ID,링크거리누계,정류장순번
            100100447,111000907,73.0,1
            100100447,111000225,294.0,2
            100100447,113000022,713.0,3
            102900001,102900097,208.0,15
            102900001,102900097,168.0,23
            """;

    private static final String STOPS = "\uFEFF" + """
            정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부
            111000907,은평공영차고지,일반차로,35331,126.883807875,37.5918053134,미설치
            111000225,덕은교.은평차고지앞,일반차로,12315,126.883446945,37.589961128,설치
            113000022,DMC첨단산업센터,일반차로,14112,126.884787,37.585598,설치
            102900097,도원삼성래미안아파트101동앞,마을버스,03511,126.956232081,37.5390685906,미설치
            """;

    private static final String SCOPED_ROUTES = "\uFEFF" + """
            노선ID,노선명,노선유형,거리
            100100447,7016,지선,50.0
            102900001,마포01,마을,5.9
            241006973,8455안성,경기,
            300000001,인천01,인천,10.0
            100100586,N26,,50.0
            123000021,한강버스(동부압구정),,20.0
            """;

    private static final String SCOPED_ROUTE_STOPS = "\uFEFF" + """
            노선ID,노드ID,링크거리누계,정류장순번
            100100447,111000907,73.0,1
            100100447,111000225,294.0,2
            100100447,113000022,713.0,3
            102900001,102900097,208.0,15
            102900001,102900097,168.0,23
            100100586,115000321,0.0,1
            123000021,123000689,0.0,1
            """;

    private static final String SCOPED_STOPS = "\uFEFF" + """
            정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부
            111000907,은평공영차고지,일반차로,35331,126.883807875,37.5918053134,미설치
            111000225,덕은교.은평차고지앞,일반차로,12315,126.883446945,37.589961128,설치
            113000022,DMC첨단산업센터,일반차로,14112,126.884787,37.585598,설치
            102900097,도원삼성래미안아파트101동앞,마을버스,03511,126.956232081,37.5390685906,미설치
            115000321,개화역광역환승센터,일반차로,16435,126.797978,37.57822,설치
            123000689,한강버스.잠실선착장,선착장,00001,127.084778,37.518944,미설치
            """;
}
