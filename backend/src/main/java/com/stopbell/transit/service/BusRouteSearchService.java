package com.stopbell.transit.service;

import java.util.List;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.dto.BusRouteSearchResponse;
import com.stopbell.transit.repository.BusRouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusRouteSearchService {

    private final BusRouteRepository busRouteRepository;
    private final BusRouteRegionNameResolver regionNameResolver = new BusRouteRegionNameResolver();

    public BusRouteSearchService(BusRouteRepository busRouteRepository) {
        this.busRouteRepository = busRouteRepository;
    }

    @Transactional(readOnly = true)
    public List<BusRouteSearchResponse> search(String query) {
        String normalizedQuery = normalizeQuery(query);
        return busRouteRepository
                .findTop50ByRouteNumberStartingWithIgnoreCaseOrderByRouteNumberAscIdAsc(normalizedQuery)
                .stream()
                .map(route -> new BusRouteSearchResponse(
                        route.getId(),
                        route.getRouteNumber(),
                        regionNameResolver.resolve(route)
                ))
                .toList();
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
