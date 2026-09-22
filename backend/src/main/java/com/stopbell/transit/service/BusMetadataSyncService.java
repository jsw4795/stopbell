package com.stopbell.transit.service;

import java.util.Set;
import java.time.Instant;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusMetadataSyncState;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import org.springframework.stereotype.Service;

@Service
public class BusMetadataSyncService {

    private final BusRouteMetadataSyncService routeMetadataSyncService;
    private final BusProviderMetadataCleanupService providerMetadataCleanupService;
    private final BusMetadataSyncStateRepository syncStateRepository;

    public BusMetadataSyncService(
            BusRouteMetadataSyncService routeMetadataSyncService,
            BusProviderMetadataCleanupService providerMetadataCleanupService,
            BusMetadataSyncStateRepository syncStateRepository
    ) {
        this.routeMetadataSyncService = routeMetadataSyncService;
        this.providerMetadataCleanupService = providerMetadataCleanupService;
        this.syncStateRepository = syncStateRepository;
    }

    public BusRoute syncRoute(BusRouteMetadataSnapshot snapshot) {
        return routeMetadataSyncService.syncRoute(snapshot);
    }

    public void syncCompleteProviderSnapshot(CompleteBusMetadataSnapshot completeSnapshot) {
        TransitProvider provider = completeSnapshot.provider();
        Set<String> externalRouteIds = completeSnapshot.routes().stream()
                .map(BusRouteMetadataSnapshot::externalRouteId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (BusRouteMetadataSnapshot snapshot : completeSnapshot.routes()) {
            syncRoute(snapshot);
        }
        providerMetadataCleanupService.cleanupProvider(provider, externalRouteIds);
        Instant completedAt = Instant.now();
        BusMetadataSyncState state = syncStateRepository.findById(provider)
                .orElseGet(() -> new BusMetadataSyncState(provider, completedAt));
        state.markComplete(completedAt);
        syncStateRepository.save(state);
    }
}
