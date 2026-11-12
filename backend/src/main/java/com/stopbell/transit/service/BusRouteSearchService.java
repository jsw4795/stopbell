package com.stopbell.transit.service;

import java.util.List;
import java.util.Map;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.dto.BusRouteSearchResponse;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.repository.BusRouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusRouteSearchService {

    private final BusRouteRepository busRouteRepository;
    private final BusRouteEndpointResolver endpointResolver;
    private final BusRouteRegionNameResolver regionNameResolver = new BusRouteRegionNameResolver();

    public BusRouteSearchService(BusRouteRepository busRouteRepository, BusRouteEndpointResolver endpointResolver) {
        this.busRouteRepository = busRouteRepository;
        this.endpointResolver = endpointResolver;
    }

    @Transactional(readOnly = true)
    public List<BusRouteSearchResponse> search(String query) {
        List<BusRoute> routes = busRouteRepository.searchByRouteNumberPrefix(normalizeQuery(query));
        Map<Long, BusRouteEndpointResolver.Endpoints> endpoints = endpointResolver.resolve(routes);

        return routes.stream().map(route -> {
            var routeEndpoints = endpoints.getOrDefault(route.getId(), BusRouteEndpointResolver.Endpoints.UNKNOWN);
            return new BusRouteSearchResponse(route.getId(), route.getRouteNumber(),
                    regionNameResolver.resolve(route), route.getProvider(), route.getRouteType(),
                    regionNameResolver.category(route), routeEndpoints.start(), routeEndpoints.end());
        }).toList();
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        String normalizedQuery = query.trim();
        if (normalizedQuery.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return normalizedQuery;
    }
}
