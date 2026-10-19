package com.stopbell.transit.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationResponse;
import com.stopbell.transit.dto.tago.TagoVehicleLocationItem;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;

final class BoardingCandidateTestFixtures {
    static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    static final String DATA_TIME = "20261019120000";

    static BoardingRouteSnapshot route(TransitProvider provider, Long boardingId) {
        return new BoardingRouteSnapshot(1L, provider, "external-route", provider == TransitProvider.TAGO
                ? "31010" : null, 14L, boardingId, List.of(
                new BoardingRouteSnapshot.Occurrence(11L, "s1", 10, "첫 정류장"),
                new BoardingRouteSnapshot.Occurrence(12L, "s2", 20, "승차 정류장"),
                new BoardingRouteSnapshot.Occurrence(13L, "s3", 30, "다음 정류장"),
                new BoardingRouteSnapshot.Occurrence(14L, "s4", 40, "목적지")), "7000");
    }

    static TagoVehicleLocationItem tago(String number, String stop, Integer order) {
        return new TagoVehicleLocationItem(number, stop, order, null, null);
    }

    static TagoVehicleLocationResponse tagoResponse(TagoVehicleLocationItem... items) {
        return new TagoVehicleLocationResponse(new TagoVehicleLocationResponse.Response(
                new TagoVehicleLocationResponse.Header("00", null), new TagoVehicleLocationResponse.Body(
                new TagoVehicleLocationResponse.Items(List.of(items)), items.length, 1, 1000)));
    }

    static SeoulBusVehicleLocationItem roster(String id, String number, String time) {
        return new SeoulBusVehicleLocationItem(id, number, 999, "coarse", 1, time,
                null, null, "not-an-occurrence", null, null);
    }

    static SeoulBusVehicleLocationResponse rosterResponse(SeoulBusVehicleLocationItem... items) {
        return new SeoulBusVehicleLocationResponse(new SeoulBusVehicleLocationResponse.Header("0", null),
                new SeoulBusVehicleLocationResponse.Body(List.of(items)));
    }

    static SeoulBusVehicleDetailItem detail(String id, String number, String stop, Integer order, String time) {
        return new SeoulBusVehicleDetailItem(id, number, stop, order, 1, time, null, null);
    }

    static SeoulBusVehicleDetailResponse detailResponse(SeoulBusVehicleDetailItem... items) {
        return new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("0", null),
                new SeoulBusVehicleDetailResponse.Body(List.of(items)));
    }

    static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
