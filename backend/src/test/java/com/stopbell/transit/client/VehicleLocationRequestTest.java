package com.stopbell.transit.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VehicleLocationRequestTest {

    @Test
    @DisplayName("차량 위치 조회 요청에는 비어 있지 않은 외부 노선 ID가 필요하다")
    void create_with_blank_external_route_id_fails() {
        assertThatThrownBy(() -> new VehicleLocationRequest<>(
                " ", new SeoulBusVehicleLocationRequestContext()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("차량 위치 조회 요청에는 Provider별 요청 문맥이 필요하다")
    void create_without_request_context_fails() {
        assertThatThrownBy(() -> new VehicleLocationRequest<SeoulBusVehicleLocationRequestContext>(
                "route-A-007", null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TAGO 차량 위치 조회 문맥에는 비어 있지 않은 cityCode가 필요하다")
    void create_tago_context_without_city_code_fails() {
        assertThatThrownBy(() -> new TagoVehicleLocationRequestContext(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
