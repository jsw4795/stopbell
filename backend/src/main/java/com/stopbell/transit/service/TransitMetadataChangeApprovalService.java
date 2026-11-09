package com.stopbell.transit.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import com.stopbell.transit.domain.TransitProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, one-use authorization for a particular verified pre-apply analysis. */
@Service
public class TransitMetadataChangeApprovalService {
    public enum Status { APPROVED, CLAIMED, APPLIED, FAILED, UNCERTAIN, REVOKED }
    public record Approval(long id, long sourceRunId, TransitProvider provider, String resultFingerprint,
            String reviewFingerprint, Instant approvedAt, Instant expiresAt, String note, Status status,
            Long applyRunId, Instant claimedAt, Instant finishedAt) { }
    private record Evidence(TransitProvider provider, String resultFingerprint, String reviewFingerprint) { }
    private final JdbcTemplate jdbc;
    private final TransitMetadataUnsafeChangeReviewService reviews;
    private final Clock clock;

    public TransitMetadataChangeApprovalService(JdbcTemplate jdbc, TransitMetadataUnsafeChangeReviewService reviews,
            @Qualifier("transitMetadataClock") Clock clock) {
        this.jdbc = jdbc; this.reviews = reviews; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Approval approve(long sourceRunId, TransitProvider provider, String resultFingerprint,
            String reviewFingerprint, Instant expiresAt, String note) {
        requireFingerprint(resultFingerprint); requireFingerprint(reviewFingerprint);
        Objects.requireNonNull(provider); Objects.requireNonNull(expiresAt);
        Instant now = now();
        expiresAt = expiresAt.truncatedTo(ChronoUnit.MICROS);
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("Approval must expire in the future");
        if (note == null || note.isBlank() || note.length() > 1000) throw new IllegalArgumentException("Approval note required");
        Evidence source = evidence(sourceRunId, "FAILED", true);
        match(source, provider, resultFingerprint, reviewFingerprint);
        jdbc.update("insert into transit_metadata_change_approvals (source_run_id,provider,result_fingerprint,review_fingerprint,approved_at,expires_at,approval_note,status) values (?,?,?,?,?,?,?,'APPROVED')",
                sourceRunId, provider.name(), resultFingerprint, reviewFingerprint, Timestamp.from(now), Timestamp.from(expiresAt), note);
        Long id = jdbc.queryForObject("select id from transit_metadata_change_approvals where source_run_id = ?", Long.class, sourceRunId);
        return find(id);
    }

    @Transactional(readOnly = true)
    public Approval find(long id) { return find(id, false); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revoke(long id) {
        Approval a = find(id, true);
        if (a.status() != Status.APPROVED) throw new IllegalStateException("Approval is not available for revocation");
        jdbc.update("update transit_metadata_change_approvals set status='REVOKED', finished_at=? where id=?", Timestamp.from(now()), id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claim(long approvalId, long applyRunId, TransitProvider provider) {
        Approval a = find(approvalId, true);
        if (a.status() != Status.APPROVED || !a.expiresAt().isAfter(now())) throw new IllegalStateException("Approval unavailable or expired");
        if (a.sourceRunId() == applyRunId) throw new IllegalArgumentException("New collection run required");
        match(evidence(a.sourceRunId(), "FAILED", true), a.provider(), a.resultFingerprint(), a.reviewFingerprint());
        match(evidence(applyRunId, "COLLECTED", true), a.provider(), a.resultFingerprint(), a.reviewFingerprint());
        if (a.provider() != provider) throw new IllegalArgumentException("Provider mismatch");
        jdbc.update("update transit_metadata_change_approvals set status='CLAIMED', apply_run_id=?, claimed_at=? where id=?",
                applyRunId, Timestamp.from(now()), approvalId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(long approvalId, long applyRunId, Status status) {
        if (status != Status.APPLIED && status != Status.FAILED && status != Status.UNCERTAIN)
            throw new IllegalArgumentException("Invalid terminal status");
        Approval a = find(approvalId, true);
        if (a.status() != Status.CLAIMED || !Objects.equals(a.applyRunId(), applyRunId))
            throw new IllegalStateException("Approval is not claimed by this run");
        if (status == Status.APPLIED) {
            String runStatus = jdbc.queryForObject("select status from transit_metadata_collection_runs where id=? for update", String.class, applyRunId);
            if (!"COMPLETED".equals(runStatus)) throw new IllegalStateException("Completed collection run required");
        }
        jdbc.update("update transit_metadata_change_approvals set status=?, finished_at=? where id=?", status.name(), Timestamp.from(now()), approvalId);
    }

    private Evidence evidence(long runId, String requiredStatus, boolean lock) {
        var rows = jdbc.query("select r.provider,r.status,r.failure_code,a.provider,a.result_fingerprint,a.review_fingerprint,a.summary,a.fingerprint_version from transit_metadata_collection_runs r join transit_metadata_diff_analyses a on a.collection_run_id=r.id where r.id=?" + (lock ? " for update" : ""),
                (rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8)}, runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Collection run or analysis not found");
        Object[] row = rows.getFirst();
        if (!requiredStatus.equals(row[1]) || (requiredStatus.equals("FAILED") && !"UNSAFE_CHANGE".equals(row[2])))
            throw new IllegalStateException("Collection run is not eligible");
        if (!Objects.equals(row[0], row[3]) || row[5] == null) throw new IllegalStateException("Analysis binding invalid");
        TransitProvider provider = TransitProvider.valueOf((String) row[0]);
        var page = reviews.find(runId, 0, Integer.MAX_VALUE);
        if (page.total() == 0 || page.total() != page.details().size()
                || !row[5].equals(TransitMetadataUnsafeChangeDetails.fingerprint(page.details())))
            throw new IllegalStateException("Review evidence incomplete or modified");
        Map<BusMetadataSafetyGuard.Reason, Long> actual = new EnumMap<>(BusMetadataSafetyGuard.Reason.class);
        page.details().forEach(d -> actual.merge(d.reason(), 1L, Long::sum));
        var summary = TransitRawMetadataDiff.parse((String) row[6]);
        if (actual.getOrDefault(BusMetadataSafetyGuard.Reason.ROUTE_REMOVAL, 0L) != summary.path("routes").path("removed").asLong()
                || actual.getOrDefault(BusMetadataSafetyGuard.Reason.OCCURRENCE_REMOVAL, 0L) != summary.path("occurrences").path("removed").asLong()
                || actual.getOrDefault(BusMetadataSafetyGuard.Reason.STOP_CLEANUP, 0L) != summary.path("stops").path("removed").asLong()
                || actual.getOrDefault(BusMetadataSafetyGuard.Reason.SHARED_STOP_CONFLICT, 0L) != summary.path("stops").path("conflicts").asLong())
            throw new IllegalStateException("Review evidence does not match summary");
        if ((int) row[7] < 2 && actual.containsKey(BusMetadataSafetyGuard.Reason.SHARED_STOP_CONFLICT))
            throw new IllegalStateException("Legacy conflicting-stop analysis lacks apply-order binding");
        if ((int) row[7] < 3)
            throw new IllegalStateException("Legacy analysis lacks route type binding; recollect and review");
        return new Evidence(provider, (String) row[4], (String) row[5]);
    }

    private static void match(Evidence evidence, TransitProvider provider, String result, String review) {
        if (evidence.provider() != provider || !evidence.resultFingerprint().equals(result) || !evidence.reviewFingerprint().equals(review))
            throw new IllegalArgumentException("Approval evidence mismatch");
    }
    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Full fingerprint required");
    }
    private Approval find(long id, boolean lock) {
        var rows = jdbc.query("select id,source_run_id,provider,result_fingerprint,review_fingerprint,approved_at,expires_at,approval_note,status,apply_run_id,claimed_at,finished_at from transit_metadata_change_approvals where id=?" + (lock ? " for update" : ""), (rs, n) ->
                new Approval(rs.getLong(1), rs.getLong(2), TransitProvider.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), rs.getString(8), Status.valueOf(rs.getString(9)),
                        (Long) rs.getObject(10), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(),
                        rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant()), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Approval not found");
        return rows.getFirst();
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
