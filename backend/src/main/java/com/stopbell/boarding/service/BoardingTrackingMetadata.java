package com.stopbell.boarding.service;

import java.util.Objects;

import com.stopbell.boarding.domain.BoardingTrackingSnapshot;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BoardingTrackingMetadata {
    private final BusRouteRepository routes;
    private final BusRouteStopOccurrenceRepository occurrences;

    public BoardingTrackingMetadata(BusRouteRepository routes, BusRouteStopOccurrenceRepository occurrences) {
        this.routes = routes;
        this.occurrences = occurrences;
    }

    @Transactional(readOnly = true)
    public boolean matches(BoardingTrackingSnapshot session) {
        var route = routes.findByProviderAndExternalRouteId(session.provider(), session.routeId()).orElse(null);
        if (route == null || route.getProvider() != session.provider()
                || !route.getExternalRouteId().equals(session.routeId())
                || !Objects.equals(route.getCityCode(), session.cityCode())) {
            return false;
        }
        var current = occurrences.findAllByRouteOrderByStopOrderAsc(route);
        var saved = session.traversal().occurrences();
        if (current.size() != saved.size()) {
            return false;
        }
        for (int index = 0; index < current.size(); index++) {
            var occurrence = current.get(index);
            var original = saved.get(index);
            if (!occurrence.getId().equals(original.occurrenceId()) || occurrence.getStopOrder() != original.order()
                    || !occurrence.getStop().getExternalStopId().equals(original.externalStopId())) {
                return false;
            }
        }
        return true;
    }
}
