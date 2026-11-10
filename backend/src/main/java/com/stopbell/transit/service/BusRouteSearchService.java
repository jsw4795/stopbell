package com.stopbell.transit.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.dto.BusRouteSearchResponse;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.TransitSourceMetadata;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.TransitSourceMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class BusRouteSearchService {

    private final BusRouteRepository busRouteRepository;
    private final TransitSourceMetadataRepository sourceMetadataRepository;
    private final ObjectMapper objectMapper;
    private final BusRouteRegionNameResolver regionNameResolver = new BusRouteRegionNameResolver();

    public BusRouteSearchService(BusRouteRepository busRouteRepository,
            TransitSourceMetadataRepository sourceMetadataRepository, ObjectMapper objectMapper) {
        this.busRouteRepository = busRouteRepository;
        this.sourceMetadataRepository = sourceMetadataRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<BusRouteSearchResponse> search(String query) {
        List<BusRoute> routes = busRouteRepository.searchByRouteNumberPrefix(normalizeQuery(query));
        List<String> tagoKeys = routes.stream()
                .filter(route -> route.getProvider() == TransitProvider.TAGO)
                .map(BusRoute::getExternalRouteId)
                .toList();
        Map<String, TransitSourceMetadata> tagoRows = tagoKeys.isEmpty() ? Map.of()
                : sourceMetadataRepository.findAllBySourceSystemAndDataKindAndSourceKeyIn(
                        TransitSourceSystem.TAGO, TransitSourceDataKind.ROUTE, tagoKeys).stream()
                        .collect(Collectors.toMap(TransitSourceMetadata::getSourceKey, Function.identity()));

        return routes.stream().map(route -> {
            Endpoints endpoints = route.getProvider() == TransitProvider.TAGO
                    ? endpoints(route, tagoRows.get(route.getExternalRouteId())) : Endpoints.UNKNOWN;
            return new BusRouteSearchResponse(route.getId(), route.getRouteNumber(),
                    regionNameResolver.resolve(route), route.getProvider(), route.getRouteType(),
                    regionNameResolver.category(route), endpoints.start(), endpoints.end());
        }).toList();
    }

    private Endpoints endpoints(BusRoute route, TransitSourceMetadata source) {
        if (source == null || !route.getId().equals(source.getRouteId())) return Endpoints.UNKNOWN;

        JsonNode raw = objectMapper.readTree(source.getRawPayload());
        JsonNode routeNumber = raw.path("routeno");
        if (!route.getExternalRouteId().equals(raw.path("routeid").asString(""))
                || !(routeNumber.isString() || routeNumber.isIntegralNumber())
                || !route.getRouteNumber().equals(routeNumber.asString("").strip())) {
            return Endpoints.UNKNOWN;
        }

        String start = stopName(raw.path("startnodenm"));
        String end = stopName(raw.path("endnodenm"));
        return start.isEmpty() || end.isEmpty() || start.equals(end)
                ? Endpoints.UNKNOWN : new Endpoints(start, end);
    }

    private static String stopName(JsonNode name) {
        return name.isString() ? name.asString().strip() : "";
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

    private record Endpoints(String start, String end) {
        private static final Endpoints UNKNOWN = new Endpoints(null, null);
    }
}
