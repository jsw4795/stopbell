package com.stopbell.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusMetadataSyncState;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import com.stopbell.transit.service.BusMetadataBootstrapService;
import com.stopbell.transit.service.BusMetadataSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = {
        "transit.monitoring.enabled=false",
        "notification.dispatch.enabled=false",
        "notification.fcm.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class SeoulCsvMetadataSyncIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11")
            .withCommand("--log-bin-trust-function-creators=1");

    private static final Instant PREVIOUS_COMPLETION = Instant.parse("2026-09-01T00:00:00Z");
    private static final String ROUTE_A = "100100447";
    private static final String ROUTE_B = "100100586";

    @Autowired
    private BusMetadataSyncService syncService;

    @Autowired
    private BusMetadataSyncStateRepository syncStateRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void clear_test_metadata() {
        jdbc.update("DELETE FROM bus_route_stop_occurrences");
        jdbc.update("DELETE FROM bus_routes");
        jdbc.update("DELETE FROM bus_stops");
        jdbc.update("DELETE FROM bus_metadata_sync_states");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("기존 서울 노선의 occurrence가 0개이면 노선·정류장·occurrence와 완료 시각을 모두 보존한다")
    void preserve_existing_metadata_when_route_has_no_occurrences(CapturedOutput output) {
        seedMetadata();
        DatabaseSnapshot before = databaseSnapshot();
        int logStart = output.getAll().length();

        assertThatThrownBy(() -> bootstrap(source(ROUTES, withoutRoute(ROUTE_STOPS, ROUTE_A), STOPS))
                .bootstrap(TransitProvider.SEOUL_BUS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no occurrences")
                .hasMessageContaining(ROUTE_A);

        assertThat(databaseSnapshot()).isEqualTo(before);
        assertThat(output.getAll().substring(logStart))
                .contains("Seoul CSV validation failed", "reason=NO_OCCURRENCES", ROUTE_A,
                        "completeSnapshotApproved=false", "databaseSyncStarted=false")
                .doesNotContain("Metadata bootstrap completed", "Metadata database sync started",
                        "Metadata database sync completed");
    }

    @Test
    @DisplayName("신규 노선의 occurrence가 0개이면 데이터와 complete sync 상태를 생성하지 않는다")
    void reject_new_empty_route_without_creating_sync_state() {
        DatabaseSnapshot before = databaseSnapshot();

        assertThatThrownBy(() -> bootstrap(source(ROUTES, withoutRoute(ROUTE_STOPS, ROUTE_B), STOPS))
                .bootstrap(TransitProvider.SEOUL_BUS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ROUTE_B);

        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("정상 변경 노선 뒤에 빈 노선이 있어도 첫 노선 갱신과 삭제 대상 cleanup을 시작하지 않는다")
    void reject_mixed_snapshot_before_any_route_update_or_cleanup() {
        seedMetadata();
        DatabaseSnapshot before = databaseSnapshot();
        String changedRoutes = ROUTES.replace("7016", "7016변경");
        String changedStops = STOPS.replace("첫 정류장", "변경 정류장");

        assertThatThrownBy(() -> bootstrap(source(changedRoutes, withoutRoute(ROUTE_STOPS, ROUTE_B), changedStops))
                .bootstrap(TransitProvider.SEOUL_BUS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ROUTE_B);

        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("경기·인천 제외 노선에 occurrence가 없어도 서울 동기화와 완료 시각 갱신은 성공한다")
    void allow_excluded_empty_routes() {
        String routes = ROUTES + "excluded-gyeonggi,경기 노선,경기,\nexcluded-incheon,인천 노선,인천,\n";

        bootstrap(source(routes, ROUTE_STOPS, STOPS)).bootstrap(TransitProvider.SEOUL_BUS);

        assertThat(databaseSnapshot().routes()).extracting(row -> row.get("external_route_id"))
                .containsExactly(ROUTE_A, ROUTE_B);
        assertThat(databaseSnapshot().occurrences()).hasSize(4);
        assertThat(lastCompleteSyncAt()).isAfter(PREVIOUS_COMPLETION);
    }

    @Test
    @DisplayName("정상 CSV는 기존 ID와 반복 방문을 보존하며 변경·삭제·신규 occurrence와 cleanup을 반영한다")
    void update_valid_snapshot_and_preserve_repeated_occurrences() {
        seedMetadata();
        Object routeId = jdbc.queryForObject(
                "SELECT id FROM bus_routes WHERE external_route_id = ?", Long.class, ROUTE_A);
        List<Map<String, Object>> retainedOccurrences = jdbc.queryForList(
                "SELECT * FROM bus_route_stop_occurrences WHERE route_id = ? AND stop_order IN (1, 3) ORDER BY id",
                routeId);
        Object stopId = jdbc.queryForObject(
                "SELECT id FROM bus_stops WHERE external_stop_id = 'stop-1'", Long.class);

        bootstrap(source(ROUTES.replace("7016", "7016변경"),
                ROUTE_STOPS.replace("100100447,stop-2,1.0,2", "100100447,stop-2,1.0,4"),
                STOPS.replace("첫 정류장", "변경 정류장"))).bootstrap(TransitProvider.SEOUL_BUS);

        assertThat(jdbc.queryForList(
                "SELECT * FROM bus_route_stop_occurrences WHERE route_id = ? AND stop_order IN (1, 3) ORDER BY id",
                routeId))
                .isEqualTo(retainedOccurrences);
        assertThat(jdbc.queryForObject("SELECT id FROM bus_routes WHERE external_route_id = ?", Long.class, ROUTE_A))
                .isEqualTo(routeId);
        assertThat(jdbc.queryForObject("SELECT route_number FROM bus_routes WHERE id = ?", String.class, routeId))
                .isEqualTo("7016변경");
        assertThat(jdbc.queryForObject("SELECT stop_name FROM bus_stops WHERE id = ?", String.class, stopId))
                .isEqualTo("변경 정류장");
        assertThat(jdbc.queryForList("SELECT stop_order FROM bus_route_stop_occurrences WHERE route_id = ? "
                + "ORDER BY stop_order", Integer.class, routeId)).containsExactly(1, 3, 4);
        assertThat(databaseSnapshot().routes()).extracting(row -> row.get("external_route_id"))
                .containsExactly(ROUTE_A, ROUTE_B);
        assertThat(databaseSnapshot().stops()).extracting(row -> row.get("external_stop_id"))
                .doesNotContain("stale-stop");
        assertThat(lastCompleteSyncAt()).isAfter(PREVIOUS_COMPLETION);
    }

    @Test
    @DisplayName("불완전 CSV 실패 뒤 동일 bootstrap과 source에 정상 임시 CSV를 넣으면 복구한다")
    void recover_with_valid_csv_after_validation_failure() throws IOException {
        seedMetadata();
        DatabaseSnapshot before = databaseSnapshot();
        Path routeStopsPath = temporaryDirectory.resolve("route-stops.csv");
        Files.writeString(routeStopsPath, withoutRoute(ROUTE_STOPS, ROUTE_A), StandardCharsets.UTF_8);
        SeoulCsvMetadataSource source = new SeoulCsvMetadataSource(
                resource(ROUTES), new FileSystemResource(routeStopsPath), resource(STOPS));
        BusMetadataBootstrapService bootstrap = bootstrap(source);

        assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(databaseSnapshot()).isEqualTo(before);

        Files.writeString(routeStopsPath, ROUTE_STOPS, StandardCharsets.UTF_8);
        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);

        assertThat(lastCompleteSyncAt()).isAfter(PREVIOUS_COMPLETION);
        assertThat(databaseSnapshot().occurrences()).hasSize(4);
        assertThat(databaseSnapshot().routes()).hasSize(2);
    }

    @Test
    @DisplayName("CSV 정류장 연결이 누락되면 기존 데이터와 마지막 정상 완료 시각을 보존한다")
    void preserve_metadata_when_stop_join_is_missing() {
        seedMetadata();
        DatabaseSnapshot before = databaseSnapshot();

        assertThatThrownBy(() -> bootstrap(source(ROUTES, ROUTE_STOPS.replace("stop-2", "missing-stop"), STOPS))
                .bootstrap(TransitProvider.SEOUL_BUS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown stop");

        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("CSV 행 파싱 오류가 있으면 기존 데이터와 마지막 정상 완료 시각을 보존한다")
    void preserve_metadata_when_csv_is_malformed() {
        seedMetadata();
        DatabaseSnapshot before = databaseSnapshot();

        assertThatThrownBy(() -> bootstrap(source(ROUTES, ROUTE_STOPS + "malformed,row\n", STOPS))
                .bootstrap(TransitProvider.SEOUL_BUS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed Seoul CSV row");

        assertThat(databaseSnapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("기존 occurrence 삭제 후 DB INSERT가 실패하면 해당 노선 전체 rollback과 완료 시각 보존 뒤 재시도가 가능하다")
    void roll_back_route_replacement_when_database_insert_fails() {
        seedMetadata();
        DatabaseSnapshot before = databaseSnapshot();
        BusMetadataBootstrapService bootstrap = bootstrap(source(ROUTES.replace("7016", "7016변경"),
                ROUTE_STOPS.replace(
                        "100100447,stop-2,1.0,2", "100100447,stop-2,1.0,4"), STOPS));
        jdbc.execute("""
                CREATE TRIGGER reject_test_occurrence BEFORE INSERT ON bus_route_stop_occurrences
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test occurrence insert failure'
                """);
        try {
            assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS))
                    .isInstanceOf(DataAccessException.class);
            assertThat(databaseSnapshot()).isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_occurrence");
        }

        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
        assertThat(lastCompleteSyncAt()).isAfter(PREVIOUS_COMPLETION);
    }

    @Test
    @DisplayName("DB 정류장 UPDATE 실패 시 노선 변경도 rollback하고 cleanup과 완료 시각 갱신을 차단한다")
    void roll_back_route_when_database_update_fails() {
        seedMetadata();
        DatabaseSnapshot before = databaseSnapshot();
        jdbc.execute("""
                CREATE TRIGGER reject_test_stop_update BEFORE UPDATE ON bus_stops
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test stop update failure'
                """);
        try {
            assertThatThrownBy(() -> bootstrap(source(ROUTES.replace("7016", "7016변경"), ROUTE_STOPS,
                    STOPS.replace("첫 정류장", "변경 정류장"))).bootstrap(TransitProvider.SEOUL_BUS))
                    .isInstanceOf(DataAccessException.class);
            assertThat(databaseSnapshot()).isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_stop_update");
        }
    }

    private void seedMetadata() {
        bootstrap(source(ROUTES + "stale-route,이전 노선,지선,1.0\n",
                ROUTE_STOPS + "stale-route,stale-stop,0.0,1\n",
                STOPS + "stale-stop,이전 정류장,일반차로,00004,127.0,37.0,미설치\n"))
                .bootstrap(TransitProvider.SEOUL_BUS);
        syncStateRepository.saveAndFlush(new BusMetadataSyncState(TransitProvider.SEOUL_BUS, PREVIOUS_COMPLETION));
    }

    private Instant lastCompleteSyncAt() {
        return syncStateRepository.findById(TransitProvider.SEOUL_BUS).orElseThrow().getLastCompleteSyncAt();
    }

    private BusMetadataBootstrapService bootstrap(SeoulCsvMetadataSource source) {
        return new BusMetadataBootstrapService(List.of(source), syncService);
    }

    private SeoulCsvMetadataSource source(String routes, String routeStops, String stops) {
        return new SeoulCsvMetadataSource(resource(routes), resource(routeStops), resource(stops));
    }

    private ByteArrayResource resource(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private String withoutRoute(String routeStops, String routeId) {
        return routeStops.lines().filter(line -> !line.startsWith(routeId + ","))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    }

    private DatabaseSnapshot databaseSnapshot() {
        return new DatabaseSnapshot(
                jdbc.queryForList("SELECT * FROM bus_routes ORDER BY id"),
                jdbc.queryForList("SELECT * FROM bus_stops ORDER BY id"),
                jdbc.queryForList("SELECT * FROM bus_route_stop_occurrences ORDER BY id"),
                jdbc.queryForList("SELECT * FROM bus_metadata_sync_states ORDER BY provider"));
    }

    private record DatabaseSnapshot(List<Map<String, Object>> routes, List<Map<String, Object>> stops,
            List<Map<String, Object>> occurrences, List<Map<String, Object>> syncStates) {
    }

    private static final String ROUTES = """
            노선ID,노선명,노선유형,거리
            100100447,7016,지선,50.0
            100100586,N26,간선,50.0
            """;

    private static final String ROUTE_STOPS = """
            노선ID,노드ID,링크거리누계,정류장순번
            100100447,stop-1,0.0,1
            100100447,stop-2,1.0,2
            100100447,stop-1,2.0,3
            100100586,stop-3,0.0,1
            """;

    private static final String STOPS = """
            정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부
            stop-1,첫 정류장,일반차로,00001,127.0,37.0,미설치
            stop-2,두번째 정류장,일반차로,00002,127.1,37.1,미설치
            stop-3,세번째 정류장,일반차로,00003,127.2,37.2,미설치
            """;
}
