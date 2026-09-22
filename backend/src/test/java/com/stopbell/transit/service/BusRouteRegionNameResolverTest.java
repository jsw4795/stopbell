package com.stopbell.transit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusRouteRegionNameResolverTest {

    private final BusRouteRegionNameResolver resolver = new BusRouteRegionNameResolver();

    @Test
    @DisplayName("서울과 주요 경기도 TAGO cityCode를 표시 지역명으로 변환한다")
    void resolve_known_region_names() {
        assertThat(resolver.resolve(route(TransitProvider.SEOUL_BUS, "seoul", "N26", null))).isEqualTo("서울");
        assertThat(resolver.resolve(route(TransitProvider.TAGO, "suwon", "7000", "31010"))).isEqualTo("수원시");
        assertThat(resolver.resolve(route(TransitProvider.TAGO, "gimpo", "7000", "31230"))).isEqualTo("김포시");
        assertThat(resolver.resolve(route(TransitProvider.TAGO, "gapyeong", "7000", "31370"))).isEqualTo("가평군");
        assertThat(resolver.resolve(route(TransitProvider.TAGO, "yeoju", "7000", "31320"))).isEqualTo("여주시");
    }

    @Test
    @DisplayName("지원하지 않는 TAGO cityCode는 잘못된 지역명으로 변환하지 않고 실패한다")
    void reject_unknown_tago_city_code() {
        assertThatThrownBy(() -> resolver.resolve(route(TransitProvider.TAGO, "legacy-yeoju", "7000", "31280")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported TAGO city code: 31280");
    }

    private BusRoute route(TransitProvider provider, String externalRouteId, String routeNumber, String cityCode) {
        return new BusRoute(provider, externalRouteId, routeNumber, cityCode);
    }
}
