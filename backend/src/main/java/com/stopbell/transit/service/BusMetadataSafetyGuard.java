package com.stopbell.transit.service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Evaluates the already computed pre-apply diff without reading or changing data. */
public final class BusMetadataSafetyGuard {
    public enum Reason { ROUTE_REMOVAL, OCCURRENCE_REMOVAL, STOP_CLEANUP, SHARED_STOP_CONFLICT }
    public enum Decision { ALLOW, BLOCK }

    public record Result(Decision decision, Map<Reason, Integer> reasons) {
        public Result { reasons = Map.copyOf(reasons); }
    }

    public Result evaluate(TransitMetadataDiffAnalysisService.Analysis analysis) {
        var diff = Objects.requireNonNull(analysis).normalized();
        var reasons = new EnumMap<Reason, Integer>(Reason.class);
        add(reasons, Reason.ROUTE_REMOVAL, diff.removedRoutes().size());
        add(reasons, Reason.OCCURRENCE_REMOVAL, diff.removedOccurrences().size());
        add(reasons, Reason.STOP_CLEANUP, diff.orphanStops().size());
        add(reasons, Reason.SHARED_STOP_CONFLICT, diff.conflictingStops().size());
        return new Result(reasons.isEmpty() ? Decision.ALLOW : Decision.BLOCK, reasons);
    }

    private static void add(Map<Reason, Integer> reasons, Reason reason, int count) {
        if (count > 0) reasons.put(reason, count);
    }
}
