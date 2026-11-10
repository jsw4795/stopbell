import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_presentation.dart';

void main() {
  const red = (0xffb92232, 0xffffa8ad);
  const blue = (0xff006493, 0xff87ceff);
  const green = (0xff007351, 0xff79deb4);
  const yellow = (0xff876000, 0xffffd26f);
  const neutral = (0xff666666, 0xffb0b0b0);

  const gyeonggiTypes = {
    11: ('CITY_EXPRESS_SEAT', '직행좌석형시내', red),
    12: ('CITY_SEAT', '좌석형시내', blue),
    13: ('CITY_LOCAL', '일반형시내', green),
    14: ('CITY_METRO_EXPRESS', '광역급행형시내', red),
    15: ('CITY_DDABOK', '따복형시내', neutral),
    16: ('GYEONGGI_CIRCULAR', '경기순환', red),
    21: ('RURAL_EXPRESS_SEAT', '직행좌석형농어촌', neutral),
    22: ('RURAL_SEAT', '좌석형농어촌', neutral),
    23: ('RURAL_LOCAL', '일반형농어촌', neutral),
    30: ('VILLAGE', '마을', yellow),
    41: ('INTERCITY_EXPRESS', '고속형시외', neutral),
    42: ('INTERCITY_SEAT', '좌석형시외', neutral),
    43: ('INTERCITY_LOCAL', '일반형시외', neutral),
    51: ('AIRPORT_LIMOUSINE', '리무진공항', neutral),
    52: ('AIRPORT_SEAT', '좌석형공항', neutral),
    53: ('AIRPORT_LOCAL', '일반형공항', neutral),
  };
  const seoulTypes = {
    'TRUNK': ('간선', (0xff194bb5, 0xffa6c0ff)),
    'BRANCH': ('지선', (0xff17683f, 0xff82d9a1)),
    'METROPOLITAN': ('광역', (0xffad2634, 0xffffa8ad)),
    'CIRCULAR': ('순환', (0xff876000, 0xffffd26f)),
    'VILLAGE': ('마을', neutral),
    'AIRPORT': ('공항', neutral),
    'TOUR': ('관광', neutral),
  };

  for (final theme in [StopBellTheme.light, StopBellTheme.dark]) {
    Color expectedColor((int, int) colors) =>
        Color(theme.brightness == Brightness.dark ? colors.$2 : colors.$1);

    BusRoutePresentation present(
      String? provider,
      String? type, {
      String number = '165',
    }) {
      final route = BusRoute.fromJson({
        'id': 1,
        'routeNumber': number,
        'regionName': '경기',
        'provider': provider,
        'routeType': type,
      });
      expect(route.routeType, type);
      return BusRoutePresentation.forRoute(route, theme.brightness);
    }

    void expectNeutral(String? provider, String? type) {
      final presentation = present(provider, type);
      expect(presentation.label, isNull, reason: '$provider/$type');
      expect(presentation.color, expectedColor(neutral));
    }

    group('${theme.brightness}', () {
      for (final MapEntry(key: code, value: (type, label, colors))
          in gyeonggiTypes.entries) {
        test('GBIS $code $type uses the route color and keeps its label', () {
          final presentation = present('TAGO', type);
          expect(presentation.label, label);
          expect(presentation.color, expectedColor(colors));
          expect(
            present('TAGO', type, number: 'M2352').color,
            presentation.color,
          );
          final background = theme.cardTheme.color!.computeLuminance();
          final foreground = presentation.color.computeLuminance();
          final contrast = background > foreground
              ? (background + 0.05) / (foreground + 0.05)
              : (foreground + 0.05) / (background + 0.05);
          expect(contrast, greaterThanOrEqualTo(4.5));
          if (type != 'VILLAGE') expectNeutral('SEOUL_BUS', type);
        });
      }

      test('all Seoul types keep their exact previous colors', () {
        for (final MapEntry(key: type, value: (label, colors))
            in seoulTypes.entries) {
          final presentation = present('SEOUL_BUS', type);
          expect(presentation.label, label);
          expect(presentation.color, expectedColor(colors));
          if (type != 'VILLAGE') expectNeutral('TAGO', type);
        }
      });

      test('missing and unknown provider or type fall back safely', () {
        for (final provider in ['TAGO', 'SEOUL_BUS', null, 'OTHER']) {
          for (final type in [null, 'FUTURE', 'LOCAL', '50']) {
            expectNeutral(provider, type);
          }
        }
        expectNeutral(null, 'CITY_LOCAL');
        expectNeutral('OTHER', 'TRUNK');
      });
    });
  }
}
