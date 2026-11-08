package com.stopbell.transit.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.stopbell.transit.domain.TransitProvider;
import tools.jackson.databind.json.JsonMapper;

/** Immutable review evidence derived only from the already analyzed snapshot and diff. */
public final class TransitMetadataUnsafeChangeDetails {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private TransitMetadataUnsafeChangeDetails() { }

    private record Position(String routeId, int stopOrder) { }
    public record Variant(String routeId, int stopOrder, String stopName, BigDecimal latitude, BigDecimal longitude) { }
    public record Detail(TransitProvider provider, BusMetadataSafetyGuard.Reason reason, String routeId,
            String stopId, Integer stopOrder, String replacementStopId, List<Variant> variants) {
        public Detail { variants = List.copyOf(variants); }
    }

    public static List<Detail> build(BusMetadataDiffService.Result diff, CompleteBusMetadataSnapshot snapshot) {
        if (diff.provider() != snapshot.provider()) throw new IllegalArgumentException("Provider mismatch");
        var provider = diff.provider();
        List<Detail> details = new ArrayList<>();
        diff.removedRoutes().forEach(id -> details.add(new Detail(provider, BusMetadataSafetyGuard.Reason.ROUTE_REMOVAL, id, null, null, null, List.of())));
        Map<Position, String> replacements = diff.positionChanges().stream().collect(Collectors.toMap(
                p -> new Position(p.routeId(), p.stopOrder()), BusMetadataDiffService.PositionChange::afterStopId));
        diff.removedOccurrences().forEach(o -> details.add(new Detail(provider, BusMetadataSafetyGuard.Reason.OCCURRENCE_REMOVAL,
                o.routeId(), o.stopId(), o.stopOrder(), replacements.get(new Position(o.routeId(), o.stopOrder())), List.of())));
        diff.orphanStops().forEach(id -> details.add(new Detail(provider, BusMetadataSafetyGuard.Reason.STOP_CLEANUP, null, id, null, null, List.of())));
        var conflicts = new HashSet<>(diff.conflictingStops());
        Map<String, List<Variant>> variantsByStop = new HashMap<>();
        for (var route : snapshot.routes()) for (var occurrence : route.occurrences()) {
            if (conflicts.contains(occurrence.externalStopId()))
                variantsByStop.computeIfAbsent(occurrence.externalStopId(), ignored -> new ArrayList<>())
                        .add(new Variant(route.externalRouteId(), occurrence.stopOrder(), occurrence.stopName(),
                                decimal(occurrence.latitude()), decimal(occurrence.longitude())));
        }
        for (String stop : diff.conflictingStops()) {
            List<Variant> variants = variantsByStop.getOrDefault(stop, new ArrayList<>());
            variants.sort(Comparator.comparing(Variant::routeId).thenComparingInt(Variant::stopOrder));
            details.add(new Detail(provider, BusMetadataSafetyGuard.Reason.SHARED_STOP_CONFLICT, null, stop, null, null, variants));
        }
        details.sort(Comparator.comparing((Detail d) -> d.reason().name())
                .thenComparing(d -> d.routeId() == null ? "" : d.routeId())
                .thenComparing(d -> d.stopOrder() == null ? 0 : d.stopOrder())
                .thenComparing(d -> d.stopId() == null ? "" : d.stopId()));
        return List.copyOf(details);
    }
    private static BigDecimal decimal(BigDecimal value) { return value == null ? null : new BigDecimal(value.stripTrailingZeros().toPlainString()); }
    public static String fingerprint(List<Detail> details) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(details).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
