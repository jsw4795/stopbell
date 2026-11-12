import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/boarding/boarding_bus_timeline.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_stop_screen.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_vehicle.dart';

import 'boarding_test_fakes.dart';

// id = index + 1. 승차 id 10(index 9), 목표 id 20(index 19).
final _stops = [
  for (var i = 1; i <= 30; i++)
    BusRouteStopOccurrence(
      id: i,
      name: '정류장$i',
      order: i,
      canNotifyOneStopBefore: true,
      canNotifyOneStopAfter: true,
    ),
];

Map<String, dynamic> _vehicle(String number, int occurrence, String position) =>
    {
      'vehicleNumber': number,
      'occurrenceId': occurrence,
      'positionState': position,
      'seat': {'status': 'AVAILABLE', 'remaining': 12},
      'congestion': 1,
    };

BusRouteVehicles _vehicles() => BusRouteVehicles.fromJson({
  'observedAt': '2026-11-12T01:00:00Z',
  'status': 'AVAILABLE',
  'vehicles': [
    _vehicle('서울70사1234', 5, 'AT_STOP'), // 승차 전
    _vehicle('경기70아5678', 12, 'DEPARTED'), // 승차 후, 목표 전
    _vehicle('서울70사9999', 25, 'AT_STOP'), // 목표 통과
  ],
});

// 후보 번호는 하이픈이 있어 정규화 매칭을 확인한다. 마지막은 위치 없는 후보.
BoardingCandidates _candidates() => BoardingCandidates.fromJson(
  candidatesJson(
    candidates: [
      candidateJson(number: '서울70사-1234'),
      candidateJson(number: '경기70아 5678'),
      candidateJson(number: '서울70사9999'),
      candidateJson(number: '인천70바0001'),
    ],
  ),
);

void main() {
  test('번호 정규화로 연결하고 목표 통과·미연결 후보는 others로 나눈다', () {
    final candidates = _candidates();
    final split = splitBoardingBuses(
      candidates: candidates.candidates,
      vehicles: _vehicles().vehicles,
      stops: _stops,
      targetId: 20,
    );
    expect(split.drawn.map((e) => e.vehicle.vehicleNumber), [
      '서울70사1234',
      '경기70아5678',
    ]);
    // 목표 통과(서울70사9999)는 어느 쪽에도 없다.
    expect(split.others.map((e) => e.vehicleNumber), ['인천70바0001']);
  });

  test('목표 정류장을 출발한 차량은 그리지 않는다', () {
    final split = splitBoardingBuses(
      candidates: [BoardingCandidate.fromJson(candidateJson())],
      vehicles: BusRouteVehicles.fromJson({
        'observedAt': '2026-11-12T01:00:00Z',
        'status': 'AVAILABLE',
        'vehicles': [_vehicle('서울70사1234', 20, 'DEPARTED')],
      }).vehicles,
      stops: _stops,
      targetId: 20,
    );
    expect(split.drawn, isEmpty);
    expect(split.others, isEmpty);
  });

  test('후보 progress가 목표 이후인 미연결 후보도 제외한다', () {
    final past = BoardingCandidate.fromJson(
      candidateJson()
        ..['progress'] = {
          'occurrenceId': 25,
          'stopName': '정류장25',
          'order': 25,
          'kind': 'ROUTE_PROGRESS',
        },
    );
    final split = splitBoardingBuses(
      candidates: [past],
      vehicles: const [],
      stops: _stops,
      targetId: 20,
    );
    expect(split.drawn, isEmpty);
    expect(split.others, isEmpty);
  });

  group('BoardingBusTimeline', () {
    BoardingCandidate? picked;

    Future<void> pumpTimeline(WidgetTester tester) async {
      picked = null;
      final candidates = _candidates();
      final split = splitBoardingBuses(
        candidates: candidates.candidates,
        vehicles: _vehicles().vehicles,
        stops: _stops,
        targetId: 20,
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.light,
          home: Scaffold(
            body: SingleChildScrollView(
              child: BoardingBusTimeline(
                stops: _stops,
                boardingId: 10,
                targetId: 20,
                buses: split.drawn,
                selected: picked,
                canSelect: (_) => true,
                onSelect: (c) => picked = c,
                vehicleColor: Colors.blue,
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('거리 라벨·접기·펼치기·버스 탭 선택', (tester) async {
      tester.view.physicalSize = const Size(600, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await pumpTimeline(tester);

      expect(find.text('승차까지 5정거장'), findsOneWidget);
      expect(find.text('목표까지 8정거장'), findsOneWidget);
      expect(find.textContaining('12석 · 여유'), findsNWidgets(2));
      // 버스가 없는 연속 4개 구간(정류장14~17)만 접힌다.
      expect(find.text('· · ·  정류장 4개'), findsOneWidget);
      expect(find.text('정류장15'), findsNothing);
      // 목표 이후 정류장은 그리지 않는다.
      expect(find.text('정류장21'), findsNothing);

      await tester.tap(find.byKey(const ValueKey('boarding-bus-collapsed-13')));
      await tester.pump();
      expect(find.text('정류장15'), findsOneWidget);
      expect(find.textContaining('정류장 4개'), findsNothing);

      // 도착·출발 카드는 같은 왼쪽 시작선·폭이고 꼬리 정렬만 다르다.
      Finder card(String number) => find
          .ancestor(
            of: find.byKey(ValueKey('boarding-bus-$number')),
            matching: find.byType(Material),
          )
          .first;
      final arrived = card('서울70사1234'), departed = card('경기70아5678');
      expect(tester.getTopLeft(arrived).dx, tester.getTopLeft(departed).dx);
      expect(tester.getSize(arrived).width, tester.getSize(departed).width);
      expect(tester.getSize(arrived).height, greaterThanOrEqualTo(56));
      expect(
        (tester.widget<Material>(arrived).shape! as BusBubbleBorder).tailAtTop,
        isTrue,
      );
      expect(
        (tester.widget<Material>(departed).shape! as BusBubbleBorder).tailAtTop,
        isFalse,
      );
      expect(find.byIcon(Icons.radio_button_unchecked), findsNWidgets(2));

      await tester.tap(find.byKey(const ValueKey('boarding-bus-서울70사1234')));
      expect(picked?.vehicleNumber, '서울70사-1234');
    });
  });

  group('BoardingStopScreen', () {
    testWidgets('후보와 차량을 함께 조회해 버스 선택 후 확정 버튼을 활성화한다', (tester) async {
      tester.view.physicalSize = const Size(1000, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final transport = MockClient((_) async => http.Response('', 204));
      final auth = await boardingAuth(transport);
      addTearDown(auth.dispose);
      addTearDown(transport.close);
      var vehicleCalls = 0, candidateCalls = 0;
      BoardingCandidate? opened;

      await tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.light,
          home: Scaffold(
            body: BoardingStopScreen(
              route: boardingRoute,
              authSession: auth,
              findStops: (_) async => _stops,
              findVehicles: (_) async {
                vehicleCalls++;
                return _vehicles();
              },
              findCandidates:
                  ({
                    required routeId,
                    required targetStopOccurrenceId,
                    boardingStopOccurrenceId,
                    expectedGeneration,
                    isRequestCurrent,
                  }) async {
                    candidateCalls++;
                    return _candidates();
                  },
              onSelectCandidate: (c, _, {required boarding, required target}) =>
                  opened = c,
              onBack: () {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      Future<void> tap(Finder finder) async {
        await tester.ensureVisible(finder);
        await tester.pump();
        await tester.tap(finder);
        await tester.pumpAndSettle();
      }

      await tap(find.byKey(const ValueKey('occurrence-10')));
      await tap(find.byKey(const ValueKey('occurrence-20')));
      await tap(find.byKey(const ValueKey('find-candidates')));
      expect(candidateCalls, 1);
      expect(vehicleCalls, greaterThanOrEqualTo(2)); // 진입 + 후보 조회

      // 위치 확인 불가 목록: 미연결(3번) 후보만. 목표 통과(2번)는 표시하지 않는다.
      expect(find.text('위치 확인 불가'), findsOneWidget);
      expect(find.byKey(const ValueKey('candidate-0')), findsNothing);
      expect(find.byKey(const ValueKey('candidate-2')), findsNothing);
      expect(find.byKey(const ValueKey('candidate-3')), findsOneWidget);

      FilledButton confirm() => tester.widget<FilledButton>(
        find.byKey(const ValueKey('continue-boarding-candidate')),
      );
      // 선택 전에는 확정 버튼 영역이 없거나 비활성이다.
      expect(
        find.byKey(const ValueKey('continue-boarding-candidate')),
        findsNothing,
      );

      await tap(find.byKey(const ValueKey('boarding-bus-경기70아5678')));
      expect(confirm().onPressed, isNotNull);
      confirm().onPressed!();
      expect(opened?.vehicleNumber, '경기70아 5678');
    });
  });
}
