package com.stopbell.transit.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.TransitSourceMetadata;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.TransitSourceMetadataRepository;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 공식 원본(TAGO 노선 원본, 서울 TOPIS 기종점)에서 검증된 노선 기·종점만 제공한다.
 */
@Component
public class BusRouteEndpointResolver {

    private final BusRouteRepository busRouteRepository;
    private final TransitSourceMetadataRepository sourceMetadataRepository;
    private final ObjectMapper objectMapper;

    public BusRouteEndpointResolver(BusRouteRepository busRouteRepository,
            TransitSourceMetadataRepository sourceMetadataRepository, ObjectMapper objectMapper) {
        this.busRouteRepository = busRouteRepository;
        this.sourceMetadataRepository = sourceMetadataRepository;
        this.objectMapper = objectMapper;
    }

    public Map<Long, Endpoints> resolve(List<BusRoute> routes) {
        List<String> tagoKeys = routes.stream()
                .filter(route -> route.getProvider() == TransitProvider.TAGO)
                .map(BusRoute::getExternalRouteId)
                .distinct()
                .toList();
        Map<String, TransitSourceMetadata> tagoRows = tagoKeys.isEmpty() ? Map.of()
                : sourceMetadataRepository.findAllBySourceSystemAndDataKindAndSourceKeyIn(
                        TransitSourceSystem.TAGO, TransitSourceDataKind.ROUTE, tagoKeys).stream()
                        .collect(Collectors.toMap(TransitSourceMetadata::getSourceKey, Function.identity()));

        List<Long> seoulIds = routes.stream()
                .filter(route -> route.getProvider() == TransitProvider.SEOUL_BUS)
                .map(BusRoute::getId)
                .distinct()
                .toList();
        Map<Long, Endpoints> seoulEndpoints = seoulIds.isEmpty() ? Map.of()
                : busRouteRepository.findSeoulEndpoints(seoulIds).stream()
                        .collect(Collectors.toMap(BusRouteRepository.SeoulEndpoints::getRouteId,
                                row -> endpointPair(row.getStartStopName(), row.getEndStopName())));

        Map<Long, Endpoints> result = new HashMap<>();
        for (BusRoute route : routes) {
            result.put(route.getId(), switch (route.getProvider()) {
                case TAGO -> tagoEndpoints(route, tagoRows.get(route.getExternalRouteId()));
                case SEOUL_BUS -> seoulEndpoints.getOrDefault(route.getId(), Endpoints.UNKNOWN);
            });
        }
        return result;
    }

    private Endpoints tagoEndpoints(BusRoute route, TransitSourceMetadata source) {
        if (source == null || !route.getId().equals(source.getRouteId())) return Endpoints.UNKNOWN;

        JsonNode raw = objectMapper.readTree(source.getRawPayload());
        JsonNode routeNumber = raw.path("routeno");
        if (!route.getExternalRouteId().equals(raw.path("routeid").asString(""))
                || !(routeNumber.isString() || routeNumber.isIntegralNumber())
                || !route.getRouteNumber().equals(routeNumber.asString("").strip())) {
            return Endpoints.UNKNOWN;
        }

        return endpointPair(stopName(raw.path("startnodenm")), stopName(raw.path("endnodenm")));
    }

    /**
     * 부가 표시 정보이므로 원본 해석 실패는 기능 실패 대신 미확인으로 처리한다.
     */
    public Map<Long, Endpoints> resolveForDisplay(List<BusRoute> routes) {
        try {
            return resolve(routes);
        } catch (JacksonException exception) {
            return Map.of();
        }
    }

    private static Endpoints endpointPair(String start, String end) {
        if (start == null || end == null) return Endpoints.UNKNOWN;
        return start.isBlank() || end.isBlank() || start.equals(end)
                ? Endpoints.UNKNOWN : new Endpoints(start, end);
    }

    private static String stopName(JsonNode name) {
        return name.isString() ? name.asString().strip() : "";
    }

    public record Endpoints(String start, String end) {
        public static final Endpoints UNKNOWN = new Endpoints(null, null);
    }
}
