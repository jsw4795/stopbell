import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_stop_screen.dart';
import 'package:stopbell/features/transit/bus_route_timeline.dart';
import 'package:stopbell/features/transit/bus_route_vehicle.dart';
import 'package:stopbell/features/transit/bus_route_vehicle_bar.dart';
import 'package:stopbell/features/transit/bus_route_vehicle_controller.dart';

import 'alarm_creation_test_fake.dart';
import 'alarm_test_fakes.dart';

Map<String, dynamic> vehicleJson(
  String number, {
  int? occurrence,
  String position = 'AT_STOP',
  Map<String, dynamic>? seat,
  int? congestion,
}) => {
  'vehicleNumber': number,
  'occurrenceId': occurrence,
  'positionState': position,
  'seat': seat ?? {'status': 'UNAVAILABLE', 'remaining': null},
  'congestion': congestion,
};

BusRouteVehicles vehicles(
  List<BusRouteVehicle> list, {
  BusRouteVehiclesStatus status = BusRouteVehiclesStatus.available,
  DateTime? at,
}) => BusRouteVehicles(
  observedAt: at ?? DateTime.utc(2026, 11, 12, 8, 30),
  status: status,
  vehicles: list,
);

BusRouteVehicle bus(
  String number, {
  int? at,
  bool departed = false,
  BusVehicleSeatStatus seat = BusVehicleSeatStatus.unavailable,
  int? remaining,
  int? congestion,
}) => BusRouteVehicle(
  vehicleNumber: number,
  occurrenceId: at,
  position: departed ? BusVehiclePosition.departed : BusVehiclePosition.atStop,
  seatStatus: seat,
  seatRemaining: remaining,
  congestion: congestion,
);

const stops = [
  BusRouteStopOccurrence(
    id: 1,
    name: '가정류장',
    order: 1,
    canNotifyOneStopBefore: false,
    canNotifyOneStopAfter: true,
  ),
  BusRouteStopOccurrence(
    id: 2,
    name: '나정류장',
    order: 2,
    canNotifyOneStopBefore: true,
    canNotifyOneStopAfter: true,
  ),
  BusRouteStopOccurrence(
    id: 3,
    name: '다정류장',
    order: 3,
    canNotifyOneStopBefore: true,
    canNotifyOneStopAfter: false,
  ),
];

void main() {
  group('모델', () {
    test('응답을 파싱하고 차량번호는 끝 4자리로 줄인다', () {
      final parsed = BusRouteVehicles.fromJson({
        'observedAt': '2026-11-12T08:30:00Z',
        'status': 'PARTIAL',
        'vehicles': [
          vehicleJson(
            '경기74아3491',
            occurrence: 7,
            seat: {'status': 'AVAILABLE', 'remaining': 12},
            congestion: 2,
          ),
          vehicleJson('서울70사1234'),
        ],
      });
      expect(parsed.status, BusRouteVehiclesStatus.partial);
      expect(parsed.vehicles.first.shortNumber, '3491');
      expect(parsed.vehicles.first.seatRemaining, 12);
      expect(parsed.vehicles.last.occurrenceId, isNull);
    });

    test('계약에 어긋난 값은 거부한다', () {
      BusRouteVehicles parse(Map<String, dynamic> vehicle, [String? status]) =>
          BusRouteVehicles.fromJson({
            'observedAt': '2026-11-12T08:30:00Z',
            'status': status ?? 'AVAILABLE',
            'vehicles': [vehicle],
          });
      expect(
        () => parse(vehicleJson('1', congestion: 5)),
        throwsFormatException,
      );
      expect(
        () => parse(
          vehicleJson('1', seat: {'status': 'AVAILABLE', 'remaining': 0}),
        ),
        throwsFormatException,
      );
      expect(() => parse(vehicleJson('1'), 'UNKNOWN'), throwsFormatException);
    });
  });

  group('컨트롤러', () {
    test('조회 중 중복 요청을 막고 실패해도 마지막 성공 결과를 유지한다', () async {
      var calls = 0;
      var fail = false;
      final gate = Completer<BusRouteVehicles>();
      final controller = BusRouteVehicleController(
        routeId: 1,
        find: (_) {
          calls++;
          if (fail) return Future.error(Exception());
          return calls == 1 ? gate.future : Future.value(vehicles([]));
        },
      );
      final first = controller.refresh();
      await controller.refresh();
      expect(calls, 1);
      expect(controller.loading, isTrue);
      gate.complete(vehicles([bus('1111', at: 1)]));
      await first;
      expect(controller.data!.vehicles, hasLength(1));
      fail = true;
      await controller.refresh();
      expect(controller.failed, isTrue);
      expect(controller.data!.vehicles, hasLength(1));
      controller.dispose();
    });

    test('조회 2분 뒤 오래된 위치로 본다', () async {
      var now = DateTime(2026, 11, 12, 9);
      final controller = BusRouteVehicleController(
        routeId: 1,
        find: (_) async => vehicles([]),
        now: () => now,
      );
      await controller.refresh();
      expect(
        controller.isOutdated(now.add(const Duration(seconds: 119))),
        false,
      );
      expect(controller.isOutdated(now.add(const Duration(minutes: 2))), true);
      controller.dispose();
    });
  });

  group('노선도', () {
    Widget app(List<BusRouteVehicle>? list, {double scale = 1}) => MaterialApp(
      theme: StopBellTheme.light,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(scale)),
        child: child!,
      ),
      home: Scaffold(
        body: BusRouteTimeline(stops: stops, onSelect: (_) {}, vehicles: list),
      ),
    );

    testWidgets('차량이 없으면 차량 문구를 그리지 않는다', (tester) async {
      await tester.pumpWidget(app(null));
      expect(find.textContaining('3491'), findsNothing);
    });

    testWidgets('정류장 행에 좌석·혼잡도를 묶어 보여 주고 위치 불명 차량은 찍지 않는다', (tester) async {
      await tester.pumpWidget(
        app([
          bus(
            '경기74아3491',
            at: 2,
            seat: BusVehicleSeatStatus.available,
            remaining: 12,
          ),
          bus(
            '경기74아5555',
            at: 2,
            seat: BusVehicleSeatStatus.none,
            congestion: 4,
          ),
          bus('서울70사1234', congestion: 1),
        ]),
      );
      expect(find.textContaining('3491 · 12석'), findsOneWidget);
      expect(find.textContaining('5555 · 만석 · 매우혼잡'), findsOneWidget);
      expect(find.textContaining('1234'), findsNothing);
      expect(find.text('2'), findsOneWidget);
    });

    testWidgets('도착 차량은 정류장 동그라미 중심에, 출발 차량은 다음 정류장과의 사이에 그리며 색을 적용한다', (tester) async {
      const color = Color(0xffe53935);
      await tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.light,
          home: Scaffold(
            body: BusRouteTimeline(
              stops: stops,
              onSelect: (_) {},
              vehicleColor: color,
              vehicles: [
                bus('1111', at: 1),
                bus('2222', at: 2, departed: true),
              ],
            ),
          ),
        ),
      );
      final rail = find.byKey(const ValueKey(1));
      final markers = find.byWidgetPredicate(
        (w) => w is Icon && w.icon == Icons.directions_bus && w.size == 18,
      );
      expect(markers, findsNWidgets(2));
      for (final icon in tester.widgetList<Icon>(markers)) {
        expect(icon.color, color);
      }
      final first = tester.getCenter(find.textContaining('가정류장'));
      final atStop = tester.getCenter(markers.first);
      final between = tester.getCenter(markers.last);
      final row1 = tester.getRect(rail);
      final row2 = tester.getRect(find.byKey(const ValueKey(3)));
      expect(atStop.dy, closeTo(row1.center.dy, 1));
      expect(between.dy, closeTo(row2.top, 1));
      expect(atStop.dx, closeTo(between.dx, 0.1));
      expect(first.dy, closeTo(row1.center.dy, 30));
    });

    testWidgets('마커가 있어도 정류장 선택 탭이 동작하고 큰 글자에서도 넘치지 않는다', (tester) async {
      int? tapped;
      await tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.dark,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: const TextScaler.linear(2)),
            child: child!,
          ),
          home: Scaffold(
            body: BusRouteTimeline(
              stops: stops,
              onSelect: (s) => tapped = s.id,
              vehicles: [bus('1111', at: 2, congestion: 3)],
            ),
          ),
        ),
      );
      await tester.tap(find.textContaining('나정류장'));
      expect(tapped, 2);
      expect(tester.takeException(), isNull);
    });
  });

  group('안내 바', () {
    Future<BusRouteVehicleController> pumpBar(
      WidgetTester tester,
      Future<BusRouteVehicles> Function(int) find,
      DateTime Function() now,
    ) async {
      final controller = BusRouteVehicleController(
        routeId: 1,
        find: find,
        now: now,
      );
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: BusRouteVehicleBar(controller: controller, now: now),
          ),
        ),
      );
      return controller;
    }

    testWidgets('기준 시각·위치 확인 불가·PARTIAL 안내를 보여 준다', (tester) async {
      final now = DateTime.now();
      final controller = await pumpBar(
        tester,
        (_) async => vehicles(
          [bus('1', at: 1), bus('2'), bus('3')],
          status: BusRouteVehiclesStatus.partial,
          at: DateTime(2026, 11, 12, 8, 5),
        ),
        () => now,
      );
      await controller.refresh();
      await tester.pump();
      expect(find.text('08:05 기준 위치'), findsOneWidget);
      expect(find.text('위치 확인 불가 2대'), findsOneWidget);
      expect(find.text('일부 차량의 위치·좌석을 확인하지 못했어요'), findsOneWidget);
    });

    testWidgets('2분이 지나면 위치가 오래됐다는 안내를 숨기지 않고 보여 준다', (tester) async {
      var now = DateTime(2026, 11, 12, 9);
      final controller = await pumpBar(
        tester,
        (_) async => vehicles([bus('1', at: 1)]),
        () => now,
      );
      await controller.refresh();
      await tester.pump();
      expect(find.textContaining('오래됐어요'), findsNothing);
      now = now.add(const Duration(minutes: 2));
      await tester.pump(const Duration(minutes: 2));
      expect(find.text('위치가 오래됐어요. 새로고침해 주세요'), findsOneWidget);
    });

    testWidgets('이미 조회가 끝난 controller로 바를 만들어도 2분 뒤 안내한다', (tester) async {
      var now = DateTime(2026, 11, 12, 9);
      final controller = BusRouteVehicleController(
        routeId: 1,
        find: (_) async => vehicles([bus('1', at: 1)]),
        now: () => now,
      );
      addTearDown(controller.dispose);
      await controller.refresh();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: BusRouteVehicleBar(controller: controller, now: () => now),
          ),
        ),
      );
      expect(find.textContaining('오래됐어요'), findsNothing);
      now = now.add(const Duration(minutes: 2));
      await tester.pump(const Duration(minutes: 2));
      expect(find.text('위치가 오래됐어요. 새로고침해 주세요'), findsOneWidget);
    });

    testWidgets('실패하면 안내와 재시도를 보여 주고 운행 종료로 표시하지 않는다', (tester) async {
      var fail = true;
      final controller = await pumpBar(
        tester,
        (_) async => fail ? throw Exception() : vehicles([]),
        DateTime.now,
      );
      await controller.refresh();
      await tester.pump();
      expect(find.text('차량 정보를 불러오지 못했어요'), findsOneWidget);
      expect(find.textContaining('운행'), findsNothing);
      fail = false;
      await tester.tap(find.text('다시 시도'));
      await tester.pump();
      await tester.pump();
      expect(find.text('차량 정보를 불러오지 못했어요'), findsNothing);
    });
  });

  group('도착 알람 화면', () {
    testWidgets('진입 시 1회 조회하고 실패해도 노선도와 선택 동작은 유지한다', (tester) async {
      var calls = 0;
      await tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.light,
          home: Scaffold(
            body: BusRouteStopScreen(
              route: const BusRoute(
                id: 754,
                routeNumber: '7000',
                regionName: '수원시',
              ),
              findStops: (_) async => stops,
              findVehicles: (_) {
                calls++;
                return Future.error(Exception());
              },
              onBack: () {},
              alarmClient: EmptyAlarmClient(),
              creationController: ScreenCreationFake(EmptyAlarmClient()),
              onCreated: (_, {bool activationUnverified = false}) {},
              onShowAlarms: () {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(calls, 1);
      expect(find.text('차량 정보를 불러오지 못했어요'), findsOneWidget);
      await tester.tap(find.text('나정류장'));
      await tester.pump();
      expect(find.textContaining('선택한 정류장'), findsOneWidget);
    });
  });
}
