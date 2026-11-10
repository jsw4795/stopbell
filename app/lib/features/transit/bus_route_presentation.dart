import 'package:flutter/material.dart';

import 'bus_route.dart';

/// Presentation of normalized types; colors are only assigned where verified.
class BusRoutePresentation {
  const BusRoutePresentation(this.label, this.color);

  final String? label;
  final Color color;

  factory BusRoutePresentation.forRoute(BusRoute route, Brightness brightness) {
    final dark = brightness == Brightness.dark;
    final neutral = dark ? const Color(0xffb0b0b0) : const Color(0xff666666);
    if (route.provider == 'SEOUL_BUS') {
      return switch (route.routeType) {
        'TRUNK' => BusRoutePresentation(
          '간선',
          dark ? const Color(0xffa6c0ff) : const Color(0xff194bb5),
        ),
        'BRANCH' => BusRoutePresentation(
          '지선',
          dark ? const Color(0xff82d9a1) : const Color(0xff17683f),
        ),
        'METROPOLITAN' => BusRoutePresentation(
          '광역',
          dark ? const Color(0xffffa8ad) : const Color(0xffad2634),
        ),
        'CIRCULAR' => BusRoutePresentation(
          '순환',
          dark ? const Color(0xffffd26f) : const Color(0xff876000),
        ),
        'VILLAGE' => BusRoutePresentation('마을', neutral),
        'AIRPORT' => BusRoutePresentation('공항', neutral),
        'TOUR' => BusRoutePresentation('관광', neutral),
        _ => BusRoutePresentation(null, neutral),
      };
    }
    if (route.provider == 'TAGO') {
      final label = switch (route.routeType) {
        'CITY_EXPRESS_SEAT' => '직행좌석형시내',
        'CITY_SEAT' => '좌석형시내',
        'CITY_LOCAL' => '일반형시내',
        'CITY_METRO_EXPRESS' => '광역급행형시내',
        'CITY_DDABOK' => '따복형시내',
        'GYEONGGI_CIRCULAR' => '경기순환',
        'RURAL_EXPRESS_SEAT' => '직행좌석형농어촌',
        'RURAL_SEAT' => '좌석형농어촌',
        'RURAL_LOCAL' => '일반형농어촌',
        'VILLAGE' => '마을',
        'INTERCITY_EXPRESS' => '고속형시외',
        'INTERCITY_SEAT' => '좌석형시외',
        'INTERCITY_LOCAL' => '일반형시외',
        'AIRPORT_LIMOUSINE' => '리무진공항',
        'AIRPORT_SEAT' => '좌석형공항',
        'AIRPORT_LOCAL' => '일반형공항',
        _ => null,
      };
      return BusRoutePresentation(label, neutral);
    }
    return BusRoutePresentation(null, neutral);
  }
}
