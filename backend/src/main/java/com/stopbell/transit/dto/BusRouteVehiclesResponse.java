package com.stopbell.transit.dto;

import java.time.Instant;
import java.util.List;

/** Read-only real-time vehicle view of one route. Provider IDs and GPS are intentionally not exposed. */
public record BusRouteVehiclesResponse(Instant observedAt, Status status, List<Vehicle> vehicles) {

    public enum Status {
        /** Positions (and seats where supported) are all resolved. */
        AVAILABLE,
        /** The Provider reported no vehicle in service. Provider failure never maps to this. */
        EMPTY,
        /** Some vehicle position or seat could not be verified or fetched. */
        PARTIAL,
        /** Provider failed or its daily limit was reached; this is the last successful result. */
        STALE
    }

    public enum SeatStatus { AVAILABLE, NONE, UNAVAILABLE }

    public record Seat(SeatStatus status, Integer remaining) {
        public static final Seat UNAVAILABLE = new Seat(SeatStatus.UNAVAILABLE, null);
    }

    /**
     * @param occurrenceId verified route occurrence the vehicle last passed/is at, or null when unverified
     * @param congestion   1 comfortable, 2 moderate, 3 crowded, 4 very crowded; null when unknown
     */
    public record Vehicle(String vehicleNumber, Long occurrenceId, Seat seat, Integer congestion) {
    }

    public BusRouteVehiclesResponse withStatus(Status newStatus) {
        return new BusRouteVehiclesResponse(observedAt, newStatus, vehicles);
    }
}
