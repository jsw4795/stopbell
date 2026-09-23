package com.stopbell.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusRouteTraversalServiceTest {

    private final BusRouteRepository routeRepository = mock(BusRouteRepository.class);
    private final BusRouteStopOccurrenceRepository occurrenceRepository = mock(BusRouteStopOccurrenceRepository.class);
    private final BusRouteTraversalService traversalService = new BusRouteTraversalService(routeRepository, occurrenceRepository);

    @Test
    @DisplayName("stopsPastTarget은 raw stop order 차이가 아니라 metadata traversal edge 수를 사용한다")
    void counts_metadata_edges_instead_of_raw_stop_order_difference() {
        BusAlarmTarget target = new BusAlarmTarget(
                TransitProvider.SEOUL_BUS, "route", "target", 20, "7000", "Target",
                new BigDecimal("37.0"), new BigDecimal("127.0"), null, null, null
        );
        BusRoute route = new BusRoute(TransitProvider.SEOUL_BUS, "route", "7000", null);
        when(routeRepository.findByProviderAndExternalRouteId(TransitProvider.SEOUL_BUS, "route"))
                .thenReturn(java.util.Optional.of(route));
        List<BusRouteStopOccurrence> occurrences = List.of(
                occurrence("first", 10), occurrence("target", 20), occurrence("middle", 35), occurrence("current", 90)
        );
        when(occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route)).thenReturn(occurrences);
        TransitObservation observation = new TransitObservation(
                TransitProvider.SEOUL_BUS, "route", "vehicle", "current", 90, null, null, null,
                null, null, Instant.parse("2026-09-23T03:00:00Z"), null, ArrivalEvidence.MOVING
        );

        assertThat(traversalService.stopsPastTarget(target, observation)).hasValue(2);
    }

    @Test
    @DisplayName("현재 metadata가 Alarm target snapshot과 안전하게 일치하지 않으면 stopsPastTarget을 생략한다")
    void omits_stops_past_target_when_snapshot_cannot_be_matched() {
        BusAlarmTarget target = new BusAlarmTarget(
                TransitProvider.SEOUL_BUS, "route", "target", 20, "7000", "Target",
                null, null, null, null, null
        );
        BusRoute route = new BusRoute(TransitProvider.SEOUL_BUS, "route", "7000", null);
        when(routeRepository.findByProviderAndExternalRouteId(TransitProvider.SEOUL_BUS, "route"))
                .thenReturn(java.util.Optional.of(route));
        List<BusRouteStopOccurrence> occurrences = List.of(
                occurrence("different-target", 20), occurrence("current", 90)
        );
        when(occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route)).thenReturn(occurrences);
        TransitObservation observation = new TransitObservation(
                TransitProvider.SEOUL_BUS, "route", "vehicle", "current", 90, null, null, null,
                null, null, Instant.parse("2026-09-23T03:00:00Z"), null, ArrivalEvidence.MOVING
        );

        assertThat(traversalService.stopsPastTarget(target, observation)).isEmpty();
    }

    private static BusRouteStopOccurrence occurrence(String stopId, int order) {
        BusStop stop = mock(BusStop.class);
        when(stop.getExternalStopId()).thenReturn(stopId);
        BusRouteStopOccurrence occurrence = mock(BusRouteStopOccurrence.class);
        when(occurrence.getStop()).thenReturn(stop);
        when(occurrence.getStopOrder()).thenReturn(order);
        return occurrence;
    }
}
