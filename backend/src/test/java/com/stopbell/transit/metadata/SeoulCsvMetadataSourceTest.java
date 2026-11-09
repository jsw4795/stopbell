package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.service.CompleteBusMetadataSnapshot;
import com.stopbell.transit.service.TransitMetadataArchiveService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

class SeoulCsvMetadataSourceTest {

    @Test
    void archive_exact_source_bytes_and_keep_snapshot() {
        String routes = SCOPED_ROUTES.replace("\n", "\r\n");
        String stops = SCOPED_STOPS.replace("\n", "\r\n");
        String routeStops = SCOPED_ROUTE_STOPS.replace("\n", "\r\n");
        SeoulCsvMetadataSource source = source(routes, routeStops, stops);
        TransitMetadataArchiveService archive = mock(TransitMetadataArchiveService.class);

        var snapshot = source.fetchSnapshotWithRawRows(42L, archive);

        assertThat(snapshot).isEqualTo(source.fetchSnapshotWithRawRows());
        verify(archive).store(42L, TransitSourceSystem.SEOUL_TDATA, "seoul_route_master", routes.getBytes(StandardCharsets.UTF_8));
        verify(archive).store(42L, TransitSourceSystem.SEOUL_TDATA, "seoul_stop_master", stops.getBytes(StandardCharsets.UTF_8));
        verify(archive).store(42L, TransitSourceSystem.SEOUL_TDATA, "seoul_route_stop_master", routeStops.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void archive_failure_prevents_returning_snapshot() {
        TransitMetadataArchiveService archive = mock(TransitMetadataArchiveService.class);
        doThrow(new IllegalStateException("storage failed")).when(archive).store(anyLong(), any(), anyString(), any());

        assertThatThrownBy(() -> source(SCOPED_ROUTES, SCOPED_ROUTE_STOPS, SCOPED_STOPS)
                .fetchSnapshotWithRawRows(42L, archive))
                .isInstanceOf(IllegalStateException.class).hasMessage("storage failed");
    }

    @Test
    void archive_reads_each_resource_once_and_rejects_invalid_csv_before_store() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        Resource routes = countingResource(SCOPED_ROUTES, reads);
        Resource routeStops = countingResource(SCOPED_ROUTE_STOPS, reads);
        Resource stops = countingResource(SCOPED_STOPS, reads);
        TransitMetadataArchiveService archive = mock(TransitMetadataArchiveService.class);
        new SeoulCsvMetadataSource(routes, routeStops, stops).fetchSnapshotWithRawRows(42, archive);
        assertThat(reads.get()).isEqualTo(3);

        TransitMetadataArchiveService unused = mock(TransitMetadataArchiveService.class);
        assertThatThrownBy(() -> source(SCOPED_ROUTES.replace("노선명", "bad"), SCOPED_ROUTE_STOPS, SCOPED_STOPS)
                .fetchSnapshotWithRawRows(42, unused)).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(unused);
    }

    private static Resource countingResource(String value, java.util.concurrent.atomic.AtomicInteger reads) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8)) {
            @Override public java.io.InputStream getInputStream() throws java.io.IOException {
                reads.incrementAndGet();
                return super.getInputStream();
            }
        };
    }

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
    @DisplayName("원본 세 CSV의 모든 필드와 서비스 범위 밖 행을 정규화 결과와 별도로 보존한다")
    void preserve_all_raw_fields_and_out_of_scope_rows() {
        String routes = SCOPED_ROUTES.replace("100100447,7016,지선,50.0", "100100447, 7016 ,지선,50.00");
        String routeStops = SCOPED_ROUTE_STOPS + "241006973,111000907,001.50,1\n";
        String stops = SCOPED_STOPS + "999,미사용 정류장,일반차로,00000,127.00,37.00,미설치\n";
        SeoulCsvMetadataSource source = source(routes, routeStops, stops);

        SeoulCsvMetadataSource.SeoulCsvSnapshot snapshot = source.fetchSnapshotWithRawRows();
        assertThat(snapshot.normalized()).isEqualTo(source.fetchCompleteSourceSnapshot());
        assertThat(source.fetchCompleteSnapshot().routes()).extracting("externalRouteId")
                .containsExactlyInAnyOrder("100100447", "102900001", "100100586")
                .doesNotContain("241006973", "300000001");
        assertThat(raw(snapshot, TransitSourceDataKind.ROUTE, "100100447").fields()).containsExactlyInAnyOrderEntriesOf(
                Map.of("노선ID", "100100447", "노선명", " 7016 ", "노선유형", "지선", "거리", "50.00"));
        assertThat(raw(snapshot, TransitSourceDataKind.STOP, "111000907").fields()).containsExactlyInAnyOrderEntriesOf(
                Map.of("정류장ID", "111000907", "정류장명", "은평공영차고지", "정류장유형", "일반차로",
                        "정류장번호", "35331", "좌표X", "126.883807875", "좌표Y", "37.5918053134", "BIT설치여부", "미설치"));
        assertThat(raw(snapshot, TransitSourceDataKind.OCCURRENCE, "9:241006973:1").fields())
                .containsExactlyInAnyOrderEntriesOf(Map.of("노선ID", "241006973", "노드ID", "111000907",
                        "링크거리누계", "001.50", "정류장순번", "1"));
        assertThat(raw(snapshot, TransitSourceDataKind.OCCURRENCE, "9:100100447:1").fields().get("노드ID"))
                .isEqualTo("111000907");
        assertThat(raw(snapshot, TransitSourceDataKind.ROUTE, "300000001").fields().get("노선유형")).isEqualTo("인천");
        assertThat(raw(snapshot, TransitSourceDataKind.STOP, "999").fields().get("정류장번호")).isEqualTo("00000");
        assertThat(snapshot.rawRows()).hasSize(6 + 7 + 8);
        assertThatThrownBy(() -> snapshot.rawRows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> raw(snapshot, TransitSourceDataKind.ROUTE, "100100447").fields().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("서울 좌표는 유효성 검증 후 MySQL 정밀도로 반올림하고 원본을 보존한다")
    void normalize_coordinates_at_decimal_boundaries_and_preserve_raw() {
        String routes = "노선ID,노선명,노선유형,거리\nr,1,간선,1\n";
        String occurrences = "노선ID,노드ID,링크거리누계,정류장순번\nr,a,0,1\nr,b,0,2\nr,a,0,3\n";
        String stops = "정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\n"
                + "a,A,일반차로,1,127.12345674,37.12345675,미설치\n"
                + "b,B,일반차로,2,-127.12345675,-37.12345675,미설치\n";
        var source = source(routes, occurrences, stops);
        var collected = source.fetchSnapshotWithRawRows();
        var normalized = source.toCompleteSnapshot(collected.normalized()).routes().getFirst().occurrences();
        assertThat(normalized).hasSize(3);
        assertThat(normalized.get(0).latitude()).isEqualByComparingTo("37.1234568");
        assertThat(normalized.get(0).longitude()).isEqualByComparingTo("127.1234567");
        assertThat(normalized.get(1).latitude()).isEqualByComparingTo("-37.1234568");
        assertThat(normalized.get(1).longitude()).isEqualByComparingTo("-127.1234568");
        assertThat(normalized.get(2).latitude()).isEqualTo(normalized.get(0).latitude());
        assertThat(normalized.get(2).stopOrder()).isEqualTo(3);
        assertThat(raw(collected, TransitSourceDataKind.STOP, "a").fields())
                .containsEntry("좌표X", "127.12345674").containsEntry("좌표Y", "37.12345675");
        assertThat(raw(collected, TransitSourceDataKind.STOP, "b").fields())
                .containsEntry("좌표X", "-127.12345675").containsEntry("좌표Y", "-37.12345675");
        assertThatThrownBy(() -> source(routes, occurrences, stops.replace("37.12345675", "90.00000001"))
                .fetchCompleteSourceSnapshot()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latitude");
    }

    private SeoulCsvMetadataSource.RawSourceRow raw(SeoulCsvMetadataSource.SeoulCsvSnapshot snapshot,
            TransitSourceDataKind kind, String key) {
        return snapshot.rawRows().stream().filter(row -> row.dataKind() == kind && row.sourceKey().equals(key))
                .findFirst().orElseThrow();
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
    @DisplayName("모든 포함 노선유형은 occurrence가 0개이면 complete snapshot 승인을 거부한다")
    void reject_included_route_without_occurrences() {
        for (String routeType : List.of("공항", "마을", "간선", "지선", "순환", "광역", "관광")) {
            String routes = ROUTES + "empty-route,빈 노선," + routeType + ",1.0\n";

            assertThatThrownBy(() -> source(routes, ROUTE_STOPS, STOPS).fetchCompleteSnapshot())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no occurrences")
                    .hasMessageContaining("empty-route");
        }
    }

    @Test
    @DisplayName("빈 occurrence 노선이 여러 개이면 전체 건수와 최대 10개 식별자만 오류에 남긴다")
    void bound_empty_route_error_details() {
        StringBuilder routes = new StringBuilder(ROUTES);
        for (int index = 0; index < 12; index++) {
            routes.append("empty-route-").append(index).append(",빈 노선,지선,1.0\n");
        }

        assertThatThrownBy(() -> source(routes.toString(), ROUTE_STOPS, STOPS).fetchCompleteSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("routeCount=12")
                .hasMessageContaining("empty-route-0")
                .hasMessageContaining("empty-route-9")
                .hasMessageNotContaining("empty-route-10")
                .hasMessageNotContaining("empty-route-11");
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
