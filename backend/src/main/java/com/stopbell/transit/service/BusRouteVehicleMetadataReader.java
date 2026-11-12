package com.stopbell.transit.service;

import java.util.List;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.domain.BusRouteType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import org.springframework.stereotype.Component;

/** Reads route metadata for the vehicle view without touching Boarding/Alarm metadata services. */
@Component
public class BusRouteVehicleMetadataReader {

    public record Occurrence(Long id, String externalStopId, int order) {
    }

    public record RouteMeta(Long routeId, TransitProvider provider, String externalRouteId, String cityCode,
            BusRouteType routeType, List<Occurrence> traversal) {
        public RouteMeta {
            traversal = List.copyOf(traversal);
        }
    }

    private final BusRouteRepository routes;
    private final BusRouteStopOccurrenceRepository occurrences;

    public BusRouteVehicleMetadataReader(BusRouteRepository routes, BusRouteStopOccurrenceRepository occurrences) {
        this.routes = routes;
        this.occurrences = occurrences;
    }

    public RouteMeta read(Long routeId) {
        if (routeId == null || routeId <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        BusRoute route = routes.findById(routeId).orElseThrow(() -> new ApiException(ErrorCode.BUS_ROUTE_NOT_FOUND));
        var list = occurrences.findAllByRouteOrderByStopOrderAsc(route).stream()
                .map(o -> new Occurrence(o.getId(), o.getStop().getExternalStopId(), o.getStopOrder())).toList();
        if (list.isEmpty()) {
            throw new IllegalStateException("Bus route metadata must contain at least one stop occurrence");
        }
        return new RouteMeta(route.getId(), route.getProvider(), route.getExternalRouteId(), route.getCityCode(),
                route.getRouteType(), list);
    }
}
