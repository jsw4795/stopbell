package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.*;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"transit.monitoring.enabled=false", "notification.dispatch.enabled=false", "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BusMetadataSafetyIntegrationTest {
    private static final Path ROOT;
    static { try { ROOT = Files.createTempDirectory("safety-bootstrap").toRealPath(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "safety_bootstrap");
        registry.add("transit.metadata.archive.directory", ROOT::toString);
    }
    @AfterAll static void cleanup() throws Exception {
        try (var files = Files.list(ROOT)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(ROOT);
    }
    @Autowired BusMetadataSyncService sync;
    @Autowired TransitMetadataCollectionHistoryService history;
    @Autowired TransitMetadataArchiveService archive;
    @Autowired TransitMetadataDiffAnalysisService analysis;
    @Autowired JdbcTemplate jdbc;

    @Test @DisplayName("서울 위험 변경은 실제 DB를 보존하고 수정된 데이터로 새 실행을 허용한다")
    void blocks_database_writes_and_allows_retry() throws Exception {
        Path routes = ROOT.resolve("routes.csv"), routeStops = ROOT.resolve("route-stops.csv"), stops = ROOT.resolve("stops.csv");
        String routeHeader = "노선ID,노선명,노선유형,거리\n";
        String stopHeader = "정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\n";
        String mappingHeader = "노선ID,노드ID,링크거리누계,정류장순번\n";
        Files.writeString(routes, routeHeader + "r1,1,지선,1\nr2,2,지선,1\n");
        Files.writeString(stops, stopHeader + "s1,출발,일반차로,1,127,37,미설치\ns2,종점,일반차로,2,127,37,미설치\n");
        Files.writeString(routeStops, mappingHeader + "r1,s1,1,1\nr2,s2,1,1\n");
        var source = new SeoulCsvMetadataSource(new FileSystemResource(routes), new FileSystemResource(routeStops), new FileSystemResource(stops));
        var bootstrap = new BusMetadataBootstrapService(List.of(source), sync, history, archive, analysis, Clock.systemUTC());
        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
        var beforeRoutes = rows("bus_routes", "id"); var beforeStops = rows("bus_stops", "id");
        var beforeOccurrences = rows("bus_route_stop_occurrences", "id"); var beforeRaw = rows("transit_source_metadata", "id");
        var beforeState = rows("bus_metadata_sync_states", "provider");
        Files.writeString(routes, routeHeader + "r1,변경,지선,1\n");
        Files.writeString(routeStops, mappingHeader + "r1,s1,1,1\n");
        assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("stage=VALIDATION").hasMessageContaining("code=UNSAFE_CHANGE");
        long failed = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        var run = history.find(failed);
        assertThat(run.getFailureMessage()).isEqualTo("Unsafe metadata change blocked");
        assertThat(run.getStatus().name()).isEqualTo("FAILED");
        var summary = TransitRawMetadataDiff.parse(analysis.find(failed).summaryJson());
        assertThat(summary.path("routes").path("removed").asInt()).isEqualTo(1);
        assertThat(summary.path("stops").path("removed").asInt()).isEqualTo(1);
        assertThat(summary.path("occurrences").path("removed").asInt()).isEqualTo(1);
        assertThat(rows("bus_routes", "id")).isEqualTo(beforeRoutes);
        assertThat(rows("bus_stops", "id")).isEqualTo(beforeStops);
        assertThat(rows("bus_route_stop_occurrences", "id")).isEqualTo(beforeOccurrences);
        assertThat(rows("transit_source_metadata", "id")).isEqualTo(beforeRaw);
        assertThat(rows("bus_metadata_sync_states", "provider")).isEqualTo(beforeState);
        Files.writeString(routes, routeHeader + "r1,변경,지선,1\nr2,2,지선,1\n");
        Files.writeString(routeStops, mappingHeader + "r1,s1,1,1\nr2,s2,1,1\n");
        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
        long retry = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        assertThat(retry).isGreaterThan(failed);
        assertThat(history.find(retry).getStatus().name()).isEqualTo("COMPLETED");
        assertThat(analysis.find(failed)).isNotNull();
        assertThat(jdbc.queryForObject("select route_number from bus_routes where external_route_id='r1'", String.class)).isEqualTo("변경");
    }
    private List<java.util.Map<String, Object>> rows(String table, String order) {
        return jdbc.queryForList("select * from " + table + " order by " + order);
    }
}
