package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.*;

import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
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

@Tag("db")
@Tag("isolated-db")
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
    @org.springframework.beans.factory.annotation.Autowired private com.stopbell.transit.service.TransitMetadataUnsafeChangeReviewService review;
    @org.springframework.beans.factory.annotation.Autowired private com.stopbell.transit.service.TransitMetadataChangeApprovalService approvals;
    @Autowired JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void clear_metadata() {
        jdbc.update("delete from transit_source_metadata");
        jdbc.update("delete from bus_route_stop_occurrences");
        jdbc.update("delete from bus_routes");
        jdbc.update("delete from bus_stops");
        jdbc.update("delete from bus_metadata_sync_states");
    }

    private record ApprovedFixture(BusMetadataBootstrapService bootstrap, SeoulCsvMetadataSource source, long approvalId, long sourceId) { }

    private ApprovedFixture approved_fixture() throws Exception {
        Path routes = ROOT.resolve("failure-routes.csv"), routeStops = ROOT.resolve("failure-route-stops.csv"), stops = ROOT.resolve("failure-stops.csv");
        String header = "노선ID,노선명,노선유형,거리\n";
        Files.writeString(routes, header + "r1,1,지선,1\nr2,2,지선,1\nr3,3,지선,1\n");
        Files.writeString(stops, "정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\na,A,일반차로,1,127,37,미설치\nb,B,일반차로,2,127,37,미설치\nc,C,일반차로,3,127,37,미설치\n");
        Files.writeString(routeStops, "노선ID,노드ID,링크거리누계,정류장순번\nr1,a,1,1\nr2,b,1,1\nr3,c,1,1\n");
        var source = new SeoulCsvMetadataSource(new FileSystemResource(routes), new FileSystemResource(routeStops), new FileSystemResource(stops));
        var bootstrap = new BusMetadataBootstrapService(List.of(source), sync, history, archive, analysis, review, approvals, Clock.systemUTC());
        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
        Files.writeString(routes, header + "r1,changed1,지선,1\nr2,changed2,지선,1\n");
        Files.writeString(routeStops, "노선ID,노드ID,링크거리누계,정류장순번\nr1,a,1,1\nr2,b,1,1\n");
        assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("UNSAFE_CHANGE");
        long blocked = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, blocked);
        var approval = approvals.approve(blocked, TransitProvider.SEOUL_BUS, analysis.find(blocked).fingerprint(), hash, java.time.Instant.now().plusSeconds(3600), "reviewed");
        return new ApprovedFixture(bootstrap, source, approval.id(), blocked);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"claim", "syncing", "route", "cleanup", "sync_state", "complete"})
    @DisplayName("승인 처리 단계별 실제 DB 오류는 승인을 반환하지 않고 적용 결과를 보존한다")
    void keeps_approval_safe_on_database_failures(String phase) throws Exception {
        var fixture = approved_fixture();
        String trigger = switch (phase) {
            case "claim" -> "before update on transit_metadata_change_approvals for each row begin if NEW.status='CLAIMED' then signal sqlstate '45000' set message_text='claim failure'; end if; end";
            case "syncing" -> "before update on transit_metadata_collection_runs for each row begin if NEW.status='SYNCING' then signal sqlstate '45000' set message_text='syncing failure'; end if; end";
            case "route" -> "before update on bus_routes for each row begin if NEW.external_route_id='r2' then signal sqlstate '45000' set message_text='route failure'; end if; end";
            case "cleanup" -> "before delete on bus_routes for each row signal sqlstate '45000' set message_text='cleanup failure'";
            case "sync_state" -> "before update on bus_metadata_sync_states for each row signal sqlstate '45000' set message_text='state failure'";
            case "complete" -> "before update on transit_metadata_collection_runs for each row begin if NEW.status='COMPLETED' then signal sqlstate '45000' set message_text='completion failure'; end if; end";
            default -> throw new IllegalArgumentException();
        };
        var priorState = jdbc.queryForList("select * from bus_metadata_sync_states order by provider");
        jdbc.execute("create trigger fail_approved_test " + trigger);
        try { assertThatThrownBy(() -> fixture.bootstrap().bootstrapWithApproval(TransitProvider.SEOUL_BUS, fixture.approvalId())).isInstanceOf(IllegalStateException.class); }
        finally { jdbc.execute("drop trigger fail_approved_test"); }
        var approval = approvals.find(fixture.approvalId());
        if (phase.equals("claim")) {
            assertThat(approval.status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPROVED);
            assertThat(approval.applyRunId()).isNull();
            assertThat(jdbc.queryForObject("select route_number from bus_routes where external_route_id='r1'", String.class)).isEqualTo("1");
        } else {
            assertThat(approval.status()).isEqualTo(phase.equals("complete") ? TransitMetadataChangeApprovalService.Status.UNCERTAIN : TransitMetadataChangeApprovalService.Status.FAILED);
            assertThat(approval.applyRunId()).isNotNull();
            assertThatThrownBy(() -> approvals.claim(fixture.approvalId(), approval.applyRunId(), TransitProvider.SEOUL_BUS)).isInstanceOf(IllegalStateException.class);
            if (phase.equals("route")) {
                assertThat(jdbc.queryForObject("select route_number from bus_routes where external_route_id='r1'", String.class)).isEqualTo("changed1");
                assertThat(jdbc.queryForObject("select route_number from bus_routes where external_route_id='r2'", String.class)).isEqualTo("2");
            }
            if (phase.equals("syncing") || phase.equals("route") || phase.equals("cleanup") || phase.equals("sync_state"))
                assertThat(jdbc.queryForList("select * from bus_metadata_sync_states order by provider")).isEqualTo(priorState);
            if (phase.equals("complete")) assertThat(history.find(approval.applyRunId()).getStatus().name()).isEqualTo("SYNCING");
        }
        assertThat(history.find(fixture.sourceId()).getStatus().name()).isEqualTo("FAILED");
    }

    @Test @DisplayName("다른 Bootstrap 인스턴스의 동일 승인 동시 적용은 한 번만 성공한다")
    void concurrent_bootstraps_consume_approval_once() throws Exception {
        var fixture = approved_fixture();
        var other = new BusMetadataBootstrapService(List.of(fixture.source()), sync, history, archive, analysis, review, approvals, Clock.systemUTC());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> first = () -> { start.await(); try { fixture.bootstrap().bootstrapWithApproval(TransitProvider.SEOUL_BUS, fixture.approvalId()); return true; } catch (RuntimeException e) { return false; } };
            java.util.concurrent.Callable<Boolean> second = () -> { start.await(); try { other.bootstrapWithApproval(TransitProvider.SEOUL_BUS, fixture.approvalId()); return true; } catch (RuntimeException e) { return false; } };
            var a = pool.submit(first); var b = pool.submit(second); start.countDown();
            assertThat(List.of(a.get(30, java.util.concurrent.TimeUnit.SECONDS), b.get(30, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(approvals.find(fixture.approvalId()).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPLIED);
        assertThat(jdbc.queryForObject("select count(*) from bus_routes where external_route_id='r3'", Integer.class)).isZero();
    }

    @Test @DisplayName("완료 이력 첫 오류 후 재시도 성공 시 승인 적용을 확정한다")
    void confirms_approved_apply_after_completion_retry() throws Exception {
        var fixture = approved_fixture();
        jdbc.execute("create table completion_failure_once (n int not null) engine=MyISAM");
        jdbc.update("insert into completion_failure_once values (0)");
        jdbc.execute("create trigger fail_completion_once before update on transit_metadata_collection_runs for each row begin if NEW.status='COMPLETED' and (select n from completion_failure_once limit 1)=0 then update completion_failure_once set n=1; signal sqlstate '45000' set message_text='completion failure'; end if; end");
        try { fixture.bootstrap().bootstrapWithApproval(TransitProvider.SEOUL_BUS, fixture.approvalId()); }
        finally { jdbc.execute("drop trigger fail_completion_once"); jdbc.execute("drop table completion_failure_once"); }
        var approval = approvals.find(fixture.approvalId());
        assertThat(approval.status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPLIED);
        assertThat(history.find(approval.applyRunId()).getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test @DisplayName("승인된 정류장 순서 변경은 동일 경유 ID만 유지한다")
    void applies_approved_occurrence_reordering() throws Exception {
        Path routes = ROOT.resolve("order-routes.csv"), routeStops = ROOT.resolve("order-route-stops.csv"), stops = ROOT.resolve("order-stops.csv");
        Files.writeString(routes, "노선ID,노선명,노선유형,거리\nr,1,지선,1\n");
        Files.writeString(stops, "정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\na,A,일반차로,1,127,37,미설치\nb,B,일반차로,2,127,37,미설치\nc,C,일반차로,3,127,37,미설치\n");
        String header = "노선ID,노드ID,링크거리누계,정류장순번\n";
        Files.writeString(routeStops, header + "r,a,1,1\nr,b,1,2\nr,c,1,3\n");
        var source = new SeoulCsvMetadataSource(new FileSystemResource(routes), new FileSystemResource(routeStops), new FileSystemResource(stops));
        var bootstrap = new BusMetadataBootstrapService(List.of(source), sync, history, archive, analysis, review, approvals, Clock.systemUTC());
        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
        var before = jdbc.queryForList("select id, stop_order from bus_route_stop_occurrences order by stop_order");
        Files.writeString(routeStops, header + "r,a,1,1\nr,c,1,2\nr,b,1,3\n");
        assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("UNSAFE_CHANGE");
        long blocked = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        String reviewHash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, blocked);
        var approval = approvals.approve(blocked, TransitProvider.SEOUL_BUS, analysis.find(blocked).fingerprint(), reviewHash, java.time.Instant.now().plusSeconds(3600), "reviewed reordering");
        bootstrap.bootstrapWithApproval(TransitProvider.SEOUL_BUS, approval.id());
        var after = jdbc.queryForList("select id, stop_order from bus_route_stop_occurrences order by stop_order");
        assertThat(after.get(0).get("id")).isEqualTo(before.get(0).get("id"));
        assertThat(after.get(1).get("id")).isNotEqualTo(before.get(1).get("id"));
        assertThat(after.get(2).get("id")).isNotEqualTo(before.get(2).get("id"));
        assertThat(approvals.find(approval.id()).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPLIED);
    }

    @Test @DisplayName("서울 삭제 위험은 명시적 일회성 승인 후 새 수집에서만 적용한다")
    void applies_approved_seoul_change_in_a_new_run() throws Exception {
        Path routes = ROOT.resolve("approval-routes.csv"), routeStops = ROOT.resolve("approval-route-stops.csv"), stops = ROOT.resolve("approval-stops.csv");
        String routeHeader = "노선ID,노선명,노선유형,거리\n";
        String stopHeader = "정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\n";
        String mappingHeader = "노선ID,노드ID,링크거리누계,정류장순번\n";
        Files.writeString(routes, routeHeader + "r1,1,지선,1\nr2,2,지선,1\n");
        Files.writeString(stops, stopHeader + "s1,출발,일반차로,1,127,37,미설치\ns2,종점,일반차로,2,127,37,미설치\n");
        Files.writeString(routeStops, mappingHeader + "r1,s1,1,1\nr2,s2,1,1\n");
        var source = new SeoulCsvMetadataSource(new FileSystemResource(routes), new FileSystemResource(routeStops), new FileSystemResource(stops));
        var bootstrap = new BusMetadataBootstrapService(List.of(source), sync, history, archive, analysis, review, approvals, Clock.systemUTC());
        bootstrap.bootstrap(TransitProvider.SEOUL_BUS);
        Files.writeString(routes, routeHeader + "r1,1,지선,1\n");
        Files.writeString(routeStops, mappingHeader + "r1,s1,1,1\n");
        assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("UNSAFE_CHANGE");
        long blocked = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        String reviewHash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, blocked);
        var approval = approvals.approve(blocked, TransitProvider.SEOUL_BUS, analysis.find(blocked).fingerprint(), reviewHash,
                java.time.Instant.now().plusSeconds(3600), "reviewed");
        bootstrap.bootstrapWithApproval(TransitProvider.SEOUL_BUS, approval.id());
        var applied = approvals.find(approval.id());
        assertThat(applied.status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPLIED);
        assertThat(applied.applyRunId()).isGreaterThan(blocked);
        assertThat(history.find(blocked).getStatus().name()).isEqualTo("FAILED");
        assertThat(history.find(applied.applyRunId()).getStatus().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from bus_routes where provider='SEOUL_BUS' and external_route_id='r2'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bus_stops where provider='SEOUL_BUS' and external_stop_id='s2'", Integer.class)).isZero();
        assertThatThrownBy(() -> bootstrap.bootstrapWithApproval(TransitProvider.SEOUL_BUS, approval.id())).hasMessageContaining("APPROVAL_MISMATCH");
    }

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
        var bootstrap = new BusMetadataBootstrapService(List.of(source), sync, history, archive, analysis, review, approvals, Clock.systemUTC());
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
        var reviewPage = review.find(failed, 0, 20);
        assertThat(reviewPage.fingerprint()).isEqualTo(analysis.find(failed).fingerprint());
        assertThat(reviewPage.details()).extracting(TransitMetadataUnsafeChangeDetails.Detail::reason).contains(
                BusMetadataSafetyGuard.Reason.ROUTE_REMOVAL, BusMetadataSafetyGuard.Reason.OCCURRENCE_REMOVAL,
                BusMetadataSafetyGuard.Reason.STOP_CLEANUP);
        var summary = TransitRawMetadataDiff.parse(analysis.find(failed).summaryJson());
        assertThat(summary.path("routes").path("removed").asInt()).isEqualTo(1);
        assertThat(summary.path("stops").path("removed").asInt()).isEqualTo(1);
        assertThat(summary.path("occurrences").path("removed").asInt()).isEqualTo(1);
        assertThat(rows("bus_routes", "id")).isEqualTo(beforeRoutes);
        assertThat(rows("bus_stops", "id")).isEqualTo(beforeStops);
        assertThat(rows("bus_route_stop_occurrences", "id")).isEqualTo(beforeOccurrences);
        assertThat(rows("transit_source_metadata", "id")).isEqualTo(beforeRaw);
        assertThat(rows("bus_metadata_sync_states", "provider")).isEqualTo(beforeState);
        jdbc.execute("create trigger reject_bootstrap_review before insert on transit_metadata_unsafe_change_details for each row signal sqlstate '45000' set message_text = 'test failure'");
        try {
            assertThatThrownBy(() -> bootstrap.bootstrap(TransitProvider.SEOUL_BUS)).hasMessageContaining("code=REVIEW_SAVE_FAILED");
        } finally { jdbc.execute("drop trigger reject_bootstrap_review"); }
        long storageFailure = jdbc.queryForObject("select max(id) from transit_metadata_collection_runs", Long.class);
        assertThat(review.find(storageFailure, 0, 20).total()).isZero();
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
        assertThat(review.find(retry, 0, 20).total()).isZero();
        assertThat(analysis.find(failed)).isNotNull();
        assertThat(jdbc.queryForObject("select route_number from bus_routes where external_route_id='r1'", String.class)).isEqualTo("변경");
    }
    private List<java.util.Map<String, Object>> rows(String table, String order) {
        return jdbc.queryForList("select * from " + table + " order by " + order);
    }
}
