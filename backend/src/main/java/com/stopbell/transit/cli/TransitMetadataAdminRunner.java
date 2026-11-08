package com.stopbell.transit.cli;

import java.time.Instant;

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
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    public TransitMetadataAdminRunner(TransitMetadataChangeApprovalService approvals, TransitMetadataUnsafeChangeReviewService reviews,
            BusMetadataBootstrapService bootstrap, TransitMetadataPreflightService preflight, JdbcTemplate jdbc, Environment environment, ConfigurableApplicationContext context) {
        this.approvals = approvals; this.reviews = reviews; this.bootstrap = bootstrap; this.preflight = preflight; this.jdbc = jdbc;
        this.environment = environment; this.context = context;
    }
    @Override public void run(ApplicationArguments args) {
        try {
            if (!"none".equals(option(args, "spring.main.web-application-type"))
                    || !"true".equals(option(args, "transit.metadata.admin.enabled"))
                    || !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local & metadata-admin")))
                throw new IllegalArgumentException("Explicit local non-web administration required");
            switch (option(args, "metadata.command")) {
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
