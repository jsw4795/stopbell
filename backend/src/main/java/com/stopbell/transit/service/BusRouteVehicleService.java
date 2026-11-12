package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.client.GbisBusLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationRequestContext;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationRequestContext;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.BusRouteType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BusRouteVehiclesResponse;
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
    /** GBIS provides remainSeatCnt for 11, 12, 14, 16, 17, 21, 22 (17 has no StopBell type yet). */
    private static final Set<BusRouteType> SEAT_TYPES = Set.of(BusRouteType.CITY_EXPRESS_SEAT,
            BusRouteType.CITY_SEAT, BusRouteType.CITY_METRO_EXPRESS, BusRouteType.GYEONGGI_CIRCULAR,
            BusRouteType.RURAL_EXPRESS_SEAT, BusRouteType.RURAL_SEAT);
    /** GBIS provides crowded only for 13, 15, 23. */
    private static final Set<BusRouteType> CROWDED_TYPES = Set.of(BusRouteType.CITY_LOCAL,
            BusRouteType.CITY_DDABOK, BusRouteType.RURAL_LOCAL);

    private record Entry(BusRouteVehiclesResponse response, Instant validUntil) {
    }

    /** Primary position Provider is unusable (failure or daily limit). */
    private static final class PositionUnavailable extends RuntimeException {
        PositionUnavailable() {
            super(null, null, false, false);
        }
    }

    private final BusRouteVehicleMetadataReader metadata;
    private final TagoVehicleLocationClient tagoClient;
    private final SeoulBusVehicleLocationClient seoulClient;
    private final GbisBusLocationClient gbisClient;
    private final ProviderDailyCallBudget budget;
    private final Clock clock;
    private final Duration ttl;
    private final Duration staleMaxAge;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<BusRouteVehiclesResponse>> inFlight = new ConcurrentHashMap<>();

    public BusRouteVehicleService(BusRouteVehicleMetadataReader metadata, TagoVehicleLocationClient tagoClient,
            SeoulBusVehicleLocationClient seoulClient, GbisBusLocationClient gbisClient,
            ProviderDailyCallBudget budget, @Qualifier("transitMetadataClock") Clock clock,
            @Value("${transit.vehicle-view.cache-ttl:PT15S}") Duration ttl,
            @Value("${transit.vehicle-view.stale-max-age:PT5M}") Duration staleMaxAge) {
        this.metadata = metadata;
        this.tagoClient = tagoClient;
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
            Long occurrenceId = seoulOccurrence(route, item.sectOrd(), item.nextStId());
            partial |= occurrenceId == null;
            rows.add(new Row(item.plainNo().trim(), occurrenceId, Seat.UNAVAILABLE, seoulCongestion(item.congetion())));
        }
        return build(route, now, rows, partial);
    }

    private BusRouteVehiclesResponse loadGyeonggi(RouteMeta route, Instant now) {
        if (!budget.tryAcquire(Source.TAGO)) {
            log.warn("Vehicle view skipped: provider=TAGO reason=daily-limit");
            throw new PositionUnavailable();
        }
        List<com.stopbell.transit.dto.tago.TagoVehicleLocationItem> items;
        try {
            var response = tagoClient.fetchVehicleLocations(new VehicleLocationRequest<>(
                    route.externalRouteId(), new TagoVehicleLocationRequestContext(route.cityCode())));
            var wrapper = response.response().body().items();
            items = wrapper == null || wrapper.item() == null ? List.of() : wrapper.item();
        } catch (TransitProviderClientException exception) {
            logFailure(exception);
            throw new PositionUnavailable();
        }
        var tago = items.stream().filter(item -> item != null && !blank(item.vehicleNo())).toList();
        Map<String, Integer> numberCount = new HashMap<>();
        tago.forEach(item -> numberCount.merge(normalize(item.vehicleNo()), 1, Integer::sum));

        boolean seatType = route.routeType() != null && SEAT_TYPES.contains(route.routeType());
        boolean crowdType = route.routeType() != null && CROWDED_TYPES.contains(route.routeType());
        Map<String, List<GbisBusLocationItem>> gbis = null;
        boolean partial = false;
        if ((seatType || crowdType) && !tago.isEmpty()) {
            gbis = fetchGbis(route);
            partial = gbis == null;
        }

        List<Row> rows = new ArrayList<>();
        for (var item : tago) {
            String key = normalize(item.vehicleNo());
            Long occurrenceId = tagoOccurrence(route, item.nodeOrder(), item.nodeId());
            partial |= occurrenceId == null;
            Seat seat = Seat.UNAVAILABLE;
            Integer congestion = null;
            if (gbis != null) {
                var matches = gbis.getOrDefault(key, List.of());
                if (matches.size() == 1 && numberCount.get(key) == 1) {
                    var match = matches.getFirst();
                    if (seatType) {
                        seat = seat(match.remainSeats());
                    }
                    if (crowdType) {
                        congestion = gbisCongestion(match.crowded());
                    }
                } else {
                    partial = true;
                }
            }
            rows.add(new Row(item.vehicleNo().trim(), occurrenceId, seat, congestion));
        }
        return build(route, now, rows, partial);
    }

    /** @return GBIS vehicles of this route by normalized plate number, or null when unavailable */
    private Map<String, List<GbisBusLocationItem>> fetchGbis(RouteMeta route) {
        String id = route.externalRouteId();
        if (id == null || !id.startsWith(GBIS_ROUTE_PREFIX) || id.length() == GBIS_ROUTE_PREFIX.length()) {
            return null;
        }
        String gbisRouteId = id.substring(GBIS_ROUTE_PREFIX.length());
        if (!budget.tryAcquire(Source.GBIS)) {
            log.warn("Vehicle view skipped: provider=GBIS reason=daily-limit");
            return null;
        }
        try {
            var response = gbisClient.fetch(gbisRouteId);
            Map<String, List<GbisBusLocationItem>> byNumber = new HashMap<>();
            for (var item : GbisBusLocationClient.vehicles(response)) {
                if (item != null && !blank(item.plateNo()) && item.routeId() != null
                        && gbisRouteId.equals(String.valueOf(item.routeId()))) {
                    byNumber.computeIfAbsent(normalize(item.plateNo()), k -> new ArrayList<>()).add(item);
                }
            }
            return byNumber;
        } catch (TransitProviderClientException exception) {
            logFailure(exception);
            return null;
        }
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
                .map(row -> new Vehicle(row.number(), row.occurrenceId(), row.seat(), row.congestion()))
                .toList();
        Status status = vehicles.isEmpty() ? Status.EMPTY : partial ? Status.PARTIAL : Status.AVAILABLE;
        return new BusRouteVehiclesResponse(now, status, vehicles);
    }

    private record Row(String number, Long occurrenceId, Seat seat, Integer congestion) {
    }

    /** Order plus external stop ID must both match one occurrence; repeated-stop routes stay unverified. */
    private static Long tagoOccurrence(RouteMeta route, Integer order, String nodeId) {
        if (order == null || order <= 0 || blank(nodeId) || !route.hasUniqueStopIds()) {
            return null;
        }
        var matches = route.traversal().stream().filter(o -> o.order() == order).toList();
        return matches.size() == 1 && matches.getFirst().externalStopId().equals(nodeId)
                ? matches.getFirst().id() : null;
    }

    /** The section after the occurrence of sectOrd must end at nextStId; otherwise the position is unverified. */
    private static Long seoulOccurrence(RouteMeta route, Integer sectOrd, String nextStopId) {
        if (sectOrd == null || sectOrd <= 0 || blank(nextStopId) || !route.hasUniqueStopIds()) {
            return null;
        }
        var list = route.traversal();
        for (int i = 0; i < list.size() - 1; i++) {
            if (list.get(i).order() == sectOrd) {
                return list.get(i + 1).externalStopId().equals(nextStopId) ? list.get(i).id() : null;
            }
        }
        return null;
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

    static String normalize(String number) {
        return number.replaceAll("[\\s-]", "");
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
