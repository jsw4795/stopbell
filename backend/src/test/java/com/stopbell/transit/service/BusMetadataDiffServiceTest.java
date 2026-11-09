package com.stopbell.transit.service;

import static com.stopbell.transit.domain.TransitProvider.SEOUL_BUS;
import static com.stopbell.transit.domain.TransitProvider.TAGO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.BusMetadataDiffService.CurrentState;
import com.stopbell.transit.service.BusMetadataDiffService.OccurrenceKey;
import com.stopbell.transit.service.BusMetadataDiffService.Stop;
import org.junit.jupiter.api.Test;

class BusMetadataDiffServiceTest {
    private final BusMetadataDiffService service = new BusMetadataDiffService();

    private static BusStopOccurrenceMetadataSnapshot o(String id, int order, String name, String latitude, String destination) {
        return new BusStopOccurrenceMetadataSnapshot(id, name, latitude == null ? null : new BigDecimal(latitude),
                latitude == null ? null : new BigDecimal("127.00"), order, destination);
    }
    private static BusRouteMetadataSnapshot route(TransitProvider p, String id, String number, String city,
            BusStopOccurrenceMetadataSnapshot... occurrences) {
        return new BusRouteMetadataSnapshot(p, id, number, city, List.of(occurrences));
    }
    private static Stop stop(TransitProvider p, String id, String name, String latitude) {
        return new Stop(p, id, name, latitude == null ? null : new BigDecimal(latitude),
                latitude == null ? null : new BigDecimal("127.0"));
    }
    private static CompleteBusMetadataSnapshot snapshot(TransitProvider p, BusRouteMetadataSnapshot... routes) {
        return new CompleteBusMetadataSnapshot(p, List.of(routes));
    }
    private static OccurrenceKey key(String route, String stop, int order) { return new OccurrenceKey(route, stop, order); }

    @Test void identicalAndDecimalScaleAreUnchanged() {
        var old = route(SEOUL_BUS, "r", "1", null, o("a", 1, "A", "37.0", "D"));
        var next = route(SEOUL_BUS, "r", "1", null, o("a", 1, "A", "37.000", "D"));
        var result = service.analyze(new CurrentState(List.of(old), List.of(stop(SEOUL_BUS, "a", "A", "37.00"))), snapshot(SEOUL_BUS, next));
        assertThat(result.unchangedRoutes()).containsExactly("r");
        assertThat(result.unchangedStops()).containsExactly("a");
        assertThat(result.unchangedOccurrences()).containsExactly(key("r", "a", 1));
        assertThat(result.modifiedStops()).isEmpty();
        assertThat(result.modifiedOccurrences()).isEmpty();
    }

    @Test void comparesOnlyObservedRouteTypes() {
        var stops = List.of(o("a", 1, "A", null, null));
        var current = new BusRouteMetadataSnapshot(TAGO, "r", "1", "1", stops,
                com.stopbell.transit.domain.BusRouteType.CITY_LOCAL, true);
        var unknown = new BusRouteMetadataSnapshot(TAGO, "r", "1", "1", stops);
        var cleared = new BusRouteMetadataSnapshot(TAGO, "r", "1", "1", stops, null, true);
        var state = new CurrentState(List.of(current), List.of(stop(TAGO, "a", "A", null)));
        assertThat(service.analyze(state, snapshot(TAGO, unknown)).unchangedRoutes()).containsExactly("r");
        assertThat(service.analyze(state, snapshot(TAGO, cleared)).modifiedRoutes()).singleElement()
                .satisfies(change -> assertThat(change.fields()).singleElement().satisfies(field -> {
                    assertThat(field.field()).isEqualTo("routeType");
                    assertThat(field.before()).isEqualTo("CITY_LOCAL");
                    assertThat(field.after()).isNull();
                }));
    }

    @Test void routeStopAndOccurrenceChangesAreCountedIndependently() {
        var old = route(TAGO, "r", "1", "1", o("a", 1, "Old", "37", "D"), o("b", 2, "B", null, null));
        var removed = route(TAGO, "gone", "9", "1", o("orphan", 1, "O", null, null));
        var next = route(TAGO, "r", "2", "2", o("a", 1, "New", "38", "E"), o("c", 2, "C", null, null));
        var added = route(TAGO, "new", "3", "1", o("c", 1, "C", null, null));
        var result = service.analyze(new CurrentState(List.of(old, removed), List.of(stop(TAGO, "a", "Old", "37"),
                stop(TAGO, "b", "B", null), stop(TAGO, "orphan", "O", null))), snapshot(TAGO, next, added));
        assertThat(result.addedRoutes()).containsExactly("new");
        assertThat(result.removedRoutes()).containsExactly("gone");
        assertThat(result.modifiedRoutes()).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("r");
            assertThat(c.fields()).extracting(BusMetadataDiffService.FieldChange::field).containsExactly("routeNumber", "cityCode");
        });
        assertThat(result.addedStops()).containsExactly("c");
        assertThat(result.orphanStops()).containsExactly("b", "orphan");
        assertThat(result.modifiedStops()).singleElement().satisfies(c ->
                assertThat(c.fields()).extracting(BusMetadataDiffService.FieldChange::field).containsExactly("stopName", "latitude"));
        assertThat(result.addedOccurrences()).containsExactly(key("new", "c", 1), key("r", "c", 2));
        assertThat(result.removedOccurrences()).containsExactly(key("gone", "orphan", 1), key("r", "b", 2));
        assertThat(result.modifiedOccurrences()).singleElement().satisfies(c -> {
            assertThat(c.key()).isEqualTo(key("r", "a", 1));
            assertThat(c.beforeDestination()).isEqualTo("D"); assertThat(c.afterDestination()).isEqualTo("E");
        });
        assertThat(result.positionChanges()).singleElement().satisfies(c -> {
            assertThat(c.beforeStopId()).isEqualTo("b"); assertThat(c.afterStopId()).isEqualTo("c");
        });
    }

    @Test void repeatedStopsAndSwappedOrdersPreserveOnlyExactOccurrenceIdentity() {
        var old = route(SEOUL_BUS, "r", "1", null, o("a", 1, "A", null, null), o("b", 2, "B", null, null),
                o("a", 3, "A", null, null), o("c", 4, "C", null, null));
        var next = route(SEOUL_BUS, "r", "1", null, o("a", 1, "A", null, null), o("c", 2, "C", null, null),
                o("a", 3, "A", null, null), o("b", 4, "B", null, null));
        var result = service.analyze(new CurrentState(List.of(old), List.of(stop(SEOUL_BUS, "a", "A", null),
                stop(SEOUL_BUS, "b", "B", null), stop(SEOUL_BUS, "c", "C", null))), snapshot(SEOUL_BUS, next));
        assertThat(result.unchangedOccurrences()).containsExactly(key("r", "a", 1), key("r", "a", 3));
        assertThat(result.addedOccurrences()).containsExactly(key("r", "c", 2), key("r", "b", 4));
        assertThat(result.removedOccurrences()).containsExactly(key("r", "b", 2), key("r", "c", 4));
        assertThat(result.positionChanges()).hasSize(2);
        assertThat(result.unchangedStops()).containsExactly("a", "b", "c");
    }

    @Test void seoulNullPreservesDestinationButTagoNullClearsIt() {
        for (TransitProvider p : List.of(SEOUL_BUS, TAGO)) {
            String city = p == TAGO ? "1" : null;
            var old = route(p, "r", "1", city, o("a", 1, "A", null, "D"));
            var next = route(p, "r", "1", city, o("a", 1, "A", null, null));
            var result = service.analyze(new CurrentState(List.of(old), List.of(stop(p, "a", "A", null))), snapshot(p, next));
            if (p == SEOUL_BUS) assertThat(result.unchangedOccurrences()).hasSize(1);
            else assertThat(result.modifiedOccurrences()).singleElement().satisfies(c -> {
                assertThat(c.beforeDestination()).isEqualTo("D"); assertThat(c.afterDestination()).isNull();
            });
        }
    }

    @Test void providerIsolationAndExistingOrphanStop() {
        var other = route(TAGO, "r", "2", "1", o("a", 1, "Other", null, null));
        var next = route(SEOUL_BUS, "r", "1", null, o("a", 1, "A", null, null));
        var result = service.analyze(new CurrentState(List.of(other), List.of(stop(TAGO, "a", "Other", null),
                stop(SEOUL_BUS, "unused", "Unused", null))), snapshot(SEOUL_BUS, next));
        assertThat(result.addedRoutes()).containsExactly("r"); assertThat(result.addedStops()).containsExactly("a");
        assertThat(result.orphanStops()).containsExactly("unused"); assertThat(result.removedRoutes()).isEmpty();
    }

    @Test void conflictingSharedStopIsExplicitAndNeverAssignedAnArbitraryFinalValue() {
        var a = route(TAGO, "a", "1", "1", o("s", 1, "One", null, null));
        var b = route(TAGO, "b", "2", "1", o("s", 1, "Two", null, null));
        var current = new CurrentState(List.of(), List.of(stop(TAGO, "s", "Old", null)));
        var first = service.analyze(current, snapshot(TAGO, a, b));
        assertThat(first.conflictingStops()).containsExactly("s");
        assertThat(first.modifiedStops()).isEmpty(); assertThat(first.unchangedStops()).isEmpty();
        assertThat(service.analyze(current, snapshot(TAGO, b, a))).isEqualTo(first);
        var fresh = service.analyze(new CurrentState(List.of(), List.of()), snapshot(TAGO, a, b));
        assertThat(fresh.addedStops()).containsExactly("s"); assertThat(fresh.conflictingStops()).containsExactly("s");
    }

    @Test void inputOrderIsDeterministicAndInputCollectionsAreNotMutated() {
        var a = route(SEOUL_BUS, "a", "1", null, o("x", 2, "X", null, null), o("y", 1, "Y", null, null));
        var b = route(SEOUL_BUS, "b", "2", null, o("x", 1, "X", null, null));
        var routes = new ArrayList<>(List.of(b, a));
        var stops = new ArrayList<>(List.of(stop(SEOUL_BUS, "y", "Y", null), stop(SEOUL_BUS, "x", "X", null)));
        var current = new CurrentState(routes, stops);
        var first = service.analyze(current, snapshot(SEOUL_BUS, b, a));
        assertThat(service.analyze(new CurrentState(List.of(a, b), List.of(stops.get(1), stops.get(0))), snapshot(SEOUL_BUS, a, b))).isEqualTo(first);
        assertThat(routes).containsExactly(b, a); assertThat(stops).extracting(Stop::externalStopId).containsExactly("y", "x");
        assertThatThrownBy(() -> first.unchangedStops().add("z")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void sharedCoordinateScaleDoesNotChangeResultWhenInputOrderChanges() {
        var a = route(TAGO, "a", "1", "1", o("s", 1, "S", "38.0", null));
        var b = route(TAGO, "b", "2", "1", o("s", 1, "S", "38.00", null));
        var current = new CurrentState(List.of(), List.of(stop(TAGO, "s", "S", "37")));
        var result = service.analyze(current, snapshot(TAGO, a, b));
        assertThat(result.modifiedStops()).hasSize(1);
        assertThat(service.analyze(current, snapshot(TAGO, b, a))).isEqualTo(result);
    }

    @Test void movingAStopToAnEmptyOrderReplacesTheOccurrence() {
        var old = route(TAGO, "r", "1", "1", o("a", 1, "A", null, null));
        var next = route(TAGO, "r", "1", "1", o("a", 2, "A", null, null));
        var result = service.analyze(new CurrentState(List.of(old), List.of(stop(TAGO, "a", "A", null))), snapshot(TAGO, next));
        assertThat(result.removedOccurrences()).containsExactly(key("r", "a", 1));
        assertThat(result.addedOccurrences()).containsExactly(key("r", "a", 2));
        assertThat(result.positionChanges()).isEmpty();
        assertThat(result.unchangedStops()).containsExactly("a");
    }

    @Test void missingCurrentStopIsRejected() {
        var route = route(TAGO, "r", "1", "1", o("a", 1, "A", null, null));
        assertThatThrownBy(() -> new CurrentState(List.of(route), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateCurrentIdentityIsRejected() {
        var stop = stop(TAGO, "s", "S", null);
        assertThatThrownBy(() -> new CurrentState(List.of(), List.of(stop, stop))).isInstanceOf(IllegalArgumentException.class);
    }
}
