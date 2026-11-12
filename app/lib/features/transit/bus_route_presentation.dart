import 'package:flutter/material.dart';

import 'bus_route.dart';

// Search accents based on the official color families, adjusted for card text.
const _gyeonggiRed = (light: Color(0xffb92232), dark: Color(0xffffa8ad));
const _gyeonggiBlue = (light: Color(0xff006493), dark: Color(0xff87ceff));
const _gyeonggiGreen = (light: Color(0xff007351), dark: Color(0xff79deb4));
const _gyeonggiYellow = (light: Color(0xff876000), dark: Color(0xffffd26f));

const _gyeonggiRouteColors = {
  'CITY_EXPRESS_SEAT': _gyeonggiRed,
  'CITY_SEAT': _gyeonggiBlue,
  'CITY_LOCAL': _gyeonggiGreen,
  'CITY_METRO_EXPRESS': _gyeonggiRed,
  'GYEONGGI_CIRCULAR': _gyeonggiRed,
  'VILLAGE': _gyeonggiYellow,
};

/// Presentation of normalized types; colors are only assigned where verified.
class BusRoutePresentation {
  const BusRoutePresentation(this.label, this.color);

  final String? label;
  final Color color;

  /// 공식 유형에 근거한 색이 배정됐는지 여부. 미분류·미검증 유형은 중립색이다.
  bool hasTypeColor(Brightness brightness) => color != neutral(brightness);

  static Color neutral(Brightness brightness) => brightness == Brightness.dark
      ? const Color(0xffb0b0b0)
      : const Color(0xff666666);

  factory BusRoutePresentation.forRoute(BusRoute route, Brightness brightness) {
    final dark = brightness == Brightness.dark;
    final neutral = BusRoutePresentation.neutral(brightness);
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
        // 공식 유형이 없는 서울 N번 노선은 심야(올빼미)버스다. 공식 색은 미확인이라
        // 다른 유형과 겹치지 않는 남색 계열을 쓴다.
        null when route.routeNumber.startsWith('N') => BusRoutePresentation(
          '심야',
          dark ? const Color(0xffb8b8ff) : const Color(0xff2b2f77),
        ),
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
      final colors = _gyeonggiRouteColors[route.routeType];
      return BusRoutePresentation(
        label,
        (dark ? colors?.dark : colors?.light) ?? neutral,
      );
    }
    return BusRoutePresentation(null, neutral);
  }
}

/// 노선 유형 표시색. 미분류·미검증 유형이면 null이다(호출부가 기본색을 쓴다).
Color? routeTypeColor(BuildContext context, BusRoute route) {
  final brightness = Theme.of(context).brightness;
  final presentation = BusRoutePresentation.forRoute(route, brightness);
  return presentation.hasTypeColor(brightness) ? presentation.color : null;
}
