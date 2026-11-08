package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.metadata.GbisCollectedSnapshot;
import com.stopbell.transit.metadata.GbisDestinationEnricher;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.metadata.TagoMetadataSource;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource.RawSourceRow;
import com.stopbell.transit.service.BusRouteMetadataSyncService.SeoulRouteRawData;
import com.stopbell.transit.service.BusRouteMetadataSyncService.TagoRouteRawData;
import com.stopbell.transit.service.TransitSourceMetadataService.SourceRecord;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusMetadataSyncState;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

@Service
public class BusMetadataSyncService {

    private static final Logger log = LoggerFactory.getLogger(BusMetadataSyncService.class);
    private static final int PROGRESS_LOG_INTERVAL = 100;
    private static final int RAW_BATCH_SIZE = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BusRouteMetadataSyncService routeMetadataSyncService;
    private final TransitSourceMetadataService sourceMetadataService;
    private final BusProviderMetadataCleanupService providerMetadataCleanupService;
    private final BusMetadataSyncStateRepository syncStateRepository;
    private final Clock clock;

    public BusMetadataSyncService(
            BusRouteMetadataSyncService routeMetadataSyncService,
            TransitSourceMetadataService sourceMetadataService,
            BusProviderMetadataCleanupService providerMetadataCleanupService,
            BusMetadataSyncStateRepository syncStateRepository,
            @Qualifier("transitMetadataClock") Clock clock
    ) {
        this.routeMetadataSyncService = routeMetadataSyncService;
        this.sourceMetadataService = sourceMetadataService;
        this.providerMetadataCleanupService = providerMetadataCleanupService;
        this.syncStateRepository = syncStateRepository;
        this.clock = clock;
    }

    public BusRoute syncRoute(BusRouteMetadataSnapshot snapshot) {
        return routeMetadataSyncService.syncRoute(snapshot);
    }

    public void syncSeoulSource(SeoulCsvMetadataSource source) {
        Instant collectedAt = clock.instant();
        syncSeoulSnapshot(source, source.fetchSnapshotWithRawRows(), collectedAt);
    }

    public void syncSeoulSource(SeoulCsvMetadataSource source, long runId, TransitMetadataArchiveService archive) {
        Instant collectedAt = clock.instant();
        syncSeoulSnapshot(source, source.fetchSnapshotWithRawRows(runId, archive), collectedAt);
    }

    public void syncTagoSource(TagoMetadataSource source) {
        Instant collectedAt = clock.instant();
        syncTagoSnapshot(source.fetchSnapshotWithRawRows(), collectedAt);
    }

    public void syncTagoSource(TagoMetadataSource source, long runId, TransitMetadataArchiveService archive) {
        Instant collectedAt = clock.instant();
        syncTagoSnapshot(source.fetchSnapshotWithRawRows(runId, archive), collectedAt);
    }

    /** Reuse both the snapshot and collection time when retrying a partial database sync. */
    public void syncTagoSnapshot(TagoMetadataSource.TagoSnapshot collected, Instant collectedAt) {
        prepareTagoSnapshot(collected, collectedAt).run();
    }

    public Runnable prepareTagoSnapshot(TagoMetadataSource.TagoSnapshot collected, Instant collectedAt) {
        Objects.requireNonNull(collectedAt, "Collection time must not be null");
        CompleteBusMetadataSnapshot complete = Objects.requireNonNull(collected, "Snapshot must not be null").normalized();
        if (complete == null || complete.provider() != TransitProvider.TAGO) throw new IllegalArgumentException("Expected TAGO snapshot");
        Map<String, TagoMetadataSource.RawSourceRow> routes = new HashMap<>();
        Map<String, TagoMetadataSource.RawSourceRow> occurrences = new HashMap<>();
        for (TagoMetadataSource.RawSourceRow row : collected.rawRows()) {
            if (row == null || row.sourceSystem() != TransitSourceSystem.TAGO || row.fields() == null || !row.fields().isObject()
                    || row.sourceKey() == null || row.sourceKey().isBlank() || !row.sourceKey().equals(row.sourceKey().strip())
                    || row.sourceKey().length() > 255 || row.externalRouteId() == null || row.externalRouteId().isBlank()) {
                throw new IllegalStateException("Invalid TAGO raw row");
            }
            Map<String, TagoMetadataSource.RawSourceRow> index;
            if (row.dataKind() == TransitSourceDataKind.ROUTE) {
                if (row.stopOrder() != null || !row.sourceKey().equals(row.externalRouteId())
                        || !row.externalRouteId().equals(rawText(row.fields().get("routeid")))) {
                    throw new IllegalStateException("Invalid TAGO raw route identity");
                }
                index = routes;
            } else if (row.dataKind() == TransitSourceDataKind.OCCURRENCE) {
                if (row.stopOrder() == null || row.stopOrder() <= 0
                        || !row.sourceKey().equals(TagoMetadataSource.occurrenceSourceKey(row.externalRouteId(), row.stopOrder()))
                        || !sameOrder(row.fields().get("nodeord"), row.stopOrder())) {
                    throw new IllegalStateException("Invalid TAGO raw occurrence identity");
                }
                index = occurrences;
            } else throw new IllegalStateException("Unexpected TAGO raw data kind");
            if (index.put(row.sourceKey(), row) != null) throw new IllegalStateException("Duplicate TAGO raw key");
        }
        int expectedOccurrences = 0;
        for (BusRouteMetadataSnapshot route : complete.routes()) {
            TagoMetadataSource.RawSourceRow rawRoute = routes.get(route.externalRouteId());
            if (rawRoute == null || !route.externalRouteId().equals(rawRoute.externalRouteId())) throw new IllegalStateException("Missing TAGO raw route");
            for (BusStopOccurrenceMetadataSnapshot stop : route.occurrences()) {
                String key = TagoMetadataSource.occurrenceSourceKey(route.externalRouteId(), stop.stopOrder());
                TagoMetadataSource.RawSourceRow raw = occurrences.get(key);
                if (raw == null || !route.externalRouteId().equals(raw.externalRouteId())
                        || !Objects.equals(stop.stopOrder(), raw.stopOrder())
                        || !stop.externalStopId().equals(rawText(raw.fields().get("nodeid")))) {
                    throw new IllegalStateException("TAGO raw occurrence does not match snapshot");
                }
                expectedOccurrences++;
            }
        }
        if (routes.size() != complete.routes().size() || occurrences.size() != expectedOccurrences) {
            throw new IllegalStateException("Unexpected TAGO raw rows");
        }
        GbisCollectedSnapshot gbis = collected.gbis();
        Map<String, String> matches = Map.of();
        if (gbis != null) {
            GbisRawValidation.validate(gbis);
            matches = GbisDestinationEnricher.verifiedMatches(complete.routes(), gbis.normalized());
        }
        Map<String, String> verifiedMatches = matches;
        return () -> syncComplete(complete, route -> {
            Map<String, String> payloads = new HashMap<>();
            for (BusStopOccurrenceMetadataSnapshot stop : route.occurrences()) {
                String key = TagoMetadataSource.occurrenceSourceKey(route.externalRouteId(), stop.stopOrder());
                payloads.put(key, JSON.writeValueAsString(occurrences.get(key).fields()));
            }
            routeMetadataSyncService.syncTagoRoute(route, new TagoRouteRawData(
                    JSON.writeValueAsString(routes.get(route.externalRouteId()).fields()), payloads, collectedAt));
        }, () -> {
            if (gbis != null) saveGbis(gbis, verifiedMatches, collectedAt);
        });
    }

    private void saveGbis(GbisCollectedSnapshot gbis, Map<String, String> matches, Instant collectedAt) {
        Map<String, TransitSourceMetadataService.TagoEntityIds> ids = new HashMap<>();
        matches.forEach((gbisId, tagoId) -> ids.put(gbisId, sourceMetadataService.findTagoEntityIds(tagoId)));
        List<SourceRecord> batch = new ArrayList<>(RAW_BATCH_SIZE);
        for (var row : gbis.rawRows()) {
            var matched = ids.get(row.externalRouteId());
            Long entityId = matched == null ? null : row.dataKind() == TransitSourceDataKind.ROUTE
                    ? matched.routeId() : matched.occurrences().get(row.stopOrder());
            if (matched != null && entityId == null) throw new IllegalStateException("Missing reconciled TAGO occurrence");
            batch.add(new SourceRecord(TransitSourceSystem.GBIS, row.dataKind(), row.sourceKey(), entityId,
                    JSON.writeValueAsString(row.fields()), gbis.sourceVersion(), collectedAt));
            if (batch.size() == RAW_BATCH_SIZE) {
                sourceMetadataService.saveGbisBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) sourceMetadataService.saveGbisBatch(batch);
    }

    private static String rawText(JsonNode node) {
        return node != null && (node.isString() || node.isIntegralNumber()) ? node.asString() : null;
    }

    private static boolean sameOrder(JsonNode node, int order) {
        try {
            String value = rawText(node);
            return value != null && Integer.parseInt(value) == order;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /** Reusing the same snapshot and collection time represents a retry of the same collection. */
    public void syncSeoulSnapshot(SeoulCsvMetadataSource source, SeoulCsvMetadataSource.SeoulCsvSnapshot collected,
            Instant collectedAt) {
        prepareSeoulSnapshot(source, collected, collectedAt).run();
    }

    public Runnable prepareSeoulSnapshot(SeoulCsvMetadataSource source, SeoulCsvMetadataSource.SeoulCsvSnapshot collected,
            Instant collectedAt) {
        Objects.requireNonNull(collectedAt, "Collection time must not be null");
        CompleteBusMetadataSnapshot complete = source.toCompleteSnapshot(collected.normalized());
        Map<TransitSourceDataKind, Map<String, RawSourceRow>> rows = new EnumMap<>(TransitSourceDataKind.class);
        for (TransitSourceDataKind kind : TransitSourceDataKind.values()) {
            rows.put(kind, new HashMap<>());
        }
        for (RawSourceRow row : collected.rawRows()) {
            if (rows.get(row.dataKind()).put(row.sourceKey(), row) != null) {
                throw new IllegalStateException("Duplicate Seoul raw row: " + row.sourceKey());
            }
        }
        // Validate the complete mapping before any route transaction can start.
        for (BusRouteMetadataSnapshot route : complete.routes()) {
            requireRow(rows, TransitSourceDataKind.ROUTE, route.externalRouteId());
            for (BusStopOccurrenceMetadataSnapshot occurrence : route.occurrences()) {
                requireRow(rows, TransitSourceDataKind.STOP, occurrence.externalStopId());
                requireRow(rows, TransitSourceDataKind.OCCURRENCE,
                        SeoulCsvMetadataSource.occurrenceSourceKey(route.externalRouteId(), occurrence.stopOrder()));
            }
        }
        Map<TransitSourceDataKind, Set<String>> saved = new EnumMap<>(TransitSourceDataKind.class);
        for (TransitSourceDataKind kind : TransitSourceDataKind.values()) {
            saved.put(kind, new HashSet<>());
        }
        Set<String> savedStops = saved.get(TransitSourceDataKind.STOP);
        return () -> syncComplete(complete, route -> {
            Map<String, String> stops = new HashMap<>();
            Map<String, String> occurrences = new HashMap<>();
            for (BusStopOccurrenceMetadataSnapshot occurrence : route.occurrences()) {
                String stopId = occurrence.externalStopId();
                if (!savedStops.contains(stopId)) {
                    stops.computeIfAbsent(stopId, key -> payload(rows, TransitSourceDataKind.STOP, key));
                }
                String key = SeoulCsvMetadataSource.occurrenceSourceKey(route.externalRouteId(), occurrence.stopOrder());
                occurrences.put(key, payload(rows, TransitSourceDataKind.OCCURRENCE, key));
            }
            routeMetadataSyncService.syncSeoulRoute(route, new SeoulRouteRawData(
                    payload(rows, TransitSourceDataKind.ROUTE, route.externalRouteId()), stops, occurrences, collectedAt));
            savedStops.addAll(stops.keySet());
            saved.get(TransitSourceDataKind.ROUTE).add(route.externalRouteId());
            saved.get(TransitSourceDataKind.OCCURRENCE).addAll(occurrences.keySet());
        }, () -> {
            List<SourceRecord> batch = new ArrayList<>(RAW_BATCH_SIZE);
            for (RawSourceRow row : collected.rawRows()) {
                if (saved.get(row.dataKind()).contains(row.sourceKey())) {
                    continue;
                }
                batch.add(new SourceRecord(TransitSourceSystem.SEOUL_TDATA, row.dataKind(), row.sourceKey(),
                        null, JSON.writeValueAsString(row.fields()), null, collectedAt));
                if (batch.size() == RAW_BATCH_SIZE) {
                    sourceMetadataService.saveUnlinkedBatch(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                sourceMetadataService.saveUnlinkedBatch(batch);
            }
        });
    }

    private static RawSourceRow requireRow(Map<TransitSourceDataKind, Map<String, RawSourceRow>> rows,
            TransitSourceDataKind kind, String key) {
        RawSourceRow row = rows.get(kind).get(key);
        if (row == null) {
            throw new IllegalStateException("Missing Seoul raw row: " + kind + " " + key);
        }
        return row;
    }

    private static String payload(Map<TransitSourceDataKind, Map<String, RawSourceRow>> rows,
            TransitSourceDataKind kind, String key) {
        return JSON.writeValueAsString(requireRow(rows, kind, key).fields());
    }

    public void syncCompleteProviderSnapshot(CompleteBusMetadataSnapshot completeSnapshot) {
        syncComplete(completeSnapshot, this::syncRoute, () -> {});
    }

    private void syncComplete(CompleteBusMetadataSnapshot completeSnapshot,
            java.util.function.Consumer<BusRouteMetadataSnapshot> syncRoute, Runnable beforeCleanup) {
        TransitProvider provider = completeSnapshot.provider();
        int totalRoutes = completeSnapshot.routes().size();
        Set<String> externalRouteIds = completeSnapshot.routes().stream()
                .map(BusRouteMetadataSnapshot::externalRouteId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        log.info("Metadata database sync started: provider={}, routes={}", provider, totalRoutes);
        int routesProcessed = 0;
        for (BusRouteMetadataSnapshot snapshot : completeSnapshot.routes()) {
            syncRoute.accept(snapshot);
            routesProcessed++;
            if (routesProcessed % PROGRESS_LOG_INTERVAL == 0) {
                log.info("Metadata database sync progress: provider={}, routesProcessed={}/{}",
                        provider, routesProcessed, totalRoutes);
            }
        }
        beforeCleanup.run();
        providerMetadataCleanupService.cleanupProvider(provider, externalRouteIds);
        Instant completedAt = Instant.now(clock);
        BusMetadataSyncState state = syncStateRepository.findById(provider)
                .orElseGet(() -> new BusMetadataSyncState(provider, completedAt));
        state.markComplete(completedAt);
        syncStateRepository.save(state);
        log.info("Metadata database sync completed: provider={}, routes={}", provider, totalRoutes);
    }
}
