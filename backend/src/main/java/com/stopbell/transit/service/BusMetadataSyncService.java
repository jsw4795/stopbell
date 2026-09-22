package com.stopbell.transit.service;

import java.util.Set;
import java.time.Clock;
import java.time.Instant;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusMetadataSyncState;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BusMetadataSyncService {

    private static final Logger log = LoggerFactory.getLogger(BusMetadataSyncService.class);
    private static final int PROGRESS_LOG_INTERVAL = 100;

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

    public void syncCompleteProviderSnapshot(CompleteBusMetadataSnapshot completeSnapshot) {
        TransitProvider provider = completeSnapshot.provider();
        int totalRoutes = completeSnapshot.routes().size();
        Set<String> externalRouteIds = completeSnapshot.routes().stream()
                .map(BusRouteMetadataSnapshot::externalRouteId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        log.info("Metadata database sync started: provider={}, routes={}", provider, totalRoutes);
        int routesProcessed = 0;
        for (BusRouteMetadataSnapshot snapshot : completeSnapshot.routes()) {
            syncRoute(snapshot);
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
