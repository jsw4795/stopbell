package com.stopbell.transit.service;

import java.util.List;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.dto.BusRouteStopResponse;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusRouteStopService {

    private final BusRouteRepository busRouteRepository;
    private final BusRouteStopOccurrenceRepository occurrenceRepository;

    public BusRouteStopService(
            BusRouteRepository busRouteRepository,
            BusRouteStopOccurrenceRepository occurrenceRepository
    ) {
        this.busRouteRepository = busRouteRepository;
        this.occurrenceRepository = occurrenceRepository;
    }

    @Transactional(readOnly = true)
    public List<BusRouteStopResponse> findAll(Long routeId) {
        if (routeId == null || routeId <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        BusRoute route = busRouteRepository.findById(routeId)
                .orElseThrow(() -> new ApiException(ErrorCode.BUS_ROUTE_NOT_FOUND));
        List<BusRouteStopOccurrence> occurrences = occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route);
        if (occurrences.isEmpty()) {
            throw new IllegalStateException("Bus route metadata must contain at least one stop occurrence");
        }

        return java.util.stream.IntStream.range(0, occurrences.size())
                .mapToObj(index -> toResponse(occurrences, index))
                .toList();
    }

    private static BusRouteStopResponse toResponse(List<BusRouteStopOccurrence> occurrences, int index) {
        BusRouteStopOccurrence occurrence = occurrences.get(index);
        return new BusRouteStopResponse(
                occurrence.getId(),
                occurrence.getStop().getStopName(),
                occurrence.getStopOrder(),
                index > 0,
                index < occurrences.size() - 1
        );
    }
}
