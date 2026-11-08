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
import com.stopbell.transit.service.BusMetadataBootstrapService;
import java.time.Clock;
import java.util.List;
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
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private com.stopbell.transit.service.TransitMetadataDiffAnalysisService analysis;
    @org.springframework.beans.factory.annotation.Autowired private com.stopbell.transit.service.TransitMetadataUnsafeChangeReviewService review;
    @org.springframework.beans.factory.annotation.Autowired private com.stopbell.transit.service.TransitMetadataChangeApprovalService approvals;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean private com.stopbell.transit.service.BusMetadataCurrentStateReader stateReader;
    @Autowired TransitMetadataArchiveService archives;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean TransitMetadataCollectionHistoryService runs;
    @Autowired BusMetadataSyncService sync;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;

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
        SeoulCsvMetadataSource source = new SeoulCsvMetadataSource(resource(SEOUL_ROUTES), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC()).bootstrap(TransitProvider.SEOUL_BUS);
        long run = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='SEOUL_BUS'", Long.class);
        assertThat(runs.find(run).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.COMPLETED);
        assertThat(runs.find(run).getFinishedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT last_complete_sync_at FROM bus_metadata_sync_states WHERE provider='SEOUL_BUS'", java.sql.Timestamp.class)).isNotNull();
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
        new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC()).bootstrap(TransitProvider.TAGO);
        long run = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='TAGO'", Long.class);
        assertThat(runs.find(run).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.COMPLETED);
        assertThat(runs.find(run).getFinishedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT last_complete_sync_at FROM bus_metadata_sync_states WHERE provider='TAGO'", java.sql.Timestamp.class)).isNotNull();
        server.verify();
        assertArchive(run, TransitSourceSystem.GBIS, "gbis_route", GBIS_ROUTES);
        assertArchive(run, TransitSourceSystem.GBIS, "gbis_route_station", GBIS_STOPS);
        assertArchive(run, TransitSourceSystem.TAGO, "tago_city_codes", CITY);
        assertArchive(run, TransitSourceSystem.TAGO, TagoMetadataSource.archiveKey("route", "31010", "", 1), ROUTE);
        assertArchive(run, TransitSourceSystem.TAGO, TagoMetadataSource.archiveKey("stops", "31010", "GGB200", 1), stop(1, 10, "출발"));
        assertArchive(run, TransitSourceSystem.TAGO, TagoMetadataSource.archiveKey("stops", "31010", "GGB200", 2), stop(2, 20, "종점"));
        assertThat(countArchives(run)).isEqualTo(6);
        var summary = com.stopbell.transit.service.TransitRawMetadataDiff.parse(analysis.find(run).summaryJson());
        assertThat(summary.path("sources").path("TAGO").path("compared").asBoolean()).isTrue();
        assertThat(summary.path("sources").path("GBIS").path("compared").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_system IN ('TAGO','GBIS')", Integer.class)).isGreaterThanOrEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT destination_name FROM bus_route_stop_occurrences WHERE destination_name IS NOT NULL LIMIT 1", String.class)).isEqualTo("종점");
        assertThat(jdbc.queryForList("SELECT file_name FROM transit_metadata_archives WHERE collection_run_id=?", String.class, run)).allSatisfy(name -> assertThat(name).matches("[0-9a-f]{64}"));
        assertThat(output.getAll()).doesNotContain("fake-service-key", "signature=fake", "openapi.gbis.go.kr");
    }

    @Test void incomplete_tago_keeps_partial_archives_but_does_not_sync() {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_system='TAGO'", Integer.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expect(server, "https://provider.test/tago/getCtyCodeList?serviceKey=fake-service-key&_type=json", CITY);
        expect(server, "https://provider.test/tago/getRouteNoList?serviceKey=fake-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010", ROUTE);
        expect(server, "https://provider.test/tago/getRouteAcctoThrghSttnList?serviceKey=fake-service-key&pageNo=1&numOfRows=1000&_type=json&cityCode=31010&routeId=GGB200",
                "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[]},\"totalCount\":2,\"pageNo\":1}}}");
        var tago = new TagoMetadataClient(builder.build(), new TransitClientProperties.Tago("https://provider.test/unused", "https://provider.test/tago", "fake-service-key"));
        var source = new TagoMetadataSource(tago, 0, Set.of("31010"));
        assertThatThrownBy(() -> new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC())
                .bootstrap(TransitProvider.TAGO)).isInstanceOf(IllegalStateException.class).hasMessageContaining("stage=VALIDATION");
        long run = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='TAGO'", Long.class);
        assertThat(runs.find(run).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.FAILED);
        assertThat(runs.find(run).getFailureCode()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureCode.INVALID_SOURCE);
        server.verify();
        assertThat(countArchives(run)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transit_source_metadata WHERE source_system='TAGO'", Integer.class)).isEqualTo(before);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("누락된 서울 원본은 실패 이력을 남기고 파일 복구 후 새 실행으로 완료한다")
    void missing_seoul_source_can_retry() throws Exception {
        Path input = ROOT.resolve("retry-source.csv");
        Files.deleteIfExists(input);
        var source = new SeoulCsvMetadataSource(new org.springframework.core.io.FileSystemResource(input),
                resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        var bootstrap = new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC());
        assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("code=SOURCE_UNAVAILABLE");
        long failedId = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='SEOUL_BUS'", Long.class);
        var failed = runs.find(failedId);
        assertThat(failed.getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.FAILED);
        assertThat(failed.getFailureStage()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureStage.COLLECTION);
        assertThat(failed.getFailureMessage()).isEqualTo("Source unavailable");
        assertThat(failed.getFinishedAt()).isNotNull();
        assertThat(countArchives(failedId)).isZero();
        Files.writeString(input, SEOUL_ROUTES);
        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
        long successId = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='SEOUL_BUS'", Long.class);
        assertThat(successId).isGreaterThan(failedId);
        assertThat(runs.find(successId).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.COMPLETED);
        assertThat(runs.find(failedId).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.FAILED);
        assertThat(countArchives(successId)).isEqualTo(3);
        Files.delete(input);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("잘못된 서울 CSV는 검증 실패로 기록하고 동기화하지 않는다")
    void invalid_seoul_source_is_validation_failure() {
        var source = new SeoulCsvMetadataSource(resource("invalid,header\n"), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        assertThatThrownBy(() -> new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC())
                .bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("code=INVALID_SOURCE");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='SEOUL_BUS'", Long.class);
        var failed = runs.find(id);
        assertThat(failed.getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.FAILED);
        assertThat(failed.getFailureStage()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION);
        assertThat(failed.getFailureMessage()).isEqualTo("Source validation failed");
        assertThat(countArchives(id)).isZero();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("아카이브 미설정은 Provider 접근 전에 실패 이력을 저장한다")
    void missing_archive_records_failure() {
        var missing = new TransitMetadataArchiveService(jdbc, Clock.systemUTC(), "", manager);
        var source = new SeoulCsvMetadataSource(resource(SEOUL_ROUTES), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        assertThatThrownBy(() -> new BusMetadataBootstrapService(List.of(source), sync, runs, missing, analysis, review, approvals, Clock.systemUTC())
                .bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("code=UNEXPECTED_FAILURE");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='SEOUL_BUS'", Long.class);
        var failed = runs.find(id);
        assertThat(failed.getFailureStage()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureStage.COLLECTION);
        assertThat(failed.getFailureCode()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureCode.UNEXPECTED_FAILURE);
        assertThat(countArchives(id)).isZero();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("TAGO API 오류는 수집 실패로 기록한다")
    void tago_api_failure_is_collection_failure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.test/tago/getCtyCodeList?serviceKey=fake-service-key&_type=json"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
        var client = new TagoMetadataClient(builder.build(), new TransitClientProperties.Tago("https://provider.test/unused", "https://provider.test/tago", "fake-service-key"));
        var source = new TagoMetadataSource(client, 0, Set.of("31010"));
        assertThatThrownBy(() -> new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC())
                .bootstrap(TransitProvider.TAGO)).hasMessageContaining("code=SOURCE_UNAVAILABLE").hasMessageNotContaining("fake-service-key");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='TAGO'", Long.class);
        var failed = runs.find(id);
        assertThat(failed.getFailureStage()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureStage.COLLECTION);
        assertThat(failed.getFailureCode()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureCode.SOURCE_UNAVAILABLE);
        server.verify();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("GBIS 버전 불일치는 TAGO 실행 검증 실패로 기록한다")
    void gbis_version_mismatch_is_validation_failure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expect(server, "https://provider.test/base?format=json&serviceKey=fake-service-key", BASE.replace("routeStationVersion\":\"1", "routeStationVersion\":\"2"));
        var rest = builder.build();
        var client = new TagoMetadataClient(rest, new TransitClientProperties.Tago("https://provider.test/unused", "https://provider.test/tago", "fake-service-key"));
        var gbis = new GbisMetadataClient(rest, "https://provider.test/base", "fake-service-key");
        var source = new TagoMetadataSource(client, gbis, 0, Set.of("31010"));
        assertThatThrownBy(() -> new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC())
                .bootstrap(TransitProvider.TAGO)).hasMessageContaining("code=INVALID_SOURCE");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='TAGO'", Long.class);
        var failed = runs.find(id);
        assertThat(failed.getFailureStage()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION);
        assertThat(failed.getFailureCode()).isEqualTo(com.stopbell.transit.domain.TransitCollectionFailureCode.INVALID_SOURCE);
        assertThat(countArchives(id)).isZero();
        server.verify();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("TAGO 요청 간격 대기 중 인터럽트는 실제 이력에 중단을 기록한다")
    void interrupted_tago_wait_records_interrupted() throws Exception {
        var client = org.mockito.Mockito.mock(TagoMetadataClient.class);
        var source = new TagoMetadataSource(client, 60_000, Set.of("31010"));
        var bootstrap = new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC());
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var caught = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try { bootstrap.bootstrap(TransitProvider.TAGO); }
            catch (Throwable failure) { caught.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        thread.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (thread.getState() != Thread.State.TIMED_WAITING && thread.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            thread.interrupt();
            thread.join(10_000);
            assertThat(thread.isAlive()).isFalse();
            assertThat(interrupted).isTrue();
            assertThat(caught.get()).isInstanceOf(IllegalStateException.class);
            long id = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='TAGO'", Long.class);
            assertThat(runs.find(id).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.INTERRUPTED);
            org.mockito.Mockito.verifyNoInteractions(client);
        } finally { thread.interrupt(); thread.join(10_000); }
    }

    @Test
    @org.junit.jupiter.api.DisplayName("동기화 성공 후 완료 이력 저장 실패는 SYNC_FAILED로 오기록하지 않는다")
    void completion_record_failure_preserves_sync_result() {
        org.mockito.Mockito.doThrow(new IllegalStateException("db secret")).when(runs).complete(org.mockito.ArgumentMatchers.anyLong());
        var source = new SeoulCsvMetadataSource(resource(SEOUL_ROUTES), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        assertThatThrownBy(() -> new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC())
                .bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("completion history is uncertain").hasMessageNotContaining("db secret");
        long id = jdbc.queryForObject("SELECT MAX(id) FROM transit_metadata_collection_runs WHERE provider='SEOUL_BUS'", Long.class);
        var run = runs.find(id);
        assertThat(run.getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.SYNCING);
        assertThat(analysis.find(id)).isNotNull();
        assertThat(run.getFailureCode()).isNull();
        assertThat(run.getFinishedAt()).isNull();
        assertThat(countArchives(id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT last_complete_sync_at FROM bus_metadata_sync_states WHERE provider='SEOUL_BUS'", java.sql.Timestamp.class)).isNotNull();
        org.mockito.Mockito.verify(runs, org.mockito.Mockito.times(2)).complete(id);
    }

    @Test void analysis_is_committed_before_first_route_write() {
        String routes = SEOUL_ROUTES + "diff-proof,1,지선,1\r\n";
        String routeStops = SEOUL_ROUTE_STOPS + "diff-proof,diff-stop,1,1\r\n";
        String stops = SEOUL_STOPS + "diff-stop,출발,일반차로,1,127,37,미설치\r\n";
        var source = new SeoulCsvMetadataSource(resource(routes), resource(routeStops), resource(stops));
        jdbc.execute("""
                create trigger verify_diff_before_route before insert on bus_routes for each row
                begin
                  if new.external_route_id = 'diff-proof' and not exists (
                    select 1 from transit_metadata_diff_analyses d join transit_metadata_collection_runs r on r.id = d.collection_run_id
                    where r.id = (select max(id) from transit_metadata_collection_runs where provider = 'SEOUL_BUS')
                      and r.status = 'SYNCING' and d.provider = 'SEOUL_BUS') then
                    signal sqlstate '45000' set message_text = 'analysis not committed';
                  end if;
                end
                """);
        try {
            new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC()).bootstrap(TransitProvider.SEOUL_BUS);
        } finally { jdbc.execute("drop trigger verify_diff_before_route"); }
        long run = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs where provider='SEOUL_BUS'", Long.class);
        var result = analysis.find(run);
        assertThat(result).isNotNull();
        var summary = com.stopbell.transit.service.TransitRawMetadataDiff.parse(result.summaryJson());
        assertThat(summary.path("routes").path("added").asInt()).isEqualTo(1);
        assertThat(summary.path("stops").path("added").asInt()).isEqualTo(1);
        assertThat(summary.path("occurrences").path("added").asInt()).isEqualTo(1);
        assertThat(countArchives(run)).isEqualTo(3);
        assertThat(runs.find(run).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.COMPLETED);
        sync.syncSeoulSource(new SeoulCsvMetadataSource(resource(SEOUL_ROUTES), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS)));
    }

    @Test void interruption_during_analysis_records_interrupted_and_releases_guard() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var blocker = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            entered.countDown();
            try { blocker.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return call.callRealMethod();
        }).when(analysis).analyzeAndStore(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(SeoulCsvMetadataSource.class),
                org.mockito.ArgumentMatchers.any(SeoulCsvMetadataSource.SeoulCsvSnapshot.class));
        var source = new SeoulCsvMetadataSource(resource(SEOUL_ROUTES), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        var bootstrap = new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC());
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var flag = new java.util.concurrent.atomic.AtomicBoolean();
        var thread = new Thread(() -> { try { bootstrap.bootstrap(TransitProvider.SEOUL_BUS); }
            catch (Throwable e) { failure.set(e); flag.set(Thread.currentThread().isInterrupted()); } });
        thread.start();
        try {
            assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            thread.interrupt(); thread.join(10_000);
            assertThat(thread.isAlive()).isFalse(); assertThat(flag).isTrue(); assertThat(failure.get()).isInstanceOf(IllegalStateException.class);
            long id = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs where provider='SEOUL_BUS'", Long.class);
            assertThat(runs.find(id).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.INTERRUPTED);
            assertThat(analysis.find(id)).isNull();
            org.mockito.Mockito.reset(analysis);
            bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
            long retry = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs where provider='SEOUL_BUS'", Long.class);
            assertThat(retry).isGreaterThan(id);
            assertThat(runs.find(retry).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.COMPLETED);
        } finally { blocker.countDown(); thread.interrupt(); thread.join(10_000); }
    }

    @Test void database_read_failure_during_analysis_blocks_sync() {
        var source = new SeoulCsvMetadataSource(resource(SEOUL_ROUTES), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        var before = jdbc.queryForList("select * from bus_routes order by id");
        com.stopbell.transit.service.BusMetadataCurrentStateReader target = org.springframework.test.util.AopTestUtils.getTargetObject(stateReader);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("private database error"))
                .when(target).read(TransitProvider.SEOUL_BUS);
        assertThatThrownBy(() -> new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC())
                .bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("code=UNEXPECTED_FAILURE").hasMessageNotContaining("private database error");
        long run = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs where provider='SEOUL_BUS'", Long.class);
        assertThat(runs.find(run).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.FAILED);
        assertThat(analysis.find(run)).isNull();
        assertThat(jdbc.queryForList("select * from bus_routes order by id")).isEqualTo(before);
    }

    @Test void invalid_stored_raw_json_structure_blocks_sync() {
        var source = new SeoulCsvMetadataSource(resource(SEOUL_ROUTES), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        var before = jdbc.queryForList("select * from bus_routes order by id");
        jdbc.update("insert into transit_source_metadata (source_system,data_kind,source_key,raw_payload,collected_at,updated_at) values ('SEOUL_TDATA','ROUTE','bad-diff-test',cast('[1]' as json),now(6),now(6))");
        try {
            assertThatThrownBy(() -> new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC())
                    .bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("code=UNEXPECTED_FAILURE");
            long run = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs where provider='SEOUL_BUS'", Long.class);
            assertThat(runs.find(run).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.FAILED);
            assertThat(analysis.find(run)).isNull();
            assertThat(jdbc.queryForList("select * from bus_routes order by id")).isEqualTo(before);
        } finally { jdbc.update("delete from transit_source_metadata where source_system='SEOUL_TDATA' and source_key='bad-diff-test'"); }
    }

    @Test void failed_mysql_analysis_insert_blocks_sync_and_allows_new_run() {
        var source = new SeoulCsvMetadataSource(resource(SEOUL_ROUTES), resource(SEOUL_ROUTE_STOPS), resource(SEOUL_STOPS));
        var beforeRoutes = jdbc.queryForList("select * from bus_routes order by id");
        var beforeStops = jdbc.queryForList("select * from bus_stops order by id");
        var beforeOccurrences = jdbc.queryForList("select * from bus_route_stop_occurrences order by id");
        var beforeState = jdbc.queryForList("select * from bus_metadata_sync_states order by provider");
        var bootstrap = new BusMetadataBootstrapService(List.of(source), sync, runs, archives, analysis, review, approvals, Clock.systemUTC());
        jdbc.execute("create trigger reject_bootstrap_diff before insert on transit_metadata_diff_analyses for each row signal sqlstate '45000' set message_text = 'private error'");
        try {
            assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS))
                    .hasMessageContaining("stage=VALIDATION").hasMessageContaining("code=UNEXPECTED_FAILURE").hasMessageNotContaining("private error");
        } finally { jdbc.execute("drop trigger reject_bootstrap_diff"); }
        long failed = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs where provider='SEOUL_BUS'", Long.class);
        assertThat(runs.find(failed).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.FAILED);
        assertThat(analysis.find(failed)).isNull();
        assertThat(jdbc.queryForList("select * from bus_routes order by id")).isEqualTo(beforeRoutes);
        assertThat(jdbc.queryForList("select * from bus_stops order by id")).isEqualTo(beforeStops);
        assertThat(jdbc.queryForList("select * from bus_route_stop_occurrences order by id")).isEqualTo(beforeOccurrences);
        assertThat(jdbc.queryForList("select * from bus_metadata_sync_states order by provider")).isEqualTo(beforeState);
        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
        long retried = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs where provider='SEOUL_BUS'", Long.class);
        assertThat(retried).isGreaterThan(failed);
        assertThat(analysis.find(retried)).isNotNull();
        assertThat(runs.find(retried).getStatus()).isEqualTo(com.stopbell.transit.domain.TransitCollectionStatus.COMPLETED);
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
