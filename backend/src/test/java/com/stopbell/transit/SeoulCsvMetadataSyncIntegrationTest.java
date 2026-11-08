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

import com.stopbell.support.SharedMySqlExtension;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {
        "transit.monitoring.enabled=false",
        "notification.dispatch.enabled=false",
        "notification.fcm.enabled=false"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeoulCsvMetadataSyncIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "seoul_csv_metadata_sync");
    }

    private static final Instant PREVIOUS_COMPLETION = Instant.parse("2026-09-01T00:00:00Z");
    private static final String ROUTE_A = "100100447";
    private static final String ROUTE_B = "100100586";

    @Autowired private com.stopbell.transit.service.TransitMetadataDiffAnalysisService analysis;
    @Autowired
    private BusMetadataSyncService syncService;

    @Autowired
    private BusMetadataSyncStateRepository syncStateRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired private com.stopbell.transit.service.TransitMetadataCollectionHistoryService history;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void clear_test_metadata() {
        jdbc.update("DELETE FROM transit_source_metadata");
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
                .hasMessageContaining("stage=VALIDATION");

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
                .hasMessageContaining("stage=VALIDATION");

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
                .hasMessageContaining("stage=VALIDATION");

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
        jdbc.update("UPDATE bus_route_stop_occurrences SET destination_name='기존 행선지' WHERE route_id=? AND stop_order=1",
                routeId);
        retainedOccurrences = jdbc.queryForList(
                "SELECT * FROM bus_route_stop_occurrences WHERE route_id = ? AND stop_order IN (1, 3) ORDER BY id",
                routeId);

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
                .hasMessageContaining("stage=VALIDATION");

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
                .hasMessageContaining("stage=VALIDATION");

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
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("stage=SYNC");
            assertLastSyncFailed();
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
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("stage=SYNC");
            assertLastSyncFailed();
            assertThat(databaseSnapshot()).isEqualTo(before);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_stop_update");
        }
    }

    @Test
    @DisplayName("서울 서비스 범위 원본을 FK와 함께 저장하고 동일 수집 재처리 시 ID와 원본 행을 유지한다")
    void save_raw_rows_with_ids_and_replay_same_collection() {
        String routes = ROUTES.replace("7016,지선,50.0", " 7016 ,지선,050.00")
                + "outside,경기 노선,경기,9.0\nincheon,인천 노선,인천,1.0\n";
        String stops = STOPS + "outside-stop,범위 밖,일반차로,00999,127.0,37.0,설치\n"
                + "unreferenced,미참조,일반차로,00111,127.0,37.0,설치\n";
        String routeStops = ROUTE_STOPS + "100100586,stop-1,000.50,2\noutside,outside-stop,0,1\noutside,stop-1,0,2\n";
        SeoulCsvMetadataSource source = source(routes, routeStops, stops);
        var collected = source.fetchSnapshotWithRawRows();
        Instant collectedAt = Instant.parse("2026-11-07T00:00:00Z");
        syncService.syncSeoulSnapshot(source, collected, collectedAt);
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM transit_source_metadata ORDER BY id");
        DatabaseSnapshot normalized = databaseSnapshot();

        assertThat(before).hasSize(collected.rawRows().size()).hasSize(16);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        for (Map<String, Object> stored : before) {
            var original = collected.rawRows().stream()
                    .filter(row -> row.dataKind().name().equals(stored.get("data_kind"))
                            && row.sourceKey().equals(stored.get("source_key")))
                    .findFirst().orElseThrow();
            assertThat(json.readTree(stored.get("raw_payload").toString()))
                    .isEqualTo(json.valueToTree(original.fields()));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_key IN "
                + "('outside','outside-stop','7:outside:1')", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE external_route_id IN ('outside','incheon')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_stops WHERE external_stop_id IN ('outside-stop','unreferenced')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE data_kind='STOP' "
                + "AND source_key='stop-1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata m "
                + "LEFT JOIN bus_routes r ON m.route_id=r.id LEFT JOIN bus_stops s ON m.stop_id=s.id "
                + "LEFT JOIN bus_route_stop_occurrences o ON m.occurrence_id=o.id "
                + "WHERE (m.data_kind='ROUTE' AND r.external_route_id=m.source_key) "
                + "OR (m.data_kind='STOP' AND s.external_stop_id=m.source_key) "
                + "OR (m.data_kind='OCCURRENCE' AND o.id IS NOT NULL)", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.\"노선명\"')) "
                + "FROM transit_source_metadata WHERE data_kind='ROUTE' AND source_key=?", String.class, ROUTE_A))
                .isEqualTo(" 7016 ");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.\"거리\"')) "
                + "FROM transit_source_metadata WHERE data_kind='ROUTE' AND source_key=?", String.class, ROUTE_A))
                .isEqualTo("050.00");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.\"정류장번호\"')) "
                + "FROM transit_source_metadata WHERE data_kind='STOP' AND source_key='stop-1'", String.class))
                .isEqualTo("00001");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.\"링크거리누계\"')) "
                + "FROM transit_source_metadata WHERE data_kind='OCCURRENCE' AND source_key='9:100100586:2'", String.class))
                .isEqualTo("000.50");

        syncService.syncSeoulSnapshot(source, collected, collectedAt);
        assertThat(jdbc.queryForList("SELECT * FROM transit_source_metadata ORDER BY id")).isEqualTo(before);
        DatabaseSnapshot after = databaseSnapshot();
        assertThat(after.routes()).isEqualTo(normalized.routes());
        assertThat(after.stops()).isEqualTo(normalized.stops());
        assertThat(after.occurrences()).isEqualTo(normalized.occurrences());

        Instant newCollection = collectedAt.plusSeconds(60);
        syncService.syncSeoulSnapshot(source, source.fetchSnapshotWithRawRows(), newCollection);
        assertThat(jdbc.queryForObject("SELECT collected_at FROM transit_source_metadata "
                + "WHERE data_kind='ROUTE' AND source_key=?", java.time.LocalDateTime.class, ROUTE_A))
                .isEqualTo(java.time.LocalDateTime.ofInstant(newCollection, java.time.ZoneOffset.UTC));
        assertThat(jdbc.queryForList("SELECT id FROM transit_source_metadata ORDER BY id", Long.class))
                .containsExactlyElementsOf(before.stream().map(row -> ((Number) row.get("id")).longValue()).toList());
    }

    @Test
    @DisplayName("원본 일부 저장 후 실패하면 해당 노선 전체 rollback하고 cleanup과 완료 시각을 보존한다")
    void roll_back_raw_and_normalized_data_when_raw_insert_fails() {
        seedMetadata();
        DatabaseSnapshot before = databaseSnapshot();
        List<Map<String, Object>> rawBefore = jdbc.queryForList("SELECT * FROM transit_source_metadata ORDER BY id");
        BusMetadataBootstrapService bootstrap = bootstrap(source(ROUTES.replace("7016", "변경 노선"), ROUTE_STOPS,
                STOPS.replace("첫 정류장", "변경 정류장")));
        jdbc.execute("""
                CREATE TRIGGER reject_raw_occurrence BEFORE INSERT ON transit_source_metadata
                FOR EACH ROW BEGIN
                  IF NEW.data_kind = 'OCCURRENCE' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test raw occurrence failure';
                  END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("stage=SYNC");
            assertLastSyncFailed();
            assertThat(databaseSnapshot()).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM transit_source_metadata ORDER BY id")).isEqualTo(rawBefore);
        } finally {
            jdbc.execute("DROP TRIGGER reject_raw_occurrence");
        }
        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
        assertThat(lastCompleteSyncAt()).isAfter(PREVIOUS_COMPLETION);
        assertThat(jdbc.queryForObject("SELECT route_number FROM bus_routes WHERE external_route_id=?",
                String.class, ROUTE_A)).isEqualTo("변경 노선");
    }

    @Test
    @DisplayName("나중 노선 원본 저장 실패는 앞서 성공한 노선은 유지하고 cleanup을 생략한다")
    void retain_earlier_route_commit_when_later_raw_write_fails() {
        seedMetadata();
        jdbc.execute("""
                CREATE TRIGGER reject_second_route_raw BEFORE INSERT ON transit_source_metadata
                FOR EACH ROW BEGIN
                  IF NEW.data_kind = 'OCCURRENCE' AND NEW.source_key = '9:100100586:1' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'second route raw failure';
                  END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> bootstrap(source(ROUTES.replace("7016", "앞 노선 변경")
                    .replace("N26", "뒤 노선 변경"), ROUTE_STOPS, STOPS)).bootstrap(TransitProvider.SEOUL_BUS))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("stage=SYNC");
            assertLastSyncFailed();
            assertThat(jdbc.queryForObject("SELECT route_number FROM bus_routes WHERE external_route_id=?",
                    String.class, ROUTE_A)).isEqualTo("앞 노선 변경");
            assertThat(jdbc.queryForObject("SELECT route_number FROM bus_routes WHERE external_route_id=?",
                    String.class, ROUTE_B)).isEqualTo("N26");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE external_route_id='stale-route'",
                    Integer.class)).isEqualTo(1);
            assertThat(lastCompleteSyncAt()).isEqualTo(PREVIOUS_COMPLETION);
        } finally {
            jdbc.execute("DROP TRIGGER reject_second_route_raw");
        }
    }

    @Test
    @DisplayName("범위 밖 배치 실패는 배치를 롤백하고 cleanup과 완료를 차단하며 재시도한다")
    void roll_back_outside_batch_and_retry() {
        seedMetadata();
        SeoulCsvMetadataSource source = source(ROUTES.replace("7016", "변경")
                + "outside,경기 노선,경기,1.0\n", ROUTE_STOPS,
                STOPS + "outside-stop,범위 밖,일반차로,00009,127.0,37.0,설치\n");
        var collected = source.fetchSnapshotWithRawRows();
        Instant collectedAt = Instant.parse("2026-11-07T00:00:00Z");
        jdbc.execute("""
                CREATE TRIGGER reject_outside BEFORE INSERT ON transit_source_metadata
                FOR EACH ROW BEGIN
                  IF NEW.source_key = 'outside-stop' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'outside failure';
                  END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> syncService.syncSeoulSnapshot(source, collected, collectedAt))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_key='outside'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT route_number FROM bus_routes WHERE external_route_id=?",
                    String.class, ROUTE_A)).isEqualTo("변경");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE external_route_id='stale-route'",
                    Integer.class)).isEqualTo(1);
            assertThat(lastCompleteSyncAt()).isEqualTo(PREVIOUS_COMPLETION);
        } finally {
            jdbc.execute("DROP TRIGGER reject_outside");
        }
        syncService.syncSeoulSnapshot(source, collected, collectedAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_key='outside'",
                Integer.class)).isEqualTo(1);
        assertThat(lastCompleteSyncAt()).isAfter(PREVIOUS_COMPLETION);
    }

    @Test
    @DisplayName("범위 밖 원본 갱신은 기존 유효 FK와 ID를 유지한다")
    void preserve_existing_association_in_outside_batch() {
        seedMetadata();
        Long id = jdbc.queryForObject("SELECT id FROM transit_source_metadata WHERE data_kind='ROUTE' AND source_key='stale-route'", Long.class);
        Long routeId = jdbc.queryForObject("SELECT route_id FROM transit_source_metadata WHERE id=?", Long.class, id);
        jdbc.execute("""
                CREATE TRIGGER reject_cleanup BEFORE DELETE ON bus_routes
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stop cleanup'
                """);
        try {
            assertThatThrownBy(() -> bootstrap(source(ROUTES + "stale-route,이전 노선,경기,1.0\n",
                    ROUTE_STOPS, STOPS)).bootstrap(TransitProvider.SEOUL_BUS)).isInstanceOf(IllegalStateException.class).hasMessageContaining("stage=SYNC");
            assertLastSyncFailed();
            assertThat(jdbc.queryForObject("SELECT route_id FROM transit_source_metadata WHERE id=?", Long.class, id))
                    .isEqualTo(routeId);
        } finally {
            jdbc.execute("DROP TRIGGER reject_cleanup");
        }
    }

    @Test
    @DisplayName("두 번째 배치 실패 시 첫 배치는 보존되고 재처리로 전체 원본을 저장한다")
    void retry_after_second_batch_fails() {
        StringBuilder stops = new StringBuilder(STOPS);
        for (int i = 0; i < 501; i++) {
            stops.append("extra-").append(i).append(",추가,일반차로,00100,127.0,37.0,설치\n");
        }
        SeoulCsvMetadataSource source = source(ROUTES, ROUTE_STOPS, stops.toString());
        var collected = source.fetchSnapshotWithRawRows();
        Instant collectedAt = Instant.parse("2026-11-07T00:00:00Z");
        jdbc.execute("""
                CREATE TRIGGER reject_last_batch BEFORE INSERT ON transit_source_metadata
                FOR EACH ROW BEGIN
                  IF NEW.source_key = 'extra-500' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'second batch failure';
                  END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> syncService.syncSeoulSnapshot(source, collected, collectedAt))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_key LIKE 'extra-%'",
                    Integer.class)).isEqualTo(500);
            assertThat(syncStateRepository.findById(TransitProvider.SEOUL_BUS)).isEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER reject_last_batch");
        }
        syncService.syncSeoulSnapshot(source, collected, collectedAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata", Integer.class))
                .isEqualTo(collected.rawRows().size());
    }

    @Test
    @DisplayName("cleanup 실패는 이전 완료 시각을 보존하고 실패 이력을 남긴다")
    void records_cleanup_failure() {
        seedMetadata();
        jdbc.execute("CREATE TRIGGER reject_cleanup BEFORE DELETE ON bus_routes FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cleanup failure'");
        try {
            assertThatThrownBy(() -> bootstrap(source(ROUTES, ROUTE_STOPS, STOPS)).bootstrap(TransitProvider.SEOUL_BUS))
                    .hasMessageContaining("stage=SYNC");
            assertLastSyncFailed();
            assertThat(lastCompleteSyncAt()).isEqualTo(PREVIOUS_COMPLETION);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bus_routes WHERE external_route_id='stale-route'", Integer.class)).isEqualTo(1);
        } finally { jdbc.execute("DROP TRIGGER reject_cleanup"); }
    }

    @Test
    @DisplayName("완료 시각 저장 실패는 완료 이력을 만들지 않고 이전 시각을 보존한다")
    void records_completion_timestamp_failure() {
        seedMetadata();
        jdbc.execute("CREATE TRIGGER reject_completion BEFORE UPDATE ON bus_metadata_sync_states FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'completion failure'");
        try {
            assertThatThrownBy(() -> bootstrap(source(ROUTES, ROUTE_STOPS, STOPS)).bootstrap(TransitProvider.SEOUL_BUS))
                    .hasMessageContaining("stage=SYNC");
            assertLastSyncFailed();
            assertThat(lastCompleteSyncAt()).isEqualTo(PREVIOUS_COMPLETION);
        } finally { jdbc.execute("DROP TRIGGER reject_completion"); }
    }

    private void assertLastSyncFailed() {
        var rows = jdbc.queryForList("SELECT status, failure_stage, failure_code, failure_message, finished_at FROM transit_metadata_collection_runs WHERE provider='SEOUL_BUS' ORDER BY id DESC LIMIT 1");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("status", "FAILED").containsEntry("failure_stage", "SYNC")
                .containsEntry("failure_code", "SYNC_FAILED").containsEntry("failure_message", "Database synchronization failed");
        assertThat(rows.getFirst().get("finished_at")).isNotNull();
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='SEOUL_BUS'", Long.class);
        assertThat(analysis.find(id)).isNotNull();
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
        var clock = java.time.Clock.systemUTC();
        var archive = new com.stopbell.transit.service.TransitMetadataArchiveService(
                jdbc, clock, temporaryDirectory.toAbsolutePath().normalize().toString(), transactionManager);
        return new BusMetadataBootstrapService(List.of(source), syncService, history, archive, analysis, clock);
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
