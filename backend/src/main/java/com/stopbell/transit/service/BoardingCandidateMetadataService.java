package com.stopbell.transit.service;

import java.util.List;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardingCandidateMetadataService {
    private final BusRouteRepository routeRepository;
    private final BusRouteStopOccurrenceRepository occurrenceRepository;

    public BoardingCandidateMetadataService(
            BusRouteRepository routeRepository, BusRouteStopOccurrenceRepository occurrenceRepository
    ) {
        this.routeRepository = routeRepository;
        this.occurrenceRepository = occurrenceRepository;
    }

    @Transactional(readOnly = true)
    public BoardingRouteSnapshot read(Long routeId, Long targetId, Long boardingId) {
        if (routeId == null || routeId <= 0 || targetId == null || targetId <= 0
                || (boardingId != null && boardingId <= 0)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        BusRoute route = routeRepository.findById(routeId)
                .orElseThrow(() -> new ApiException(ErrorCode.BUS_ROUTE_NOT_FOUND));
        List<BoardingRouteSnapshot.Occurrence> traversal = occurrenceRepository
                .findAllByRouteOrderByStopOrderAsc(route).stream()
                .map(occurrence -> new BoardingRouteSnapshot.Occurrence(
                        occurrence.getId(), occurrence.getStop().getExternalStopId(),
                        occurrence.getStopOrder(), occurrence.getStop().getStopName()))
                .toList();
        validateOccurrence(targetId, traversal);
        if (boardingId != null) {
            validateOccurrence(boardingId, traversal);
        }
        return new BoardingRouteSnapshot(routeId, route.getProvider(), route.getExternalRouteId(),
                route.getCityCode(), targetId, boardingId, traversal);
    }

    private void validateOccurrence(Long id, List<BoardingRouteSnapshot.Occurrence> traversal) {
        if (traversal.stream().anyMatch(occurrence -> occurrence.id().equals(id))) {
            return;
        }
        if (!occurrenceRepository.existsById(id)) {
            throw new ApiException(ErrorCode.TARGET_STOP_OCCURRENCE_NOT_FOUND);
        }
        throw new ApiException(ErrorCode.INVALID_REQUEST);
    }
}
