package com.stopbell.transit.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.metadata.TagoMetadataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit pre-apply analysis; never changes the collection status or transit metadata. */
@Service
public class TransitMetadataDiffAnalysisService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final BusMetadataCurrentStateReader reader;
    private final BusMetadataSyncService sync;
    private final Clock clock;

    public TransitMetadataDiffAnalysisService(JdbcTemplate jdbc, BusMetadataCurrentStateReader reader,
            BusMetadataSyncService sync, @Qualifier("transitMetadataClock") Clock clock) {
        this.jdbc = jdbc; this.reader = reader; this.sync = sync; this.clock = clock;
    }
    public record Counts(int added, int modified, int removed, int unchanged, int conflicts, int positions) { }
    public record Summary(Counts routes, Counts stops, Counts occurrences, Map<TransitSourceSystem, TransitRawMetadataDiff.Counts> sources) { }
    public record Stored(long runId, TransitProvider provider, Instant previousCompleteSyncAt, Instant analyzedAt,
                         String baselineType, boolean exactPreviousSnapshotGuaranteed, String fingerprint, String summaryJson) { }
    public record Analysis(Stored stored, BusMetadataDiffService.Result normalized) { }

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Analysis analyzeAndStore(long runId, SeoulCsvMetadataSource seoul, SeoulCsvMetadataSource.SeoulCsvSnapshot snapshot) {
        lockCollected(runId, TransitProvider.SEOUL_BUS);
        sync.prepareSeoulSnapshot(seoul, snapshot, clock.instant());
        var complete = seoul.toCompleteSnapshot(snapshot.normalized());
        List<TransitRawMetadataDiff.Row> rows = new ArrayList<>(snapshot.rawRows().size());
        for (var row : snapshot.rawRows()) rows.add(raw(TransitSourceSystem.SEOUL_TDATA, row.dataKind(), row.sourceKey(), TransitRawMetadataDiff.fromMap(row.fields()), null));
        return analyze(runId, complete, rows, Set.of(TransitSourceSystem.SEOUL_TDATA));
    }
    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Analysis analyzeAndStore(long runId, TagoMetadataSource.TagoSnapshot snapshot) {
        lockCollected(runId, TransitProvider.TAGO);
        sync.prepareTagoSnapshot(snapshot, clock.instant());
        List<TransitRawMetadataDiff.Row> rows = new ArrayList<>(snapshot.rawRows().size());
        for (var row : snapshot.rawRows()) rows.add(raw(row.sourceSystem(), row.dataKind(), row.sourceKey(), row.fields(), null));
        Set<TransitSourceSystem> sources = Set.of(TransitSourceSystem.TAGO);
        if (snapshot.gbis() != null) {
            sources = Set.of(TransitSourceSystem.TAGO, TransitSourceSystem.GBIS);
            for (var row : snapshot.gbis().rawRows()) rows.add(raw(row.sourceSystem(), row.dataKind(), row.sourceKey(),
                    TransitRawMetadataDiff.fromMap(row.fields()), snapshot.gbis().sourceVersion()));
        }
        return analyze(runId, snapshot.normalized(), rows, sources);
    }
    private void lockCollected(long runId, TransitProvider provider) {
        var rows = jdbc.query("select provider, status from transit_metadata_collection_runs where id = ? for update",
                (rs, n) -> List.of(rs.getString(1), rs.getString(2)), runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Collection run not found");
        if (!rows.getFirst().get(0).equals(provider.name())) throw new IllegalArgumentException("Provider does not match collection run");
        if (!rows.getFirst().get(1).equals("COLLECTED")) throw new IllegalStateException("Collection run is not COLLECTED");
    }
    private Analysis analyze(long runId, CompleteBusMetadataSnapshot complete, List<TransitRawMetadataDiff.Row> incoming,
            Set<TransitSourceSystem> sources) {
        if (complete.routes().stream().anyMatch(route -> route.occurrences().isEmpty()))
            throw new IllegalArgumentException("Complete snapshot contains a route without occurrences");
        var current = reader.read(complete.provider());
        var normalized = new BusMetadataDiffService().analyze(current, complete);
        List<TransitRawMetadataDiff.Row> previous = new ArrayList<>();
        for (var source : sources) jdbc.query("select data_kind, source_key, raw_payload, source_version from transit_source_metadata where source_system = ?",
                rs -> { previous.add(raw(source, TransitSourceDataKind.valueOf(rs.getString(1)), rs.getString(2),
                        TransitRawMetadataDiff.parse(rs.getString(3)), rs.getString(4))); }, source.name());
        var rawDiff = new TransitRawMetadataDiff().analyze(previous, incoming, sources);
        var summary = new Summary(new Counts(normalized.addedRoutes().size(), normalized.modifiedRoutes().size(), normalized.removedRoutes().size(), normalized.unchangedRoutes().size(), 0, 0),
                new Counts(normalized.addedStops().size(), normalized.modifiedStops().size(), normalized.orphanStops().size(), normalized.unchangedStops().size(), normalized.conflictingStops().size(), 0),
                new Counts(normalized.addedOccurrences().size(), normalized.modifiedOccurrences().size(), normalized.removedOccurrences().size(), normalized.unchangedOccurrences().size(), 0, normalized.positionChanges().size()), rawDiff);
        var timestamps = jdbc.query("select last_complete_sync_at from bus_metadata_sync_states where provider = ?", (rs, n) -> rs.getTimestamp(1).toInstant(), complete.provider().name());
        Instant last = timestamps.isEmpty() ? null : timestamps.getFirst();
        String fingerprint = fingerprint(normalized, current, complete, previous, incoming, sources, last);
        var existing = find(runId);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw new IllegalStateException("Conflicting analysis for collection run");
            return new Analysis(existing, normalized);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String summaryJson = JSON.writeValueAsString(summary);
        jdbc.update("insert into transit_metadata_diff_analyses (collection_run_id, provider, baseline_type, previous_complete_sync_at, analyzed_at, result_fingerprint, summary) values (?, ?, 'CURRENT_DB_BEFORE_APPLY', ?, ?, ?, cast(? as json))",
                runId, complete.provider().name(), last == null ? null : Timestamp.from(last), Timestamp.from(now), fingerprint, summaryJson);
        return new Analysis(find(runId), normalized);
    }
    @Transactional(readOnly = true)
    public Stored find(long runId) {
        var rows = jdbc.query("select provider, previous_complete_sync_at, analyzed_at, baseline_type, result_fingerprint, summary from transit_metadata_diff_analyses where collection_run_id = ?",
                (rs, n) -> new Stored(runId, TransitProvider.valueOf(rs.getString(1)), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3).toInstant(), rs.getString(4), false, rs.getString(5), rs.getString(6)), runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static TransitRawMetadataDiff.Row raw(TransitSourceSystem source, TransitSourceDataKind kind, String key, JsonNode fields, String version) {
        return new TransitRawMetadataDiff.Row(new TransitRawMetadataDiff.Key(source, kind, key), fields, version);
    }
    private static String fingerprint(BusMetadataDiffService.Result result, BusMetadataDiffService.CurrentState current,
            CompleteBusMetadataSnapshot complete, List<TransitRawMetadataDiff.Row> old,
            List<TransitRawMetadataDiff.Row> incoming, Set<TransitSourceSystem> sources, Instant last) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, JSON.writeValueAsString(result)); update(digest, last == null ? null : last.toString());
            appendRoutes(digest, current.routes()); appendRoutes(digest, complete.routes());
            var stops = new ArrayList<>(current.stops()); stops.sort(Comparator.comparing(BusMetadataDiffService.Stop::externalStopId));
            for (var stop : stops) { update(digest, stop.externalStopId()); update(digest, stop.name());
                update(digest, decimal(stop.latitude())); update(digest, decimal(stop.longitude())); }
            sources.stream().sorted().forEach(s -> update(digest, s.name()));
            appendRows(digest, old); appendRows(digest, incoming);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String decimal(java.math.BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
    private static void appendRoutes(MessageDigest digest, List<BusRouteMetadataSnapshot> routes) {
        var ordered = new ArrayList<>(routes); ordered.sort(Comparator.comparing(BusRouteMetadataSnapshot::externalRouteId));
        update(digest, Integer.toString(ordered.size()));
        for (var route : ordered) {
            update(digest, route.externalRouteId()); update(digest, route.routeNumber()); update(digest, route.cityCode());
            var occurrences = new ArrayList<>(route.occurrences()); occurrences.sort(Comparator.comparingInt(BusStopOccurrenceMetadataSnapshot::stopOrder));
            update(digest, Integer.toString(occurrences.size()));
            for (var occurrence : occurrences) {
                update(digest, occurrence.externalStopId()); update(digest, occurrence.stopName());
                update(digest, decimal(occurrence.latitude())); update(digest, decimal(occurrence.longitude()));
                update(digest, Integer.toString(occurrence.stopOrder())); update(digest, occurrence.destinationName());
            }
        }
    }
    private static void appendRows(MessageDigest digest, List<TransitRawMetadataDiff.Row> rows) {
        List<TransitRawMetadataDiff.Row> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing((TransitRawMetadataDiff.Row r) -> r.key().source()).thenComparing(r -> r.key().kind()).thenComparing(r -> r.key().sourceKey()));
        update(digest, Integer.toString(ordered.size()));
        for (var row : ordered) {
            update(digest, row.key().source().name()); update(digest, row.key().kind().name()); update(digest, row.key().sourceKey());
            update(digest, row.version()); canonical(digest, row.fields());
        }
    }
    private static void canonical(MessageDigest digest, JsonNode node) {
        if (node.isObject()) {
            update(digest, "object"); List<String> keys = new ArrayList<>(node.propertyNames()); keys.sort(String::compareTo);
            update(digest, Integer.toString(keys.size())); for (String key : keys) { update(digest, key); canonical(digest, node.get(key)); }
        } else if (node.isArray()) {
            update(digest, "array"); update(digest, Integer.toString(node.size())); node.forEach(n -> canonical(digest, n));
        } else { update(digest, node.getNodeType().name()); update(digest, node.toString()); }
    }
    private static void update(MessageDigest digest, String value) {
        if (value == null) { digest.update(new byte[] {-1, -1, -1, -1}); return; }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
}
