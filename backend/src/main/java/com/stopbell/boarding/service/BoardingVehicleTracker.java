package com.stopbell.boarding.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.boarding.domain.*;
import com.stopbell.boarding.entity.BoardingStateReason;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.mapper.BoardingCandidateMapper;
import com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle;
import com.stopbell.transit.service.VehiclePollingCycle;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class BoardingVehicleTracker {
    private final Clock clock;

    public BoardingVehicleTracker(@Qualifier("transitMetadataClock") Clock clock) {
        this.clock = clock;
    }

    public record Input(BoardingProgressObservation observation, BoardingStateReason reason) {
        static Input held(BoardingStateReason reason) { return new Input(null, reason); }
    }

    public Input observe(BoardingTrackingSnapshot session, VehiclePollingCycle cycle) {
        List<Vehicle> roster;
        if (session.provider() == TransitProvider.TAGO) {
            var received = cycle.tago(session.routeId(), session.cityCode());
            var items = received.response().response().body().items();
            roster = items == null || items.item() == null ? List.of() : items.item().stream()
                    .map(item -> BoardingCandidateMapper.tago(item, received.observedAt())).toList();
        } else {
            var received = cycle.roster(session.routeId());
            var response = received.response();
            roster = "4".equals(response.header().headerCd()) ? List.of() : response.body().itemList().stream()
                    .map(item -> BoardingCandidateMapper.roster(item, received.observedAt())).toList();
        }
        List<Vehicle> matches = roster.stream().filter(vehicle -> session.vehicleId().equals(vehicle.id())).toList();
        if (matches.size() > 1 || roster.stream().anyMatch(vehicle -> session.vehicleNumber().equals(vehicle.number())
                && !session.vehicleId().equals(vehicle.id()))) {
            return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
        }
        if (matches.isEmpty()) {
            if (roster.stream().anyMatch(vehicle -> vehicle.id() == null)) {
                return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
            }
            return Input.held(BoardingStateReason.VEHICLE_MISSING);
        }
        Vehicle vehicle = matches.getFirst();
        if (!session.vehicleNumber().equals(vehicle.number())) {
            return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
        }
        if (stale(vehicle, clock.instant())) {
            return Input.held(BoardingStateReason.STALE_PROGRESS);
        }
        if (vehicle.runEnded()) {
            // isrunyn=0 is explicit negative continuity evidence. Neither missing
            // values nor isrunyn=1 prove the same trip/lap or permit automatic recovery.
            return Input.held(BoardingStateReason.TRAVERSAL_AMBIGUOUS);
        }
        if (session.provider() == TransitProvider.SEOUL_BUS) {
            var received = cycle.detail(session.vehicleId());
            var response = received.response();
            var items = response.body() == null ? null : response.body().itemList();
            // Roster presence with empty detail is unavailable progress, never vehicle disappearance.
            if ("4".equals(response.header().headerCd()) || items == null || items.isEmpty()) {
                return Input.held(BoardingStateReason.STALE_PROGRESS);
            }
            if (items.size() != 1 || items.getFirst() == null) {
                return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
            }
            Vehicle detail = BoardingCandidateMapper.detail(items.getFirst(), received.observedAt());
            if (!vehicle.id().equals(detail.id()) || !vehicle.number().equals(detail.number())) {
                return Input.held(BoardingStateReason.IDENTITY_CONFLICT);
            }
            if (stale(vehicle, clock.instant()) || stale(detail, clock.instant())
                    || vehicle.providerDataTime() == null || detail.providerDataTime() == null) {
                return Input.held(BoardingStateReason.STALE_PROGRESS);
            }
            if (session.checkpoint().getProviderDataTime() != null && vehicle.providerDataTime()
                    .isBefore(session.checkpoint().getProviderDataTime().toInstant(ZoneOffset.UTC))) {
                return Input.held(BoardingStateReason.PROGRESS_REGRESSION);
            }
            vehicle = detail;
        }
        // Production evidence supports only Seoul exact stopped detail. Moving semantics, TAGO
        // alight tracking and order-only remain unverified; no environment switch bypasses this.
        var meaning = session.provider() == TransitProvider.SEOUL_BUS && Integer.valueOf(1).equals(vehicle.stopFlag())
                && vehicle.stopId() != null ? BoardingProgressObservation.Meaning.DIRECT_ARRIVAL
                : BoardingProgressObservation.Meaning.UNVERIFIED;
        return new Input(new BoardingProgressObservation(session.provider(), session.routeId(), session.cityCode(),
                vehicle, meaning, false, null, false), null);
    }

    private static boolean stale(Vehicle vehicle, Instant now) {
        return vehicle.timeIssue() != null || invalidTime(vehicle.observedAt(), now)
                || (vehicle.providerDataTime() != null && (invalidTime(vehicle.providerDataTime(), now)
                    || vehicle.providerDataTime().isAfter(vehicle.observedAt())));
    }

    private static boolean invalidTime(Instant time, Instant now) {
        return time == null || time.isAfter(now) || time.isBefore(now.minus(BoardingProgressEvaluator.FRESHNESS));
    }
}
