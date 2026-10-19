package com.stopbell.transit.mapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

import com.stopbell.transit.dto.BoardingCandidatesResponse.ReasonCode;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleLocationItem;
import com.stopbell.transit.dto.tago.TagoVehicleLocationItem;

/** Preserves full plainNo separately from vehId without changing Alarm observation mapping. */
public final class BoardingCandidateMapper {
    private static final DateTimeFormatter DATA_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private BoardingCandidateMapper() {
    }

    public record Vehicle(
            String id, String number, String stopId, Integer order, Integer stopFlag,
            Instant observedAt, Instant providerDataTime, ReasonCode timeIssue, String coarseContext
    ) {
    }

    public static Vehicle tago(TagoVehicleLocationItem item, Instant observedAt) {
        return item == null ? new Vehicle(null, null, null, null, null, observedAt, null, null, null)
                : new Vehicle(text(item.vehicleNo()), text(item.vehicleNo()), text(item.nodeId()),
                        item.nodeOrder(), null, observedAt, null, null, null);
    }

    public static Vehicle roster(SeoulBusVehicleLocationItem item, Instant observedAt) {
        if (item == null) {
            return new Vehicle(null, null, null, null, null, observedAt, null, null, null);
        }
        return seoul(item.vehId(), item.plainNo(), null, null, null, observedAt, item.dataTm(),
                item.sectOrd() + ":" + item.sectionId() + ":" + item.nextStId() + ":" + item.stopFlag());
    }

    public static Vehicle detail(SeoulBusVehicleDetailItem item, Instant observedAt) {
        return seoul(item.vehId(), item.plainNo(), text(item.stId()), item.stOrd(), item.stopFlag(),
                observedAt, item.dataTm(), null);
    }

    private static Vehicle seoul(String id, String number, String stopId, Integer order, Integer flag,
            Instant observedAt, String dataTm, String coarseContext) {
        Instant providerTime = null;
        ReasonCode issue = null;
        if (text(dataTm) == null) {
            issue = ReasonCode.PROVIDER_TIME_MISSING;
        } else {
            try {
                providerTime = LocalDateTime.parse(dataTm, DATA_TIME).atZone(SEOUL).toInstant();
            } catch (DateTimeParseException exception) {
                issue = ReasonCode.PROVIDER_TIME_INVALID;
            }
        }
        return new Vehicle(text(id), text(number), stopId, order, flag, observedAt, providerTime,
                issue, coarseContext);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
