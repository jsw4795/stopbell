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
import com.stopbell.common.observability.OperationalMetrics;
import com.stopbell.transit.domain.TransitProvider;

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
    private final OperationalMetrics metrics;
    private final Map<TagoKey, Attempt<TagoVehicleLocationResponse>> tagoResponses = new HashMap<>();
    private final Map<String, Attempt<SeoulBusVehicleLocationResponse>> rosters = new HashMap<>();
    private final Map<String, Attempt<SeoulBusVehicleDetailResponse>> details = new HashMap<>();

    public VehiclePollingCycle(TagoVehicleLocationClient tago, SeoulBusVehicleLocationClient seoul,
            SeoulBusVehicleDetailClient detail, Clock clock) {
        this(tago, seoul, detail, clock, null);
    }

    public VehiclePollingCycle(TagoVehicleLocationClient tago, SeoulBusVehicleLocationClient seoul,
            SeoulBusVehicleDetailClient detail, Clock clock, OperationalMetrics metrics) {
        this.tago = tago;
        this.seoul = seoul;
        this.detail = detail;
        this.clock = clock;
        this.metrics = metrics;
    }

    public Received<TagoVehicleLocationResponse> tago(String routeId, String cityCode) {
        return tagoResponses.computeIfAbsent(new TagoKey(routeId, cityCode), key -> capture(TransitProvider.TAGO, OperationalMetrics.Operation.TAGO_LOCATION, () ->
                tago.fetchVehicleLocations(new VehicleLocationRequest<>(routeId,
                        new TagoVehicleLocationRequestContext(cityCode))))).get();
    }

    public Received<SeoulBusVehicleLocationResponse> roster(String routeId) {
        return rosters.computeIfAbsent(routeId, key -> capture(TransitProvider.SEOUL_BUS, OperationalMetrics.Operation.SEOUL_ROSTER, () -> seoul.fetchVehicleLocations(
                new VehicleLocationRequest<>(routeId, new SeoulBusVehicleLocationRequestContext())))).get();
    }

    public Received<SeoulBusVehicleDetailResponse> detail(String vehicleId) {
        return details.computeIfAbsent(vehicleId, key -> capture(TransitProvider.SEOUL_BUS, OperationalMetrics.Operation.SEOUL_DETAIL, () -> detail.fetchVehicleDetail(vehicleId))).get();
    }

    private <T> Attempt<T> capture(TransitProvider provider, OperationalMetrics.Operation operation, Supplier<T> fetch) {
        long started = System.nanoTime();
        try {
            T response = fetch.get();
            Instant observedAt = clock.instant();
            if (metrics != null) metrics.provider(provider, operation, null, System.nanoTime() - started);
            return new Attempt<>(new Received<>(response, observedAt), null);
        } catch (TransitProviderClientException exception) {
            if (metrics != null) metrics.provider(provider, operation, exception.failureKind(), System.nanoTime() - started);
            return new Attempt<>(null, exception);
        } catch (RuntimeException exception) {
            if (metrics != null) metrics.unexpectedProviderFailure(provider, operation, System.nanoTime() - started);
            throw exception;
        }
    }
}
