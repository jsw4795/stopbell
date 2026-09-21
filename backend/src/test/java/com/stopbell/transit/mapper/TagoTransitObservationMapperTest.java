package com.stopbell.transit.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.transit.client.TagoVehicleLocationRequestContext;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.tago.TagoVehicleLocationItem;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TagoTransitObservationMapperTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-20T10:05:56Z");
    private final TagoTransitObservationMapper mapper = new TagoTransitObservationMapper(
            Clock.fixed(RECEIVED_AT, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("TAGO 차량 위치를 요청 노선 문맥의 TransitObservation으로 변환한다")
    void map_tago_location_to_observation() {
        var observations = mapper.map(request(), response(new TagoVehicleLocationItem(
                "경기70바5770", "GGB228001174", 1,
                new BigDecimal("37.2402833"), new BigDecimal("127.0824")
        )));

        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.provider()).isEqualTo(TransitProvider.TAGO);
            assertThat(observation.externalRouteId()).isEqualTo("GGB200000112");
            assertThat(observation.vehicleTrackingId()).isEqualTo("경기70바5770");
            assertThat(observation.currentStopExternalId()).isEqualTo("GGB228001174");
            assertThat(observation.currentStopOrder()).isEqualTo(1);
            assertThat(observation.latitude()).isEqualByComparingTo("37.2402833");
            assertThat(observation.longitude()).isEqualByComparingTo("127.0824");
            assertThat(observation.arrivalEvidence()).isEqualTo(ArrivalEvidence.UNAVAILABLE);
            assertThat(observation.observedAt()).isEqualTo(RECEIVED_AT);
            assertThat(observation.providerDataTime()).isNull();
        });
    }

    @Test
    @DisplayName("TAGO 응답의 여러 차량은 같은 성공 응답 수신 시각을 공유한다")
    void map_multiple_items_with_same_observed_at() {
        var observations = mapper.map(request(), response(
                new TagoVehicleLocationItem("경기70바5770", null, null, null, null),
                new TagoVehicleLocationItem("경기70바5771", null, null, null, null)
        ));

        assertThat(observations).extracting(observation -> observation.observedAt())
                .containsOnly(RECEIVED_AT);
        assertThat(observations).allSatisfy(observation -> {
            assertThat(observation.currentStopExternalId()).isNull();
            assertThat(observation.currentStopOrder()).isNull();
            assertThat(observation.latitude()).isNull();
            assertThat(observation.longitude()).isNull();
        });
    }

    @Test
    @DisplayName("TAGO 차량 식별자가 없으면 임의의 tracking ID를 만들지 않는다")
    void map_without_vehicle_id_fails() {
        assertThatThrownBy(() -> mapper.map(request(), response(
                new TagoVehicleLocationItem(null, "GGB228001174", 1, null, null)
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vehicle tracking ID must not be blank");
    }

    private VehicleLocationRequest<TagoVehicleLocationRequestContext> request() {
        return new VehicleLocationRequest<>("GGB200000112", new TagoVehicleLocationRequestContext("31010"));
    }

    private TagoVehicleLocationResponse response(TagoVehicleLocationItem... items) {
        return new TagoVehicleLocationResponse(new TagoVehicleLocationResponse.Response(
                new TagoVehicleLocationResponse.Header("00", "NORMAL SERVICE."),
                new TagoVehicleLocationResponse.Body(
                        new TagoVehicleLocationResponse.Items(List.of(items)), 1, 1, 10
                )
        ));
    }
}
