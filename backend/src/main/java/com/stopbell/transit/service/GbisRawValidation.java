package com.stopbell.transit.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.stopbell.transit.domain.TransitSourceDataKind;
import com.stopbell.transit.domain.TransitSourceSystem;
import com.stopbell.transit.metadata.GbisCollectedSnapshot;
import com.stopbell.transit.metadata.TagoMetadataSource;

/** Checks the complete GBIS raw/normalized correspondence before any database writes. */
final class GbisRawValidation {
    private GbisRawValidation() { }

    static void validate(GbisCollectedSnapshot snapshot) {
        if (snapshot.normalized() == null || snapshot.sourceVersion() == null
                || snapshot.sourceVersion().isBlank() || snapshot.sourceVersion().length() > 100) {
            throw new IllegalStateException("Invalid GBIS snapshot or version");
        }
        Map<String, GbisCollectedSnapshot.RawRow> routes = new HashMap<>();
        Map<String, GbisCollectedSnapshot.RawRow> occurrences = new HashMap<>();
        for (var row : snapshot.rawRows()) {
            if (row.sourceSystem() != TransitSourceSystem.GBIS || row.externalRouteId() == null
                    || row.externalRouteId().isBlank() || row.sourceKey() == null || row.sourceKey().isBlank()
                    || !row.sourceKey().equals(row.sourceKey().strip()) || row.sourceKey().length() > 255
                    || !row.externalRouteId().equals(trim(row.fields().get("routeId")))) {
                throw new IllegalStateException("Invalid GBIS raw identity");
            }
            Map<String, GbisCollectedSnapshot.RawRow> index;
            if (row.dataKind() == TransitSourceDataKind.ROUTE) {
                if (row.stopOrder() != null || !row.sourceKey().equals(row.externalRouteId())) {
                    throw new IllegalStateException("Invalid GBIS route key");
                }
                index = routes;
            } else if (row.dataKind() == TransitSourceDataKind.OCCURRENCE) {
                if (row.stopOrder() == null || row.stopOrder() <= 0
                        || !row.sourceKey().equals(TagoMetadataSource.occurrenceSourceKey(row.externalRouteId(), row.stopOrder()))
                        || !sameOrder(row.fields().get("staOrder"), row.stopOrder())) {
                    throw new IllegalStateException("Invalid GBIS occurrence key");
                }
                index = occurrences;
            } else throw new IllegalStateException("Invalid GBIS raw kind");
            if (index.put(row.sourceKey(), row) != null) throw new IllegalStateException("Duplicate GBIS raw key");
        }
        int expected = 0;
        for (var entry : snapshot.normalized().routes().entrySet()) {
            var route = entry.getValue();
            var raw = routes.remove(entry.getKey());
            if (raw == null || route.routeId() == null || route.routeId().isBlank()
                    || !route.routeId().equals(route.routeId().trim()) || route.routeName() == null
                    || route.routeName().isBlank() || route.turnSeq() <= 0 || route.stations() == null
                    || !entry.getKey().equals(route.routeId())
                    || !Objects.equals(route.routeName(), trim(raw.fields().get("routeName")))
                    || !sameOrder(raw.fields().get("turnSeq"), route.turnSeq())) {
                throw new IllegalStateException("GBIS raw route does not match snapshot");
            }
            boolean turnFound = false;
            for (var station : route.stations()) {
                if (station.order() <= 0 || station.stationId() == null || station.stationId().isBlank()
                        || station.stationName() == null || station.stationName().isBlank()) {
                    throw new IllegalStateException("Invalid GBIS normalized station");
                }
                if (station.order() == route.turnSeq()) turnFound = true;
                String key = TagoMetadataSource.occurrenceSourceKey(route.routeId(), station.order());
                var occurrence = occurrences.remove(key);
                if (occurrence == null || !Objects.equals(route.routeId(), occurrence.externalRouteId())
                        || !Objects.equals(station.order(), occurrence.stopOrder())
                        || !Objects.equals(station.stationId(), trim(occurrence.fields().get("stationId")))
                        || !Objects.equals(route.routeName(), trim(occurrence.fields().get("routeName")))
                        || !Objects.equals(station.stationName(), trim(occurrence.fields().get("stationName")))) {
                    throw new IllegalStateException("GBIS raw occurrence does not match snapshot");
                }
                expected++;
            }
            if (!turnFound) throw new IllegalStateException("GBIS turn station missing");
        }
        if (!routes.isEmpty() || !occurrences.isEmpty()
                || snapshot.rawRows().size() != snapshot.normalized().routes().size() + expected) {
            throw new IllegalStateException("Unexpected GBIS raw rows");
        }
    }

    private static String trim(String value) { return value == null ? null : value.trim(); }
    private static boolean sameOrder(String value, int expected) {
        try { return value != null && Integer.parseInt(value.trim()) == expected; }
        catch (NumberFormatException e) { return false; }
    }
}
