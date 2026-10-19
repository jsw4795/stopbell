package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.stopbell.transit.client.*;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationResponse;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;

/** One synchronous scheduler cycle only. Shares raw responses/failures, never extends observedAt. */
public final class VehiclePollingCycle {
    public record Received<T>(T response, Instant observedAt) { }
    private record TagoKey(String routeId, String cityCode) { }
    private record Attempt<T>(Received<T> received, TransitProviderClientException failure) {
        Received<T> get() {
            if (failure != null) {
                throw failure;
            }
            return received;
        }
    }

    private final TagoVehicleLocationClient tago;
    private final SeoulBusVehicleLocationClient seoul;
    private final SeoulBusVehicleDetailClient detail;
    private final Clock clock;
    private final Map<TagoKey, Attempt<TagoVehicleLocationResponse>> tagoResponses = new HashMap<>();
    private final Map<String, Attempt<SeoulBusVehicleLocationResponse>> rosters = new HashMap<>();
    private final Map<String, Attempt<SeoulBusVehicleDetailResponse>> details = new HashMap<>();

    public VehiclePollingCycle(TagoVehicleLocationClient tago, SeoulBusVehicleLocationClient seoul,
            SeoulBusVehicleDetailClient detail, Clock clock) {
        this.tago = tago;
        this.seoul = seoul;
        this.detail = detail;
        this.clock = clock;
    }

    public Received<TagoVehicleLocationResponse> tago(String routeId, String cityCode) {
        return tagoResponses.computeIfAbsent(new TagoKey(routeId, cityCode), key -> capture(() ->
                tago.fetchVehicleLocations(new VehicleLocationRequest<>(routeId,
                        new TagoVehicleLocationRequestContext(cityCode))))).get();
    }

    public Received<SeoulBusVehicleLocationResponse> roster(String routeId) {
        return rosters.computeIfAbsent(routeId, key -> capture(() -> seoul.fetchVehicleLocations(
                new VehicleLocationRequest<>(routeId, new SeoulBusVehicleLocationRequestContext())))).get();
    }

    public Received<SeoulBusVehicleDetailResponse> detail(String vehicleId) {
        return details.computeIfAbsent(vehicleId, key -> capture(() -> detail.fetchVehicleDetail(vehicleId))).get();
    }

    private <T> Attempt<T> capture(Supplier<T> fetch) {
        try {
            T response = fetch.get();
            return new Attempt<>(new Received<>(response, clock.instant()), null);
        } catch (TransitProviderClientException exception) {
            return new Attempt<>(null, exception);
        }
    }
}
