package com.stopbell.transit.service;

import java.util.HashMap;
import java.util.Map;

import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.repository.BusStopRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusRouteMetadataSyncService {

    private final BusRouteRepository busRouteRepository;
    private final BusStopRepository busStopRepository;
    private final BusRouteStopOccurrenceRepository occurrenceRepository;

    public BusRouteMetadataSyncService(
            BusRouteRepository busRouteRepository,
            BusStopRepository busStopRepository,
            BusRouteStopOccurrenceRepository occurrenceRepository
    ) {
        this.busRouteRepository = busRouteRepository;
        this.busStopRepository = busStopRepository;
        this.occurrenceRepository = occurrenceRepository;
    }

    @Transactional
    public BusRoute syncRoute(BusRouteMetadataSnapshot snapshot) {
        BusRoute route = busRouteRepository
                .findByProviderAndExternalRouteId(snapshot.provider(), snapshot.externalRouteId())
                .orElseGet(() -> busRouteRepository.save(new BusRoute(
                        snapshot.provider(),
                        snapshot.externalRouteId(),
                        snapshot.routeNumber(),
                        snapshot.cityCode()
                )));
        route.updateMetadata(snapshot.routeNumber(), snapshot.cityCode());

        Map<OccurrenceKey, BusRouteStopOccurrence> existingOccurrences = new HashMap<>();
        for (BusRouteStopOccurrence occurrence : occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route)) {
            existingOccurrences.put(new OccurrenceKey(
                    occurrence.getStop().getExternalStopId(), occurrence.getStopOrder()
            ), occurrence);
        }

        Map<OccurrenceKey, BusStop> incomingOccurrences = new HashMap<>();
        for (BusStopOccurrenceMetadataSnapshot occurrenceSnapshot : snapshot.occurrences()) {
            OccurrenceKey key = new OccurrenceKey(occurrenceSnapshot.externalStopId(), occurrenceSnapshot.stopOrder());
            BusStop stop = busStopRepository
                    .findByProviderAndExternalStopId(snapshot.provider(), occurrenceSnapshot.externalStopId())
                    .orElseGet(() -> busStopRepository.save(new BusStop(
                            snapshot.provider(),
                            occurrenceSnapshot.externalStopId(),
                            occurrenceSnapshot.stopName(),
                            occurrenceSnapshot.latitude(),
                            occurrenceSnapshot.longitude()
                    )));
            stop.updateMetadata(
                    occurrenceSnapshot.stopName(), occurrenceSnapshot.latitude(), occurrenceSnapshot.longitude()
            );
            incomingOccurrences.put(key, stop);
        }

        existingOccurrences.entrySet().stream()
                .filter(entry -> !incomingOccurrences.containsKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .forEach(occurrenceRepository::delete);
        occurrenceRepository.flush();

        for (Map.Entry<OccurrenceKey, BusStop> incomingOccurrence : incomingOccurrences.entrySet()) {
            if (!existingOccurrences.containsKey(incomingOccurrence.getKey())) {
                occurrenceRepository.save(new BusRouteStopOccurrence(
                        route,
                        incomingOccurrence.getValue(),
                        incomingOccurrence.getKey().stopOrder()
                ));
            }
        }
        return route;
    }

    private record OccurrenceKey(String externalStopId, int stopOrder) {
    }
}
