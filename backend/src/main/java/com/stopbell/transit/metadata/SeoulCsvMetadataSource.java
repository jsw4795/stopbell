package com.stopbell.transit.metadata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.BusMetadataSource;
import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;
import com.stopbell.transit.service.CompleteBusMetadataSnapshot;
import org.springframework.core.io.Resource;

/**
 * Parser for the 2026-09-22 Seoul T Data CSV contract.
 *
 * <p>The provider scope follows the documented T Data route and stop type semantics.</p>
 */
public final class SeoulCsvMetadataSource implements BusMetadataSource {

    private static final List<String> ROUTE_HEADERS = List.of("노선ID", "노선명", "노선유형", "거리");
    private static final List<String> ROUTE_STOP_HEADERS = List.of("노선ID", "노드ID", "링크거리누계", "정류장순번");
    private static final List<String> STOP_HEADERS = List.of(
            "정류장ID", "정류장명", "정류장유형", "정류장번호", "좌표X", "좌표Y", "BIT설치여부"
    );
    private static final Set<String> INCLUDED_ROUTE_TYPES = Set.of("공항", "마을", "간선", "지선", "순환", "광역", "관광");
    private static final Set<String> EXCLUDED_ROUTE_TYPES = Set.of("경기", "인천");
    private static final Set<String> BUS_STOP_TYPES = Set.of(
            "일반차로", "중앙차로", "일반중앙차로", "가로변전일", "가로변시간", "마을버스", "가상정류장"
    );
    private static final String FERRY_STOP_TYPE = "선착장";
    private static final Set<String> KNOWN_STOP_TYPES = Set.of(
            "일반차로", "중앙차로", "일반중앙차로", "가로변전일", "가로변시간", "마을버스", "가상정류장", FERRY_STOP_TYPE
    );

    private final Resource routeMaster;
    private final Resource routeStopMaster;
    private final Resource stopMaster;

    public SeoulCsvMetadataSource(Resource routeMaster, Resource routeStopMaster, Resource stopMaster) {
        this.routeMaster = routeMaster;
        this.routeStopMaster = routeStopMaster;
        this.stopMaster = stopMaster;
    }

    @Override
    public TransitProvider provider() {
        return TransitProvider.SEOUL_BUS;
    }

    @Override
    public CompleteBusMetadataSnapshot fetchCompleteSnapshot() {
        CompleteSourceSnapshot sourceSnapshot = fetchCompleteSourceSnapshot();
        List<BusRouteMetadataSnapshot> routes = new ArrayList<>();
        for (SourceRouteSnapshot route : sourceSnapshot.routes()) {
            if (scope(route) == Scope.INCLUDE) {
                routes.add(new BusRouteMetadataSnapshot(
                        provider(),
                        route.externalRouteId(),
                        route.routeNumber(),
                        null,
                        route.occurrences().stream().map(SourceStopOccurrenceSnapshot::toMetadataSnapshot).toList()
                ));
            }
        }
        return new CompleteBusMetadataSnapshot(provider(), routes);
    }

    public CompleteSourceSnapshot fetchCompleteSourceSnapshot() {
        try {
            Map<String, Map<String, String>> routes = indexBy(
                    readRows("route master", routeMaster, ROUTE_HEADERS), "노선ID"
            );
            Map<String, Map<String, String>> stops = indexBy(
                    readRows("stop master", stopMaster, STOP_HEADERS), "정류장ID"
            );
            List<Map<String, String>> routeStops = readRows("route-stop master", routeStopMaster, ROUTE_STOP_HEADERS);

            if (routes.isEmpty() || stops.isEmpty() || routeStops.isEmpty()) {
                throw new IllegalStateException("Seoul CSV source must not be empty");
            }

            Map<String, List<SourceStopOccurrenceSnapshot>> occurrencesByRoute = new HashMap<>();
            Map<String, Set<Integer>> stopOrdersByRoute = new HashMap<>();
            for (Map<String, String> routeStop : routeStops) {
                String routeId = required(routeStop, "노선ID");
                String stopId = required(routeStop, "노드ID");
                int stopOrder = positiveInteger(required(routeStop, "정류장순번"), "정류장순번");

                if (!routes.containsKey(routeId)) {
                    throw new IllegalStateException("Route-stop CSV references an unknown route: " + routeId);
                }
                Map<String, String> stop = stops.get(stopId);
                if (stop == null) {
                    throw new IllegalStateException("Route-stop CSV references an unknown stop: " + stopId);
                }
                if (!stopOrdersByRoute.computeIfAbsent(routeId, ignored -> new LinkedHashSet<>()).add(stopOrder)) {
                    throw new IllegalStateException("Route-stop CSV contains duplicate stop order for route: " + routeId);
                }

                occurrencesByRoute.computeIfAbsent(routeId, ignored -> new ArrayList<>())
                        .add(new SourceStopOccurrenceSnapshot(
                                stopId,
                                required(stop, "정류장명"),
                                coordinate(required(stop, "좌표Y"), "latitude"),
                                coordinate(required(stop, "좌표X"), "longitude"),
                                stopOrder,
                                required(stop, "정류장유형")
                        ));
            }
            validateStopTypes(stops);

            List<SourceRouteSnapshot> snapshots = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> route : routes.entrySet()) {
                List<SourceStopOccurrenceSnapshot> occurrences = new ArrayList<>(
                        occurrencesByRoute.getOrDefault(route.getKey(), List.of())
                );
                occurrences.sort(Comparator.comparingInt(SourceStopOccurrenceSnapshot::stopOrder));
                snapshots.add(new SourceRouteSnapshot(
                        route.getKey(),
                        required(route.getValue(), "노선명"),
                        optional(route.getValue(), "노선유형"),
                        occurrences
                ));
            }
            return new CompleteSourceSnapshot(snapshots);
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalStateException("Unable to parse Seoul metadata CSV", exception);
        }
    }

    private static Map<String, Map<String, String>> indexBy(List<Map<String, String>> rows, String identityColumn) {
        Map<String, Map<String, String>> indexed = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String identity = required(row, identityColumn);
            if (indexed.put(identity, row) != null) {
                throw new IllegalStateException("CSV contains duplicate identity for " + identityColumn + ": " + identity);
            }
        }
        return indexed;
    }

    private static void validateStopTypes(Map<String, Map<String, String>> stops) {
        for (Map<String, String> stop : stops.values()) {
            String stopType = required(stop, "정류장유형");
            if (!KNOWN_STOP_TYPES.contains(stopType)) {
                throw new IllegalStateException("Seoul CSV contains an unknown stop type: " + stopType);
            }
        }
    }

    private static Scope scope(SourceRouteSnapshot route) {
        if (route.routeType() == null) {
            if (route.occurrences().isEmpty()) {
                throw new IllegalStateException("Route with no route type has no occurrences: " + route.externalRouteId());
            }
            boolean allBusStops = route.occurrences().stream()
                    .allMatch(occurrence -> BUS_STOP_TYPES.contains(occurrence.stopType()));
            if (allBusStops) {
                return Scope.INCLUDE;
            }
            boolean allFerryStops = route.occurrences().stream()
                    .allMatch(occurrence -> FERRY_STOP_TYPE.equals(occurrence.stopType()));
            if (allFerryStops) {
                return Scope.EXCLUDE;
            }
            throw new IllegalStateException("Route with no route type has mixed stop types: " + route.externalRouteId());
        }
        if (INCLUDED_ROUTE_TYPES.contains(route.routeType())) {
            return Scope.INCLUDE;
        }
        if (EXCLUDED_ROUTE_TYPES.contains(route.routeType())) {
            return Scope.EXCLUDE;
        }
        throw new IllegalStateException("Seoul CSV contains an unknown route type: " + route.routeType());
    }

    private static List<Map<String, String>> readRows(String sourceName, Resource resource, List<String> expectedHeaders)
            throws IOException {
        if (resource == null || !resource.exists()) {
            throw new IllegalStateException("Required Seoul CSV source is missing: " + sourceName);
        }
        try (InputStream inputStream = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     inputStream,
                     StandardCharsets.UTF_8.newDecoder()
                             .onMalformedInput(CodingErrorAction.REPORT)
                             .onUnmappableCharacter(CodingErrorAction.REPORT)
             ))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalStateException("Seoul CSV is missing a header: " + sourceName);
            }
            List<String> headers = csv(header);
            if (!headers.isEmpty()) {
                headers.set(0, removeBom(headers.getFirst()));
            }
            if (!headers.equals(expectedHeaders)) {
                throw new IllegalStateException("Seoul CSV headers do not match the source contract: " + sourceName);
            }

            List<Map<String, String>> rows = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null;) {
                List<String> values = csv(line);
                if (values.size() != headers.size()) {
                    throw new IllegalStateException("Malformed Seoul CSV row: " + sourceName);
                }
                Map<String, String> row = new HashMap<>();
                for (int index = 0; index < headers.size(); index++) {
                    row.put(headers.get(index), values.get(index).trim());
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static String removeBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static List<String> csv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append(character);
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        if (quoted) {
            throw new IllegalStateException("Malformed quoted Seoul CSV row");
        }
        values.add(value.toString());
        return values;
    }

    private static String required(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Seoul CSV required value is missing or blank: " + column);
        }
        return value;
    }

    private static String optional(Map<String, String> row, String column) {
        String value = row.get(column);
        return value == null || value.isBlank() ? null : value;
    }

    private static int positiveInteger(String value, String column) {
        int number = Integer.parseInt(value);
        if (number <= 0) {
            throw new IllegalStateException("Seoul CSV value must be a positive integer: " + column);
        }
        return number;
    }

    private static BigDecimal coordinate(String value, String coordinateName) {
        BigDecimal coordinate = new BigDecimal(value);
        if ("latitude".equals(coordinateName)
                && (coordinate.compareTo(BigDecimal.valueOf(-90)) < 0 || coordinate.compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new IllegalStateException("Seoul CSV latitude is outside the valid range");
        }
        if ("longitude".equals(coordinateName)
                && (coordinate.compareTo(BigDecimal.valueOf(-180)) < 0 || coordinate.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new IllegalStateException("Seoul CSV longitude is outside the valid range");
        }
        return coordinate;
    }

    /** A fully validated T Data source snapshot before any V1 provider-scope decision is applied. */
    public record CompleteSourceSnapshot(List<SourceRouteSnapshot> routes) {

        public CompleteSourceSnapshot {
            routes = List.copyOf(Objects.requireNonNull(routes, "Routes must not be null"));
            if (routes.isEmpty()) {
                throw new IllegalArgumentException("A complete Seoul T Data source snapshot must not be empty");
            }
            Set<String> routeIds = new LinkedHashSet<>();
            for (SourceRouteSnapshot route : routes) {
                if (route == null || !routeIds.add(route.externalRouteId())) {
                    throw new IllegalArgumentException("Complete Seoul T Data source snapshot contains duplicate route identity");
                }
            }
        }
    }

    /** Source-only route metadata; routeType is retained only until provider scope is explicitly decided. */
    public record SourceRouteSnapshot(
            String externalRouteId,
            String routeNumber,
            String routeType,
            List<SourceStopOccurrenceSnapshot> occurrences
    ) {

        public SourceRouteSnapshot {
            if (externalRouteId == null || externalRouteId.isBlank()) {
                throw new IllegalArgumentException("External route ID must not be blank");
            }
            if (routeNumber == null || routeNumber.isBlank()) {
                throw new IllegalArgumentException("Route number must not be blank");
            }
            occurrences = List.copyOf(Objects.requireNonNull(occurrences, "Occurrences must not be null"));
        }
    }

    /** Source-only occurrence metadata retains stopType until the null route-type scope is decided. */
    public record SourceStopOccurrenceSnapshot(
            String externalStopId,
            String stopName,
            BigDecimal latitude,
            BigDecimal longitude,
            int stopOrder,
            String stopType
    ) {

        public BusStopOccurrenceMetadataSnapshot toMetadataSnapshot() {
            return new BusStopOccurrenceMetadataSnapshot(externalStopId, stopName, latitude, longitude, stopOrder);
        }
    }

    private enum Scope {
        INCLUDE,
        EXCLUDE
    }
}
