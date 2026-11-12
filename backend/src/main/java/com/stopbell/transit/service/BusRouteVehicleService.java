package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.client.GbisBusLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationRequestContext;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BusRouteVehiclesResponse;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.PositionState;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.Seat;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.SeatStatus;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.Status;
import com.stopbell.transit.dto.BusRouteVehiclesResponse.Vehicle;
import com.stopbell.transit.dto.gbis.GbisBusLocationItem;
import com.stopbell.transit.service.BusRouteVehicleMetadataReader.RouteMeta;
import com.stopbell.transit.service.ProviderDailyCallBudget.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Read-only multi-vehicle view of a route. Separate from Boarding candidates, Alarm and tracking: it keeps a
 * short per-route cache, merges concurrent requests, and respects per-Provider daily call limits.
 */
@Service
public class BusRouteVehicleService {

    private static final Logger log = LoggerFactory.getLogger(BusRouteVehicleService.class);
    private static final String GBIS_ROUTE_PREFIX = "GGB";
    private record Entry(BusRouteVehiclesResponse response, Instant validUntil) {
    }

    /** Primary position Provider is unusable (failure or daily limit). */
    private static final class PositionUnavailable extends RuntimeException {
        PositionUnavailable() {
            super(null, null, false, false);
        }
    }

    private final BusRouteVehicleMetadataReader metadata;
    private final SeoulBusVehicleLocationClient seoulClient;
    private final GbisBusLocationClient gbisClient;
    private final ProviderDailyCallBudget budget;
    private final Clock clock;
    private final Duration ttl;
    private final Duration staleMaxAge;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<BusRouteVehiclesResponse>> inFlight = new ConcurrentHashMap<>();

    public BusRouteVehicleService(BusRouteVehicleMetadataReader metadata,
            SeoulBusVehicleLocationClient seoulClient, GbisBusLocationClient gbisClient,
            ProviderDailyCallBudget budget, @Qualifier("transitMetadataClock") Clock clock,
            @Value("${transit.vehicle-view.cache-ttl:PT15S}") Duration ttl,
            @Value("${transit.vehicle-view.stale-max-age:PT5M}") Duration staleMaxAge) {
        this.metadata = metadata;
        this.seoulClient = seoulClient;
        this.gbisClient = gbisClient;
        this.budget = budget;
        this.clock = clock;
        this.ttl = ttl;
        this.staleMaxAge = staleMaxAge;
    }

    public BusRouteVehiclesResponse find(Long routeId) {
        RouteMeta route = metadata.read(routeId);
        Entry cached = cache.get(route.routeId());
        if (cached != null && clock.instant().isBefore(cached.validUntil())) {
            return cached.response();
        }
        var mine = new CompletableFuture<BusRouteVehiclesResponse>();
        var running = inFlight.putIfAbsent(route.routeId(), mine);
        if (running != null) {
            try {
                return running.join();
            } catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw exception;
            }
        }
        try {
            BusRouteVehiclesResponse result = load(route, cached);
            mine.complete(result);
            return result;
        } catch (RuntimeException exception) {
            mine.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(route.routeId(), mine);
        }
    }

    private BusRouteVehiclesResponse load(RouteMeta route, Entry previous) {
        Instant now = clock.instant();
        try {
            BusRouteVehiclesResponse fresh = route.provider() == TransitProvider.TAGO
                    ? loadGyeonggi(route, now) : loadSeoul(route, now);
            cache.put(route.routeId(), new Entry(fresh, now.plus(ttl)));
            evictExpired(now);
            return fresh;
        } catch (PositionUnavailable unavailable) {
            if (previous != null && !previous.response().observedAt().plus(staleMaxAge).isBefore(now)) {
                var stale = previous.response().withStatus(Status.STALE);
                cache.put(route.routeId(), new Entry(stale, now.plus(ttl)));
                return stale;
            }
            throw new ApiException(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE);
        }
    }

    private void evictExpired(Instant now) {
        cache.values().removeIf(entry -> entry.response().observedAt().plus(staleMaxAge).isBefore(now));
    }

    private BusRouteVehiclesResponse loadSeoul(RouteMeta route, Instant now) {
        if (!budget.tryAcquire(Source.SEOUL)) {
            log.warn("Vehicle view skipped: provider=SEOUL_BUS reason=daily-limit");
            throw new PositionUnavailable();
        }
        List<com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem> items;
        try {
            var response = seoulClient.fetchVehicleLocations(new VehicleLocationRequest<>(
                    route.externalRouteId(), new SeoulBusVehicleLocationRequestContext()));
            items = "4".equals(response.header().headerCd()) || response.body() == null
                    || response.body().itemList() == null ? List.of() : response.body().itemList();
        } catch (TransitProviderClientException exception) {
            logFailure(exception);
            throw new PositionUnavailable();
        }
        boolean partial = false;
        List<Row> rows = new ArrayList<>();
        for (var item : items) {
            if (item == null || blank(item.plainNo()) || "0".equals(item.isrunyn())) {
                continue;
            }
            Long occurrenceId = seoulOccurrence(route, item.sectOrd());
            partial |= occurrenceId == null;
            PositionState state = Integer.valueOf(1).equals(item.stopFlag()) ? PositionState.AT_STOP
                    : PositionState.DEPARTED;
            rows.add(new Row(item.plainNo().trim(), occurrenceId, state, Seat.UNAVAILABLE,
                    seoulCongestion(item.congetion())));
        }
        return build(route, now, rows, partial);
    }

    /** GBIS is the single source for Gyeonggi position, state, seats and crowding. */
    private BusRouteVehiclesResponse loadGyeonggi(RouteMeta route, Instant now) {
        String id = route.externalRouteId();
        if (id == null || !id.startsWith(GBIS_ROUTE_PREFIX) || id.length() == GBIS_ROUTE_PREFIX.length()) {
            throw new PositionUnavailable();
        }
        String gbisRouteId = id.substring(GBIS_ROUTE_PREFIX.length());
        if (!budget.tryAcquire(Source.GBIS)) {
            log.warn("Vehicle view skipped: provider=GBIS reason=daily-limit");
            throw new PositionUnavailable();
        }
        List<GbisBusLocationItem> items;
        try {
            items = GbisBusLocationClient.vehicles(gbisClient.fetch(gbisRouteId));
        } catch (TransitProviderClientException exception) {
            logFailure(exception);
            throw new PositionUnavailable();
        }
        boolean partial = false;
        List<Row> rows = new ArrayList<>();
        for (var item : items) {
            if (item == null || blank(item.plateNo()) || item.routeId() == null
                    || !gbisRouteId.equals(String.valueOf(item.routeId()))) {
                continue;
            }
            Long occurrenceId = occurrenceByOrder(route, item.stationSeq());
            partial |= occurrenceId == null;
            PositionState state = Integer.valueOf(1).equals(item.stateCd()) ? PositionState.AT_STOP
                    : PositionState.DEPARTED;
            rows.add(new Row(item.plateNo().trim(), occurrenceId, state, seat(item.remainSeats()),
                    gbisCongestion(item.crowded())));
        }
        return build(route, now, rows, partial);
    }

    private BusRouteVehiclesResponse build(RouteMeta route, Instant now, List<Row> rows, boolean partial) {
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < route.traversal().size(); i++) {
            index.put(route.traversal().get(i).id(), i);
        }
        List<Vehicle> vehicles = rows.stream()
                .sorted(Comparator.comparing((Row row) -> row.occurrenceId() == null ? Integer.MAX_VALUE
                                : index.get(row.occurrenceId()))
                        .thenComparing(Row::number))
                .map(row -> new Vehicle(row.number(), row.occurrenceId(), row.positionState(), row.seat(), row.congestion()))
                .toList();
        Status status = vehicles.isEmpty() ? Status.EMPTY : partial ? Status.PARTIAL : Status.AVAILABLE;
        return new BusRouteVehiclesResponse(now, status, vehicles);
    }

    private record Row(String number, Long occurrenceId, PositionState positionState, Seat seat,
            Integer congestion) {
    }

    private static Long seoulOccurrence(RouteMeta route, Integer sectOrd) {
        return occurrenceByOrder(route, sectOrd);
    }

    private static Long occurrenceByOrder(RouteMeta route, Integer order) {
        if (order == null || order <= 0) {
            return null;
        }
        return route.traversal().stream().filter(o -> o.order() == order).findFirst()
                .map(BusRouteVehicleMetadataReader.Occurrence::id).orElse(null);
    }

    private static Seat seat(Integer remaining) {
        if (remaining == null) {
            return Seat.UNAVAILABLE;
        }
        return remaining == 0 ? new Seat(SeatStatus.NONE, 0) : new Seat(SeatStatus.AVAILABLE, remaining);
    }

    /** Seoul congetion: 3 comfortable, 4 moderate, 5 crowded, 6 very crowded; 0 and others unknown. */
    static Integer seoulCongestion(String code) {
        if (code == null) {
            return null;
        }
        return switch (code.trim()) {
            case "3" -> 1;
            case "4" -> 2;
            case "5" -> 3;
            case "6" -> 4;
            default -> null;
        };
    }

    /** GBIS crowded: 1 comfortable, 2 moderate, 3 crowded, 4 very crowded. */
    static Integer gbisCongestion(Integer code) {
        return code != null && code >= 1 && code <= 4 ? code : null;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void logFailure(TransitProviderClientException exception) {
        // No cause, URI or body: they can contain service keys.
        log.warn("Vehicle view provider failure: source={} operation={} kind={} upstreamStatus={}",
                exception.source(), exception.operation(), exception.failureKind(), exception.httpStatus());
    }
}
