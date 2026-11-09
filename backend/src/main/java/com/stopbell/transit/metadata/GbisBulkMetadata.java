package com.stopbell.transit.metadata;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;

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
        return collect(routeBytes, stationBytes, routeVersion, stationVersion, false).normalized();
    }

    static GbisCollectedSnapshot parseWithRaw(byte[] routeBytes, byte[] stationBytes,
            String routeVersion, String stationVersion) {
        return collect(routeBytes, stationBytes, routeVersion, stationVersion, true);
    }

    private static GbisCollectedSnapshot collect(byte[] routeBytes, byte[] stationBytes,
            String routeVersion, String stationVersion, boolean preserveRaw) {
        if (routeVersion == null || routeVersion.isBlank() || !routeVersion.equals(stationVersion)) {
            throw new IllegalArgumentException("GBIS bulk versions do not match");
        }
        Map<String, Route> routes = new HashMap<>();
        List<GbisCollectedSnapshot.RawRow> rawRows = new ArrayList<>();
        rows(routeBytes, ROUTE_COLUMNS, row -> {
            String id = row.required("routeId");
            Route route = new Route(id, row.required("routeName"), row.positive("turnSeq"), row.optional("routeTypeCd"), new ArrayList<>());
            if (routes.putIfAbsent(id, route) != null) {
                throw new IllegalArgumentException("GBIS duplicate route identity");
            }
            if (preserveRaw) rawRows.add(new GbisCollectedSnapshot.RawRow(TransitSourceSystem.GBIS,
                    TransitSourceDataKind.ROUTE, id, id, null, row.rawFields()));
        });
        Map<String, Set<Integer>> orders = new HashMap<>();
        rows(stationBytes, STATION_COLUMNS, row -> {
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
            if (preserveRaw) rawRows.add(new GbisCollectedSnapshot.RawRow(TransitSourceSystem.GBIS,
                    TransitSourceDataKind.OCCURRENCE, routeId.length() + ":" + routeId + ":" + order,
                    routeId, order, row.rawFields()));
        });
        Map<String, Route> validated = new HashMap<>();
        for (Route route : routes.values()) {
            route.stations().sort(java.util.Comparator.comparingInt(Station::order));
            Set<Integer> routeOrders = orders.get(route.routeId());
            if (routeOrders == null || !routeOrders.contains(route.turnSeq())) {
                throw new IllegalArgumentException("GBIS route has invalid turn sequence or no stations");
            }
            validated.put(route.routeId(), new Route(route.routeId(), route.routeName(),
                    route.turnSeq(), route.routeTypeCd(), List.copyOf(route.stations())));
        }
        return new GbisCollectedSnapshot(new GbisBulkMetadata(validated), rawRows, routeVersion);
    }

    private static void rows(byte[] bytes, List<String> requiredColumns, Consumer<Row> consumer) {
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
        int count = 0;
        for (int i = 1; i < records.length; i++) {
            if (i == records.length - 1 && records[i].isEmpty()) continue;
            if (records[i].isBlank()) throw new IllegalArgumentException("GBIS blank record");
            String[] fields = records[i].split("\\|", -1);
            if (fields.length != header.length) throw new IllegalArgumentException("GBIS corrupt record");
            consumer.accept(new Row(columns, fields));
            count++;
        }
        if (count == 0) throw new IllegalArgumentException("GBIS bulk has no records");
    }

    private record Row(Map<String, Integer> columns, String[] fields) {
        Map<String, String> rawFields() {
            Map<String, String> raw = new LinkedHashMap<>();
            columns.forEach((name, index) -> raw.put(name, fields[index]));
            return raw;
        }
        String optional(String name) {
            Integer index = columns.get(name);
            return index == null ? null : fields[index].trim();
        }
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

    public record Route(String routeId, String routeName, int turnSeq, String routeTypeCd, List<Station> stations) {
        public Route(String routeId, String routeName, int turnSeq, List<Station> stations) {
            this(routeId, routeName, turnSeq, null, stations);
        }
    }
    public record Station(int order, String stationId, String stationName) { }
}
