package com.stopbell.transit.mapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.stopbell.transit.client.SeoulBusVehicleLocationRequestContext;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;

public class SeoulBusTransitObservationMapper {

    private static final DateTimeFormatter PROVIDER_DATA_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId PROVIDER_ZONE = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    public SeoulBusTransitObservationMapper(Clock clock) {
        this.clock = clock;
    }

    public List<TransitObservation> map(
            VehicleLocationRequest<SeoulBusVehicleLocationRequestContext> request,
            SeoulBusVehicleDetailResponse response
    ) {
        Instant observedAt = clock.instant();
        List<SeoulBusVehicleDetailItem> items = response.body().itemList();

        return items.stream()
                .map(item -> mapItem(request.externalRouteId(), item, observedAt))
                .toList();
    }

    private TransitObservation mapItem(
            String externalRouteId,
            SeoulBusVehicleDetailItem item,
            Instant observedAt
    ) {
        return new TransitObservation(
                TransitProvider.SEOUL_BUS,
                externalRouteId,
                item.vehId(),
                optional(item.stId()),
                item.stOrd(),
                null,
                null,
                null,
                item.tmY(),
                item.tmX(),
                observedAt,
                providerDataTime(item.dataTm()),
                arrivalEvidence(item.stopFlag())
        );
    }

    private ArrivalEvidence arrivalEvidence(Integer stopFlag) {
        if (Integer.valueOf(1).equals(stopFlag)) {
            return ArrivalEvidence.ARRIVED;
        }
        if (Integer.valueOf(0).equals(stopFlag)) {
            return ArrivalEvidence.MOVING;
        }
        return ArrivalEvidence.UNAVAILABLE;
    }

    private Instant providerDataTime(String dataTm) {
        if (dataTm == null || dataTm.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(dataTm, PROVIDER_DATA_TIME_FORMAT)
                .atZone(PROVIDER_ZONE)
                .toInstant();
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
