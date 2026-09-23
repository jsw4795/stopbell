package com.stopbell.alarm.service;

import java.util.List;
import java.util.OptionalInt;

import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads current metadata only for optional PASSED display evidence.
 */
@Service
public class BusRouteTraversalService {

    private final BusRouteRepository busRouteRepository;
    private final BusRouteStopOccurrenceRepository occurrenceRepository;

    public BusRouteTraversalService(
            BusRouteRepository busRouteRepository,
            BusRouteStopOccurrenceRepository occurrenceRepository
    ) {
        this.busRouteRepository = busRouteRepository;
        this.occurrenceRepository = occurrenceRepository;
    }

    @Transactional(readOnly = true)
    public OptionalInt stopsPastTarget(BusAlarmTarget target, TransitObservation observation) {
        if (observation.currentStopExternalId() == null || observation.currentStopOrder() == null) {
            return OptionalInt.empty();
        }
        BusRoute route = busRouteRepository.findByProviderAndExternalRouteId(
                target.getProvider(), target.getExternalRouteId()
        ).orElse(null);
        if (route == null) {
            return OptionalInt.empty();
        }
        List<BusRouteStopOccurrence> occurrences = occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route);
        int targetIndex = indexOf(occurrences, target.getExternalStopId(), target.getTargetStopOrder());
        int currentIndex = indexOf(occurrences, observation.currentStopExternalId(), observation.currentStopOrder());
        if (targetIndex < 0 || currentIndex <= targetIndex) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(currentIndex - targetIndex);
    }

    private static int indexOf(List<BusRouteStopOccurrence> occurrences, String externalStopId, int stopOrder) {
        for (int index = 0; index < occurrences.size(); index++) {
            BusRouteStopOccurrence occurrence = occurrences.get(index);
            if (occurrence.getStopOrder() == stopOrder
                    && occurrence.getStop().getExternalStopId().equals(externalStopId)) {
                return index;
            }
        }
        return -1;
    }
}
