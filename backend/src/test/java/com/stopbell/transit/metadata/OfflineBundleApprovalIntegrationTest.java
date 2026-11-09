package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"transit.monitoring.enabled=false", "notification.dispatch.enabled=false", "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OfflineBundleApprovalIntegrationTest {
    private static final Path ROOT;
    private static final Path ARCHIVE;
    static {
        try {
            ROOT = Files.createTempDirectory("offline-bundle-approval").toRealPath();
            ARCHIVE = Files.createDirectory(ROOT.resolve("archive"));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "offline_bundle_approval");
        registry.add("transit.metadata.archive.directory", ARCHIVE::toString);
    }
    @AfterAll static void cleanup() throws Exception {
        try (var files = Files.walk(ROOT)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
    @Autowired BusMetadataBootstrapService bootstrap;
    @Autowired BusMetadataSyncService sync;
    @Autowired TransitMetadataDiffAnalysisService analysis;
    @Autowired TransitMetadataChangeApprovalService approvals;
    @Autowired JdbcTemplate jdbc;
    private Path tago;
    private Path gbis;

    @BeforeEach void setup() throws Exception {
        for (String table : List.of("transit_metadata_change_approvals", "transit_metadata_unsafe_change_variants",
                "transit_metadata_unsafe_change_details", "transit_metadata_diff_analyses", "transit_metadata_archives",
                "transit_metadata_collection_runs", "transit_source_metadata", "bus_route_stop_occurrences",
                "bus_routes", "bus_stops", "bus_metadata_sync_states")) jdbc.update("delete from " + table);
        String stops = OfflineBundleFixtures.stops("A", "A");
        Path original = OfflineBundleFixtures.tago(ROOT, List.of("GGB200", "GGB201"), Map.of("GGB200", stops, "GGB201", stops), "old");
        tago = OfflineBundleFixtures.tago(ROOT, List.of("GGB200"), Map.of("GGB200", stops), "new");
        gbis = OfflineBundleFixtures.gbis(ROOT, "20261109", "A", "A");
        sync.syncTagoSnapshot(TagoLocalBundle.replay(original, OfflineBundleFixtures.KEY), Instant.parse("2026-01-01T00:00:00Z"));
    }
    private long reviewAndApprove() {
        assertThatThrownBy(() -> bootstrap.reviewOfflineBundles(tago, gbis, OfflineBundleFixtures.KEY)).hasMessageContaining("UNSAFE_CHANGE");
        long run = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        assertThat(jdbc.queryForObject("select count(*) from bus_routes", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from transit_metadata_archives where collection_run_id=?", Integer.class, run)).isEqualTo(35);
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            var tagoManifest = mapper.readValue(Files.readAllBytes(tago.resolve("manifest.json")), TagoLocalBundle.Manifest.class);
            var gbisManifest = mapper.readValue(Files.readAllBytes(gbis.resolve("manifest.json")), GbisLocalBundle.Manifest.class);
            var expected = new java.util.ArrayList<>(tagoManifest.responses().stream().map(TagoLocalBundle.Entry::sha256).toList());
            gbisManifest.files().stream().filter(e -> e.source() != GbisLocalBundle.Source.BASE_INFO).map(GbisLocalBundle.Entry::sha256).forEach(expected::add);
            assertThat(jdbc.queryForList("select sha256 from transit_metadata_archives where collection_run_id=?", String.class, run)).containsExactlyInAnyOrderElementsOf(expected);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, run);
        return approvals.approve(run, TransitProvider.TAGO, analysis.find(run).fingerprint(), hash, Instant.now().plusSeconds(3600), "reviewed").id();
    }
    @ParameterizedTest
    @CsvSource({
            "2026-01-02T00:00:00.123456789Z, 2026-01-02T23:59:59.999999999Z, 2026-01-02T00:00:00.123457Z, 2026-01-03T00:00:00Z",
            "2026-01-02T00:00:00.123456499Z, 2026-01-02T00:00:00.123456000Z, 2026-01-02T00:00:00.123456Z, 2026-01-02T00:00:00.123456Z"
    })
    @DisplayName("완료 Bundle은 위험 변경 상세 승인 후 같은 원본과 수집 시각으로 오프라인 적용된다")
    void applies_reviewed_bundles_without_collection(String tagoStartedAt, String gbisStartedAt, String tagoStoredAt, String gbisStoredAt) throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var tagoFile = tago.resolve("manifest.json");
        var t = mapper.readValue(Files.readAllBytes(tagoFile), TagoLocalBundle.Manifest.class);
        Files.write(tagoFile, mapper.writeValueAsBytes(new TagoLocalBundle.Manifest(t.id(), tagoStartedAt, t.completedAt(), t.complete(), t.cities(), t.responses())));
        var gbisFile = gbis.resolve("manifest.json");
        var g = mapper.readValue(Files.readAllBytes(gbisFile), GbisLocalBundle.Manifest.class);
        Files.write(gbisFile, mapper.writeValueAsBytes(new GbisLocalBundle.Manifest(g.id(), gbisStartedAt, g.completedAt(), g.complete(), g.sourceSystem(), g.routeVersion(), g.routeStationVersion(), g.files())));
        long id = reviewAndApprove();
        bootstrap.applyOfflineBundlesWithApproval(tago, gbis, OfflineBundleFixtures.KEY, id);
        assertThat(approvals.find(id).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPLIED);
        assertThat(jdbc.queryForList("select external_route_id from bus_routes", String.class)).containsExactly("GGB200");
        assertThat(jdbc.queryForObject("select count(*) from transit_source_metadata where source_system='GBIS'", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select json_unquote(json_extract(raw_payload,'$.extra')) from transit_source_metadata where source_system='TAGO' and data_kind='ROUTE' and source_key='GGB200'", String.class)).isEqualTo("new");
        assertThat(jdbc.queryForObject("select json_unquote(json_extract(raw_payload,'$.extra')) from transit_source_metadata where source_system='TAGO' and data_kind='ROUTE' and source_key='GGB201' and route_id is null", String.class)).isEqualTo("old");
        var tagoManifest = new tools.jackson.databind.ObjectMapper().readValue(Files.readAllBytes(tago.resolve("manifest.json")), TagoLocalBundle.Manifest.class);
        var gbisManifest = new tools.jackson.databind.ObjectMapper().readValue(Files.readAllBytes(gbis.resolve("manifest.json")), GbisLocalBundle.Manifest.class);
        assertThat(tagoManifest.startedAt()).isEqualTo(tagoStartedAt);
        assertThat(gbisManifest.startedAt()).isEqualTo(gbisStartedAt);
        for (var entry : Map.of("TAGO", tagoStoredAt, "GBIS", gbisStoredAt).entrySet()) {
            var time = jdbc.queryForObject("select max(collected_at) from transit_source_metadata where source_system=?", java.sql.Timestamp.class, entry.getKey()).toInstant();
            assertThat(time).isEqualTo(Instant.parse(entry.getValue()));
        }
    }
    @Test @DisplayName("승인 후 DB 기준이 달라지면 Bundle 적용과 승인 선점을 차단한다")
    void rejects_changed_database_before_claim() {
        long id = reviewAndApprove();
        jdbc.update("update bus_routes set route_number='changed' where external_route_id='GGB200'");
        assertThatThrownBy(() -> bootstrap.applyOfflineBundlesWithApproval(tago, gbis, OfflineBundleFixtures.KEY, id)).hasMessageContaining("APPROVAL_MISMATCH");
        assertThat(approvals.find(id).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPROVED);
        assertThat(jdbc.queryForObject("select count(*) from bus_routes", Integer.class)).isEqualTo(2);
    }
    @Test @DisplayName("승인된 Bundle과 다른 정상 Bundle은 지문 불일치로 차단한다")
    void rejects_different_valid_bundle() throws Exception {
        long id = reviewAndApprove();
        Path other = OfflineBundleFixtures.tago(ROOT, List.of("GGB200"), Map.of("GGB200", OfflineBundleFixtures.stops("A", "A")), "different");
        assertThatThrownBy(() -> bootstrap.applyOfflineBundlesWithApproval(other, gbis, OfflineBundleFixtures.KEY, id)).hasMessageContaining("APPROVAL_MISMATCH");
        assertThat(approvals.find(id).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPROVED);
        assertThat(jdbc.queryForObject("select count(*) from bus_routes", Integer.class)).isEqualTo(2);
    }
    @Test @DisplayName("승인 뒤 Bundle 수집 시각이 바뀌면 적용과 승인 선점을 차단한다")
    void rejects_changed_collection_time() throws Exception {
        long id = reviewAndApprove();
        var mapper = new tools.jackson.databind.ObjectMapper();
        var file = tago.resolve("manifest.json");
        var manifest = mapper.readValue(Files.readAllBytes(file), TagoLocalBundle.Manifest.class);
        var changed = new TagoLocalBundle.Manifest(manifest.id(), Instant.parse(manifest.startedAt()).minusSeconds(60).toString(),
                manifest.completedAt(), manifest.complete(), manifest.cities(), manifest.responses());
        Files.write(file, mapper.writeValueAsBytes(changed));
        assertThatThrownBy(() -> bootstrap.applyOfflineBundlesWithApproval(tago, gbis, OfflineBundleFixtures.KEY, id)).hasMessageContaining("APPROVAL_MISMATCH");
        assertThat(approvals.find(id).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPROVED);
    }
    @Test @DisplayName("승인 후 Bundle 원본이 손상되면 적용과 승인 선점을 차단한다")
    void rejects_corrupt_bundle_before_claim() throws Exception {
        long id = reviewAndApprove();
        Files.writeString(gbis.resolve("route.dat"), "corrupt");
        assertThatThrownBy(() -> bootstrap.applyOfflineBundlesWithApproval(tago, gbis, OfflineBundleFixtures.KEY, id)).isInstanceOf(IllegalStateException.class);
        assertThat(approvals.find(id).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPROVED);
        assertThat(jdbc.queryForObject("select count(*) from bus_routes", Integer.class)).isEqualTo(2);
    }
    @Test @DisplayName("위험 변경이 없는 Bundle 검토도 실제 동기화를 실행하지 않는다")
    void review_never_applies_safe_change() throws Exception {
        var same = OfflineBundleFixtures.tago(ROOT, List.of("GGB200", "GGB201"), Map.of("GGB200", OfflineBundleFixtures.stops("A", "A"), "GGB201", OfflineBundleFixtures.stops("A", "A")), "old");
        var before = jdbc.queryForList("select * from bus_routes order by id");
        assertThatThrownBy(() -> bootstrap.reviewOfflineBundles(same, gbis, OfflineBundleFixtures.KEY)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForList("select * from bus_routes order by id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from transit_source_metadata where source_system='GBIS'", Integer.class)).isZero();
    }
}
