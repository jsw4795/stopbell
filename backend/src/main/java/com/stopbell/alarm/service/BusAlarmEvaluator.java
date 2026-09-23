package com.stopbell.alarm.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.domain.TransitProvider;
import org.springframework.stereotype.Service;

/**
 * Deterministically evaluates one Route observation snapshot against one Bus Alarm.
 * It owns no scheduler, database lifecycle mutation, or raw observation persistence.
 */
@Service
public class BusAlarmEvaluator {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final BusRouteTraversalService routeTraversalService;

    public BusAlarmEvaluator(BusRouteTraversalService routeTraversalService) {
        this.routeTraversalService = routeTraversalService;
    }

    public BusAlarmEvaluationResult evaluate(
            Alarm alarm,
            BusAlarmEvaluationState state,
            List<TransitObservation> observations,
            Instant now
    ) {
        Set<String> presentVehicleIds = observations == null ? null : observations.stream()
                .map(TransitObservation::vehicleTrackingId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return evaluate(alarm, state, presentVehicleIds, observations, now);
    }

    public BusAlarmEvaluationResult evaluate(
            Alarm alarm,
            BusAlarmEvaluationState state,
            Set<String> presentVehicleIds,
            List<TransitObservation> observations,
            Instant now
    ) {
        if (alarm == null || state == null || presentVehicleIds == null || observations == null || now == null) {
            throw new IllegalArgumentException("Alarm, state, present vehicles, observations, and evaluation time must not be null");
        }
        if (presentVehicleIds.stream().anyMatch(vehicleId -> vehicleId == null || vehicleId.isBlank())) {
            throw new IllegalArgumentException("Present vehicle IDs must not be blank");
        }
        BusAlarmTarget target = requireTarget(alarm);
        validateObservationRoute(target, observations);

        return switch (alarm.getStatus()) {
            case ACTIVE -> evaluateActive(target, state, presentVehicleIds, observations, now);
            case FOLLOW_UP -> evaluateFollowUp(alarm, target, state, presentVehicleIds, observations, now);
            case INACTIVE -> throw new IllegalStateException("Inactive alarm cannot be evaluated");
        };
    }

    private BusAlarmEvaluationResult evaluateActive(
            BusAlarmTarget target,
            BusAlarmEvaluationState state,
            Set<String> presentVehicleIds,
            List<TransitObservation> observations,
            Instant now
    ) {
        BusAlarmEvaluationState.Mutable next = state.mutableCopy();
        touchPresentVehicles(next, presentVehicleIds, now);
        expireMissingVehicles(next.trackedVehicles(), now);
        expireMissingBaselineAfterVehicles(next.baselineAfterVehicles(), now);

        Snapshot snapshot = uniqueSnapshot(observations);
        List<TransitEvent> events = new ArrayList<>();
        for (Map.Entry<String, TransitObservation> entry : snapshot.observations().entrySet()) {
            String vehicleId = entry.getKey();
            TransitObservation observation = entry.getValue();
            if (snapshot.ambiguousVehicleIds().contains(vehicleId)) {
                touch(next.trackedVehicles(), vehicleId, now);
                continue;
            }
            if (!isFresh(observation, now)) {
                touch(next.trackedVehicles(), vehicleId, now);
                continue;
            }

            PositionRelation relation = positionOf(target, observation);
            if (!next.baselineEstablished()) {
                evaluateBaseline(target, next, observation, relation, now, events);
                continue;
            }
            evaluateActiveVehicle(target, next, observation, relation, now, events);
        }
        if (!next.baselineEstablished()) {
            next.establishBaseline();
        }
        return new BusAlarmEvaluationResult(events, next.freeze(), false);
    }

    private void evaluateBaseline(
            BusAlarmTarget target,
            BusAlarmEvaluationState.Mutable next,
            TransitObservation observation,
            PositionRelation relation,
            Instant now,
            List<TransitEvent> events
    ) {
        switch (relation) {
            case BEFORE_TARGET -> {
                VehicleTrackingState tracking = VehicleTrackingState.beginBeforeTarget(observation, now);
                next.trackedVehicles().put(observation.vehicleTrackingId(), tracking);
                emitOneStopBeforeIfEligible(target, tracking, observation, now, events, next.trackedVehicles());
            }
            case AT_TARGET -> {
                if (canArrive(target, null, observation, true)) {
                    VehicleTrackingState tracking = VehicleTrackingState.begin(observation, now);
                    next.trackedVehicles().put(observation.vehicleTrackingId(), tracking);
                    emit(TransitEventType.ARRIVED, tracking, observation, null, events);
                    next.trackedVehicles().put(observation.vehicleTrackingId(),
                            tracking.emit(TransitEventType.ARRIVED, observation, now));
                }
            }
            case AFTER_TARGET -> next.baselineAfterVehicles().put(observation.vehicleTrackingId(), now);
            case UNKNOWN -> {
                // Ambiguous baseline observations deliberately establish no tracking history.
            }
        }
    }

    private void evaluateActiveVehicle(
            BusAlarmTarget target,
            BusAlarmEvaluationState.Mutable next,
            TransitObservation observation,
            PositionRelation relation,
            Instant now,
            List<TransitEvent> events
    ) {
        String vehicleId = observation.vehicleTrackingId();
        if (next.baselineAfterVehicles().containsKey(vehicleId)) {
            next.baselineAfterVehicles().put(vehicleId, now);
            return;
        }

        VehicleTrackingState tracking = next.trackedVehicles().get(vehicleId);
        if (tracking == null) {
            if (relation == PositionRelation.BEFORE_TARGET) {
                tracking = VehicleTrackingState.beginBeforeTarget(observation, now);
                next.trackedVehicles().put(vehicleId, tracking);
                emitOneStopBeforeIfEligible(target, tracking, observation, now, events, next.trackedVehicles());
            } else if (relation == PositionRelation.AFTER_TARGET) {
                next.baselineAfterVehicles().put(vehicleId, now);
            } else if (relation == PositionRelation.AT_TARGET && canArrive(target, null, observation, true)) {
                tracking = VehicleTrackingState.begin(observation, now);
                next.trackedVehicles().put(vehicleId, tracking);
                emit(TransitEventType.ARRIVED, tracking, observation, null, events);
                next.trackedVehicles().put(vehicleId, tracking.emit(TransitEventType.ARRIVED, observation, now));
            }
            return;
        }

        if (!isMonotonic(tracking.lastObservation(), observation) || hasDirectionConflict(tracking.lastObservation(), observation)) {
            next.trackedVehicles().put(vehicleId, tracking.observe(tracking.lastObservation(), now));
            return;
        }

        switch (relation) {
            case AT_TARGET -> {
                if (canArrive(target, tracking, observation, false) && !tracking.hasEmitted(TransitEventType.ARRIVED)) {
                    emit(TransitEventType.ARRIVED, tracking, observation, null, events);
                    next.trackedVehicles().put(vehicleId, tracking.emit(TransitEventType.ARRIVED, observation, now));
                } else {
                    next.trackedVehicles().put(vehicleId, tracking.observe(observation, now));
                }
            }
            case AFTER_TARGET -> {
                if (!tracking.hasEmitted(TransitEventType.PASSED)
                        && !tracking.hasEmitted(TransitEventType.ARRIVED)
                        && tracking.hasObservedBeforeTarget()
                        && hasNewProgressEvidence(tracking.lastObservation(), observation)) {
                    OptionalInt stopsPastTarget = routeTraversalService.stopsPastTarget(target, observation);
                    emit(TransitEventType.PASSED, tracking, observation,
                            stopsPastTarget.isPresent() ? stopsPastTarget.getAsInt() : null, events);
                    next.trackedVehicles().remove(vehicleId);
                } else {
                    next.trackedVehicles().put(vehicleId, tracking.observe(observation, now));
                }
            }
            case BEFORE_TARGET -> {
                emitOneStopBeforeIfEligible(target, tracking, observation, now, events, next.trackedVehicles());
                VehicleTrackingState updatedTracking = next.trackedVehicles().get(vehicleId);
                if (updatedTracking != null) {
                    next.trackedVehicles().put(vehicleId, updatedTracking.observe(observation, now));
                }
            }
            case UNKNOWN -> next.trackedVehicles().put(vehicleId, tracking.observe(tracking.lastObservation(), now));
        }
    }

    private BusAlarmEvaluationResult evaluateFollowUp(
            Alarm alarm,
            BusAlarmTarget target,
            BusAlarmEvaluationState state,
            Set<String> presentVehicleIds,
            List<TransitObservation> observations,
            Instant now
    ) {
        if (!target.isNotifyOneStopAfter() || target.getSuccessorExternalStopId() == null
                || target.getSuccessorStopOrder() == null || alarm.getFollowUpVehicleTrackingId() == null
                || alarm.getFollowUpExpiresAt() == null) {
            throw new IllegalStateException("Follow-up alarm requires complete successor and runtime state");
        }
        if (!now.isBefore(alarm.getFollowUpExpiresAt().toInstant(ZoneOffset.UTC))) {
            return new BusAlarmEvaluationResult(List.of(), state, true);
        }

        BusAlarmEvaluationState.Mutable next = state.mutableCopy();
        touchPresentVehicles(next, presentVehicleIds, now);
        Snapshot snapshot = uniqueSnapshot(observations);
        String followUpVehicleId = alarm.getFollowUpVehicleTrackingId();
        TransitObservation observation = snapshot.observations().get(followUpVehicleId);
        if (observation == null || snapshot.ambiguousVehicleIds().contains(followUpVehicleId) || !isFresh(observation, now)) {
            return new BusAlarmEvaluationResult(List.of(), next.freeze(), false);
        }

        VehicleTrackingState tracking = next.followUpVehicles().get(followUpVehicleId);
        if (tracking != null && (!isMonotonic(tracking.lastObservation(), observation)
                || hasDirectionConflict(tracking.lastObservation(), observation))) {
            next.followUpVehicles().put(followUpVehicleId, tracking.observe(tracking.lastObservation(), now));
            return new BusAlarmEvaluationResult(List.of(), next.freeze(), false);
        }
        if (hasKnownStopConflict(target, observation) || !reachedSuccessor(target, observation)) {
            if (tracking != null) {
                next.followUpVehicles().put(followUpVehicleId, tracking.observe(observation, now));
            }
            return new BusAlarmEvaluationResult(List.of(), next.freeze(), false);
        }

        if (tracking == null) {
            tracking = VehicleTrackingState.begin(observation, now);
        }
        if (tracking.hasEmitted(TransitEventType.ONE_STOP_AFTER)) {
            next.followUpVehicles().put(followUpVehicleId, tracking.observe(observation, now));
            return new BusAlarmEvaluationResult(List.of(), next.freeze(), false);
        }
        TransitEvent event = event(TransitEventType.ONE_STOP_AFTER, tracking, observation, null);
        next.followUpVehicles().put(followUpVehicleId, tracking.emit(TransitEventType.ONE_STOP_AFTER, observation, now));
        return new BusAlarmEvaluationResult(List.of(event), next.freeze(), false);
    }

    private void emitOneStopBeforeIfEligible(
            BusAlarmTarget target,
            VehicleTrackingState tracking,
            TransitObservation observation,
            Instant now,
            List<TransitEvent> events,
            Map<String, VehicleTrackingState> trackedVehicles
    ) {
        if (!target.isNotifyOneStopBefore() || tracking.hasEmitted(TransitEventType.ONE_STOP_BEFORE)
                || !isExact(observation, target.getPredecessorExternalStopId(), target.getPredecessorStopOrder())) {
            return;
        }
        emit(TransitEventType.ONE_STOP_BEFORE, tracking, observation, null, events);
        trackedVehicles.put(observation.vehicleTrackingId(),
                tracking.emit(TransitEventType.ONE_STOP_BEFORE, observation, now));
    }

    private boolean canArrive(
            BusAlarmTarget target,
            VehicleTrackingState tracking,
            TransitObservation observation,
            boolean baseline
    ) {
        if (hasKnownStopConflict(target, observation) || positionOf(target, observation) != PositionRelation.AT_TARGET
                || gpsConflictsWithTarget(target, observation)) {
            return false;
        }
        if (target.getProvider() == TransitProvider.SEOUL_BUS) {
            return observation.arrivalEvidence() == ArrivalEvidence.ARRIVED;
        }
        if (target.getProvider() != TransitProvider.TAGO || observation.arrivalEvidence() != ArrivalEvidence.UNAVAILABLE) {
            return false;
        }
        if (baseline) {
            return hasTargetGpsEvidence(target, observation);
        }
        return tracking != null
                && positionOf(target, tracking.lastObservation()) == PositionRelation.BEFORE_TARGET
                && hasNewProgressEvidence(tracking.lastObservation(), observation);
    }

    private static boolean reachedSuccessor(BusAlarmTarget target, TransitObservation observation) {
        if (isExact(observation, target.getSuccessorExternalStopId(), target.getSuccessorStopOrder())) {
            return true;
        }
        return observation.currentStopExternalId() != null
                && observation.currentStopOrder() != null
                && observation.currentStopOrder() > target.getSuccessorStopOrder();
    }

    private static boolean isMonotonic(TransitObservation previous, TransitObservation current) {
        return previous.currentStopOrder() != null
                && current.currentStopOrder() != null
                && current.currentStopOrder() >= previous.currentStopOrder();
    }

    private static boolean hasNewProgressEvidence(TransitObservation previous, TransitObservation current) {
        if (!isMonotonic(previous, current) || previous.currentStopOrder().equals(current.currentStopOrder())) {
            return false;
        }
        return previous.providerDataTime() == null || current.providerDataTime() == null
                || !previous.providerDataTime().equals(current.providerDataTime());
    }

    private static boolean hasDirectionConflict(TransitObservation previous, TransitObservation current) {
        return previous.directionContext() != null && current.directionContext() != null
                && !previous.directionContext().equals(current.directionContext());
    }

    private static PositionRelation positionOf(BusAlarmTarget target, TransitObservation observation) {
        if (observation.currentStopExternalId() == null || observation.currentStopOrder() == null
                || hasKnownStopConflict(target, observation)) {
            return PositionRelation.UNKNOWN;
        }
        if (isExact(observation, target.getExternalStopId(), target.getTargetStopOrder())) {
            return PositionRelation.AT_TARGET;
        }
        return observation.currentStopOrder() < target.getTargetStopOrder()
                ? PositionRelation.BEFORE_TARGET : PositionRelation.AFTER_TARGET;
    }

    private static boolean hasKnownStopConflict(BusAlarmTarget target, TransitObservation observation) {
        return conflicts(observation, target.getExternalStopId(), target.getTargetStopOrder())
                || conflicts(observation, target.getPredecessorExternalStopId(), target.getPredecessorStopOrder())
                || conflicts(observation, target.getSuccessorExternalStopId(), target.getSuccessorStopOrder());
    }

    private static boolean conflicts(TransitObservation observation, String stopId, Integer stopOrder) {
        boolean sameStopIdWithDifferentOrder = stopId != null && observation.currentStopExternalId() != null
                && stopId.equals(observation.currentStopExternalId())
                && stopOrder != null && observation.currentStopOrder() != null
                && !stopOrder.equals(observation.currentStopOrder());
        boolean sameStopOrderWithDifferentId = stopOrder != null && observation.currentStopOrder() != null
                && stopOrder.equals(observation.currentStopOrder())
                && stopId != null && observation.currentStopExternalId() != null
                && !stopId.equals(observation.currentStopExternalId());
        return sameStopIdWithDifferentOrder || sameStopOrderWithDifferentId;
    }

    private static boolean isExact(TransitObservation observation, String stopId, Integer stopOrder) {
        return stopId != null && stopOrder != null && stopId.equals(observation.currentStopExternalId())
                && stopOrder.equals(observation.currentStopOrder());
    }

    private static boolean isFresh(TransitObservation observation, Instant now) {
        if (observation.observedAt().isAfter(now)
                || observation.observedAt().plus(BusAlarmEvaluationPolicy.OBSERVATION_FRESHNESS).isBefore(now)) {
            return false;
        }
        Instant providerDataTime = observation.providerDataTime();
        return providerDataTime == null || (!providerDataTime.isAfter(now)
                && !providerDataTime.plus(BusAlarmEvaluationPolicy.OBSERVATION_FRESHNESS).isBefore(now)
                && !providerDataTime.isAfter(observation.observedAt().plus(BusAlarmEvaluationPolicy.OBSERVATION_FRESHNESS)));
    }

    private static boolean hasTargetGpsEvidence(BusAlarmTarget target, TransitObservation observation) {
        return target.getTargetStopLatitude() != null && target.getTargetStopLongitude() != null
                && observation.latitude() != null && observation.longitude() != null
                && !gpsConflictsWithTarget(target, observation);
    }

    private static boolean gpsConflictsWithTarget(BusAlarmTarget target, TransitObservation observation) {
        if (target.getTargetStopLatitude() == null || target.getTargetStopLongitude() == null
                || observation.latitude() == null || observation.longitude() == null) {
            return false;
        }
        return haversineMeters(
                target.getTargetStopLatitude(), target.getTargetStopLongitude(), observation.latitude(), observation.longitude()
        ) > BusAlarmEvaluationPolicy.TAGO_TARGET_GPS_THRESHOLD_METERS;
    }

    private static double haversineMeters(BigDecimal firstLatitude, BigDecimal firstLongitude,
            BigDecimal secondLatitude, BigDecimal secondLongitude) {
        double latitudeDelta = Math.toRadians(secondLatitude.doubleValue() - firstLatitude.doubleValue());
        double longitudeDelta = Math.toRadians(secondLongitude.doubleValue() - firstLongitude.doubleValue());
        double firstLatitudeRadians = Math.toRadians(firstLatitude.doubleValue());
        double secondLatitudeRadians = Math.toRadians(secondLatitude.doubleValue());
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(firstLatitudeRadians) * Math.cos(secondLatitudeRadians)
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static void expireMissingVehicles(Map<String, VehicleTrackingState> vehicles, Instant now) {
        vehicles.entrySet().removeIf(entry -> entry.getValue().lastSeenAt()
                .plus(BusAlarmEvaluationPolicy.VEHICLE_MISSING_GRACE).isBefore(now));
    }

    private static void expireMissingBaselineAfterVehicles(Map<String, Instant> vehicles, Instant now) {
        vehicles.entrySet().removeIf(entry -> entry.getValue()
                .plus(BusAlarmEvaluationPolicy.VEHICLE_MISSING_GRACE).isBefore(now));
    }

    private static void touch(Map<String, VehicleTrackingState> vehicles, String vehicleId, Instant now) {
        VehicleTrackingState tracking = vehicles.get(vehicleId);
        if (tracking != null) {
            vehicles.put(vehicleId, tracking.observe(tracking.lastObservation(), now));
        }
    }

    private static void touchPresentVehicles(
            BusAlarmEvaluationState.Mutable state,
            Set<String> presentVehicleIds,
            Instant now
    ) {
        for (String vehicleId : presentVehicleIds) {
            touch(state.trackedVehicles(), vehicleId, now);
            if (state.baselineAfterVehicles().containsKey(vehicleId)) {
                state.baselineAfterVehicles().put(vehicleId, now);
            }
            touch(state.followUpVehicles(), vehicleId, now);
        }
    }

    private static BusAlarmTarget requireTarget(Alarm alarm) {
        if (alarm.getBusAlarmTarget() == null) {
            throw new IllegalStateException("Bus alarm evaluation requires a BusAlarmTarget");
        }
        return alarm.getBusAlarmTarget();
    }

    private static void validateObservationRoute(BusAlarmTarget target, List<TransitObservation> observations) {
        for (TransitObservation observation : observations) {
            if (observation.provider() != target.getProvider()
                    || !observation.externalRouteId().equals(target.getExternalRouteId())) {
                throw new IllegalArgumentException("Observation provider and route must match the BusAlarmTarget");
            }
        }
    }

    private static Snapshot uniqueSnapshot(List<TransitObservation> observations) {
        Map<String, TransitObservation> observationsByVehicle = new java.util.LinkedHashMap<>();
        Set<String> ambiguousVehicleIds = new HashSet<>();
        for (TransitObservation observation : observations) {
            if (observationsByVehicle.putIfAbsent(observation.vehicleTrackingId(), observation) != null) {
                ambiguousVehicleIds.add(observation.vehicleTrackingId());
            }
        }
        return new Snapshot(observationsByVehicle, ambiguousVehicleIds);
    }

    private static TransitEvent event(
            TransitEventType type,
            VehicleTrackingState tracking,
            TransitObservation observation,
            Integer stopsPastTarget
    ) {
        return new TransitEvent(
                type,
                observation.vehicleTrackingId(),
                tracking.trackingCycleId(),
                observation.observedAt(),
                observation.currentStopExternalId(),
                observation.currentStopName(),
                observation.currentStopOrder(),
                observation.latitude(),
                observation.longitude(),
                observation.providerDataTime(),
                stopsPastTarget
        );
    }

    private static void emit(TransitEventType type, VehicleTrackingState tracking, TransitObservation observation,
            Integer stopsPastTarget, List<TransitEvent> events) {
        events.add(event(type, tracking, observation, stopsPastTarget));
    }

    private enum PositionRelation {
        BEFORE_TARGET,
        AT_TARGET,
        AFTER_TARGET,
        UNKNOWN
    }

    private record Snapshot(Map<String, TransitObservation> observations, Set<String> ambiguousVehicleIds) {
    }
}
