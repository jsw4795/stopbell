package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.transit.client.SeoulBusVehicleDetailClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationClient;
import com.stopbell.transit.client.SeoulBusVehicleLocationRequestContext;
import com.stopbell.transit.client.TagoVehicleLocationClient;
import com.stopbell.transit.client.TagoVehicleLocationRequestContext;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.BoardingCandidatesResponse;
import com.stopbell.transit.dto.BoardingCandidatesResponse.Candidate;
import com.stopbell.transit.dto.BoardingCandidatesResponse.CandidateSetStatus;
import com.stopbell.transit.dto.BoardingCandidatesResponse.ConfirmationStatus;
import com.stopbell.transit.dto.BoardingCandidatesResponse.Progress;
import com.stopbell.transit.dto.BoardingCandidatesResponse.ProgressKind;
import com.stopbell.transit.dto.BoardingCandidatesResponse.ReasonCode;
import com.stopbell.transit.dto.BoardingCandidatesResponse.TrackingSupport;
import com.stopbell.transit.mapper.BoardingCandidateMapper;
import com.stopbell.transit.mapper.BoardingCandidateMapper.Vehicle;
import com.stopbell.transit.service.BoardingCandidateReferenceStore.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Read-only orchestration. Provider I/O is outside the metadata transaction. */
@Service
public class BoardingCandidateService {
    private static final Logger log = LoggerFactory.getLogger(BoardingCandidateService.class);
    private final BoardingCandidateMetadataService metadataService;
    private final TagoVehicleLocationClient tagoClient;
    private final SeoulBusVehicleLocationClient seoulClient;
    private final SeoulBusVehicleDetailClient detailClient;
    private final BoardingCandidateReferenceStore references;
    private final Clock clock;

    public BoardingCandidateService(
            BoardingCandidateMetadataService metadataService, TagoVehicleLocationClient tagoClient,
            SeoulBusVehicleLocationClient seoulClient, SeoulBusVehicleDetailClient detailClient,
            BoardingCandidateReferenceStore references, @Qualifier("transitMetadataClock") Clock clock
    ) {
        this.metadataService = metadataService;
        this.tagoClient = tagoClient;
        this.seoulClient = seoulClient;
        this.detailClient = detailClient;
        this.references = references;
        this.clock = clock;
    }

    public BoardingCandidatesResponse find(Long userId, Long routeId, Long targetId, Long boardingId) {
        BoardingRouteSnapshot route = metadataService.read(routeId, targetId, boardingId);
        Context context = new Context(userId, routeId, targetId, boardingId);
        Roster roster;
        try {
            roster = fetchRoster(route);
        } catch (TransitProviderClientException exception) {
            // Do not log the cause, request URI or response body: they can contain service keys.
            log.warn("Boarding candidate lookup failed: stage=provider-roster provider={} operation={} "
                            + "kind={} upstreamStatus={} responseStatus=503",
                    route.provider(), exception.operation(), exception.failureKind(), exception.httpStatus());
            throw new ApiException(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE);
        }
        List<Vehicle> vehicles = roster.vehicles().stream().distinct()
                .sorted(Comparator.comparing(Vehicle::number, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Vehicle::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Set<String> conflictingIds = conflictingIds(vehicles);
        List<Detail> details = new ArrayList<>();
        // With no boarding occurrence, number checking needs only roster. Otherwise each valid,
        // unique vehicle needs at most one detail for the requested progress/gap display.
        for (Vehicle vehicle : vehicles) {
            if (route.provider() == TransitProvider.SEOUL_BUS && boardingId != null
                    && identityIssue(vehicle, conflictingIds) == null && timeIssue(vehicle, clock.instant()) == null) {
                details.add(fetchDetail(vehicle));
            } else {
                details.add(new Detail(null, route.provider() == TransitProvider.SEOUL_BUS
                        ? ReasonCode.DETAIL_NOT_REQUESTED : null));
            }
        }
        includeDetailConflicts(vehicles, details, conflictingIds);
        references.replace(context);
        List<Candidate> candidates = new ArrayList<>();
        Instant evaluatedAt = clock.instant();
        for (int index = 0; index < vehicles.size(); index++) {
            candidates.add(candidate(route, context, vehicles.get(index), details.get(index),
                    conflictingIds, evaluatedAt));
        }
        // Equal gaps have no preference. Vehicle numbers give a reproducible display order;
        // The stable input order breaks remaining ties without adding recommendation evidence.
        candidates.sort(Comparator.comparing(Candidate::boardingOccurrenceGap,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Candidate::vehicleNumber, Comparator.nullsLast(Comparator.naturalOrder())));
        CandidateSetStatus status = candidates.isEmpty() ? CandidateSetStatus.EMPTY
                : candidates.stream().anyMatch(this::partial) ? CandidateSetStatus.PARTIAL
                : CandidateSetStatus.AVAILABLE;
        List<Instant> providerTimes = candidates.stream().map(Candidate::providerDataTime).distinct().toList();
        Instant commonProviderTime = providerTimes.size() == 1 ? providerTimes.getFirst() : null;
        return new BoardingCandidatesResponse(roster.observedAt(), commonProviderTime, status, candidates);
    }

    /** Reuses candidate mapping and identity checks without issuing/replacing screen references. */
    public VerifiedCandidate revalidateSelected(BoardingCandidateReferenceStore.Snapshot original,
            BoardingRouteSnapshot route, String confirmedNumber) {
        if (!route.fingerprint().equals(original.metadataFingerprint())
                || !route.targetOccurrenceId().equals(original.context().targetOccurrenceId())
                || !java.util.Objects.equals(route.boardingOccurrenceId(), original.context().boardingOccurrenceId())
                || !confirmedNumber.equals(original.rosterVehicle().number())) {
            throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        }
        if (route.provider() == TransitProvider.TAGO) {
            throw new ApiException(ErrorCode.BOARDING_TRACKING_UNSUPPORTED);
        }
        Instant validationTime = clock.instant();
        Vehicle originalRoster = original.rosterVehicle();
        if (timeIssue(originalRoster, validationTime) != null
                || originalRoster.providerDataTime().isAfter(originalRoster.observedAt())) {
            throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        }
        Roster roster;
        try {
            roster = fetchRoster(route);
        } catch (TransitProviderClientException exception) {
            throw new ApiException(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE);
        }
        List<Vehicle> vehicles = roster.vehicles().stream().distinct().toList();
        String selectedId = original.rosterVehicle().id();
        Vehicle selected = vehicles.stream().filter(vehicle -> selectedId.equals(vehicle.id()))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED));
        if (identityIssue(selected, conflictingIds(vehicles)) != null
                || !confirmedNumber.equals(selected.number())) {
            throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        }
        Detail result = fetchDetail(selected);
        if (result.issue() != null) {
            throw new ApiException(switch (result.issue()) {
                case DETAIL_HTTP_FAILURE, DETAIL_TRANSPORT_FAILURE, DETAIL_PROVIDER_FAILURE, DETAIL_PROTOCOL_FAILURE
                        -> ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE;
                default -> ErrorCode.BOARDING_CANDIDATE_CHANGED;
            });
        }
        Vehicle detail = result.vehicle();
        Instant now = clock.instant();
        if (now.isAfter(original.expiresAt()) || timeIssue(selected, now) != null || timeIssue(detail, now) != null
                || detail.providerDataTime().isAfter(detail.observedAt())
                || selected.providerDataTime().isAfter(selected.observedAt())
                || selected.providerDataTime().isBefore(original.rosterVehicle().providerDataTime())) {
            throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        }
        int current = progressIndex(route, detail);
        if (current < 0) {
            throw new ApiException(detail.stopId() == null || detail.order() == null
                    ? ErrorCode.BOARDING_TRACKING_UNSUPPORTED : ErrorCode.BOARDING_CANDIDATE_CHANGED);
        }
        if (current >= route.indexOf(route.targetOccurrenceId())) {
            throw new ApiException(ErrorCode.BOARDING_TARGET_NOT_AHEAD);
        }
        // Moving detail semantics, order-only and repeated-stop/lap contexts are not verified.
        // Do not promote candidate sorting evidence into supported alight tracking.
        Vehicle previous = original.detailVehicle();
        if (!Integer.valueOf(1).equals(detail.stopFlag()) || previous == null
                || !Integer.valueOf(1).equals(previous.stopFlag()) || progressIndex(route, previous) < 0
                || route.traversal().stream().map(BoardingRouteSnapshot.Occurrence::externalStopId)
                    .distinct().count() != route.traversal().size()) {
            throw new ApiException(ErrorCode.BOARDING_TRACKING_UNSUPPORTED);
        }
        if (!detail.id().equals(previous.id()) || !detail.number().equals(previous.number())
                || current < progressIndex(route, previous)
                || detail.observedAt().isBefore(previous.observedAt())
                || previous.providerDataTime() == null || previous.timeIssue() != null
                || previous.providerDataTime().isAfter(previous.observedAt())
                || detail.providerDataTime().isBefore(previous.providerDataTime())) {
            throw new ApiException(ErrorCode.BOARDING_CANDIDATE_CHANGED);
        }
        return new VerifiedCandidate(route, detail, current);
    }

    public record VerifiedCandidate(BoardingRouteSnapshot route, Vehicle vehicle, int progressIndex) {
    }

    private Roster fetchRoster(BoardingRouteSnapshot route) {
        if (route.provider() == TransitProvider.TAGO) {
            var response = tagoClient.fetchVehicleLocations(new VehicleLocationRequest<>(route.externalRouteId(),
                    new TagoVehicleLocationRequestContext(route.cityCode())));
            Instant receivedAt = clock.instant();
            var items = response.response().body().items();
            List<Vehicle> vehicles = items == null || items.item() == null ? List.of()
                    : items.item().stream().map(item -> BoardingCandidateMapper.tago(item, receivedAt)).toList();
            return new Roster(receivedAt, vehicles);
        }
        var response = seoulClient.fetchVehicleLocations(new VehicleLocationRequest<>(route.externalRouteId(),
                new SeoulBusVehicleLocationRequestContext()));
        Instant receivedAt = clock.instant();
        List<Vehicle> vehicles = "4".equals(response.header().headerCd()) ? List.of()
                : response.body().itemList().stream()
                        .map(item -> BoardingCandidateMapper.roster(item, receivedAt)).toList();
        return new Roster(receivedAt, vehicles);
    }

    private Detail fetchDetail(Vehicle roster) {
        try {
            var response = detailClient.fetchVehicleDetail(roster.id());
            Instant receivedAt = clock.instant();
            var items = response.body() == null ? null : response.body().itemList();
            if ("4".equals(response.header().headerCd()) || items == null || items.isEmpty()) {
                return new Detail(null, ReasonCode.DETAIL_EMPTY);
            }
            if (items.size() != 1 || items.getFirst() == null) {
                return new Detail(null, ReasonCode.IDENTITY_CONFLICT);
            }
            Vehicle detail = BoardingCandidateMapper.detail(items.getFirst(), receivedAt);
            if (!roster.id().equals(detail.id())) {
                return new Detail(detail, ReasonCode.DETAIL_ID_MISMATCH);
            }
            if (!roster.number().equals(detail.number())) {
                return new Detail(detail, ReasonCode.DETAIL_NUMBER_MISMATCH);
            }
            return new Detail(detail, null);
        } catch (TransitProviderClientException exception) {
            return new Detail(null, switch (exception.failureKind()) {
                case HTTP -> ReasonCode.DETAIL_HTTP_FAILURE;
                case TRANSPORT -> ReasonCode.DETAIL_TRANSPORT_FAILURE;
                case PROVIDER -> ReasonCode.DETAIL_PROVIDER_FAILURE;
                case PROTOCOL -> ReasonCode.DETAIL_PROTOCOL_FAILURE;
            });
        }
    }

    private Candidate candidate(BoardingRouteSnapshot route, Context context, Vehicle roster, Detail result,
            Set<String> conflictingIds, Instant now) {
        ReasonCode held = identityIssue(roster, conflictingIds);
        if (held == null) {
            held = timeIssue(roster, now);
        }
        Vehicle detail = result.vehicle();
        if (held == null && detail != null) {
            held = timeIssue(detail, now);
        }
        if (held == null && result.issue() != null && result.issue() != ReasonCode.DETAIL_NOT_REQUESTED) {
            held = result.issue();
        }
        Vehicle progressVehicle = route.provider() == TransitProvider.TAGO ? roster : detail;
        int progressIndex = -1;
        ReasonCode positionIssue = result.issue();
        if (held == null && progressVehicle != null) {
            progressIndex = progressIndex(route, progressVehicle);
            if (progressIndex < 0) {
                positionIssue = progressVehicle.stopId() == null || progressVehicle.order() == null
                        ? ReasonCode.POSITION_UNAVAILABLE : ReasonCode.METADATA_MISMATCH;
                if (positionIssue == ReasonCode.METADATA_MISMATCH) {
                    held = positionIssue;
                }
            }
        }
        Progress progress = null;
        Integer gap = null;
        if (held == null && progressIndex >= 0) {
            var occurrence = route.traversal().get(progressIndex);
            ProgressKind kind = route.provider() == TransitProvider.TAGO ? ProgressKind.ROUTE_PROGRESS
                    : Integer.valueOf(1).equals(detail.stopFlag()) ? ProgressKind.ARRIVAL_OBSERVED
                    : Integer.valueOf(0).equals(detail.stopFlag()) ? ProgressKind.MOVING_REFERENCE
                    : ProgressKind.ROUTE_PROGRESS;
            progress = new Progress(occurrence.id(), occurrence.stopName(), occurrence.order(), kind);
            if (route.boardingOccurrenceId() != null) {
                gap = Math.abs(progressIndex - route.indexOf(route.boardingOccurrenceId()));
            }
        }
        ReasonCode trackingReason = route.provider() == TransitProvider.TAGO ? ReasonCode.TAGO_ALIGHT_UNVERIFIED
                : held != null ? held : progress == null ? positionIssue
                : progressIndex >= route.indexOf(route.targetOccurrenceId()) ? ReasonCode.TARGET_NOT_AHEAD
                : ReasonCode.REVALIDATION_REQUIRED;
        TrackingSupport support = trackingReason == ReasonCode.REVALIDATION_REQUIRED
                ? TrackingSupport.CONDITIONAL : TrackingSupport.HELD;
        List<ReasonCode> limitations = new ArrayList<>();
        if (held != null) {
            limitations.add(held);
        }
        if (positionIssue != null && !limitations.contains(positionIssue)) {
            limitations.add(positionIssue);
        }
        if (!limitations.contains(trackingReason)) {
            limitations.add(trackingReason);
        }
        String reference = held == null ? references.issue(context, route, roster, detail) : null;
        if (held == null && reference == null) {
            held = ReasonCode.STALE_OBSERVATION;
            progress = null;
            gap = null;
            support = TrackingSupport.HELD;
            trackingReason = held;
            limitations.add(held);
        }
        return new Candidate(reference, roster.number(), held == null ? ConfirmationStatus.AVAILABLE
                : ConfirmationStatus.HELD, held != null ? held : positionIssue, progress, gap,
                roster.observedAt(), detail == null ? roster.providerDataTime() : detail.providerDataTime(),
                support, trackingReason, limitations);
    }

    private static int progressIndex(BoardingRouteSnapshot route, Vehicle vehicle) {
        // No independent realtime-order validation exists for order-only observations.
        // In particular, never fill a missing nodeid or treat a numeric match as a stop.
        if (vehicle.stopId() == null || vehicle.order() == null || vehicle.order() <= 0) {
            return -1;
        }
        for (int index = 0; index < route.traversal().size(); index++) {
            var occurrence = route.traversal().get(index);
            if (occurrence.order() == vehicle.order() && occurrence.externalStopId().equals(vehicle.stopId())) {
                return index;
            }
        }
        return -1;
    }

    private static ReasonCode identityIssue(Vehicle vehicle, Set<String> conflicts) {
        if (vehicle.id() == null) {
            return ReasonCode.VEHICLE_ID_MISSING;
        }
        if (vehicle.number() == null) {
            return ReasonCode.VEHICLE_NUMBER_MISSING;
        }
        return conflicts.contains(vehicle.id()) ? ReasonCode.IDENTITY_CONFLICT : null;
    }

    private static Set<String> conflictingIds(List<Vehicle> vehicles) {
        Set<String> conflicts = new HashSet<>();
        Map<String, Vehicle> byId = new HashMap<>();
        Map<String, Vehicle> byNumber = new HashMap<>();
        for (Vehicle vehicle : vehicles) {
            Vehicle previousId = vehicle.id() == null ? null : byId.putIfAbsent(vehicle.id(), vehicle);
            Vehicle previousNumber = vehicle.number() == null ? null : byNumber.putIfAbsent(vehicle.number(), vehicle);
            if (previousId != null || previousNumber != null) {
                if (vehicle.id() != null) {
                    conflicts.add(vehicle.id());
                }
                if (previousId != null && previousId.id() != null) {
                    conflicts.add(previousId.id());
                }
                if (previousNumber != null && previousNumber.id() != null) {
                    conflicts.add(previousNumber.id());
                }
            }
        }
        return conflicts;
    }

    private static void includeDetailConflicts(List<Vehicle> vehicles, List<Detail> details, Set<String> conflicts) {
        for (int index = 0; index < vehicles.size(); index++) {
            Detail result = details.get(index);
            if (result.issue() != ReasonCode.DETAIL_ID_MISMATCH
                    && result.issue() != ReasonCode.DETAIL_NUMBER_MISMATCH) {
                continue;
            }
            Vehicle source = vehicles.get(index);
            Vehicle detail = result.vehicle();
            // A mismatched detail may also contradict another roster candidate's identity.
            // Keep the source's specific mismatch reason and hold the other implicated IDs.
            for (Vehicle other : vehicles) {
                if (other.id() != null && !other.id().equals(source.id())
                        && (other.id().equals(detail.id())
                            || (other.number() != null && other.number().equals(detail.number())))) {
                    conflicts.add(other.id());
                }
            }
        }
    }

    private static ReasonCode timeIssue(Vehicle vehicle, Instant now) {
        if (vehicle.timeIssue() != null) {
            return vehicle.timeIssue();
        }
        if (vehicle.observedAt().isAfter(now)
                || (vehicle.providerDataTime() != null && vehicle.providerDataTime().isAfter(now))) {
            return ReasonCode.FUTURE_OBSERVATION;
        }
        Instant cutoff = now.minus(BoardingCandidateReferenceStore.FRESHNESS);
        return vehicle.observedAt().isBefore(cutoff)
                || (vehicle.providerDataTime() != null && vehicle.providerDataTime().isBefore(cutoff))
                ? ReasonCode.STALE_OBSERVATION : null;
    }

    private boolean partial(Candidate candidate) {
        return candidate.confirmationStatus() == ConfirmationStatus.HELD
                || (candidate.reasonCode() != null && candidate.reasonCode() != ReasonCode.DETAIL_NOT_REQUESTED
                    && candidate.reasonCode() != ReasonCode.POSITION_UNAVAILABLE);
    }

    private record Roster(Instant observedAt, List<Vehicle> vehicles) {
    }

    private record Detail(Vehicle vehicle, ReasonCode issue) {
    }
}
