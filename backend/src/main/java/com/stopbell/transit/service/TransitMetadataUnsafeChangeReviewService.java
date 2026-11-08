package com.stopbell.transit.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.stopbell.transit.domain.TransitProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores blocked review evidence atomically, without applying metadata changes. */
@Service
public class TransitMetadataUnsafeChangeReviewService {
    private final JdbcTemplate jdbc;
    public TransitMetadataUnsafeChangeReviewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Page(long runId, TransitProvider provider, String fingerprint, long total,
            List<TransitMetadataUnsafeChangeDetails.Detail> details) {
        public Page { details = List.copyOf(details); }
    }
    private record Binding(TransitProvider provider, String status, String fingerprint, String reviewFingerprint) { }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(long runId, TransitMetadataDiffAnalysisService.Analysis analysis, BusMetadataSafetyGuard.Result guard) {
        Objects.requireNonNull(analysis); Objects.requireNonNull(guard);
        var binding = binding(runId, true);
        var stored = Objects.requireNonNull(analysis.stored());
        if (stored.runId() != runId || binding.provider() != stored.provider() || binding.provider() != analysis.normalized().provider()
                || !binding.fingerprint().equals(stored.fingerprint())) throw new IllegalArgumentException("Analysis does not match collection run");
        if (!guard.equals(new BusMetadataSafetyGuard().evaluate(analysis)) || guard.decision() != BusMetadataSafetyGuard.Decision.BLOCK)
            throw new IllegalArgumentException("A matching blocked guard result is required");
        var details = analysis.details();
        if (details.isEmpty() || !Objects.equals(binding.reviewFingerprint(), TransitMetadataUnsafeChangeDetails.fingerprint(details)))
            throw new IllegalArgumentException("Review evidence does not match analysis");
        long existing = count(runId);
        if (existing > 0) {
            var previous = read(runId, binding.provider(), 0, Integer.MAX_VALUE);
            if (!previous.equals(details)) throw new IllegalStateException("Conflicting review evidence for collection run");
            return;
        }
        if (!binding.status().equals("COLLECTED")) throw new IllegalStateException("Collection run is not COLLECTED");
        int sequence = 0;
        for (var detail : details) {
            int index = sequence++;
            jdbc.update("insert into transit_metadata_unsafe_change_details (collection_run_id, sequence_number, reason, route_id, stop_id, stop_order, replacement_stop_id) values (?, ?, ?, ?, ?, ?, ?)",
                    runId, index, detail.reason().name(), detail.routeId(), detail.stopId(), detail.stopOrder(), detail.replacementStopId());
            Long id = jdbc.queryForObject("select id from transit_metadata_unsafe_change_details where collection_run_id = ? and sequence_number = ?", Long.class, runId, index);
            int variantIndex = 0;
            for (var variant : detail.variants()) jdbc.update("insert into transit_metadata_unsafe_change_variants (detail_id, sequence_number, route_id, stop_order, stop_name, latitude, longitude) values (?, ?, ?, ?, ?, ?, ?)",
                    id, variantIndex++, variant.routeId(), variant.stopOrder(), variant.stopName(),
                    variant.latitude() == null ? null : variant.latitude().toPlainString(), variant.longitude() == null ? null : variant.longitude().toPlainString());
        }
    }

    @Transactional(readOnly = true)
    public Page find(long runId, int page, int size) {
        if (page < 0 || size <= 0) throw new IllegalArgumentException("Invalid page");
        long offset = Math.multiplyExact((long) page, size);
        var binding = binding(runId, false);
        return new Page(runId, binding.provider(), binding.fingerprint(), count(runId), read(runId, binding.provider(), offset, size));
    }
    private Binding binding(long runId, boolean lock) {
        var rows = jdbc.query("select r.provider, r.status, a.provider, a.result_fingerprint, a.review_fingerprint from transit_metadata_collection_runs r join transit_metadata_diff_analyses a on a.collection_run_id = r.id where r.id = ?" + (lock ? " for update" : ""),
                (rs, n) -> {
                    if (!rs.getString(1).equals(rs.getString(3))) throw new IllegalStateException("Provider mismatch");
                    return new Binding(TransitProvider.valueOf(rs.getString(1)), rs.getString(2), rs.getString(4), rs.getString(5));
                }, runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Collection run or analysis not found");
        return rows.getFirst();
    }
    private long count(long runId) {
        return jdbc.queryForObject("select count(*) from transit_metadata_unsafe_change_details where collection_run_id = ?", Long.class, runId);
    }
    private List<TransitMetadataUnsafeChangeDetails.Detail> read(long runId, TransitProvider provider, long offset, int size) {
        record Row(long id, BusMetadataSafetyGuard.Reason reason, String route, String stop, Integer order, String replacement) { }
        List<Row> rows = jdbc.query("select id, reason, route_id, stop_id, stop_order, replacement_stop_id from transit_metadata_unsafe_change_details where collection_run_id = ? order by sequence_number limit ? offset ?",
                (rs, n) -> new Row(rs.getLong(1), BusMetadataSafetyGuard.Reason.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), (Integer) rs.getObject(5), rs.getString(6)), runId, size, offset);
        List<TransitMetadataUnsafeChangeDetails.Detail> result = new ArrayList<>();
        for (var row : rows) {
            var variants = jdbc.query("select route_id, stop_order, stop_name, latitude, longitude from transit_metadata_unsafe_change_variants where detail_id = ? order by sequence_number",
                    (rs, n) -> new TransitMetadataUnsafeChangeDetails.Variant(rs.getString(1), rs.getInt(2), rs.getString(3),
                            rs.getString(4) == null ? null : new BigDecimal(rs.getString(4)), rs.getString(5) == null ? null : new BigDecimal(rs.getString(5))), row.id());
            result.add(new TransitMetadataUnsafeChangeDetails.Detail(provider, row.reason(), row.route(), row.stop(), row.order(), row.replacement(), variants));
        }
        return List.copyOf(result);
    }
}
