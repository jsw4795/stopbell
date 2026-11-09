package com.stopbell.transit.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.stopbell.transit.domain.TransitProvider;

/** Pure comparison against current database state, not a record of applied changes. */
public final class BusMetadataDiffService {
    public record Stop(TransitProvider provider, String externalStopId, String name,
            BigDecimal latitude, BigDecimal longitude) {
        public Stop {
            Objects.requireNonNull(provider);
            if (externalStopId == null || externalStopId.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Stop identity and name are required");
            }
            if ((latitude == null) != (longitude == null)) throw new IllegalArgumentException("Incomplete coordinates");
        }
    }

    /** All current routes and stops, including orphan stops. Other providers are ignored. */
    public record CurrentState(List<BusRouteMetadataSnapshot> routes, List<Stop> stops) {
        public CurrentState {
            routes = List.copyOf(routes);
            stops = List.copyOf(stops);
            Set<ProviderKey> routeKeys = new HashSet<>();
            Set<ProviderKey> stopKeys = new HashSet<>();
            for (var route : routes) {
                if (!routeKeys.add(new ProviderKey(route.provider(), route.externalRouteId())))
                    throw new IllegalArgumentException("Duplicate current route");
            }
            for (var stop : stops) {
                if (!stopKeys.add(new ProviderKey(stop.provider(), stop.externalStopId())))
                    throw new IllegalArgumentException("Duplicate current stop");
            }
            for (var route : routes) {
                for (var occurrence : route.occurrences()) {
                    if (!stopKeys.contains(new ProviderKey(route.provider(), occurrence.externalStopId())))
                        throw new IllegalArgumentException("Current occurrence references a missing provider stop");
                }
            }
        }
    }

    private record ProviderKey(TransitProvider provider, String id) { }
    private record PositionKey(String routeId, int order) { }

    public record FieldChange(String field, String before, String after) { }
    public record EntityChange(String id, List<FieldChange> fields) {
        public EntityChange { fields = List.copyOf(fields); }
    }
    public record OccurrenceKey(String routeId, String stopId, int stopOrder) { }
    public record OccurrenceChange(OccurrenceKey key, String beforeDestination, String afterDestination) { }
    /** Same position with a different stop; these are also counted as a removal and an addition. */
    public record PositionChange(String routeId, int stopOrder, String beforeStopId, String afterStopId) { }
    public record Result(TransitProvider provider, List<String> addedRoutes, List<EntityChange> modifiedRoutes,
            List<String> removedRoutes, List<String> unchangedRoutes,
            List<String> addedStops, List<EntityChange> modifiedStops, List<String> orphanStops,
            List<String> unchangedStops, List<String> conflictingStops,
            List<OccurrenceKey> addedOccurrences, List<OccurrenceKey> removedOccurrences,
            List<OccurrenceChange> modifiedOccurrences, List<OccurrenceKey> unchangedOccurrences,
            List<PositionChange> positionChanges) {
        public Result {
            addedRoutes = List.copyOf(addedRoutes); modifiedRoutes = List.copyOf(modifiedRoutes);
            removedRoutes = List.copyOf(removedRoutes); unchangedRoutes = List.copyOf(unchangedRoutes);
            addedStops = List.copyOf(addedStops); modifiedStops = List.copyOf(modifiedStops);
            orphanStops = List.copyOf(orphanStops); unchangedStops = List.copyOf(unchangedStops);
            conflictingStops = List.copyOf(conflictingStops); addedOccurrences = List.copyOf(addedOccurrences);
            removedOccurrences = List.copyOf(removedOccurrences); modifiedOccurrences = List.copyOf(modifiedOccurrences);
            unchangedOccurrences = List.copyOf(unchangedOccurrences); positionChanges = List.copyOf(positionChanges);
        }
    }

    private static final Comparator<OccurrenceKey> OCCURRENCE_ORDER = Comparator.comparing(OccurrenceKey::routeId)
            .thenComparingInt(OccurrenceKey::stopOrder).thenComparing(OccurrenceKey::stopId);

    public Result analyze(CurrentState current, CompleteBusMetadataSnapshot incoming) {
        Objects.requireNonNull(current); Objects.requireNonNull(incoming);
        TransitProvider provider = incoming.provider();
        Map<String, BusRouteMetadataSnapshot> oldRoutes = new HashMap<>(), newRoutes = new HashMap<>();
        Map<String, Stop> oldStops = new HashMap<>(), newStops = new HashMap<>();
        Set<String> conflicts = new TreeSet<>();
        current.routes().stream().filter(r -> r.provider() == provider).forEach(r -> oldRoutes.put(r.externalRouteId(), r));
        current.stops().stream().filter(s -> s.provider() == provider).forEach(s -> oldStops.put(s.externalStopId(), s));
        incoming.routes().forEach(r -> {
            newRoutes.put(r.externalRouteId(), r);
            r.occurrences().forEach(o -> {
                Stop candidate = new Stop(provider, o.externalStopId(), o.stopName(), o.latitude(), o.longitude());
                Stop previous = newStops.putIfAbsent(candidate.externalStopId(), candidate);
                if (previous != null && !sameStop(previous, candidate)) conflicts.add(candidate.externalStopId());
            });
        });
        List<String> addedRoutes = new ArrayList<>(), removedRoutes = new ArrayList<>(), unchangedRoutes = new ArrayList<>();
        List<EntityChange> modifiedRoutes = new ArrayList<>();
        for (String id : sortedUnion(oldRoutes.keySet(), newRoutes.keySet())) {
            var before = oldRoutes.get(id); var after = newRoutes.get(id);
            if (before == null) addedRoutes.add(id);
            else if (after == null) removedRoutes.add(id);
            else {
                List<FieldChange> changes = new ArrayList<>();
                change(changes, "routeNumber", before.routeNumber(), after.routeNumber());
                change(changes, "cityCode", before.cityCode(), after.cityCode());
                if (after.routeTypeObserved()) change(changes, "routeType",
                        before.routeType() == null ? null : before.routeType().name(),
                        after.routeType() == null ? null : after.routeType().name());
                if (changes.isEmpty()) unchangedRoutes.add(id); else modifiedRoutes.add(new EntityChange(id, changes));
            }
        }
        List<String> addedStops = new ArrayList<>(), orphanStops = new ArrayList<>(), unchangedStops = new ArrayList<>();
        List<EntityChange> modifiedStops = new ArrayList<>();
        for (String id : sortedUnion(oldStops.keySet(), newStops.keySet())) {
            Stop before = oldStops.get(id), after = newStops.get(id);
            if (after == null) orphanStops.add(id);
            else if (before == null) addedStops.add(id);
            else if (conflicts.contains(id)) continue; // Reconciliation's final value depends on source order.
            else {
                List<FieldChange> changes = new ArrayList<>();
                change(changes, "stopName", before.name(), after.name());
                decimalChange(changes, "latitude", before.latitude(), after.latitude());
                decimalChange(changes, "longitude", before.longitude(), after.longitude());
                if (changes.isEmpty()) unchangedStops.add(id); else modifiedStops.add(new EntityChange(id, changes));
            }
        }
        Map<OccurrenceKey, BusStopOccurrenceMetadataSnapshot> oldOccurrences = occurrences(oldRoutes);
        Map<OccurrenceKey, BusStopOccurrenceMetadataSnapshot> newOccurrences = occurrences(newRoutes);
        List<OccurrenceKey> added = new ArrayList<>(), removed = new ArrayList<>(), unchanged = new ArrayList<>();
        List<OccurrenceChange> modified = new ArrayList<>();
        TreeSet<OccurrenceKey> keys = new TreeSet<>(OCCURRENCE_ORDER);
        keys.addAll(oldOccurrences.keySet()); keys.addAll(newOccurrences.keySet());
        for (OccurrenceKey key : keys) {
            var before = oldOccurrences.get(key); var after = newOccurrences.get(key);
            if (before == null) added.add(key);
            else if (after == null) removed.add(key);
            else {
                String destination = provider == TransitProvider.SEOUL_BUS && after.destinationName() == null
                        ? before.destinationName() : after.destinationName();
                if (Objects.equals(before.destinationName(), destination)) unchanged.add(key);
                else modified.add(new OccurrenceChange(key, before.destinationName(), destination));
            }
        }
        Map<PositionKey, OccurrenceKey> oldPositions = new HashMap<>();
        oldOccurrences.keySet().forEach(k -> oldPositions.put(new PositionKey(k.routeId(), k.stopOrder()), k));
        List<PositionChange> positions = new ArrayList<>();
        List<OccurrenceKey> orderedNew = new ArrayList<>(newOccurrences.keySet()); orderedNew.sort(OCCURRENCE_ORDER);
        for (OccurrenceKey key : orderedNew) {
            OccurrenceKey before = oldPositions.get(new PositionKey(key.routeId(), key.stopOrder()));
            if (before != null && !before.stopId().equals(key.stopId()))
                positions.add(new PositionChange(key.routeId(), key.stopOrder(), before.stopId(), key.stopId()));
        }
        return new Result(provider, addedRoutes, modifiedRoutes, removedRoutes, unchangedRoutes,
                addedStops, modifiedStops, orphanStops, unchangedStops, new ArrayList<>(conflicts),
                added, removed, modified, unchanged, positions);
    }

    private static Map<OccurrenceKey, BusStopOccurrenceMetadataSnapshot> occurrences(Map<String, BusRouteMetadataSnapshot> routes) {
        Map<OccurrenceKey, BusStopOccurrenceMetadataSnapshot> result = new HashMap<>();
        routes.forEach((id, route) -> route.occurrences().forEach(o ->
                result.put(new OccurrenceKey(id, o.externalStopId(), o.stopOrder()), o)));
        return result;
    }
    private static Set<String> sortedUnion(Set<String> a, Set<String> b) {
        Set<String> result = new TreeSet<>(a); result.addAll(b); return result;
    }
    private static boolean sameStop(Stop a, Stop b) {
        return Objects.equals(a.name(), b.name()) && sameDecimal(a.latitude(), b.latitude()) && sameDecimal(a.longitude(), b.longitude());
    }
    private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
        return a == b || (a != null && b != null && a.compareTo(b) == 0);
    }
    private static void change(List<FieldChange> changes, String field, String before, String after) {
        if (!Objects.equals(before, after)) changes.add(new FieldChange(field, before, after));
    }
    private static void decimalChange(List<FieldChange> changes, String field, BigDecimal before, BigDecimal after) {
        if (!sameDecimal(before, after)) changes.add(new FieldChange(field,
                before == null ? null : before.stripTrailingZeros().toPlainString(),
                after == null ? null : after.stripTrailingZeros().toPlainString()));
    }
}
