package com.stopbell.transit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.transit.client.SeoulBusVehicleLocationRequestContext;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeoulBusTransitObservationMapperTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-20T10:06:46Z");
    private final SeoulBusTransitObservationMapper mapper = new SeoulBusTransitObservationMapper(
            Clock.fixed(RECEIVED_AT, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("서울 차량 detail을 요청 노선 문맥의 도착 Observation으로 변환한다")
    void map_detail_to_arrived_observation() {
        var observations = mapper.map(request(), response(new SeoulBusVehicleDetailItem(
                "111033668", "서울75사2646", "112000001", 22, 1,
                "20260920190634", new BigDecimal("126.904572"), new BigDecimal("37.575465")
        )));

        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.provider()).isEqualTo(TransitProvider.SEOUL_BUS);
            assertThat(observation.externalRouteId()).isEqualTo("100100118");
            assertThat(observation.vehicleTrackingId()).isEqualTo("111033668");
            assertThat(observation.currentStopExternalId()).isEqualTo("112000001");
            assertThat(observation.currentStopOrder()).isEqualTo(22);
            assertThat(observation.longitude()).isEqualByComparingTo("126.904572");
            assertThat(observation.latitude()).isEqualByComparingTo("37.575465");
            assertThat(observation.arrivalEvidence()).isEqualTo(ArrivalEvidence.ARRIVED);
            assertThat(observation.providerDataTime()).isEqualTo(Instant.parse("2026-09-20T10:06:34Z"));
            assertThat(observation.observedAt()).isEqualTo(RECEIVED_AT);
            assertThat(observation.directionContext()).isNull();
            assertThat(observation.sectionContext()).isNull();
        });
    }

    @Test
    @DisplayName("서울 stopFlag의 이동과 해석 불가 상태를 direct evidence로 구분한다")
    void map_stop_flag_to_arrival_evidence() {
        var observations = mapper.map(request(), response(
                detailItem(0, "20260920190648"),
                detailItem(2, "20260920190655"),
                detailItem(null, null)
        ));

        assertThat(observations).extracting(observation -> observation.arrivalEvidence())
                .containsExactly(ArrivalEvidence.MOVING, ArrivalEvidence.UNAVAILABLE, ArrivalEvidence.UNAVAILABLE);
        assertThat(observations).extracting(observation -> observation.providerDataTime())
                .containsExactly(
                        Instant.parse("2026-09-20T10:06:48Z"),
                        Instant.parse("2026-09-20T10:06:55Z"),
                        null
                );
        assertThat(observations).extracting(observation -> observation.observedAt())
                .containsOnly(RECEIVED_AT);
    }

    private SeoulBusVehicleDetailItem detailItem(Integer stopFlag, String dataTm) {
        return new SeoulBusVehicleDetailItem(
                "111033668", "서울75사2646", null, null, stopFlag, dataTm, null, null
        );
    }

    private VehicleLocationRequest<SeoulBusVehicleLocationRequestContext> request() {
        return new VehicleLocationRequest<>("100100118", new SeoulBusVehicleLocationRequestContext());
    }

    private SeoulBusVehicleDetailResponse response(SeoulBusVehicleDetailItem... items) {
        return new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("0", "정상적으로 처리되었습니다."),
                new SeoulBusVehicleDetailResponse.Body(List.of(items))
        );
    }
}
