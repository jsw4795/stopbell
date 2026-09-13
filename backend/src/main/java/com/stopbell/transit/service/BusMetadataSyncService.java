package com.stopbell.transit.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import org.springframework.stereotype.Service;

@Service
public class BusMetadataSyncService {

    private final BusRouteMetadataSyncService routeMetadataSyncService;
    private final BusProviderMetadataCleanupService providerMetadataCleanupService;

    public BusMetadataSyncService(
            BusRouteMetadataSyncService routeMetadataSyncService,
            BusProviderMetadataCleanupService providerMetadataCleanupService
    ) {
        this.routeMetadataSyncService = routeMetadataSyncService;
        this.providerMetadataCleanupService = providerMetadataCleanupService;
    }

    public BusRoute syncRoute(BusRouteMetadataSnapshot snapshot) {
        return routeMetadataSyncService.syncRoute(snapshot);
    }

    /**
     * Call only after a provider's complete source snapshot has been fetched successfully.
     */
    public void syncProviderSnapshot(TransitProvider provider, List<BusRouteMetadataSnapshot> snapshots) {
        Set<String> externalRouteIds = validateProviderSnapshot(provider, snapshots);
        for (BusRouteMetadataSnapshot snapshot : snapshots) {
            syncRoute(snapshot);
        }
        providerMetadataCleanupService.cleanupProvider(provider, externalRouteIds);
    }

    private Set<String> validateProviderSnapshot(TransitProvider provider, List<BusRouteMetadataSnapshot> snapshots) {
        if (provider == null || snapshots == null) {
            throw new IllegalArgumentException("Provider and snapshots must not be null");
        }
        Set<String> externalRouteIds = new HashSet<>();
        for (BusRouteMetadataSnapshot snapshot : snapshots) {
            if (snapshot == null) {
                throw new IllegalArgumentException("Provider snapshot must not contain null");
            }
            if (snapshot.provider() != provider) {
                throw new IllegalArgumentException("Provider snapshot contains a route from another provider");
            }
            if (!externalRouteIds.add(snapshot.externalRouteId())) {
                throw new IllegalArgumentException("Provider snapshot contains duplicate route identity");
            }
        }
        return externalRouteIds;
    }
}
