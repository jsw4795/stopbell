package com.stopbell.transit.mapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.stopbell.transit.client.TagoVehicleLocationRequestContext;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.tago.TagoVehicleLocationItem;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;

public class TagoTransitObservationMapper {

    private final Clock clock;

    public TagoTransitObservationMapper(Clock clock) {
        this.clock = clock;
    }

    public List<TransitObservation> map(
            VehicleLocationRequest<TagoVehicleLocationRequestContext> request,
            TagoVehicleLocationResponse response
    ) {
        Instant observedAt = clock.instant();
        List<TagoVehicleLocationItem> items = response.response().body().items().item();

        return items.stream()
                .map(item -> mapItem(request.externalRouteId(), item, observedAt))
                .toList();
    }

    private TransitObservation mapItem(String externalRouteId, TagoVehicleLocationItem item, Instant observedAt) {
        return new TransitObservation(
                TransitProvider.TAGO,
                externalRouteId,
                item.vehicleNo(),
                optional(item.nodeId()),
                item.nodeOrder(),
                null,
                null,
                null,
                item.gpsLatitude(),
                item.gpsLongitude(),
                observedAt,
                null,
                ArrivalEvidence.UNAVAILABLE
        );
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
