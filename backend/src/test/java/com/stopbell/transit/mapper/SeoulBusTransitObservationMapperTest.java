package com.stopbell.transit.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.stopbell.transit.client.SeoulBusVehicleLocationRequestContext;
import com.stopbell.transit.client.TransitProviderClientException;
import com.stopbell.transit.client.TransitProviderClientFailureKind;
import com.stopbell.transit.client.VehicleLocationRequest;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailItem;
import com.stopbell.transit.dto.seoul.SeoulBusVehicleDetailResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SeoulBusTransitObservationMapperTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-09-20T10:06:46Z");
    private final SeoulBusTransitObservationMapper mapper = new SeoulBusTransitObservationMapper(
            Clock.fixed(RECEIVED_AT, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("서울 차량 detail을 요청 노선 문맥의 도착 Observation으로 변환한다")
    void map_detail_to_arrived_observation() {
        var observations = mapper.map(request(), "111033668", response(new SeoulBusVehicleDetailItem(
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
        var observations = mapper.map(request(), "111033668", response(
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

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "2026-09-20 19:06:34", "20260230190634", "20260229190634",
            "20261320190634", "20260920240634", "20260920190660"})
    @DisplayName("형식 오류와 불가능한 Provider 날짜는 보정하지 않고 PROTOCOL 실패로 전달한다")
    void rejects_invalid_provider_time(String dataTm) {
        assertThatThrownBy(() -> mapper.map(request(), "111033668", response(detailItem(1, dataTm))))
                .isInstanceOfSatisfying(TransitProviderClientException.class, exception -> {
                    assertThat(exception.provider()).isEqualTo(TransitProvider.SEOUL_BUS);
                    assertThat(exception.operation()).isEqualTo("getBusPosByVehIdItem");
                    assertThat(exception.failureKind()).isEqualTo(TransitProviderClientFailureKind.PROTOCOL);
                    assertThat(exception.getCause()).isInstanceOf(java.time.format.DateTimeParseException.class);
                });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "other-vehicle"})
    @DisplayName("응답 차량 ID가 누락되거나 요청 차량과 다르면 상세 응답 전체를 거부한다")
    void rejects_missing_or_mismatched_vehicle(String vehicleId) {
        var invalid = new SeoulBusVehicleDetailItem(vehicleId, null, "target", 20, 1, null, null, null);
        assertThatThrownBy(() -> mapper.map(request(), "111033668", response(detailItem(0, null), invalid)))
                .isInstanceOfSatisfying(TransitProviderClientException.class, exception ->
                        assertThat(exception.failureKind()).isEqualTo(TransitProviderClientFailureKind.PROTOCOL));
    }

    @Test
    @DisplayName("상세 응답의 NULL item은 다른 정상 item을 부분 반환하지 않고 PROTOCOL 실패로 처리한다")
    void rejects_null_item() {
        var response = new SeoulBusVehicleDetailResponse(new SeoulBusVehicleDetailResponse.Header("0", "OK"),
                new SeoulBusVehicleDetailResponse.Body(java.util.Arrays.asList(detailItem(0, null), null)));
        assertThatThrownBy(() -> mapper.map(request(), "111033668", response))
                .isInstanceOfSatisfying(TransitProviderClientException.class, exception ->
                        assertThat(exception.failureKind()).isEqualTo(TransitProviderClientFailureKind.PROTOCOL));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("누락·공백 시각과 선택 필드의 NULL은 추정 없이 기존 결측 의미로 보존한다")
    void preserves_optional_null_fields(String dataTm) {
        assertThat(mapper.map(request(), "111033668", response(detailItem(null, dataTm))))
                .singleElement().satisfies(observation -> {
                    assertThat(observation.providerDataTime()).isNull();
                    assertThat(observation.currentStopExternalId()).isNull();
                    assertThat(observation.currentStopOrder()).isNull();
                    assertThat(observation.longitude()).isNull();
                    assertThat(observation.latitude()).isNull();
                    assertThat(observation.arrivalEvidence()).isEqualTo(ArrivalEvidence.UNAVAILABLE);
                });
    }

    @Test
    @DisplayName("정상 empty 상세 응답은 실패 없이 빈 관측으로 유지한다")
    void preserves_normal_empty() {
        assertThat(mapper.map(request(), "111033668", new SeoulBusVehicleDetailResponse(
                new SeoulBusVehicleDetailResponse.Header("4", "empty"), null))).isEmpty();
        assertThat(mapper.map(request(), "111033668", response())).isEmpty();
    }

    @Test
    @DisplayName("내부 요청 문맥의 오류는 Provider 실패로 감추지 않는다")
    void does_not_classify_internal_request_error_as_protocol() {
        assertThatThrownBy(() -> mapper.map(request(), "", response(detailItem(0, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(TransitProviderClientException.class);
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
