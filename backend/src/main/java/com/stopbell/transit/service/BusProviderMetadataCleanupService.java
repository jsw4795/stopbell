package com.stopbell.transit.service;

import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.repository.BusStopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusProviderMetadataCleanupService {

    private final BusRouteRepository busRouteRepository;
    private final BusStopRepository busStopRepository;
    private final BusRouteStopOccurrenceRepository occurrenceRepository;

    public BusProviderMetadataCleanupService(
            BusRouteRepository busRouteRepository,
            BusStopRepository busStopRepository,
            BusRouteStopOccurrenceRepository occurrenceRepository
    ) {
        this.busRouteRepository = busRouteRepository;
        this.busStopRepository = busStopRepository;
        this.occurrenceRepository = occurrenceRepository;
    }

    @Transactional
    public void cleanupProvider(TransitProvider provider, Set<String> externalRouteIds) {
        busRouteRepository.findAllByProvider(provider).stream()
                .filter(route -> !externalRouteIds.contains(route.getExternalRouteId()))
                .forEach(this::deleteRouteWithOccurrences);
        busRouteRepository.flush();
        busStopRepository.deleteAll(busStopRepository.findOrphanedByProvider(provider));
    }

    private void deleteRouteWithOccurrences(BusRoute route) {
        occurrenceRepository.deleteAll(occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route));
        busRouteRepository.delete(route);
    }
}
