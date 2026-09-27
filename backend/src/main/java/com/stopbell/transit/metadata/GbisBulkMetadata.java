package com.stopbell.transit.metadata;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated GBIS bulk data. IDs here are lookup candidates, never StopBell identities. */
public record GbisBulkMetadata(Map<String, Route> routes) {
    private static final List<String> ROUTE_COLUMNS = List.of("routeId", "routeName", "turnSeq");
    private static final List<String> STATION_COLUMNS = List.of(
            "routeId", "routeName", "upDown", "staOrder", "stationId", "stationName", "x", "y");

    public GbisBulkMetadata {
        routes = Map.copyOf(routes);
        if (routes.isEmpty()) throw new IllegalArgumentException("GBIS bulk has no routes");
    }

    public static GbisBulkMetadata parse(byte[] routeBytes, byte[] stationBytes,
            String routeVersion, String stationVersion) {
        if (routeVersion == null || routeVersion.isBlank() || !routeVersion.equals(stationVersion)) {
            throw new IllegalArgumentException("GBIS bulk versions do not match");
        }
        Map<String, Route> routes = new HashMap<>();
        for (Row row : rows(routeBytes, ROUTE_COLUMNS)) {
            String id = row.required("routeId");
            Route route = new Route(id, row.required("routeName"), row.positive("turnSeq"), new ArrayList<>());
            if (routes.putIfAbsent(id, route) != null) {
                throw new IllegalArgumentException("GBIS duplicate route identity");
            }
        }
        Map<String, Set<Integer>> orders = new HashMap<>();
        for (Row row : rows(stationBytes, STATION_COLUMNS)) {
            String routeId = row.required("routeId");
            Route route = routes.get(routeId);
            if (route == null) throw new IllegalArgumentException("GBIS RouteStation has no route");
            if (!route.routeName().equals(row.required("routeName"))) {
                throw new IllegalArgumentException("GBIS RouteStation route name mismatch");
            }
            int order = row.positive("staOrder");
            if (!orders.computeIfAbsent(routeId, ignored -> new HashSet<>()).add(order)) {
                throw new IllegalArgumentException("GBIS duplicate route station order");
            }
            route.stations().add(new Station(order, row.required("stationId"), row.required("stationName")));
        }
        Map<String, Route> validated = new HashMap<>();
        for (Route route : routes.values()) {
            route.stations().sort(java.util.Comparator.comparingInt(Station::order));
            Set<Integer> routeOrders = orders.get(route.routeId());
            if (routeOrders == null || !routeOrders.contains(route.turnSeq())) {
                throw new IllegalArgumentException("GBIS route has invalid turn sequence or no stations");
            }
            validated.put(route.routeId(), new Route(route.routeId(), route.routeName(),
                    route.turnSeq(), List.copyOf(route.stations())));
        }
        return new GbisBulkMetadata(validated);
    }

    private static List<Row> rows(byte[] bytes, List<String> requiredColumns) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("GBIS bulk source is empty");
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("GBIS bulk is not valid UTF-8", e);
        }
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        String[] records = source.split("\\^", -1);
        if (records.length < 2) throw new IllegalArgumentException("GBIS bulk has no records");
        String[] header = records[0].split("\\|", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            if (columns.putIfAbsent(header[i], i) != null) {
                throw new IllegalArgumentException("GBIS duplicate header");
            }
        }
        if (!columns.keySet().containsAll(requiredColumns)) {
            throw new IllegalArgumentException("GBIS required header missing");
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.length; i++) {
            if (i == records.length - 1 && records[i].isEmpty()) continue;
            if (records[i].isBlank()) throw new IllegalArgumentException("GBIS blank record");
            String[] fields = records[i].split("\\|", -1);
            if (fields.length != header.length) throw new IllegalArgumentException("GBIS corrupt record");
            rows.add(new Row(columns, fields));
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("GBIS bulk has no records");
        return rows;
    }

    private record Row(Map<String, Integer> columns, String[] fields) {
        String required(String name) {
            String value = fields[columns.get(name)].trim();
            if (value.isEmpty()) throw new IllegalArgumentException("GBIS required value missing: " + name);
            return value;
        }
        int positive(String name) {
            try {
                int value = Integer.parseInt(required(name));
                if (value > 0) return value;
            } catch (NumberFormatException ignored) { }
            throw new IllegalArgumentException("GBIS invalid positive value: " + name);
        }
    }

    public record Route(String routeId, String routeName, int turnSeq, List<Station> stations) { }
    public record Station(int order, String stationId, String stationName) { }
}
