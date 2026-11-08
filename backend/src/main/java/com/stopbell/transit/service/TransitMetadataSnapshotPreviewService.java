package com.stopbell.transit.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
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
import org.springframework.transaction.annotation.Transactional;

/** Compares a validated complete snapshot with current DB state without storing analysis or evidence. */
@Service
public class TransitMetadataSnapshotPreviewService {
    private final JdbcTemplate jdbc;
    private final BusMetadataCurrentStateReader reader;
    private final BusMetadataSyncService sync;
    private final Clock clock;

    public TransitMetadataSnapshotPreviewService(JdbcTemplate jdbc, BusMetadataCurrentStateReader reader,
            BusMetadataSyncService sync, @Qualifier("transitMetadataClock") Clock clock) {
        this.jdbc = jdbc; this.reader = reader; this.sync = sync; this.clock = clock;
    }

    public record Size(int routes, int stops, int occurrences) { }
    public record SourceKind(TransitSourceSystem source, TransitSourceDataKind kind) { }
    public record Result(TransitProvider provider, Size current, Size snapshot, BusMetadataDiffService.Result diff,
            BusMetadataSafetyGuard.Result safety, Map<SourceKind, TransitRawMetadataDiff.Counts> raw,
            List<TransitMetadataUnsafeChangeDetails.Detail> details) {
        public Result { raw = Map.copyOf(raw); details = List.copyOf(details); }
        public List<TransitMetadataUnsafeChangeDetails.Detail> detailPage(int page, int size) {
            if (page < 0 || size < 1 || size > 500) throw new IllegalArgumentException("Invalid detail page");
            long start = (long) page * size;
            if (start >= details.size()) return List.of();
            return details.subList((int) start, (int) Math.min(start + size, details.size()));
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result preview(SeoulCsvMetadataSource source, SeoulCsvMetadataSource.SeoulCsvSnapshot snapshot) {
        sync.prepareSeoulSnapshot(source, snapshot, clock.instant());
        var incoming = new ArrayList<TransitRawMetadataDiff.Row>(snapshot.rawRows().size());
        for (var row : snapshot.rawRows()) incoming.add(raw(TransitSourceSystem.SEOUL_TDATA, row.dataKind(), row.sourceKey(), TransitRawMetadataDiff.fromMap(row.fields()), null));
        return compare(source.toCompleteSnapshot(snapshot.normalized()), incoming, Set.of(TransitSourceSystem.SEOUL_TDATA));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result preview(TagoMetadataSource.TagoSnapshot snapshot) {
        sync.prepareTagoSnapshot(snapshot, clock.instant());
        var incoming = new ArrayList<TransitRawMetadataDiff.Row>();
        for (var row : snapshot.rawRows()) incoming.add(raw(row.sourceSystem(), row.dataKind(), row.sourceKey(), row.fields(), null));
        var sources = Set.of(TransitSourceSystem.TAGO);
        if (snapshot.gbis() != null) {
            sources = Set.of(TransitSourceSystem.TAGO, TransitSourceSystem.GBIS);
            for (var row : snapshot.gbis().rawRows()) incoming.add(raw(row.sourceSystem(), row.dataKind(), row.sourceKey(), TransitRawMetadataDiff.fromMap(row.fields()), snapshot.gbis().sourceVersion()));
        }
        return compare(snapshot.normalized(), incoming, sources);
    }

    private Result compare(CompleteBusMetadataSnapshot complete, List<TransitRawMetadataDiff.Row> incoming, Set<TransitSourceSystem> sources) {
        if (complete.routes().stream().anyMatch(route -> route.occurrences().isEmpty()))
            throw new TransitMetadataDiffAnalysisService.InvalidSnapshotException();
        var current = reader.read(complete.provider());
        var diff = new BusMetadataDiffService().analyze(current, complete);
        var safety = new BusMetadataSafetyGuard().evaluate(new TransitMetadataDiffAnalysisService.Analysis(null, diff));
        var previous = new ArrayList<TransitRawMetadataDiff.Row>();
        for (var source : sources) jdbc.query("select data_kind, source_key, raw_payload, source_version from transit_source_metadata where source_system = ?",
                rs -> { previous.add(raw(source, TransitSourceDataKind.valueOf(rs.getString(1)), rs.getString(2), TransitRawMetadataDiff.parse(rs.getString(3)), rs.getString(4))); }, source.name());
        Map<SourceKind, TransitRawMetadataDiff.Counts> raw = new java.util.LinkedHashMap<>();
        for (var source : TransitSourceSystem.values()) for (var kind : TransitSourceDataKind.values()) {
            var oldRows = previous.stream().filter(r -> r.key().source() == source && r.key().kind() == kind).toList();
            var newRows = incoming.stream().filter(r -> r.key().source() == source && r.key().kind() == kind).toList();
            raw.put(new SourceKind(source, kind), new TransitRawMetadataDiff().analyze(oldRows, newRows, sources).get(source));
        }
        return new Result(complete.provider(), size(current.routes(), current.stops().size()), size(complete.routes(), -1), diff, safety, raw,
                detailsInApplyOrder(diff, complete));
    }
    private static List<TransitMetadataUnsafeChangeDetails.Detail> detailsInApplyOrder(
            BusMetadataDiffService.Result diff, CompleteBusMetadataSnapshot snapshot) {
        var details = TransitMetadataUnsafeChangeDetails.build(diff, snapshot);
        if (diff.conflictingStops().isEmpty()) return details;
        var conflicts = Set.copyOf(diff.conflictingStops());
        var variants = new java.util.HashMap<String, List<TransitMetadataUnsafeChangeDetails.Variant>>();
        for (var route : snapshot.routes()) for (var stop : route.occurrences()) {
            if (conflicts.contains(stop.externalStopId())) variants.computeIfAbsent(stop.externalStopId(), ignored -> new ArrayList<>())
                    .add(new TransitMetadataUnsafeChangeDetails.Variant(route.externalRouteId(), stop.stopOrder(), stop.stopName(), stop.latitude(), stop.longitude()));
        }
        return details.stream().map(d -> d.reason() == BusMetadataSafetyGuard.Reason.SHARED_STOP_CONFLICT
                ? new TransitMetadataUnsafeChangeDetails.Detail(d.provider(), d.reason(), d.routeId(), d.stopId(), d.stopOrder(), d.replacementStopId(), variants.get(d.stopId()))
                : d).toList();
    }
    private static Size size(List<BusRouteMetadataSnapshot> routes, int stopCount) {
        Set<String> stops = new HashSet<>();
        int occurrences = 0;
        for (var route : routes) for (var occurrence : route.occurrences()) { stops.add(occurrence.externalStopId()); occurrences++; }
        return new Size(routes.size(), stopCount < 0 ? stops.size() : stopCount, occurrences);
    }
    private static TransitRawMetadataDiff.Row raw(TransitSourceSystem source, TransitSourceDataKind kind, String key, tools.jackson.databind.JsonNode fields, String version) {
        return new TransitRawMetadataDiff.Row(new TransitRawMetadataDiff.Key(source, kind, key), fields, version);
    }
}
