import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_view.dart';

import 'boarding_session_test_fakes.dart';

void main() {
  Map<String, Object?> timeline(int? position, String? basis) => {
    'provider': 'TAGO',
    'routeType': 'CITY_LOCAL',
    'boardingIndex': 2,
    'targetIndex': 5,
    'positionIndex': position,
    'positionBasis': basis,
    'stops': [
      {'id': 10, 'index': 2, 'order': 3, 'name': '반복 정류장'},
      {'id': 11, 'index': 3, 'order': 4, 'name': '경유 (미정차)'},
      {'id': 12, 'index': 4, 'order': 5, 'name': '반복 정류장'},
      {'id': 13, 'index': 5, 'order': 6, 'name': '목표'},
    ],
  };
  Future<void> show(
    WidgetTester tester,
    Map<String, dynamic> json, {
    Brightness brightness = Brightness.light,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(brightness: brightness),
        home: Scaffold(
          body: SingleChildScrollView(
            child: BoardingSessionDetails(
              session: BoardingSession.fromJson(json),
            ),
          ),
        ),
      ),
    );
  }

  testWidgets(
    'occurrences and marker update without conflating repeated names',
    (tester) async {
      final base = {...sessionJson(), 'notificationOffsetStops': 1};
      await show(tester, {...base, 'timeline': timeline(3, 'READY')});
      expect(find.text('반복 정류장'), findsNWidgets(2));
      expect(find.text('미정차'), findsOneWidget);
      expect(find.text('하차 준비 알림 정류장'), findsOneWidget);
      expect(find.byKey(const ValueKey('tracking-bus-marker')), findsOneWidget);
      expect(
        (tester
                    .getCenter(
                      find.byKey(const ValueKey('tracking-bus-marker')),
                    )
                    .dy -
                tester.getCenter(find.text('경유')).dy)
            .abs(),
        lessThan(2),
      );
      expect(
        tester
            .widget<Icon>(find.byKey(const ValueKey('tracking-bus-marker')))
            .icon,
        Icons.directions_bus,
      );
      await show(tester, {
        ...base,
        'timeline': timeline(4, 'READY'),
      }, brightness: Brightness.dark);
      expect(find.byKey(const ValueKey('tracking-bus-marker')), findsOneWidget);
      expect(find.text('노선 순서 5'), findsNothing);
      final markerY = tester
          .getCenter(find.byKey(const ValueKey('tracking-bus-marker')))
          .dy;
      expect(
        (markerY - tester.getCenter(find.text('반복 정류장').last).dy).abs(),
        lessThan(2),
      );
    },
  );
  testWidgets('before boarding and uncertain states do not invent a marker', (
    tester,
  ) async {
    await show(tester, {...sessionJson(), 'timeline': timeline(0, 'READY')});
    expect(find.textContaining('승차까지 2개 정류장'), findsOneWidget);
    expect(find.byKey(const ValueKey('tracking-bus-marker')), findsNothing);
    await show(tester, {
      ...sessionJson(evaluation: 'RECOVERY_PENDING'),
      'timeline': timeline(null, null),
    });
    expect(find.byKey(const ValueKey('tracking-bus-marker')), findsNothing);
  });
  testWidgets(
    'initial reference and terminal states distinguish historical positions',
    (tester) async {
      await show(tester, {
        ...sessionJson(evaluation: 'VERIFICATION_PENDING'),
        'checkpointObservedAt': null,
        'initialObservation': {'stopName': '경유', 'remainingOccurrenceGap': 2},
        'timeline': timeline(3, 'INITIAL'),
      });
      expect(find.text('참고용 위치예요'), findsOneWidget);
      expect(find.byKey(const ValueKey('tracking-bus-marker')), findsOneWidget);
      await show(tester, {
        ...sessionJson(evaluation: 'VERIFICATION_PENDING'),
        'checkpointObservedAt': null,
        'timeline': timeline(null, null),
      });
      expect(find.byKey(const ValueKey('tracking-bus-marker')), findsNothing);
      for (final status in ['COMPLETED', 'CANCELLED', 'EXPIRED']) {
        await show(tester, {
          ...sessionJson(status: status, evaluation: 'ENDED'),
          'timeline': timeline(3, 'READY'),
        });
        expect(find.byKey(const ValueKey('tracking-bus-marker')), findsNothing);
      }
    },
  );
  testWidgets('long names fit a small screen at large text scale', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final data = timeline(3, 'READY');
    final stops = (data['stops'] as List).cast<Map<String, Object?>>();
    stops[1]['name'] = '아주 긴 정류장 이름을 가진 환승센터와 회차 구간 앞 정류장';
    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: const MediaQueryData(
            size: Size(320, 600),
            textScaler: TextScaler.linear(2),
          ),
          child: Scaffold(
            body: SingleChildScrollView(
              child: BoardingSessionDetails(
                session: BoardingSession.fromJson({
                  ...sessionJson(),
                  'timeline': data,
                }),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('아주 긴 정류장 이름을 가진 환승센터와 회차 구간 앞 정류장'), findsOneWidget);
  });
  testWidgets('legacy session has no timeline', (tester) async {
    await show(tester, sessionJson());
    expect(find.textContaining('전체 노선 구간을 표시할 수 없어요'), findsOneWidget);
    expect(find.byKey(const ValueKey('tracking-bus-marker')), findsNothing);
  });

  testWidgets('하차 알람 상세 제목의 번호와 아이콘은 노선 유형색을 쓴다', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: BoardingSessionDetails(
              session: BoardingSession.fromJson({
                ...sessionJson(),
                'routeNumber': 'N16',
                'timeline': {
                  ...timeline(1, 'READY'),
                  'provider': 'SEOUL_BUS',
                  'routeType': null,
                },
              }),
            ),
          ),
        ),
      ),
    );
    const night = Color(0xff2b2f77);
    expect(tester.widget<Text>(find.text('N16')).style?.color, night);
    expect(
      tester
          .widget<Icon>(find.byIcon(Icons.directions_bus_outlined).first)
          .color,
      night,
    );
  });
}
