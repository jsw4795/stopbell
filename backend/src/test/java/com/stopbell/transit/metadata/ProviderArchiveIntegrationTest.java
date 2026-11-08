package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.client.TransitClientProperties;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.service.BusMetadataSyncService;
import com.stopbell.transit.service.TransitMetadataArchiveService;
import com.stopbell.transit.service.TransitMetadataCollectionHistoryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(SharedMySqlExtension.class)
@SpringBootTest(properties = {"transit.monitoring.enabled=false", "notification.dispatch.enabled=false", "notification.fcm.enabled=false"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderArchiveIntegrationTest {
    private static final Path ROOT;
    static {
        try { ROOT = Files.createTempDirectory("provider-archive").toRealPath(); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        SharedMySqlExtension.database(registry, "provider_archive");
        registry.add("transit.metadata.archive.directory", ROOT::toString);
    }
    @AfterAll static void cleanup() throws Exception {
        try (var files = Files.list(ROOT)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(ROOT);
    }
    @Autowired TransitMetadataArchiveService archives;
    @Autowired TransitMetadataCollectionHistoryService runs;
    @Autowired BusMetadataSyncService sync;
    @Autowired JdbcTemplate jdbc;

    private static final String SEOUL_ROUTES = "\uFEFF노선ID,노선명,노선유형,거리\r\nr1,1,지선,1\r\n";
    private static final String SEOUL_STOPS = "\uFEFF정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\r\ns1,출발,일반차로,1,127,37,미설치\r\n";
    private static final String SEOUL_ROUTE_STOPS = "\uFEFF노선ID,노드ID,링크거리누계,정류장순번\r\nr1,s1,1,1\r\n";
    private static final String GBIS_ROUTES = "routeId|routeName|turnSeq^200|300|2";
    private static final String GBIS_STOPS = "routeId|routeName|upDown|staOrder|stationId|stationName|x|y^200|300|상행|1|10|출발|127|37^200|300|하행|2|20|종점|127|37";
    private static final String CITY = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"citycode\":\"31010\"}}}}}";
    private static final String ROUTE = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"routeid\":\"GGB200\",\"routeno\":\"300\",\"extra\":\"raw\"}},\"totalCount\":1,\"pageNo\":1}}}";
    private static String stop(int page, int id, String name) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"nodeid\":\"GGB" + id + "\",\"nodenm\":\"" + name + "\",\"nodeord\":" + page + "}},\"totalCount\":2,\"pageNo\":" + page + "}}}";
    }
    private static final String BASE = "{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"baseInfoItem\":{\"routeDownloadUrl\":\"http://openapi.gbis.go.kr/ws/download?file=route&signature=fake\",\"routeVersion\":\"1\",\"routeStationDownloadUrl\":\"http://openapi.gbis.go.kr/ws/download?file=station&signature=fake\",\"routeStationVersion\":\"1\"}}}}";

    @Test void seoul_files_are_identical_and_sync_only_after_archiving() throws Exception {
        long run = runs.start(TransitProvider.SEOUL_BUS).getId();
        SeoulCsvMetadataSource source = new SeoulCsvMetadataSource(resource(SEOUL_ROUTES), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        sync.syncSeoulSource(source, run, archives);
        assertArchive(run, TransitSourceSystem.SEOUL_TDATA, "seoul_route_master", SEOUL_ROUTES);
        assertArchive(run, TransitSourceSystem.SEOUL_TDATA, "seoul_stop_master", SEOUL_STOPS);
        assertArchive(run, TransitSourceSystem.SEOUL_TDATA, "seoul_route_stop_master", SEOUL_ROUTE_STOPS);
        source.fetchSnapshotWithRawRows(run, archives);
        assertThat(countArchives(run)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_system='SEOUL_TDATA'", Integer.class)).isGreaterThanOrEqualTo(3);
        assertThatThrownBy(() -> new SeoulCsvMetadataSource(resource(SEOUL_ROUTES.replace("r1,1", "r1,2")), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS)).fetchSnapshotWithRawRows(run, archives)).isInstanceOf(IllegalStateException.class);
        long wrongRun = runs.start(TransitProvider.TAGO).getId();
        assertThatThrownBy(() -> sync.syncSeoulSource(source, wrongRun, archives)).isInstanceOf(IllegalArgumentException.class);
        assertThat(countArchives(wrongRun)).isZero();
    }

    @Test
    @ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void tago_and_gbis_same_downloads_are_archived_and_synced(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        long run = runs.start(TransitProvider.TAGO).getId();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expect(server, "https://provider.test/base?format=json&serviceKey=fake-service-key", BASE);
        expect(server, "http://openapi.gbis.go.kr/ws/download?file=route&signature=fake", GBIS_ROUTES);
        expect(server, "http://openapi.gbis.go.kr/ws/download?file=station&signature=fake", GBIS_STOPS);
        expect(server, "https://provider.test/tago/getCtyCodeList?serviceKey=fake-service-key&_type=json", CITY);
        expect(server, "https://provider.test/tago/getRouteNoList?serviceKey=fake-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010", ROUTE);
        for (int i = 1; i <= 2; i++) expect(server, "https://provider.test/tago/getRouteAcctoThrghSttnList?serviceKey=fake-service-key&pageNo=" + i + "&numOfRows=1000&_type=json&cityCode=31010&routeId=GGB200", stop(i, i * 10, i == 1 ? "출발" : "종점"));
        RestClient rest = builder.build();
        var tago = new TagoMetadataClient(rest, new TransitClientProperties.Tago("https://provider.test/unused", "https://provider.test/tago", "fake-service-key"));
        var gbis = new GbisMetadataClient(rest, "https://provider.test/base", "fake-service-key");
        var source = new TagoMetadataSource(tago, gbis, 0, Set.of("31010"));
        sync.syncTagoSource(source, run, archives);
        server.verify();
        assertArchive(run, TransitSourceSystem.GBIS, "gbis_route", GBIS_ROUTES);
        assertArchive(run, TransitSourceSystem.GBIS, "gbis_route_station", GBIS_STOPS);
        assertArchive(run, TransitSourceSystem.TAGO, "tago_city_codes", CITY);
        assertArchive(run, TransitSourceSystem.TAGO, TagoMetadataSource.archiveKey("route", "31010", "", 1), ROUTE);
        assertArchive(run, TransitSourceSystem.TAGO, TagoMetadataSource.archiveKey("stops", "31010", "GGB200", 1), stop(1, 10, "출발"));
        assertArchive(run, TransitSourceSystem.TAGO, TagoMetadataSource.archiveKey("stops", "31010", "GGB200", 2), stop(2, 20, "종점"));
        assertThat(countArchives(run)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_system IN ('TAGO','GBIS')", Integer.class)).isGreaterThanOrEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT destination_name FROM bus_route_stop_occurrences WHERE destination_name IS NOT NULL LIMIT 1", String.class)).isEqualTo("종점");
        assertThat(jdbc.queryForList("SELECT file_name FROM transit_metadata_archives WHERE collection_run_id=?", String.class, run)).allSatisfy(name -> assertThat(name).matches("[0-9a-f]{64}"));
        assertThat(output.getAll()).doesNotContain("fake-service-key", "signature=fake", "openapi.gbis.go.kr");
    }

    @Test void incomplete_tago_keeps_partial_archives_but_does_not_sync() {
        long run = runs.start(TransitProvider.TAGO).getId();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_system='TAGO'", Integer.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expect(server, "https://provider.test/tago/getCtyCodeList?serviceKey=fake-service-key&_type=json", CITY);
        expect(server, "https://provider.test/tago/getRouteNoList?serviceKey=fake-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010", ROUTE);
        expect(server, "https://provider.test/tago/getRouteAcctoThrghSttnList?serviceKey=fake-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010&routeId=GGB200",
                "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[]},\"totalCount\":2,\"pageNo\":1}}}");
        var tago = new TagoMetadataClient(builder.build(), new TransitClientProperties.Tago("https://provider.test/unused", "https://provider.test/tago", "fake-service-key"));
        var source = new TagoMetadataSource(tago, 0, Set.of("31010"));
        assertThatThrownBy(() -> sync.syncTagoSource(source, run, archives)).isInstanceOf(IllegalStateException.class);
        server.verify();
        assertThat(countArchives(run)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_system='TAGO'", Integer.class)).isEqualTo(before);
    }

    private void assertArchive(long run, TransitSourceSystem source, String key, String expected) throws Exception {
        String keyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        long id = jdbc.queryForObject("SELECT id FROM transit_metadata_archives WHERE collection_run_id=? AND source_system=? AND logical_key_hash=?", Long.class, run, source.name(), keyHash);
        var archive = archives.find(id);
        byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
        assertThat(Files.readAllBytes(ROOT.resolve(archive.fileName()))).isEqualTo(bytes);
        assertThat(archive.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        archives.verify(id);
    }
    private int countArchives(long run) { return jdbc.queryForObject("SELECT COUNT(*) FROM transit_metadata_archives WHERE collection_run_id=?", Integer.class, run); }
    private static ByteArrayResource resource(String value) { return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8)); }
    private static void expect(MockRestServiceServer server, String uri, String body) {
        server.expect(requestTo(uri)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
