package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import com.stopbell.transit.domain.BusRouteType;
import org.junit.jupiter.api.Test;

class BusRouteTypeMapperTest {
    @Test
    void seoulOfficialValuesAndUnsupportedValues() {
        Map.of("간선", BusRouteType.TRUNK, "지선", BusRouteType.BRANCH,
                "광역", BusRouteType.METROPOLITAN, "마을", BusRouteType.VILLAGE,
                "순환", BusRouteType.CIRCULAR, "공항", BusRouteType.AIRPORT,
                "관광", BusRouteType.TOUR).forEach((raw, expected) ->
                        assertThat(BusRouteTypeMapper.fromSeoul(raw)).isEqualTo(expected));
        for (String raw : new String[]{null, "", "경기", "인천", "미확인"})
            assertThat(BusRouteTypeMapper.fromSeoul(raw)).isNull();
    }

    @Test
    void gbisOfficialCodesAndUnsupportedValues() {
        Map.ofEntries(
                Map.entry("11", BusRouteType.CITY_EXPRESS_SEAT), Map.entry("12", BusRouteType.CITY_SEAT),
                Map.entry("13", BusRouteType.CITY_LOCAL), Map.entry("14", BusRouteType.CITY_METRO_EXPRESS),
                Map.entry("15", BusRouteType.CITY_DDABOK), Map.entry("16", BusRouteType.GYEONGGI_CIRCULAR),
                Map.entry("21", BusRouteType.RURAL_EXPRESS_SEAT), Map.entry("22", BusRouteType.RURAL_SEAT),
                Map.entry("23", BusRouteType.RURAL_LOCAL), Map.entry("30", BusRouteType.VILLAGE),
                Map.entry("41", BusRouteType.INTERCITY_EXPRESS), Map.entry("42", BusRouteType.INTERCITY_SEAT),
                Map.entry("43", BusRouteType.INTERCITY_LOCAL), Map.entry("51", BusRouteType.AIRPORT_LIMOUSINE),
                Map.entry("52", BusRouteType.AIRPORT_SEAT), Map.entry("53", BusRouteType.AIRPORT_LOCAL)
        ).forEach((raw, expected) -> assertThat(BusRouteTypeMapper.fromGbis(raw)).isEqualTo(expected));
        for (String raw : new String[]{null, "", "50", "014", "99"})
            assertThat(BusRouteTypeMapper.fromGbis(raw)).isNull();
    }
}
