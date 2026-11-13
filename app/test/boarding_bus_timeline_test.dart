import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/boarding/boarding_bus_timeline.dart';
import 'package:stopbell/features/boarding/boarding_candidate_client.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_stop_screen.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_timeline.dart'
    show BusVehicleMarker;
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

  for (final brightness in Brightness.values) {
    testWidgets('도착 버스가 있어도 강조 배경은 정류장 이름 행만 칠한다 ($brightness)', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(1000, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final split = splitBoardingBuses(
        candidates: [BoardingCandidate.fromJson(candidateJson())],
        vehicles: BusRouteVehicles.fromJson({
          'observedAt': '2026-11-12T01:00:00Z',
          'status': 'AVAILABLE',
          'vehicles': [_vehicle('서울70사1234', 10, 'AT_STOP')],
        }).vehicles,
        stops: _stops,
        targetId: 20,
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: brightness == Brightness.light
              ? StopBellTheme.light
              : StopBellTheme.dark,
          home: Scaffold(
            body: SingleChildScrollView(
              child: BoardingBusTimeline(
                stops: _stops,
                boardingId: 10,
                targetId: 20,
                buses: split.drawn,
                selected: null,
                canSelect: (_) => true,
                onSelect: (_) {},
                vehicleColor: Colors.blue,
              ),
            ),
          ),
        ),
      );
      Iterable<Container> tinted(Finder of) => tester
          .widgetList<Container>(
            find.ancestor(of: of, matching: find.byType(Container)),
          )
          .where((c) => c.color != null);
      // 정류장 이름 행에는 강조 배경이 있고, 버스 카드에는 깔리지 않는다.
      expect(tinted(find.textContaining('정류장10')), isNotEmpty);
      expect(tinted(find.textContaining('1234')), isEmpty);
    });
  }

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

    Future<void> pumpTimeline(
      WidgetTester tester, {
      String? Function(BoardingCandidate)? holdReason,
      bool Function(BoardingBusEntry)? where,
    }) async {
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
                buses: split.drawn.where(where ?? (_) => true).toList(),
                selected: picked,
                canSelect: (_) => holdReason == null,
                holdReason: holdReason,
                onSelect: (c) => picked = c,
                vehicleColor: Colors.blue,
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('선택 불가 버스 카드에 사유를 보여 준다', (tester) async {
      await pumpTimeline(tester, holdReason: (_) => '선택 보류 · 차량번호 중복');
      expect(find.text('선택 보류 · 차량번호 중복'), findsWidgets);
    });

    testWidgets('거리 라벨·접기·펼치기·버스 탭 선택', (tester) async {
      tester.view.physicalSize = const Size(600, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await pumpTimeline(tester);

      expect(find.text('승차까지 5정류장'), findsOneWidget);
      expect(find.text('목표까지 8정류장'), findsOneWidget);
      expect(find.textContaining('12석 · 여유'), findsNWidgets(2));
      // 버스·승차·목표 주변 외 구간은 접힌다.
      expect(find.text('정류장 6개'), findsOneWidget);
      // 버스 주변·승차·목표만 보이고 나머지는 1개여도 접는다.
      expect(find.text('정류장 1개'), findsOneWidget);
      // 승차 전 가장 앞 버스(도착, 정류장5)의 이전 정류장(정류장4)부터 그린다.
      expect(find.text('정류장 3개'), findsNothing);
      expect(find.text('정류장3'), findsNothing);
      expect(find.text('정류장 4개'), findsOneWidget);
      // 도착 버스는 이전 정류장과 도착 정류장만 보이고 다음 정류장은 접힌다.
      expect(find.text('정류장4'), findsOneWidget);
      expect(find.text('정류장5'), findsOneWidget);
      expect(find.text('정류장6'), findsNothing);
      expect(find.text('정류장12'), findsOneWidget); // 출발 버스의 출발 정류장
      expect(find.text('정류장13'), findsOneWidget); // 다음 정류장
      expect(find.text('정류장11'), findsNothing);
      expect(find.text('정류장15'), findsNothing);
      // 목표 이후 정류장은 그리지 않는다.
      expect(find.text('정류장21'), findsNothing);

      expect(
        tester
            .getSize(find.byKey(const ValueKey('boarding-bus-collapsed-13')))
            .height,
        greaterThanOrEqualTo(44),
      );
      expect(find.byType(Divider), findsWidgets);
      await tester.tap(find.byKey(const ValueKey('boarding-bus-collapsed-13')));
      await tester.pump();
      expect(find.text('정류장15'), findsOneWidget);
      expect(find.textContaining('정류장 6개'), findsNothing);

      // 다시 탭하면 접힌다(왕복).
      await tester.tap(find.byKey(const ValueKey('boarding-bus-fold-13')));
      await tester.pump();
      expect(find.text('정류장15'), findsNothing);
      expect(find.text('정류장 6개'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('boarding-bus-collapsed-13')));
      await tester.pump();
      expect(find.text('정류장15'), findsOneWidget);

      Finder card(String number) => find
          .ancestor(
            of: find.byKey(ValueKey('boarding-bus-$number')),
            matching: find.byType(Material),
          )
          .first;
      final arrived = card('서울70사1234'), departed = card('경기70아5678');
      // 카드는 레일에서 떨어진 같은 왼쪽 시작선·폭이다.
      expect(tester.getTopLeft(arrived).dx, tester.getTopLeft(departed).dx);
      expect(tester.getSize(arrived).width, tester.getSize(departed).width);
      expect(tester.getSize(arrived).height, greaterThanOrEqualTo(56));
      final markers = find.byType(BusVehicleMarker);
      final arrivedMarker = tester.getRect(markers.at(0));
      final departedMarker = tester.getRect(markers.at(1));
      // 출발: 카드 세로 중앙 = 마커 중앙, 카드는 마커와 떨어져 오른쪽에 있다.
      expect(
        departedMarker.center.dy,
        closeTo(tester.getCenter(departed).dy, 0.5),
      );
      expect(
        tester.getTopLeft(departed).dx,
        greaterThan(departedMarker.right + 8),
      );
      // 도착: 마커는 정류장 이름 행에 있고 카드는 그 위로 비켜 있다.
      expect(tester.getRect(arrived).bottom, lessThan(arrivedMarker.top));
      expect(find.text('정류장5'), findsOneWidget);
      expect(find.text('도착'), findsNothing);
      // 좌석·혼잡도는 두 카드 모두 번호 줄 오른쪽, 체크 원은 카드 세로 중앙이다.
      for (final number in ['서울70사1234', '경기70아5678']) {
        final c = card(number);
        final check = find.descendant(
          of: c,
          matching: find.byIcon(Icons.radio_button_unchecked),
        );
        expect(
          tester.getCenter(check).dy,
          closeTo(tester.getCenter(c).dy, 0.5),
        );
      }
      expect(find.byKey(const ValueKey('boarding-bus-halo')), findsNothing);
      expect(find.byIcon(Icons.radio_button_unchecked), findsNWidgets(2));

      await tester.tap(find.byKey(const ValueKey('boarding-bus-서울70사1234')));
      expect(picked?.vehicleNumber, '서울70사-1234');

      // 연결선(카드와 마커 사이)을 탭해도 그 버스가 선택된다.
      final rowLeft = tester.getTopLeft(departed).dx - 68;
      await tester.tapAt(Offset(rowLeft + 60, tester.getCenter(departed).dy));
      expect(picked?.vehicleNumber, '경기70아 5678');
    });
  });

  group('BoardingBusTimeline start range', () {
    testWidgets('승차 전 버스가 없으면 승차 정류장부터 그린다', (tester) async {
      tester.view.physicalSize = const Size(600, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final split = splitBoardingBuses(
        candidates: _candidates().candidates,
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
                // 승차 후(출발, 정류장12)에 있는 버스만 남긴다.
                buses: [
                  for (final b in split.drawn)
                    if (b.vehicle.occurrenceId! > 10) b,
                ],
                selected: null,
                canSelect: (_) => true,
                onSelect: (_) {},
                vehicleColor: Colors.blue,
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.textContaining('정류장10', findRichText: true), findsOneWidget);
      expect(find.text('정류장9'), findsNothing);
      expect(find.text('정류장5'), findsNothing);
      // 승차 이전 구간은 접힘 행도 없다(첫 접힘 행은 승차~버스 사이).
      expect(
        find.byKey(const ValueKey('boarding-bus-collapsed-0')),
        findsNothing,
      );
      expect(
        find.byKey(const ValueKey('boarding-bus-collapsed-3')),
        findsNothing,
      );
    });
  });

  group('BoardingStopScreen', () {
    testWidgets('후보 화면의 새로고침은 후보와 차량을 함께 다시 조회하고 선택을 해제한다', (tester) async {
      tester.view.physicalSize = const Size(1000, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final transport = MockClient((_) async => http.Response('', 204));
      final auth = await boardingAuth(transport);
      addTearDown(auth.dispose);
      addTearDown(transport.close);
      var vehicleCalls = 0, candidateCalls = 0;

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
              onSelectCandidate: (
                c,
                _, {
                required boarding,
                required target,
              }) {},
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

      await tap(find.byKey(const ValueKey('boarding-bus-경기70아5678')));
      expect(
        find.byKey(const ValueKey('continue-boarding-candidate')),
        findsOneWidget,
      );
      await tester.pump(const Duration(minutes: 2));
      expect(find.text('위치가 오래됐어요. 새로고침해 주세요'), findsOneWidget);
      tester.view.physicalSize = const Size(320, 568);
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      final candidatesBefore = candidateCalls, vehiclesBefore = vehicleCalls;
      await tap(find.byTooltip('차량 위치 새로고침'));
      expect(candidateCalls, candidatesBefore + 1);
      expect(vehicleCalls, greaterThan(vehiclesBefore));
      expect(
        find.byKey(const ValueKey('continue-boarding-candidate')),
        findsNothing,
      );
      // 새로고침 후에도 후보 화면에 머물고 '차량 후보 다시 조회' 버튼은 없다.
      expect(find.byKey(const ValueKey('candidate-3')), findsOneWidget);
      expect(find.text('정류장 다시 선택'), findsNothing);
      expect(find.text('차량 후보 다시 조회'), findsNothing);
    });

    testWidgets('새로고침 중에는 기존 내용을 유지하고 실패하면 오류만 안내한다', (tester) async {
      tester.view.physicalSize = const Size(1000, 3000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final transport = MockClient((_) async => http.Response('', 204));
      final auth = await boardingAuth(transport);
      addTearDown(auth.dispose);
      addTearDown(transport.close);
      var calls = 0;
      Completer<BoardingCandidates>? pending;

      await tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.light,
          home: Scaffold(
            body: BoardingStopScreen(
              route: boardingRoute,
              authSession: auth,
              findStops: (_) async => _stops,
              findVehicles: (_) async => _vehicles(),
              findCandidates:
                  ({
                    required routeId,
                    required targetStopOccurrenceId,
                    boardingStopOccurrenceId,
                    expectedGeneration,
                    isRequestCurrent,
                  }) {
                    calls++;
                    if (calls == 1) return Future.value(_candidates());
                    return (pending = Completer<BoardingCandidates>()).future;
                  },
              onSelectCandidate: (
                c,
                _, {
                required boarding,
                required target,
              }) {},
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
        await tester.pump();
      }

      await tap(find.byKey(const ValueKey('occurrence-10')));
      await tap(find.byKey(const ValueKey('occurrence-20')));
      await tap(find.byKey(const ValueKey('find-candidates')));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('candidate-3')), findsOneWidget);

      await tap(find.byTooltip('차량 위치 새로고침'));
      // 로딩 중: 화면 전환 없이 기존 내용을 유지하고 위치 바에 진행 표시를 둔다.
      expect(find.byKey(const ValueKey('candidate-3')), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      pending!.completeError(
        const BoardingCandidateException(CandidateFailure.network),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('candidate-3')), findsOneWidget);
      expect(find.textContaining('통신 오류'), findsOneWidget);
      expect(find.byTooltip('차량 위치 새로고침'), findsOneWidget);
    });

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
      // 선택하면 체크 원과 마커 halo가 나타난다.
      expect(find.byIcon(Icons.check_circle), findsOneWidget);
      expect(find.byKey(const ValueKey('boarding-bus-halo')), findsOneWidget);
      confirm().onPressed!();
      expect(opened?.vehicleNumber, '경기70아 5678');
    });
  });
}
