package com.stopbell.transit.service;

import java.util.Map;

import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusRoute;

final class BusRouteRegionNameResolver {

    private static final Map<String, String> TAGO_REGION_NAMES = Map.ofEntries(
            Map.entry("31010", "수원시"),
            Map.entry("31020", "성남시"),
            Map.entry("31030", "의정부시"),
            Map.entry("31040", "안양시"),
            Map.entry("31050", "부천시"),
            Map.entry("31060", "광명시"),
            Map.entry("31070", "평택시"),
            Map.entry("31080", "동두천시"),
            Map.entry("31090", "안산시"),
            Map.entry("31100", "고양시"),
            Map.entry("31110", "과천시"),
            Map.entry("31120", "구리시"),
            Map.entry("31130", "남양주시"),
            Map.entry("31140", "오산시"),
            Map.entry("31150", "시흥시"),
            Map.entry("31160", "군포시"),
            Map.entry("31170", "의왕시"),
            Map.entry("31180", "하남시"),
            Map.entry("31190", "용인시"),
            Map.entry("31200", "파주시"),
            Map.entry("31210", "이천시"),
            Map.entry("31220", "안성시"),
            Map.entry("31230", "김포시"),
            Map.entry("31240", "화성시"),
            Map.entry("31250", "광주시"),
            Map.entry("31260", "양주시"),
            Map.entry("31270", "포천시"),
            Map.entry("31320", "여주시"),
            Map.entry("31350", "연천군"),
            Map.entry("31370", "가평군"),
            Map.entry("31380", "양평군")
    );

    String resolve(BusRoute route) {
        if (route.getProvider() == TransitProvider.SEOUL_BUS) {
            return "서울";
        }
        if (route.getProvider() == TransitProvider.TAGO) {
            String regionName = TAGO_REGION_NAMES.get(route.getCityCode());
            if (regionName != null) {
                return regionName;
            }
            throw new IllegalStateException("Unsupported TAGO city code: " + route.getCityCode());
        }
        throw new IllegalStateException("Unsupported transit provider: " + route.getProvider());
    }
}
