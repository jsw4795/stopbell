package com.stopbell.transit.service;

import static com.stopbell.transit.domain.TransitProvider.*;
import static com.stopbell.transit.domain.TransitSourceDataKind.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.metadata.TagoMetadataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"transit.monitoring.enabled=false", "notification.dispatch.enabled=false", "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransitMetadataSnapshotPreviewIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { SharedMySqlExtension.database(registry, "snapshot_preview"); }
    @Autowired TransitMetadataSnapshotPreviewService preview;
    @Autowired BusMetadataSyncService sync;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransitMetadataCollectionHistoryService history;
    @Autowired TransitMetadataDiffAnalysisService analysis;
    @Autowired TransitMetadataUnsafeChangeReviewService review;
    @Autowired TransitMetadataChangeApprovalService approvals;
    private static final List<String> TABLES = List.of("bus_routes", "bus_stops", "bus_route_stop_occurrences", "transit_source_metadata",
            "bus_metadata_sync_states", "transit_metadata_collection_runs", "transit_metadata_archives", "transit_metadata_diff_analyses",
            "transit_metadata_unsafe_change_details", "transit_metadata_unsafe_change_variants", "transit_metadata_change_approvals");
    @BeforeEach void clear() {
        for (String table : TABLES.reversed()) jdbc.update("delete from " + table);
    }
    private Map<String, List<Map<String, Object>>> rows() {
        var result = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : TABLES) result.put(table, jdbc.queryForList("select * from " + table + " order by 1"));
        return result;
    }
    private TagoMetadataSource.TagoSnapshot tago(String number, String stop, String destination) {
        var route = new BusRouteMetadataSnapshot(TAGO, "r", number, "31010", List.of(
                new BusStopOccurrenceMetadataSnapshot(stop, stop, null, null, 1, destination),
                new BusStopOccurrenceMetadataSnapshot(stop, stop, null, null, 2, null)));
        return new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, List.of(route)), List.of(
                raw(ROUTE, "r", null, "{\"routeid\":\"r\"}"),
                raw(OCCURRENCE, "1:r:1", 1, "{\"nodeid\":\"" + stop + "\",\"nodeord\":1}"),
                raw(OCCURRENCE, "1:r:2", 2, "{\"nodeid\":\"" + stop + "\",\"nodeord\":2}")));
    }
    private TagoMetadataSource.RawSourceRow raw(com.stopbell.transit.domain.TransitSourceDataKind kind, String key, Integer order, String json) {
        return new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, kind, key, "r", order, TransitRawMetadataDiff.parse(json));
    }
    private ByteArrayResource resource(String text) { return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)); }
    private SeoulCsvMetadataSource seoul(String routes, String occurrences) {
        return new SeoulCsvMetadataSource(resource("노선ID,노선명,노선유형,거리\n" + routes),
                resource("노선ID,노드ID,링크거리누계,정류장순번\n" + occurrences),
                resource("정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\na,A,일반차로,1,127,37,미설치\nb,B,일반차로,2,127,37,미설치\n"));
    }
    @Test void seoul_scope_and_changes_never_write() {
        var original = seoul("r,1,지선,1\nx,2,경기,1\n", "r,a,0,1\nx,b,0,1\n");
        var snapshot = original.fetchSnapshotWithRawRows();
        var before = rows();
        var fresh = preview.preview(original, snapshot);
        assertThat(fresh.diff().addedRoutes()).containsExactly("r");
        assertThat(fresh.snapshot()).isEqualTo(new TransitMetadataSnapshotPreviewService.Size(1, 1, 1));
        assertThat(fresh.raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.SEOUL_TDATA, ROUTE)).added()).isEqualTo(2);
        assertThat(fresh.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.ALLOW);
        assertThat(rows()).isEqualTo(before);
        sync.syncSeoulSnapshot(original, snapshot, Instant.parse("2026-01-01T00:00:00Z"));
        before = rows();
        assertThat(preview.preview(original, snapshot).diff().unchangedRoutes()).containsExactly("r");
        var changed = seoul("z,3,지선,1\n", "z,b,0,1\n");
        var result = preview.preview(changed, changed.fetchSnapshotWithRawRows());
        assertThat(result.diff().removedRoutes()).containsExactly("r");
        assertThat(result.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
        assertThat(result.detailPage(0, 1)).hasSize(1);
        assertThat(rows()).isEqualTo(before);
    }
    @Test void seoul_precision_matches_mysql_and_preserves_real_changes_and_raw() {
        String routes = "노선ID,노선명,노선유형,거리\nr,1,간선,1\nz,2,지선,1\n";
        String occurrences = "노선ID,노드ID,링크거리누계,정류장순번\nr,a,0,1\nr,b,0,2\nr,a,0,3\nz,a,0,1\n";
        String stops = "정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\n"
                + "a,A,일반차로,1,127.12345674,37.12345675,미설치\n"
                + "b,B,일반차로,2,-127.12345675,-37.12345675,미설치\n";
        var source = new SeoulCsvMetadataSource(resource(routes), resource(occurrences), resource(stops));
        var collected = source.fetchSnapshotWithRawRows();
        var snapshot = source.toCompleteSnapshot(collected.normalized());
        var first = snapshot.routes().stream().filter(r -> r.externalRouteId().equals("r")).findFirst().orElseThrow().occurrences();
        // Insert the unrounded provider values through JDBC to verify MySQL's actual DECIMAL behavior.
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name,latitude,longitude) values ('SEOUL_BUS','a','A',?,?)",
                new java.math.BigDecimal("37.12345675"), new java.math.BigDecimal("127.12345674"));
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name,latitude,longitude) values ('SEOUL_BUS','b','B',?,?)",
                new java.math.BigDecimal("-37.12345675"), new java.math.BigDecimal("-127.12345675"));
        for (int i = 0; i < 2; i++) {
            var stop = first.get(i);
            assertThat(jdbc.queryForObject("select latitude from bus_stops where external_stop_id=?", java.math.BigDecimal.class, stop.externalStopId()))
                    .isEqualByComparingTo(stop.latitude());
            assertThat(jdbc.queryForObject("select longitude from bus_stops where external_stop_id=?", java.math.BigDecimal.class, stop.externalStopId()))
                    .isEqualByComparingTo(stop.longitude());
        }
        Instant collectedAt = Instant.parse("2026-01-01T00:00:00Z");
        sync.syncSeoulSnapshot(source, collected, collectedAt);
        var before = rows();
        var result = preview.preview(source, collected);
        assertThat(result.diff().modifiedStops()).isEmpty();
        assertThat(result.diff().conflictingStops()).isEmpty();
        assertThat(result.diff().unchangedOccurrences()).hasSize(4);
        assertThat(result.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.ALLOW);
        assertThat(rows()).isEqualTo(before);
        jdbc.execute("CREATE TRIGGER reject_unnecessary_stop_update BEFORE UPDATE ON bus_stops FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'unexpected stop update'");
        try {
            sync.syncSeoulSnapshot(source, collected, collectedAt);
        } finally {
            jdbc.execute("DROP TRIGGER reject_unnecessary_stop_update");
        }
        assertThat(jdbc.queryForObject("select count(*) from bus_route_stop_occurrences", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.\"좌표Y\"')) from transit_source_metadata where source_system='SEOUL_TDATA' and data_kind='STOP' and source_key='a'", String.class))
                .isEqualTo("37.12345675");

        var changed = new SeoulCsvMetadataSource(resource(routes), resource(occurrences), resource(stops
                .replace("a,A,", "a,A changed,")
                .replace("127.12345674,37.12345675", "127.12345674,37.12345695")));
        var changedResult = preview.preview(changed, changed.fetchSnapshotWithRawRows());
        assertThat(changedResult.diff().modifiedStops()).singleElement().satisfies(change ->
                assertThat(change.fields()).extracting(BusMetadataDiffService.FieldChange::field)
                        .containsExactly("stopName", "latitude"));
        assertThat(changedResult.diff().conflictingStops()).isEmpty();
        assertThat(changedResult.diff().unchangedOccurrences()).hasSize(4);
    }

    @Test void seoul_addition_and_reordered_occurrences_are_detected() {
        var original = seoul("r,1,지선,1\n", "r,a,0,1\nr,b,0,2\n");
        sync.syncSeoulSnapshot(original, original.fetchSnapshotWithRawRows(), Instant.parse("2026-01-01T00:00:00Z"));
        var before = rows();
        var changed = seoul("r,1,지선,1\nz,2,간선,1\n", "r,b,0,1\nr,a,0,2\nz,a,0,1\n");
        var result = preview.preview(changed, changed.fetchSnapshotWithRawRows());
        assertThat(result.diff().addedRoutes()).containsExactly("z");
        assertThat(result.diff().positionChanges()).hasSize(2);
        assertThat(result.diff().removedOccurrences()).hasSize(2);
        assertThat(result.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
        assertThat(rows()).isEqualTo(before);
    }
    @Test void tago_changes_and_absent_gbis_are_read_only() {
        var original = tago("1", "a", null);
        sync.syncTagoSnapshot(original, Instant.parse("2026-01-01T00:00:00Z"));
        var before = rows();
        var result = preview.preview(tago("2", "b", "destination"));
        assertThat(result.diff().modifiedRoutes()).hasSize(1);
        assertThat(result.diff().addedStops()).containsExactly("b");
        assertThat(result.diff().orphanStops()).containsExactly("a");
        assertThat(result.diff().positionChanges()).hasSize(2);
        assertThat(result.diff().removedOccurrences()).hasSize(2);
        assertThat(result.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
        assertThat(result.raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.GBIS, ROUTE)).compared()).isFalse();
        assertThat(rows()).isEqualTo(before);
    }
    private TagoMetadataSource.TagoSnapshot withGbis(TagoMetadataSource.TagoSnapshot tago, String version) {
        var route = new com.stopbell.transit.metadata.GbisBulkMetadata.Route("999", "999", 1,
                List.of(new com.stopbell.transit.metadata.GbisBulkMetadata.Station(1, "s", "Stop")));
        var rows = List.of(new com.stopbell.transit.metadata.GbisCollectedSnapshot.RawRow(TransitSourceSystem.GBIS, ROUTE, "999", "999", null,
                        Map.of("routeId", "999", "routeName", "999", "turnSeq", "1")),
                new com.stopbell.transit.metadata.GbisCollectedSnapshot.RawRow(TransitSourceSystem.GBIS, OCCURRENCE, "3:999:1", "999", 1,
                        Map.of("routeId", "999", "routeName", "999", "staOrder", "1", "stationId", "s", "stationName", "Stop")));
        return new TagoMetadataSource.TagoSnapshot(tago.normalized(), tago.rawRows(), new com.stopbell.transit.metadata.GbisCollectedSnapshot(
                new com.stopbell.transit.metadata.GbisBulkMetadata(Map.of("999", route)), rows, version));
    }
    @Test void gbis_version_and_destination_changes_are_distinct_and_read_only() {
        var original = withGbis(tago("1", "a", null), "v1");
        sync.syncTagoSnapshot(original, Instant.parse("2026-01-01T00:00:00Z"));
        var before = rows();
        var result = preview.preview(withGbis(tago("1", "a", "changed"), "v2"));
        assertThat(result.diff().modifiedOccurrences()).hasSize(1);
        assertThat(result.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.ALLOW);
        assertThat(result.raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.GBIS, ROUTE)).versionChanged()).isEqualTo(1);
        assertThat(result.raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.GBIS, OCCURRENCE)).versionChanged()).isEqualTo(1);
        assertThat(rows()).isEqualTo(before);
    }
    @Test void cli_preview_reads_local_files_without_writing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var routes = dir.resolve("routes.csv"); var stops = dir.resolve("stops.csv"); var occurrences = dir.resolve("occurrences.csv");
        java.nio.file.Files.writeString(routes, "노선ID,노선명,노선유형,거리\nr,1,지선,1\n");
        java.nio.file.Files.writeString(stops, "정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\na,A,일반차로,1,127,37,미설치\n");
        java.nio.file.Files.writeString(occurrences, "노선ID,노드ID,링크거리누계,정류장순번\nr,a,0,1\n");
        var environment = org.mockito.Mockito.mock(org.springframework.core.env.Environment.class);
        org.mockito.Mockito.when(environment.acceptsProfiles(org.mockito.ArgumentMatchers.any(org.springframework.core.env.Profiles.class))).thenReturn(true);
        var runner = new com.stopbell.transit.cli.TransitMetadataAdminRunner(
                org.mockito.Mockito.mock(TransitMetadataChangeApprovalService.class), org.mockito.Mockito.mock(TransitMetadataUnsafeChangeReviewService.class),
                org.mockito.Mockito.mock(BusMetadataBootstrapService.class), org.mockito.Mockito.mock(TransitMetadataPreflightService.class), preview, jdbc, environment,
                org.mockito.Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class));
        var before = rows();
        runner.run(new org.springframework.boot.DefaultApplicationArguments("--spring.main.web-application-type=none", "--transit.metadata.admin.enabled=true",
                "--metadata.command=preview", "--provider=SEOUL_BUS", "--route-master=" + routes, "--stop-master=" + stops,
                "--route-stop-master=" + occurrences, "--page=0", "--size=10"));
        assertThat(rows()).isEqualTo(before);
    }
    @Test void cli_refuses_old_schema_without_migrating(SharedMySqlExtension.Database old, @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var datasource = new org.springframework.jdbc.datasource.DriverManagerDataSource(old.getJdbcUrl(), old.getUsername(), old.getPassword());
        org.flywaydb.core.Flyway.configure().dataSource(datasource).target("34").load().migrate();
        var oldJdbc = new JdbcTemplate(datasource);
        var before = oldJdbc.queryForList("select * from flyway_schema_history order by installed_rank");
        var file = dir.resolve("source.csv"); java.nio.file.Files.writeString(file, "placeholder");
        var environment = org.mockito.Mockito.mock(org.springframework.core.env.Environment.class);
        org.mockito.Mockito.when(environment.acceptsProfiles(org.mockito.ArgumentMatchers.any(org.springframework.core.env.Profiles.class))).thenReturn(true);
        var runner = new com.stopbell.transit.cli.TransitMetadataAdminRunner(
                org.mockito.Mockito.mock(TransitMetadataChangeApprovalService.class), org.mockito.Mockito.mock(TransitMetadataUnsafeChangeReviewService.class),
                org.mockito.Mockito.mock(BusMetadataBootstrapService.class), org.mockito.Mockito.mock(TransitMetadataPreflightService.class), preview, oldJdbc, environment,
                org.mockito.Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class));
        assertThatThrownBy(() -> runner.run(new org.springframework.boot.DefaultApplicationArguments("--spring.main.web-application-type=none", "--transit.metadata.admin.enabled=true",
                "--metadata.command=preview", "--provider=SEOUL_BUS", "--route-master=" + file, "--stop-master=" + file,
                "--route-stop-master=" + file, "--page=0", "--size=10"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("migrations");
        assertThat(oldJdbc.queryForList("select * from flyway_schema_history order by installed_rank")).isEqualTo(before);
    }
    @Test void synthetic_shared_stops_and_many_raw_rows_preserve_all_conflict_variants() {
        var routes = new java.util.ArrayList<BusRouteMetadataSnapshot>();
        var rawRows = new java.util.ArrayList<TagoMetadataSource.RawSourceRow>();
        for (int i = 0; i < 200; i++) {
            String id = "r" + i, name = i == 199 ? "different" : "shared";
            routes.add(new BusRouteMetadataSnapshot(TAGO, id, "1", "31010", List.of(
                    new BusStopOccurrenceMetadataSnapshot("shared", name, null, null, 1, null),
                    new BusStopOccurrenceMetadataSnapshot("shared", name, null, null, 2, null))));
            rawRows.add(new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, ROUTE, id, id, null, TransitRawMetadataDiff.parse("{\"routeid\":\"" + id + "\"}")));
            for (int order = 1; order <= 2; order++) rawRows.add(new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, OCCURRENCE,
                    TagoMetadataSource.occurrenceSourceKey(id, order), id, order, TransitRawMetadataDiff.parse("{\"nodeid\":\"shared\",\"nodeord\":" + order + "}")));
        }
        var before = rows();
        var result = preview.preview(new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, routes), rawRows));
        assertThat(result.snapshot()).isEqualTo(new TransitMetadataSnapshotPreviewService.Size(200, 1, 400));
        assertThat(result.diff().conflictingStops()).containsExactly("shared");
        assertThat(result.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
        assertThat(result.details().getFirst().variants()).hasSize(400);
        assertThat(result.details().getFirst().variants().getFirst().routeId()).isEqualTo("r0");
        java.util.Collections.reverse(routes);
        var reversed = preview.preview(new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, routes), rawRows));
        assertThat(reversed.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
        assertThat(reversed.details().getFirst().variants().getFirst().routeId()).isEqualTo("r199");
        assertThat(result.raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.TAGO, OCCURRENCE)).added()).isEqualTo(400);
        assertThat(rows()).isEqualTo(before);
    }
    @Test void existing_analysis_details_and_approval_are_unchanged(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long run = history.start(TAGO).getId(); history.collected(run);
        var tagoBundle = com.stopbell.transit.metadata.OfflineBundleFixtures.tago(temp, List.of("GGB200"),
                Map.of("GGB200", com.stopbell.transit.metadata.OfflineBundleFixtures.stops("A", "A")), "raw");
        var gbisBundle = com.stopbell.transit.metadata.OfflineBundleFixtures.gbis(temp, "v1", "turn", "end");
        var snapshot = com.stopbell.transit.metadata.TagoGbisOfflineSnapshotAssembler.withGbis(tagoBundle, gbisBundle,
                com.stopbell.transit.metadata.OfflineBundleFixtures.KEY);
        var stored = analysis.analyzeAndStore(run, snapshot);
        review.save(run, stored, new BusMetadataSafetyGuard().evaluate(stored));
        history.fail(run, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, run);
        approvals.approve(run, TAGO, stored.stored().fingerprint(), hash, Instant.now().plusSeconds(3600), "reviewed");
        var before = rows();
        assertThat(preview.preview(snapshot).safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
        assertThat(rows()).isEqualTo(before);
    }
    @Test void invalid_snapshots_are_rejected() {
        var source = seoul("r,1,지선,1\n", "r,a,0,1\nr,b,0,1\n");
        assertThatThrownBy(source::fetchSnapshotWithRawRows).isInstanceOf(RuntimeException.class);
        var valid = tago("1", "a", null);
        assertThatThrownBy(() -> preview.preview(new TagoMetadataSource.TagoSnapshot(valid.normalized(), List.of()))).isInstanceOf(IllegalStateException.class);
    }
    @Test void offline_bundle_preview_compares_both_sources_and_preserves_every_row(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var ids = List.of("GGB200");
        var tago1 = com.stopbell.transit.metadata.OfflineBundleFixtures.tago(temp, ids,
                Map.of("GGB200", com.stopbell.transit.metadata.OfflineBundleFixtures.stops("A", "A")), "old");
        var gbis1 = com.stopbell.transit.metadata.OfflineBundleFixtures.gbis(temp, "v1", "turn", "old-end");
        String key = com.stopbell.transit.metadata.OfflineBundleFixtures.KEY;
        var initial = com.stopbell.transit.metadata.TagoGbisOfflineSnapshotAssembler.withGbis(tago1, gbis1, key);
        sync.syncTagoSnapshot(initial, Instant.parse("2026-01-01T00:00:00Z"));
        var before = rows();
        var tago2 = com.stopbell.transit.metadata.OfflineBundleFixtures.tago(temp, ids,
                Map.of("GGB200", com.stopbell.transit.metadata.OfflineBundleFixtures.stops("A", "A")), "new");
        var gbis2 = com.stopbell.transit.metadata.OfflineBundleFixtures.gbis(temp, "v2", "turn", "new-end");
        var combined = com.stopbell.transit.metadata.TagoGbisOfflineSnapshotAssembler.withGbis(tago2, gbis2, key);
        var result = preview.preview(combined);
        assertThat(result.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.ALLOW);
        assertThat(result.diff().modifiedOccurrences()).hasSize(2);
        assertThat(result.raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.TAGO, ROUTE)).modified()).isEqualTo(1);
        assertThat(result.raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.GBIS, ROUTE)).versionChanged()).isEqualTo(1);
        assertThat(result.raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.GBIS, OCCURRENCE)).versionChanged()).isEqualTo(2);
        assertThat(result.raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.GBIS, OCCURRENCE)).modified()).isEqualTo(1);
        assertThat(rows()).isEqualTo(before);
        var tagoOnly = com.stopbell.transit.metadata.TagoGbisOfflineSnapshotAssembler.tagoOnly(tago2, key);
        assertThat(preview.preview(tagoOnly).raw().get(new TransitMetadataSnapshotPreviewService.SourceKind(TransitSourceSystem.GBIS, ROUTE)).compared()).isFalse();
        assertThat(rows()).isEqualTo(before);
        var shortStops = "[{\"nodeid\":\"GGB10\",\"nodenm\":\"A\",\"nodeord\":1}]";
        var reduced = com.stopbell.transit.metadata.OfflineBundleFixtures.tago(temp, ids, Map.of("GGB200", shortStops), "old");
        var blocked = preview.preview(com.stopbell.transit.metadata.TagoGbisOfflineSnapshotAssembler.withGbis(reduced, gbis2, key));
        assertThat(blocked.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
        assertThat(blocked.diff().removedOccurrences()).isNotEmpty();
        assertThat(blocked.details()).isNotEmpty();
        assertThat(rows()).isEqualTo(before);
    }
    @Test void offline_bundle_shared_stop_conflicts_follow_recorded_route_order(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var stops = Map.of("GGB200", com.stopbell.transit.metadata.OfflineBundleFixtures.stops("A", "A"),
                "GGB201", com.stopbell.transit.metadata.OfflineBundleFixtures.stops("B", "B"));
        var bundle = com.stopbell.transit.metadata.OfflineBundleFixtures.tago(temp, List.of("GGB201", "GGB200"), stops, "raw");
        var gbis = com.stopbell.transit.metadata.OfflineBundleFixtures.gbis(temp, "v1", "turn", "end");
        var before = rows();
        var snapshot = com.stopbell.transit.metadata.TagoGbisOfflineSnapshotAssembler.withGbis(bundle, gbis,
                com.stopbell.transit.metadata.OfflineBundleFixtures.KEY);
        var result = preview.preview(snapshot);
        assertThat(result.safety().decision()).isEqualTo(BusMetadataSafetyGuard.Decision.BLOCK);
        assertThat(result.diff().conflictingStops()).containsExactly("GGB10");
        assertThat(result.details().getFirst().variants().getFirst().routeId()).isEqualTo("GGB201");
        assertThat(rows()).isEqualTo(before);
    }
}
