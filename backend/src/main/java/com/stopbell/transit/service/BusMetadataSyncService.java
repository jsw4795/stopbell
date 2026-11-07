package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource;
import com.stopbell.transit.metadata.SeoulCsvMetadataSource.RawSourceRow;
import com.stopbell.transit.service.BusRouteMetadataSyncService.SeoulRouteRawData;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusMetadataSyncState;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BusMetadataSyncService {

    private static final Logger log = LoggerFactory.getLogger(BusMetadataSyncService.class);
    private static final int PROGRESS_LOG_INTERVAL = 100;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BusRouteMetadataSyncService routeMetadataSyncService;
    private final BusProviderMetadataCleanupService providerMetadataCleanupService;
    private final BusMetadataSyncStateRepository syncStateRepository;
    private final Clock clock;

    public BusMetadataSyncService(
            BusRouteMetadataSyncService routeMetadataSyncService,
            BusProviderMetadataCleanupService providerMetadataCleanupService,
            BusMetadataSyncStateRepository syncStateRepository,
            @Qualifier("transitMetadataClock") Clock clock
    ) {
        this.routeMetadataSyncService = routeMetadataSyncService;
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

    /** Reusing the same snapshot and collection time represents a retry of the same collection. */
    public void syncSeoulSnapshot(SeoulCsvMetadataSource source, SeoulCsvMetadataSource.SeoulCsvSnapshot collected,
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
        Set<String> savedStops = new HashSet<>();
        syncComplete(complete, route -> {
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
        syncComplete(completeSnapshot, this::syncRoute);
    }

    private void syncComplete(CompleteBusMetadataSnapshot completeSnapshot,
            java.util.function.Consumer<BusRouteMetadataSnapshot> syncRoute) {
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
        providerMetadataCleanupService.cleanupProvider(provider, externalRouteIds);
        Instant completedAt = Instant.now(clock);
        BusMetadataSyncState state = syncStateRepository.findById(provider)
                .orElseGet(() -> new BusMetadataSyncState(provider, completedAt));
        state.markComplete(completedAt);
        syncStateRepository.save(state);
        log.info("Metadata database sync completed: provider={}, routes={}", provider, totalRoutes);
    }
}
