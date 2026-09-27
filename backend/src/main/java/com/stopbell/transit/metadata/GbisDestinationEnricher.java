package com.stopbell.transit.metadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.stopbell.transit.service.BusRouteMetadataSnapshot;
import com.stopbell.transit.service.BusStopOccurrenceMetadataSnapshot;

/** Applies GBIS display metadata only after the complete TAGO traversal matches. */
public final class GbisDestinationEnricher {
    private GbisDestinationEnricher() { }

    public static Result enrich(List<BusRouteMetadataSnapshot> tagoRoutes, GbisBulkMetadata gbis) {
        List<BusRouteMetadataSnapshot> result = new ArrayList<>(tagoRoutes.size());
        Map<String, Integer> skipped = new HashMap<>();
        int enriched = 0;
        int destinations = 0;
        for (BusRouteMetadataSnapshot tago : tagoRoutes) {
            String routeId = candidate(tago.externalRouteId());
            GbisBulkMetadata.Route route = routeId == null ? null : gbis.routes().get(routeId);
            String reason = mismatch(tago, route);
            if (reason != null) {
                skipped.merge(reason, 1, Integer::sum);
                result.add(tago);
                continue;
            }
            Map<Integer, GbisBulkMetadata.Station> byOrder = new HashMap<>();
            for (GbisBulkMetadata.Station station : route.stations()) byOrder.put(station.order(), station);
            String turnName = byOrder.get(route.turnSeq()).stationName();
            String terminalName = route.stations().getLast().stationName();
            List<BusStopOccurrenceMetadataSnapshot> stops = tago.occurrences().stream()
                    .map(stop -> new BusStopOccurrenceMetadataSnapshot(stop.externalStopId(), stop.stopName(),
                            stop.latitude(), stop.longitude(), stop.stopOrder(),
                            stop.stopOrder() < route.turnSeq() ? turnName : terminalName))
                    .toList();
            result.add(new BusRouteMetadataSnapshot(tago.provider(), tago.externalRouteId(),
                    tago.routeNumber(), tago.cityCode(), stops));
            enriched++;
            destinations += stops.size();
        }
        return new Result(List.copyOf(result), enriched, destinations, Map.copyOf(skipped));
    }

    private static String mismatch(BusRouteMetadataSnapshot tago, GbisBulkMetadata.Route gbis) {
        if (gbis == null) return "candidate_missing";
        if (!tago.routeNumber().equals(gbis.routeName())) return "route_name";
        if (tago.occurrences().size() != gbis.stations().size()) return "occurrence_count";
        Map<Integer, GbisBulkMetadata.Station> byOrder = new HashMap<>();
        for (GbisBulkMetadata.Station station : gbis.stations()) byOrder.put(station.order(), station);
        for (BusStopOccurrenceMetadataSnapshot stop : tago.occurrences()) {
            GbisBulkMetadata.Station station = byOrder.get(stop.stopOrder());
            if (station == null) return "order";
            if (!station.stationId().equals(candidate(stop.externalStopId()))) return "stop_id";
        }
        return null;
    }

    private static String candidate(String tagoId) {
        return tagoId.startsWith("GGB") && tagoId.length() > 3 ? tagoId.substring(3) : null;
    }

    public record Result(List<BusRouteMetadataSnapshot> routes, int enrichedRoutes,
            int destinationOccurrences, Map<String, Integer> skippedRoutes) { }
}
