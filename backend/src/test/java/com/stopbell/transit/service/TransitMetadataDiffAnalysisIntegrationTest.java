package com.stopbell.transit.service;

import static com.stopbell.transit.domain.TransitProvider.*;
import static com.stopbell.transit.domain.TransitSourceDataKind.*;
import com.stopbell.transit.domain.TransitSourceSystem;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import com.stopbell.support.SharedMySqlExtension;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.metadata.TagoMetadataSource;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.metadata.GbisCollectedSnapshot;
import com.stopbell.transit.metadata.GbisBulkMetadata;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class TransitMetadataDiffAnalysisIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { SharedMySqlExtension.database(registry, "diff_analysis"); }
    @Autowired TransitMetadataDiffAnalysisService analysis;
    @Autowired TransitMetadataCollectionHistoryService history;
    @Autowired BusMetadataSyncService sync;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransitMetadataUnsafeChangeReviewService review;
    @Autowired TransitMetadataChangeApprovalService approvals;

    @BeforeEach void clear() {
        jdbc.update("delete from transit_metadata_change_approvals");
        jdbc.update("delete from transit_metadata_unsafe_change_variants");
        jdbc.update("delete from transit_metadata_unsafe_change_details");
        jdbc.update("delete from transit_metadata_diff_analyses");
        jdbc.update("delete from transit_source_metadata");
        jdbc.update("delete from bus_route_stop_occurrences");
        jdbc.update("delete from bus_routes");
        jdbc.update("delete from bus_stops");
        jdbc.update("delete from bus_metadata_sync_states");
    }
    private TagoMetadataSource.TagoSnapshot snapshot(String number, String rawExtra) {
        var occurrences = List.of(new BusStopOccurrenceMetadataSnapshot("a", "A", null, null, 1, "Destination"),
                new BusStopOccurrenceMetadataSnapshot("a", "A", null, null, 2, null));
        var route = new BusRouteMetadataSnapshot(TAGO, "r", number, "31010", occurrences);
        return new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, List.of(route)), List.of(
                raw(ROUTE, "r", null, "{\"routeid\":\"r\",\"extra\":\"" + rawExtra + "\"}"),
                raw(OCCURRENCE, "1:r:1", 1, "{\"nodeid\":\"a\",\"nodeord\":1}"),
                raw(OCCURRENCE, "1:r:2", 2, "{\"nodeid\":\"a\",\"nodeord\":2}")));
    }
    private static TagoMetadataSource.RawSourceRow raw(TransitSourceDataKind kind, String key, Integer order, String json) {
        return new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, kind, key, "r", order, TransitRawMetadataDiff.parse(json));
    }
    private long collected() { long id = history.start(TAGO).getId(); history.collected(id); return id; }

    @Test void seoulSourceIncludesOutOfScopeRawRows() {
        var source = new SeoulCsvMetadataSource(
                resource("노선ID,노선명,노선유형,거리\nr,1,지선,1\nx,2,경기,1\n"),
                resource("노선ID,노드ID,링크거리누계,정류장순번\nr,a,0,1\nx,b,0,1\n"),
                resource("정류장ID,정류장명,정류장유형,정류장번호,좌표X,좌표Y,BIT설치여부\na,A,일반차로,1,127,37,미설치\nb,B,일반차로,2,127,37,미설치\n"));
        var snapshot = source.fetchSnapshotWithRawRows();
        long run = history.start(SEOUL_BUS).getId(); history.collected(run);
        var result = analysis.analyzeAndStore(run, source, snapshot);
        assertThat(result.normalized().addedRoutes()).containsExactly("r");
        assertThat(TransitRawMetadataDiff.parse(result.stored().summaryJson()).path("sources").path("SEOUL_TDATA").path("added").asInt()).isEqualTo(6);
    }
    private static ByteArrayResource resource(String text) { return new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)); }

    @Test void freshBaselineStoresAndRetriesWithoutTouchingMetadata() {
        long run = collected();
        var first = analysis.analyzeAndStore(run, snapshot("1", "A"));
        assertThat(first.normalized().addedRoutes()).containsExactly("r");
        assertThat(first.normalized().addedStops()).containsExactly("a");
        assertThat(first.normalized().addedOccurrences()).hasSize(2);
        assertThat(first.stored().previousCompleteSyncAt()).isNull();
        assertThat(first.stored().exactPreviousSnapshotGuaranteed()).isFalse();
        assertThat(TransitRawMetadataDiff.parse(first.stored().summaryJson()).path("sources").path("GBIS").path("compared").asBoolean()).isFalse();
        assertThat(analysis.analyzeAndStore(run, snapshot("1", "A")).stored()).isEqualTo(first.stored());
        assertThatThrownBy(() -> analysis.analyzeAndStore(run, snapshot("2", "A"))).isInstanceOf(IllegalStateException.class);
        assertThat(history.find(run).getStatus().name()).isEqualTo("COLLECTED");
        assertThat(jdbc.queryForObject("select count(*) from bus_routes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from transit_metadata_diff_analyses where collection_run_id = ?", Integer.class, run)).isOne();
    }
    @Test void readsCurrentProviderAndReportsRawOnlyChange() {
        var old = snapshot("1", "A");
        sync.syncTagoSnapshot(old, Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan'),('SEOUL_BUS','a','other')");
        long run = collected();
        var result = analysis.analyzeAndStore(run, snapshot("1", "B"));
        assertThat(result.normalized().unchangedRoutes()).containsExactly("r");
        assertThat(result.normalized().unchangedOccurrences()).hasSize(2);
        assertThat(result.normalized().unchangedStops()).containsExactly("a");
        assertThat(result.normalized().orphanStops()).contains("orphan");
        assertThat(result.stored().previousCompleteSyncAt()).isNotNull();
        assertThat(result.stored().exactPreviousSnapshotGuaranteed()).isFalse();
        var raw = TransitRawMetadataDiff.parse(result.stored().summaryJson()).path("sources").path("TAGO");
        assertThat(raw.path("modified").asInt()).isEqualTo(1);
        assertThat(raw.path("unchanged").asInt()).isEqualTo(2);
        jdbc.update("update transit_source_metadata set collected_at = '2026-02-01 00:00:00', updated_at = '2026-02-01 00:00:00' where source_system = 'TAGO'");
        assertThat(analysis.analyzeAndStore(run, snapshot("1", "B")).stored()).isEqualTo(result.stored());
    }
    @Test void rejectsWrongProviderInvalidStateAndIncompleteSnapshot() {
        long pending = history.start(TAGO).getId();
        assertThatThrownBy(() -> analysis.analyzeAndStore(pending, snapshot("1", "A"))).isInstanceOf(IllegalStateException.class);
        long other = history.start(SEOUL_BUS).getId(); history.collected(other);
        assertThatThrownBy(() -> analysis.analyzeAndStore(other, snapshot("1", "A"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analysis.analyzeAndStore(-1, snapshot("1", "A"))).isInstanceOf(IllegalArgumentException.class);
        long run = collected(); var valid = snapshot("1", "A");
        assertThatThrownBy(() -> analysis.analyzeAndStore(run, new TagoMetadataSource.TagoSnapshot(valid.normalized(), List.of()))).isInstanceOf(IllegalStateException.class);
        assertThat(analysis.find(run)).isNull();
        var emptyRoute = new BusRouteMetadataSnapshot(TAGO, "empty", "1", "31010", List.of());
        var incomplete = new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, List.of(emptyRoute)),
                List.of(new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, ROUTE, "empty", "empty", null,
                        TransitRawMetadataDiff.parse("{\"routeid\":\"empty\"}"))));
        assertThatThrownBy(() -> analysis.analyzeAndStore(run, incomplete)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("without occurrences");
        assertThat(analysis.find(run)).isNull();
    }
    @Test void gbisOnlyRowsAreComparedSeparatelyAndVersionIsTracked() {
        var initial = withGbis(snapshot("1", "A"), "v1");
        sync.syncTagoSnapshot(initial, Instant.parse("2026-01-01T00:00:00Z"));
        long run = collected();
        var result = analysis.analyzeAndStore(run, withGbis(snapshot("1", "A"), "v2"));
        assertThat(result.normalized().addedRoutes()).isEmpty();
        var gbis = TransitRawMetadataDiff.parse(result.stored().summaryJson()).path("sources").path("GBIS");
        assertThat(gbis.path("unchanged").asInt()).isEqualTo(2);
        assertThat(gbis.path("versionChanged").asInt()).isEqualTo(2);
    }
    private static TagoMetadataSource.TagoSnapshot withGbis(TagoMetadataSource.TagoSnapshot tago, String version) {
        var route = new GbisBulkMetadata.Route("999", "999", 1, List.of(new GbisBulkMetadata.Station(1, "s", "Stop")));
        var rows = List.of(new GbisCollectedSnapshot.RawRow(TransitSourceSystem.GBIS, ROUTE, "999", "999", null,
                        Map.of("routeId", "999", "routeName", "999", "turnSeq", "1")),
                new GbisCollectedSnapshot.RawRow(TransitSourceSystem.GBIS, OCCURRENCE, "3:999:1", "999", 1,
                        Map.of("routeId", "999", "routeName", "999", "staOrder", "1", "stationId", "s", "stationName", "Stop")));
        return new TagoMetadataSource.TagoSnapshot(tago.normalized(), tago.rawRows(), new GbisCollectedSnapshot(new GbisBulkMetadata(Map.of("999", route)), rows, version));
    }

    @Test void missingGbisCollectionDoesNotMarkExistingGbisRowsAbsent() {
        sync.syncTagoSnapshot(withGbis(snapshot("1", "A"), "v1"), Instant.parse("2026-01-01T00:00:00Z"));
        var result = analysis.analyzeAndStore(collected(), snapshot("1", "A"));
        var gbis = TransitRawMetadataDiff.parse(result.stored().summaryJson()).path("sources").path("GBIS");
        assertThat(gbis.path("compared").asBoolean()).isFalse();
        assertThat(gbis.path("absent").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from transit_source_metadata where source_system='GBIS'", Integer.class)).isEqualTo(2);
    }
    @Test void priorTimeDoesNotProveCurrentStateMatchesSuccessfulSnapshot() {
        jdbc.update("insert into bus_metadata_sync_states(provider,last_complete_sync_at) values ('TAGO','2026-01-01 00:00:00')");
        jdbc.update("insert into bus_routes(provider,external_route_id,route_number,city_code) values ('TAGO','partial','9','31010')");
        var result = analysis.analyzeAndStore(collected(), snapshot("1", "A"));
        assertThat(result.normalized().removedRoutes()).containsExactly("partial");
        assertThat(result.stored().previousCompleteSyncAt()).isNotNull();
        assertThat(result.stored().exactPreviousSnapshotGuaranteed()).isFalse();
    }
    @Test void currentRowsWithoutPriorCompleteAreNotAssumedFresh() {
        jdbc.update("insert into bus_routes(provider,external_route_id,route_number,city_code) values ('TAGO','partial','9','31010')");
        var result = analysis.analyzeAndStore(collected(), snapshot("1", "A"));
        assertThat(result.normalized().removedRoutes()).containsExactly("partial");
        assertThat(result.stored().previousCompleteSyncAt()).isNull();
        assertThat(result.stored().exactPreviousSnapshotGuaranteed()).isFalse();
    }

    @Test void saveFailureRollsBackAndLeavesRunAndMetadataUntouched() {
        long run = collected();
        jdbc.execute("create trigger reject_diff_insert before insert on transit_metadata_diff_analyses for each row signal sqlstate '45000' set message_text = 'test failure'");
        try {
            assertThatThrownBy(() -> analysis.analyzeAndStore(run, snapshot("1", "A"))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { jdbc.execute("drop trigger reject_diff_insert"); }
        assertThat(analysis.find(run)).isNull();
        assertThat(history.find(run).getStatus().name()).isEqualTo("COLLECTED");
        assertThat(jdbc.queryForObject("select count(*) from bus_routes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from bus_metadata_sync_states", Integer.class)).isZero();
    }

    @Test void concurrentIdenticalRequestsSerialize() throws Exception {
        long run = collected(); CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<TransitMetadataDiffAnalysisService.Stored> task = () -> { start.await(); return analysis.analyzeAndStore(run, snapshot("1", "A")).stored(); };
            var a = pool.submit(task); var b = pool.submit(task); start.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(b.get(30, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("select count(*) from transit_metadata_diff_analyses where collection_run_id = ?", Integer.class, run)).isOne();
        }
    }
    @Test @org.junit.jupiter.api.DisplayName("차단 상세는 분석에 연결되어 페이지 조회와 멱등 재시도를 지원한다")
    void stores_and_pages_blocked_review_evidence() {
        sync.syncTagoSnapshot(snapshot("1", "A"), Instant.parse("2026-01-01T00:00:00Z"));
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long run = collected();
        var route = new BusRouteMetadataSnapshot(TAGO, "r", "1", "31010", List.of(
                new BusStopOccurrenceMetadataSnapshot("b", "B", null, null, 1),
                new BusStopOccurrenceMetadataSnapshot("a", "A", null, null, 2)));
        var changed = new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, List.of(route)), List.of(
                raw(ROUTE, "r", null, "{\"routeid\":\"r\"}"),
                raw(OCCURRENCE, "1:r:1", 1, "{\"nodeid\":\"b\",\"nodeord\":1}"),
                raw(OCCURRENCE, "1:r:2", 2, "{\"nodeid\":\"a\",\"nodeord\":2}")));
        var result = analysis.analyzeAndStore(run, changed);
        var guard = new BusMetadataSafetyGuard().evaluate(result);
        review.save(run, result, guard);
        review.save(run, result, guard);
        var page = review.find(run, 0, 1);
        assertThat(page.provider()).isEqualTo(TAGO);
        assertThat(page.fingerprint()).isEqualTo(result.stored().fingerprint());
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.details()).hasSize(1);
        assertThat(review.find(run, 1, 1).details()).hasSize(1);
        assertThat(review.find(run, 0, 10).details()).isEqualTo(result.details());
        assertThat(result.details()).anyMatch(d -> d.reason() == BusMetadataSafetyGuard.Reason.OCCURRENCE_REMOVAL
                && "r".equals(d.routeId()) && "a".equals(d.stopId()) && d.stopOrder() == 1 && "b".equals(d.replacementStopId()));
        assertThatThrownBy(() -> review.find(-1, 0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> review.save(-1, result, guard)).isInstanceOf(IllegalArgumentException.class);
        var stored = result.stored();
        var wrongFingerprint = new TransitMetadataDiffAnalysisService.Stored(run, TAGO, stored.previousCompleteSyncAt(), stored.analyzedAt(), stored.baselineType(), false, "f".repeat(64), stored.summaryJson());
        assertThatThrownBy(() -> review.save(run, new TransitMetadataDiffAnalysisService.Analysis(wrongFingerprint, result.normalized(), result.details()), guard)).isInstanceOf(IllegalArgumentException.class);
        var wrongProvider = new TransitMetadataDiffAnalysisService.Stored(run, SEOUL_BUS, stored.previousCompleteSyncAt(), stored.analyzedAt(), stored.baselineType(), false, stored.fingerprint(), stored.summaryJson());
        assertThatThrownBy(() -> review.save(run, new TransitMetadataDiffAnalysisService.Analysis(wrongProvider, result.normalized(), result.details()), guard)).isInstanceOf(IllegalArgumentException.class);
        var altered = new TransitMetadataDiffAnalysisService.Analysis(result.stored(), result.normalized(), List.of(result.details().getFirst()));
        assertThatThrownBy(() -> review.save(run, altered, guard)).isInstanceOf(IllegalArgumentException.class);
        assertThat(analysis.find(run)).isEqualTo(result.stored());
        assertThat(jdbc.queryForObject("select count(*) from bus_route_stop_occurrences", Integer.class)).isEqualTo(2);
    }

    @Test @org.junit.jupiter.api.DisplayName("상세 저장 오류는 전체 상세를 롤백한다")
    void rolls_back_review_on_failure() {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan'), ('TAGO','orphan2','orphan2')");
        long run = collected(); var result = analysis.analyzeAndStore(run, snapshot("1", "A"));
        jdbc.execute("create trigger reject_review before insert on transit_metadata_unsafe_change_details for each row begin if NEW.sequence_number = 1 then signal sqlstate '45000' set message_text = 'test failure'; end if; end");
        try {
            assertThatThrownBy(() -> review.save(run, result, new BusMetadataSafetyGuard().evaluate(result))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { jdbc.execute("drop trigger reject_review"); }
        assertThat(review.find(run, 0, 10).total()).isZero();
        assertThat(analysis.find(run)).isEqualTo(result.stored());
    }

    @Test @org.junit.jupiter.api.DisplayName("공유 정류장 충돌의 각 노선별 이름과 좌표를 저장한다")
    void stores_conflicting_stop_variants() {
        var a = new BusStopOccurrenceMetadataSnapshot("s", "First", new java.math.BigDecimal("37.1"), new java.math.BigDecimal("127.2"), 1);
        var b = new BusStopOccurrenceMetadataSnapshot("s", "Second", new java.math.BigDecimal("37.2"), new java.math.BigDecimal("127.3"), 1);
        var r = new BusRouteMetadataSnapshot(TAGO, "r", "1", "31010", List.of(a));
        var x = new BusRouteMetadataSnapshot(TAGO, "x", "2", "31010", List.of(b));
        var rawRows = List.of(raw(ROUTE, "r", null, "{\"routeid\":\"r\"}"),
                raw(OCCURRENCE, "1:r:1", 1, "{\"nodeid\":\"s\",\"nodeord\":1}"),
                new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, ROUTE, "x", "x", null, TransitRawMetadataDiff.parse("{\"routeid\":\"x\"}")),
                new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, OCCURRENCE, "1:x:1", "x", 1, TransitRawMetadataDiff.parse("{\"nodeid\":\"s\",\"nodeord\":1}")));
        long run = collected();
        var result = analysis.analyzeAndStore(run, new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, List.of(r, x)), rawRows));
        review.save(run, result, new BusMetadataSafetyGuard().evaluate(result));
        var page = review.find(run, 0, 10);
        assertThat(page.details()).isEqualTo(result.details());
        var detail = page.details().getFirst();
        assertThat(detail.reason()).isEqualTo(BusMetadataSafetyGuard.Reason.SHARED_STOP_CONFLICT);
        assertThat(detail.stopId()).isEqualTo("s");
        assertThat(detail.variants()).extracting(TransitMetadataUnsafeChangeDetails.Variant::routeId).containsExactly("r", "x");
        assertThat(detail.variants()).extracting(TransitMetadataUnsafeChangeDetails.Variant::stopName).containsExactly("First", "Second");
        assertThat(detail.variants().getFirst().latitude()).isEqualByComparingTo("37.1");
        history.fail(run, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, run);
        jdbc.update("update transit_metadata_diff_analyses set fingerprint_version=1 where collection_run_id=?", run);
        assertThatThrownBy(() -> approvals.approve(run, TAGO, result.stored().fingerprint(), hash, Instant.now().plusSeconds(300), "reviewed"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("apply-order");
        jdbc.update("update transit_metadata_diff_analyses set fingerprint_version=2 where collection_run_id=?", run);
        assertThatThrownBy(() -> approvals.approve(run, TAGO, result.stored().fingerprint(), hash, Instant.now().plusSeconds(300), "reviewed"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("route type binding");
    }

    @Test @org.junit.jupiter.api.DisplayName("공유 정류장 충돌 시 노선 처리 순서가 달라지면 분석 지문이 달라야 한다")
    void conflicting_stop_order_must_change_analysis_fingerprint() {
        var first = new BusRouteMetadataSnapshot(TAGO, "r", "1", "31010", List.of(
                new BusStopOccurrenceMetadataSnapshot("s", "First", null, null, 1)));
        var second = new BusRouteMetadataSnapshot(TAGO, "x", "2", "31010", List.of(
                new BusStopOccurrenceMetadataSnapshot("s", "Second", null, null, 1)));
        var rows = List.of(raw(ROUTE, "r", null, "{\"routeid\":\"r\"}"),
                raw(OCCURRENCE, "1:r:1", 1, "{\"nodeid\":\"s\",\"nodeord\":1}"),
                new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, ROUTE, "x", "x", null, TransitRawMetadataDiff.parse("{\"routeid\":\"x\"}")),
                new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, OCCURRENCE, "1:x:1", "x", 1, TransitRawMetadataDiff.parse("{\"nodeid\":\"s\",\"nodeord\":1}")));
        var ordered = new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, List.of(first, second)), rows);
        var reversed = new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, List.of(second, first)), rows);
        var analysisA = analysis.analyzeAndStore(collected(), ordered);
        var analysisB = analysis.analyzeAndStore(collected(), reversed);
        sync.syncTagoSnapshot(ordered, Instant.parse("2026-01-01T00:00:00Z"));
        var nameA = jdbc.queryForObject("select stop_name from bus_stops where provider='TAGO' and external_stop_id='s'", String.class);
        sync.syncTagoSnapshot(reversed, Instant.parse("2026-01-01T00:00:00Z"));
        var nameB = jdbc.queryForObject("select stop_name from bus_stops where provider='TAGO' and external_stop_id='s'", String.class);
        assertThat(nameA).isEqualTo("Second");
        assertThat(nameB).isEqualTo("First");
        assertThat(analysisA.stored().fingerprint()).isNotEqualTo(analysisB.stored().fingerprint());
    }

    @Test @org.junit.jupiter.api.DisplayName("승인 생성과 선점은 완전한 동일 근거를 검증하고 일회성으로 사용한다")
    void approves_and_claims_complete_evidence_once() {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long source = collected(); var a = analysis.analyzeAndStore(source, snapshot("1", "A"));
        review.save(source, a, new BusMetadataSafetyGuard().evaluate(a));
        history.fail(source, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String reviewHash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, source);
        var expiry = Instant.now().plusSeconds(3600);
        assertThatThrownBy(() -> approvals.approve(source, SEOUL_BUS, a.stored().fingerprint(), reviewHash, expiry, "reviewed")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> approvals.approve(source, TAGO, "a", reviewHash, expiry, "reviewed")).isInstanceOf(IllegalArgumentException.class);
        var approved = approvals.approve(source, TAGO, a.stored().fingerprint(), reviewHash, expiry, "reviewed");
        assertThat(approved.status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPROVED);
        assertThatThrownBy(() -> approvals.approve(source, TAGO, a.stored().fingerprint(), reviewHash, expiry, "reviewed")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        long target = collected(); var b = analysis.analyzeAndStore(target, snapshot("1", "A"));
        review.save(target, b, new BusMetadataSafetyGuard().evaluate(b));
        assertThatThrownBy(() -> approvals.claim(approved.id(), target, SEOUL_BUS)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("update transit_metadata_unsafe_change_details set stop_id='tampered' where collection_run_id=?", target);
        assertThatThrownBy(() -> approvals.claim(approved.id(), target, TAGO)).isInstanceOf(IllegalStateException.class);
        jdbc.update("update transit_metadata_unsafe_change_details set stop_id='orphan' where collection_run_id=?", target);
        approvals.claim(approved.id(), target, TAGO);
        assertThat(approvals.find(approved.id()).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.CLAIMED);
        assertThatThrownBy(() -> approvals.claim(approved.id(), target, TAGO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approvals.revoke(approved.id())).isInstanceOf(IllegalStateException.class);
        approvals.finish(approved.id(), target, TransitMetadataChangeApprovalService.Status.FAILED);
        assertThat(approvals.find(approved.id()).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.FAILED);
    }

    @Test @org.junit.jupiter.api.DisplayName("훼손된 검토 상세는 승인할 수 없다")
    void rejects_tampered_evidence() {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long run = collected(); var a = analysis.analyzeAndStore(run, snapshot("1", "A"));
        review.save(run, a, new BusMetadataSafetyGuard().evaluate(a));
        history.fail(run, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, run);
        jdbc.update("update transit_metadata_unsafe_change_details set stop_id='tampered' where collection_run_id=?", run);
        assertThatThrownBy(() -> approvals.approve(run, TAGO, a.stored().fingerprint(), hash, Instant.now().plusSeconds(3600), "reviewed"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test @org.junit.jupiter.api.DisplayName("같은 승인의 동시 선점은 한 실행만 성공한다")
    void only_one_concurrent_claim_succeeds() throws Exception {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long source = collected(); var initial = analysis.analyzeAndStore(source, snapshot("1", "A"));
        review.save(source, initial, new BusMetadataSafetyGuard().evaluate(initial));
        history.fail(source, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, source);
        var approval = approvals.approve(source, TAGO, initial.stored().fingerprint(), hash, Instant.now().plusSeconds(3600), "reviewed");
        long one = collected(), two = collected();
        var a = analysis.analyzeAndStore(one, snapshot("1", "A")); var b = analysis.analyzeAndStore(two, snapshot("1", "A"));
        review.save(one, a, new BusMetadataSafetyGuard().evaluate(a)); review.save(two, b, new BusMetadataSafetyGuard().evaluate(b));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> claimOne = () -> { start.await(); try { approvals.claim(approval.id(), one, TAGO); return true; } catch (RuntimeException e) { return false; } };
            Callable<Boolean> claimTwo = () -> { start.await(); try { approvals.claim(approval.id(), two, TAGO); return true; } catch (RuntimeException e) { return false; } };
            var x = pool.submit(claimOne); var y = pool.submit(claimTwo); start.countDown();
            assertThat(List.of(x.get(30, TimeUnit.SECONDS), y.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(approvals.find(approval.id()).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.CLAIMED);
    }

    @Test @org.junit.jupiter.api.DisplayName("상세는 같고 원본만 달라진 재수집은 승인 사용을 차단한다")
    void rejects_changed_raw_even_if_review_is_identical() {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long source = collected(); var initial = analysis.analyzeAndStore(source, snapshot("1", "A"));
        review.save(source, initial, new BusMetadataSafetyGuard().evaluate(initial));
        history.fail(source, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, source);
        var approval = approvals.approve(source, TAGO, initial.stored().fingerprint(), hash, Instant.now().plusSeconds(3600), "reviewed");
        long target = collected(); var changed = analysis.analyzeAndStore(target, snapshot("1", "B"));
        review.save(target, changed, new BusMetadataSafetyGuard().evaluate(changed));
        assertThat(changed.details()).isEqualTo(initial.details());
        assertThat(changed.stored().fingerprint()).isNotEqualTo(initial.stored().fingerprint());
        assertThatThrownBy(() -> approvals.claim(approval.id(), target, TAGO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(approvals.find(approval.id()).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPROVED);
        approvals.revoke(approval.id());
        assertThatThrownBy(() -> approvals.claim(approval.id(), target, TAGO)).isInstanceOf(IllegalStateException.class);
    }

    @Test @org.junit.jupiter.api.DisplayName("승인 취소와 선점이 경쟁해도 하나의 상태 전이만 성공한다")
    void revoke_and_claim_are_mutually_exclusive() throws Exception {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long source = collected(); var initial = analysis.analyzeAndStore(source, snapshot("1", "A"));
        review.save(source, initial, new BusMetadataSafetyGuard().evaluate(initial));
        history.fail(source, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, source);
        var approval = approvals.approve(source, TAGO, initial.stored().fingerprint(), hash, Instant.now().plusSeconds(3600), "reviewed");
        long target = collected(); var next = analysis.analyzeAndStore(target, snapshot("1", "A"));
        review.save(target, next, new BusMetadataSafetyGuard().evaluate(next));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var claim = pool.submit((Callable<Boolean>) () -> { start.await(); try { approvals.claim(approval.id(), target, TAGO); return true; } catch (RuntimeException e) { return false; } });
            var revoke = pool.submit((Callable<Boolean>) () -> { start.await(); try { approvals.revoke(approval.id()); return true; } catch (RuntimeException e) { return false; } });
            start.countDown(); assertThat(List.of(claim.get(30, TimeUnit.SECONDS), revoke.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(approvals.find(approval.id()).status()).isIn(TransitMetadataChangeApprovalService.Status.CLAIMED, TransitMetadataChangeApprovalService.Status.REVOKED);
    }

    @Test @org.junit.jupiter.api.DisplayName("상세 저장 실패 실행과 만료된 승인 요청은 거부한다")
    void rejects_failed_evidence_and_past_expiry() {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long run = collected(); var a = analysis.analyzeAndStore(run, snapshot("1", "A"));
        history.fail(run, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.REVIEW_SAVE_FAILED);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, run);
        assertThatThrownBy(() -> approvals.approve(run, TAGO, a.stored().fingerprint(), hash, Instant.now().plusSeconds(300), "reviewed")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> approvals.approve(run, TAGO, a.stored().fingerprint(), hash, Instant.now().minusSeconds(1), "reviewed")).isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"route", "stop_id", "stop_name", "coordinates", "order", "destination", "raw", "database", "route_count"})
    @org.junit.jupiter.api.DisplayName("승인 이후 비교 입력이 바뀌면 새 실행의 선점을 거부한다")
    void rejects_each_changed_analysis_input(String change) {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long source = collected(); var initial = analysis.analyzeAndStore(source, snapshot("1", "A"));
        review.save(source, initial, new BusMetadataSafetyGuard().evaluate(initial));
        history.fail(source, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, source);
        var approval = approvals.approve(source, TAGO, initial.stored().fingerprint(), hash, Instant.now().plusSeconds(3600), "reviewed");
        if (change.equals("database")) jdbc.update("update bus_stops set stop_name='changed' where provider='TAGO' and external_stop_id='orphan'");
        String stop = change.equals("stop_id") ? "b" : "a";
        String name = change.equals("stop_name") ? "Changed" : "A";
        java.math.BigDecimal lat = change.equals("coordinates") ? new java.math.BigDecimal("37") : null;
        java.math.BigDecimal lon = change.equals("coordinates") ? new java.math.BigDecimal("127") : null;
        int order = change.equals("order") ? 3 : 2;
        var occurrences = List.of(new BusStopOccurrenceMetadataSnapshot(stop, name, lat, lon, 1, change.equals("destination") ? "Changed" : "Destination"),
                new BusStopOccurrenceMetadataSnapshot(stop, name, lat, lon, order, null));
        var routes = new java.util.ArrayList<BusRouteMetadataSnapshot>();
        routes.add(new BusRouteMetadataSnapshot(TAGO, "r", change.equals("route") ? "2" : "1", "31010", occurrences));
        var rows = new java.util.ArrayList<TagoMetadataSource.RawSourceRow>();
        rows.add(raw(ROUTE, "r", null, "{\"routeid\":\"r\",\"extra\":\"" + (change.equals("raw") ? "B" : "A") + "\"}"));
        rows.add(raw(OCCURRENCE, "1:r:1", 1, "{\"nodeid\":\"" + stop + "\",\"nodeord\":1}"));
        rows.add(raw(OCCURRENCE, "1:r:" + order, order, "{\"nodeid\":\"" + stop + "\",\"nodeord\":" + order + "}"));
        if (change.equals("route_count")) {
            routes.add(new BusRouteMetadataSnapshot(TAGO, "x", "2", "31010", List.of(new BusStopOccurrenceMetadataSnapshot("a", "A", null, null, 1))));
            rows.add(new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, ROUTE, "x", "x", null, TransitRawMetadataDiff.parse("{\"routeid\":\"x\"}")));
            rows.add(new TagoMetadataSource.RawSourceRow(TransitSourceSystem.TAGO, OCCURRENCE, "1:x:1", "x", 1, TransitRawMetadataDiff.parse("{\"nodeid\":\"a\",\"nodeord\":1}")));
        }
        long target = collected();
        var next = analysis.analyzeAndStore(target, new TagoMetadataSource.TagoSnapshot(new CompleteBusMetadataSnapshot(TAGO, routes), rows));
        review.save(target, next, new BusMetadataSafetyGuard().evaluate(next));
        assertThat(next.stored().fingerprint()).isNotEqualTo(initial.stored().fingerprint());
        assertThatThrownBy(() -> approvals.claim(approval.id(), target, TAGO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(approvals.find(approval.id()).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPROVED);
    }

    @Test @org.junit.jupiter.api.DisplayName("같은 실행의 동시 승인 요청은 하나의 승인만 만든다")
    void concurrent_approval_requests_create_one_row() throws Exception {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long run = collected(); var result = analysis.analyzeAndStore(run, snapshot("1", "A"));
        review.save(run, result, new BusMetadataSafetyGuard().evaluate(result));
        history.fail(run, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, run);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task = () -> { start.await(); try { approvals.approve(run, TAGO, result.stored().fingerprint(), hash, Instant.now().plusSeconds(300), "reviewed"); return true; } catch (RuntimeException e) { return false; } };
            var a = pool.submit(task); var b = pool.submit(task); start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbc.queryForObject("select count(*) from transit_metadata_change_approvals where source_run_id=?", Integer.class, run)).isOne();
    }

    @Test @org.junit.jupiter.api.DisplayName("상세가 없거나 일부 누락된 실행은 승인할 수 없다")
    void rejects_missing_or_partial_review() {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan'),('TAGO','other','other')");
        long run = collected(); var result = analysis.analyzeAndStore(run, snapshot("1", "A"));
        review.save(run, result, new BusMetadataSafetyGuard().evaluate(result));
        history.fail(run, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, run);
        jdbc.update("delete from transit_metadata_unsafe_change_details where collection_run_id=? and sequence_number=0", run);
        assertThatThrownBy(() -> approvals.approve(run, TAGO, result.stored().fingerprint(), hash, Instant.now().plusSeconds(300), "reviewed")).isInstanceOf(IllegalStateException.class);
        jdbc.update("delete from transit_metadata_unsafe_change_details where collection_run_id=?", run);
        assertThatThrownBy(() -> approvals.approve(run, TAGO, result.stored().fingerprint(), hash, Instant.now().plusSeconds(300), "reviewed")).isInstanceOf(IllegalStateException.class);
        jdbc.update("update transit_metadata_diff_analyses set review_fingerprint=null where collection_run_id=?", run);
        assertThatThrownBy(() -> approvals.approve(run, TAGO, result.stored().fingerprint(), hash, Instant.now().plusSeconds(300), "reviewed")).isInstanceOf(IllegalStateException.class);
    }

    @Test @org.junit.jupiter.api.DisplayName("만료된 승인은 새 실행에서 선점할 수 없다")
    void rejects_claim_of_expired_approval() {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long source = collected(); var result = analysis.analyzeAndStore(source, snapshot("1", "A"));
        review.save(source, result, new BusMetadataSafetyGuard().evaluate(result));
        history.fail(source, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, source);
        var approval = approvals.approve(source, TAGO, result.stored().fingerprint(), hash, Instant.now().plusSeconds(300), "reviewed");
        long target = collected(); var next = analysis.analyzeAndStore(target, snapshot("1", "A")); review.save(target, next, new BusMetadataSafetyGuard().evaluate(next));
        jdbc.update("update transit_metadata_change_approvals set approved_at=utc_timestamp(6)-interval 10 minute, expires_at=utc_timestamp(6)-interval 5 minute where id=?", approval.id());
        assertThatThrownBy(() -> approvals.claim(approval.id(), target, TAGO)).isInstanceOf(IllegalStateException.class);
        assertThat(approvals.find(approval.id()).status()).isEqualTo(TransitMetadataChangeApprovalService.Status.APPROVED);
    }

    @Test @org.junit.jupiter.api.DisplayName("같은 수집 실행의 재분석에서 DB 변경을 감지한다")
    void detects_database_change_during_reanalysis() {
        long run = collected();
        analysis.analyzeAndStore(run, snapshot("1", "A"));
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','new_orphan','new_orphan')");
        assertThatThrownBy(() -> analysis.analyzeAndStore(run, snapshot("1", "A")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Conflicting analysis");
    }

    @Test @org.junit.jupiter.api.DisplayName("관리 명령의 검토와 승인 생성은 교통 메타데이터를 변경하지 않는다")
    void cli_review_and_approval_leave_metadata_unchanged() {
        jdbc.update("insert into bus_stops(provider,external_stop_id,stop_name) values ('TAGO','orphan','orphan')");
        long run = collected(); var result = analysis.analyzeAndStore(run, snapshot("1", "A"));
        review.save(run, result, new BusMetadataSafetyGuard().evaluate(result));
        history.fail(run, com.stopbell.transit.domain.TransitCollectionFailureStage.VALIDATION, com.stopbell.transit.domain.TransitCollectionFailureCode.UNSAFE_CHANGE);
        String hash = jdbc.queryForObject("select review_fingerprint from transit_metadata_diff_analyses where collection_run_id=?", String.class, run);
        var before = jdbc.queryForList("select * from bus_stops order by id");
        var environment = org.mockito.Mockito.mock(org.springframework.core.env.Environment.class);
        org.mockito.Mockito.when(environment.acceptsProfiles(org.mockito.ArgumentMatchers.any(org.springframework.core.env.Profiles.class))).thenReturn(true);
        var context = org.mockito.Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class);
        var runner = new com.stopbell.transit.cli.TransitMetadataAdminRunner(approvals, review,
                org.mockito.Mockito.mock(BusMetadataBootstrapService.class), org.mockito.Mockito.mock(TransitMetadataPreflightService.class), org.mockito.Mockito.mock(TransitMetadataSnapshotPreviewService.class), jdbc, environment, context);
        runner.run(new org.springframework.boot.DefaultApplicationArguments("--spring.main.web-application-type=none", "--transit.metadata.admin.enabled=true", "--metadata.command=review", "--run=" + run, "--page=0", "--size=10"));
        assertThat(jdbc.queryForObject("select count(*) from transit_metadata_change_approvals", Integer.class)).isZero();
        runner.run(new org.springframework.boot.DefaultApplicationArguments("--spring.main.web-application-type=none", "--transit.metadata.admin.enabled=true", "--metadata.command=approve", "--run=" + run, "--size=10", "--result-fingerprint=" + result.stored().fingerprint(), "--review-fingerprint=" + hash, "--expires-at=" + Instant.now().plusSeconds(300), "--note=reviewed", "--confirm=APPROVE"));
        assertThat(jdbc.queryForObject("select count(*) from transit_metadata_change_approvals", Integer.class)).isOne();
        assertThat(jdbc.queryForList("select * from bus_stops order by id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from bus_routes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from transit_source_metadata", Integer.class)).isZero();
    }

}
