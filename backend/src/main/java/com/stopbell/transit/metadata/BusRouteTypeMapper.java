package com.stopbell.transit.metadata;

import com.stopbell.transit.domain.BusRouteType;

/** Source values remain distinct from StopBell's normalized classifications. */
public final class BusRouteTypeMapper {
    private BusRouteTypeMapper() { }

    public static BusRouteType fromSeoul(String value) {
        if (value == null) return null;
        return switch (value.trim()) {
            case "간선" -> BusRouteType.TRUNK;
            case "지선" -> BusRouteType.BRANCH;
            case "광역" -> BusRouteType.METROPOLITAN;
            case "마을" -> BusRouteType.VILLAGE;
            case "순환" -> BusRouteType.CIRCULAR;
            case "공항" -> BusRouteType.AIRPORT;
            case "관광" -> BusRouteType.TOUR;
            default -> null;
        };
    }

    public static BusRouteType fromGbis(String value) {
        if (value == null) return null;
        return switch (value.trim()) {
            case "11" -> BusRouteType.CITY_EXPRESS_SEAT;
            case "12" -> BusRouteType.CITY_SEAT;
            case "13" -> BusRouteType.CITY_LOCAL;
            case "14" -> BusRouteType.CITY_METRO_EXPRESS;
            case "15" -> BusRouteType.CITY_DDABOK;
            case "16" -> BusRouteType.GYEONGGI_CIRCULAR;
            case "21" -> BusRouteType.RURAL_EXPRESS_SEAT;
            case "22" -> BusRouteType.RURAL_SEAT;
            case "23" -> BusRouteType.RURAL_LOCAL;
            case "30" -> BusRouteType.VILLAGE;
            case "41" -> BusRouteType.INTERCITY_EXPRESS;
            case "42" -> BusRouteType.INTERCITY_SEAT;
            case "43" -> BusRouteType.INTERCITY_LOCAL;
            case "51" -> BusRouteType.AIRPORT_LIMOUSINE;
            case "52" -> BusRouteType.AIRPORT_SEAT;
            case "53" -> BusRouteType.AIRPORT_LOCAL;
            default -> null;
        };
    }
}
