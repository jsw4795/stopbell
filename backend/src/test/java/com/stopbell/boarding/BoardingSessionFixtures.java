package com.stopbell.boarding;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.boarding.entity.*;
import com.stopbell.boarding.service.VerifiedBoardingStart;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.service.BoardingRouteSnapshot;
import com.stopbell.user.entity.User;

public final class BoardingSessionFixtures {
    public static final Instant NOW = Instant.parse("2026-10-20T03:00:00.123456Z");
    public static final LocalDateTime UTC_NOW = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    public static final String REQUEST_ID = "aaaaaaaa-1111-4111-8111-111111111111";
    public static final String NUMBER = "서울70사1234";

    private BoardingSessionFixtures() {
    }

    public static BoardingRouteSnapshot metadata() {
        return new BoardingRouteSnapshot(1L, TransitProvider.SEOUL_BUS, "route-id", null, 13L, 11L,
                List.of(new BoardingRouteSnapshot.Occurrence(11L, "s1", 10, "출발"),
                        new BoardingRouteSnapshot.Occurrence(12L, "s2", 20, "중간"),
                        new BoardingRouteSnapshot.Occurrence(13L, "s3", 30, "목적지")), "7000");
    }

    /** Controlled evidence for storage/HTTP/transaction tests; not a production support claim. */
    public static VerifiedBoardingStart verified(BoardingRouteSnapshot metadata) {
        var traversal = new BoardingTraversalSnapshot(1, metadata.provider(), metadata.externalRouteId(),
                metadata.cityCode(), metadata.traversal().stream().map(item -> new BoardingTraversalSnapshot.Occurrence(
                        item.id(), item.externalStopId(), item.order())).toList(),
                metadata.indexOf(metadata.targetOccurrenceId()), BoardingTraversalSnapshot.AlightAvailability.ALLOWED,
                "controlled-test-evidence");
        var progress = traversal.occurrences().getFirst();
        return new VerifiedBoardingStart(metadata, traversal, new BoardingCheckpoint(progress.externalStopId(),
                progress.order(), 0, BoardingCheckpoint.Evidence.ID_ORDER, null, UTC_NOW, UTC_NOW.minusSeconds(1)),
                NOW.plusSeconds(60), "vehicle-id", NUMBER, metadata.routeNumber(), "목적지");
    }

    public static BoardingSession session(User user) {
        var start = verified(metadata());
        return new BoardingSession(user, REQUEST_ID, "a".repeat(64), start.vehicleTrackingId(), NUMBER,
                "7000", "목적지", start.traversal(), start.checkpoint(), UTC_NOW, UTC_NOW.plusHours(4));
    }
}
