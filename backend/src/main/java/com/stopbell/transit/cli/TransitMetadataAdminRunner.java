package com.stopbell.transit.cli;

import java.time.Instant;
import java.nio.file.Path;

import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.service.TransitMetadataSnapshotPreviewService;
import org.springframework.core.io.FileSystemResource;
import org.flywaydb.core.Flyway;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.BusMetadataBootstrapService;
import com.stopbell.transit.service.TransitMetadataChangeApprovalService;
import com.stopbell.transit.service.TransitMetadataUnsafeChangeReviewService;
import com.stopbell.transit.service.TransitMetadataPreflightService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Explicit local one-shot command; no authenticated-operator identity is inferred. */
@Component
@Profile("local & metadata-admin")
@ConditionalOnProperty(name = "transit.metadata.admin.enabled", havingValue = "true")
public class TransitMetadataAdminRunner implements ApplicationRunner {
    private final TransitMetadataChangeApprovalService approvals;
    private final TransitMetadataUnsafeChangeReviewService reviews;
    private final BusMetadataBootstrapService bootstrap;
    private final TransitMetadataPreflightService preflight;
    private final TransitMetadataSnapshotPreviewService preview;
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    public TransitMetadataAdminRunner(TransitMetadataChangeApprovalService approvals, TransitMetadataUnsafeChangeReviewService reviews,
            BusMetadataBootstrapService bootstrap, TransitMetadataPreflightService preflight, TransitMetadataSnapshotPreviewService preview, JdbcTemplate jdbc, Environment environment, ConfigurableApplicationContext context) {
        this.approvals = approvals; this.reviews = reviews; this.bootstrap = bootstrap; this.preflight = preflight; this.preview = preview; this.jdbc = jdbc;
        this.environment = environment; this.context = context;
    }
    @Override public void run(ApplicationArguments args) {
        try {
            if (!"none".equals(option(args, "spring.main.web-application-type"))
                    || !"true".equals(option(args, "transit.metadata.admin.enabled"))
                    || !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local & metadata-admin")))
                throw new IllegalArgumentException("Explicit local non-web administration required");
            switch (option(args, "metadata.command")) {
                case "preview" -> printPreview(args);
                case "preflight" -> printPreflight(TransitProvider.valueOf(option(args, "provider")));
                case "review" -> printReview(number(args, "run"), integer(args, "page"), integer(args, "size"));
                case "approve" -> {
                    long runId = number(args, "run");
                    printReview(runId, 0, integer(args, "size"));
                    requireConfirmation(args, "APPROVE");
                    var page = reviews.find(runId, 0, 1);
                    var approval = approvals.approve(runId, page.provider(), option(args, "result-fingerprint"),
                            option(args, "review-fingerprint"), Instant.parse(option(args, "expires-at")), option(args, "note"));
                    System.out.println(approval);
                }
                case "status" -> System.out.println(approvals.find(number(args, "approval")));
                case "revoke" -> { requireConfirmation(args, "REVOKE"); approvals.revoke(number(args, "approval")); }
                case "apply" -> {
                    requireConfirmation(args, "APPLY");
                    long id = number(args, "approval");
                    TransitProvider provider = TransitProvider.valueOf(option(args, "provider"));
                    bootstrap.bootstrapWithApproval(provider, id);
                    System.out.println(approvals.find(id));
                }
                default -> throw new IllegalArgumentException("Unknown metadata command");
            }
        } finally { context.close(); }
    }
    private void printPreview(ApplicationArguments args) {
        if (TransitProvider.valueOf(option(args, "provider")) != TransitProvider.SEOUL_BUS)
            throw new IllegalArgumentException("CLI preview supports local Seoul CSV only");
        var routes = localFile(option(args, "route-master"));
        var routeStops = localFile(option(args, "route-stop-master"));
        var stops = localFile(option(args, "stop-master"));
        int page = integer(args, "page"), size = integer(args, "size");
        if (page < 0 || size < 1 || size > 500) throw new IllegalArgumentException("Invalid detail page");
        var migrations = Flyway.configure().dataSource(jdbc.getDataSource()).load().info();
        if (migrations.pending().length != 0 || java.util.Arrays.stream(migrations.all()).anyMatch(m -> m.getState().isFailed()))
            throw new IllegalStateException("Database migrations are pending or failed; preview will not migrate");
        var source = new SeoulCsvMetadataSource(routes, routeStops, stops);
        var result = preview.preview(source, source.fetchSnapshotWithRawRows());
        var diff = result.diff();
        System.out.println("Provider: " + result.provider() + " 현재 DB: " + result.current() + " Snapshot: " + result.snapshot());
        System.out.println("Route 추가/수정/삭제 후보: " + diff.addedRoutes().size() + "/" + diff.modifiedRoutes().size() + "/" + diff.removedRoutes().size());
        System.out.println("Stop 추가/수정/cleanup/충돌: " + diff.addedStops().size() + "/" + diff.modifiedStops().size() + "/" + diff.orphanStops().size() + "/" + diff.conflictingStops().size());
        System.out.println("Occurrence 추가/수정/제거/위치 변경: " + diff.addedOccurrences().size() + "/" + diff.modifiedOccurrences().size() + "/" + diff.removedOccurrences().size() + "/" + diff.positionChanges().size());
        System.out.println("Raw 출처/종류 비교: " + result.raw());
        long detailRows = result.details().stream().mapToLong(d -> 1L + d.variants().size()).sum();
        System.out.println("Guard: " + result.safety() + " 상세 행 전체=" + detailRows + " page=" + page + " size=" + size);
        result.details().stream().flatMap(d -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of("상세: reason=" + d.reason() + " route=" + d.routeId() + " stop=" + d.stopId()
                        + " order=" + d.stopOrder() + " replacement=" + d.replacementStopId() + " variants=" + d.variants().size()),
                d.variants().stream().map(v -> "충돌 값: stop=" + d.stopId() + " " + v)))
                .skip((long) page * size).limit(size).forEach(System.out::println);
        System.out.println("상세는 동일한 입력으로 --page와 --size를 변경해 조회합니다. 현재 DB와 입력이 바뀌면 결과도 달라질 수 있습니다.");
        System.out.println("실제 적용하지 않았습니다. 현재 DB는 마지막 성공 Snapshot과 다를 수 있으며 실제 적용 시 Guard를 다시 실행해야 합니다.");
    }
    private static FileSystemResource localFile(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute() || !java.nio.file.Files.isRegularFile(path) || !java.nio.file.Files.isReadable(path))
            throw new IllegalArgumentException("Readable absolute local file required");
        return new FileSystemResource(path);
    }
    private void printPreflight(TransitProvider provider) {
        var result = preflight.inspect(provider);
        System.out.println("Provider: " + result.provider() + "  로컬 결과: " + result.status());
        System.out.println("정규화: " + result.normalized() + " route_type: " + result.routeTypes());
        System.out.println("원본 (행의 존재는 최신성을 의미하지 않음): " + result.sources());
        System.out.println("원본 수집일 분포: " + result.collectedDistribution());
        System.out.println("수집 이력: " + result.runs() + " lastCompleteSyncAt: " + result.syncState());
        System.out.println("최신 실행 아카이브: " + result.latestRunId() + " " + result.archives());
        System.out.println("위험 변경: " + result.unsafeRuns() + " 상세 저장 실패: " + result.reviewFailures() + " 검토 대기: " + result.pendingReviews());
        System.out.println("승인: " + result.approvals());
        result.warnings().forEach(w -> System.out.println("확인: " + w));
        System.out.println("READY는 로컬 조건만 뜻합니다. Provider 최신성, API 가용성, 메모리 사용량 및 공식 유형 매핑은 검증하지 않았습니다.");
    }
    private void printReview(long runId, int page, int size) {
        var run = jdbc.queryForMap("select r.provider,r.status,r.failure_code,a.result_fingerprint,a.review_fingerprint,a.summary from transit_metadata_collection_runs r join transit_metadata_diff_analyses a on a.collection_run_id=r.id where r.id=?", runId);
        System.out.println("run=" + runId + " " + run);
        var result = reviews.find(runId, page, size);
        System.out.println("total=" + result.total() + " page=" + page + " size=" + size);
        result.details().forEach(System.out::println);
    }
    private static void requireConfirmation(ApplicationArguments args, String expected) {
        if (!expected.equals(option(args, "confirm"))) throw new IllegalArgumentException("Explicit confirmation required");
    }
    private static long number(ApplicationArguments args, String name) {
        long value = Long.parseLong(option(args, name));
        if (value <= 0) throw new IllegalArgumentException("Positive ID required");
        return value;
    }
    private static int integer(ApplicationArguments args, String name) {
        return Integer.parseInt(option(args, name));
    }
    private static String option(ApplicationArguments args, String name) {
        var values = args.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) throw new IllegalArgumentException("Explicit option required: " + name);
        return values.getFirst();
    }
}
