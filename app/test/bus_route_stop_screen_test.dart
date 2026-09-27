import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_stop_screen.dart';

import 'alarm_test_fakes.dart';

const route = BusRoute(id: 754, routeNumber: '7000', regionName: '수원시');
const first = BusRouteStopOccurrence(
  id: 11,
  name: '재방문 정류장',
  order: 1,
  previousStopName: null,
  nextStopName: '중간 정류장',
  canNotifyOneStopBefore: false,
  canNotifyOneStopAfter: true,
);
const middle = BusRouteStopOccurrence(
  id: 22,
  name: '중간 정류장',
  order: 3,
  destinationName: '종점',
  previousStopName: '재방문 정류장',
  nextStopName: '재방문 정류장',
  canNotifyOneStopBefore: true,
  canNotifyOneStopAfter: true,
);
const last = BusRouteStopOccurrence(
  id: 33,
  name: '재방문 정류장',
  order: 7,
  previousStopName: '중간 정류장',
  nextStopName: null,
  canNotifyOneStopBefore: true,
  canNotifyOneStopAfter: false,
);

Widget screen(Future<List<BusRouteStopOccurrence>> Function(int) findStops) =>
    MaterialApp(
      home: Scaffold(
        body: BusRouteStopScreen(
          route: route,
          findStops: findStops,
          onBack: () {},
          alarmClient: EmptyAlarmClient(),
          onCreated: (_) {},
          onShowAlarms: () {},
        ),
      ),
    );

void main() {
  testWidgets('loading then empty response is distinct from failure', (
    tester,
  ) async {
    final pending = Completer<List<BusRouteStopOccurrence>>();
    await tester.pumpWidget(screen((_) => pending.future));
    expect(find.text('7000'), findsOneWidget);
    expect(find.text('수원시'), findsOneWidget);
    expect(find.text('정류장 조회 중...'), findsOneWidget);
    pending.complete([]);
    await tester.pumpAndSettle();
    expect(find.text('정류장 정보가 없습니다.'), findsOneWidget);
    expect(find.text('정류장 정보를 불러오지 못했습니다.'), findsNothing);
  });

  testWidgets('error retry uses same route ID', (tester) async {
    final ids = <int>[];
    await tester.pumpWidget(
      screen((id) async {
        ids.add(id);
        if (ids.length == 1) throw Exception('internal detail');
        return [first];
      }),
    );
    await tester.pumpAndSettle();
    expect(find.text('정류장 정보를 불러오지 못했습니다.'), findsOneWidget);
    expect(find.textContaining('internal detail'), findsNothing);
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(ids, [754, 754]);
    expect(find.byKey(const ValueKey(11)), findsOneWidget);
  });

  testWidgets('repeated occurrences select by ID and options reset on change', (
    tester,
  ) async {
    await tester.pumpWidget(screen((_) async => [first, middle, last]));
    await tester.pumpAndSettle();
    expect(find.text('재방문 정류장'), findsNWidgets(2));
    expect(find.text('이전: 없음 · 다음: 중간 정류장'), findsOneWidget);
    expect(find.text('종점 방면'), findsOneWidget);
    expect(find.text('이전: 중간 정류장 · 다음: 없음'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey(22)));
    await tester.pump();
    var switches = tester
        .widgetList<SwitchListTile>(find.byType(SwitchListTile))
        .toList();
    expect(switches.map((item) => item.onChanged != null), [true, true]);
    await tester.tap(find.text('한 정거장 전 알림'));
    await tester.tap(find.text('한 정거장 후 알림'));
    await tester.pump();
    switches = tester
        .widgetList<SwitchListTile>(find.byType(SwitchListTile))
        .toList();
    expect(switches.map((item) => item.value), [true, true]);

    await tester.tap(find.byKey(const ValueKey(11)));
    await tester.pump();
    switches = tester
        .widgetList<SwitchListTile>(find.byType(SwitchListTile))
        .toList();
    expect(switches.map((item) => item.value), [false, false]);
    expect(switches.map((item) => item.onChanged != null), [false, true]);
    expect(
      tester.widget<ListTile>(find.byKey(const ValueKey(11))).selected,
      isTrue,
    );
    expect(
      tester.widget<ListTile>(find.byKey(const ValueKey(33))).selected,
      isFalse,
    );

    await tester.tap(find.byKey(const ValueKey(33)));
    await tester.pump();
    switches = tester
        .widgetList<SwitchListTile>(find.byType(SwitchListTile))
        .toList();
    expect(switches.map((item) => item.value), [false, false]);
    expect(switches.map((item) => item.onChanged != null), [true, false]);
    expect(
      tester.widget<ListTile>(find.byKey(const ValueKey(33))).selected,
      isTrue,
    );
  });

  testWidgets(
    'same destination and adjacent names use order only for unresolved duplicates',
    (tester) async {
      const repeatedA = BusRouteStopOccurrence(
        id: 41,
        name: '순환 정류장',
        order: 8,
        destinationName: '종점',
        previousStopName: '이전',
        nextStopName: '다음',
        canNotifyOneStopBefore: true,
        canNotifyOneStopAfter: true,
      );
      const repeatedB = BusRouteStopOccurrence(
        id: 42,
        name: '순환 정류장',
        order: 34,
        destinationName: '종점',
        previousStopName: '이전',
        nextStopName: '다음',
        canNotifyOneStopBefore: true,
        canNotifyOneStopAfter: true,
      );
      await tester.pumpWidget(screen((_) async => [repeatedA, repeatedB]));
      await tester.pumpAndSettle();
      expect(find.textContaining('노선 순서 8'), findsOneWidget);
      expect(find.textContaining('노선 순서 34'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey(42)));
      await tester.pump();
      expect(
        find.textContaining(
          '선택한 정류장: 순환 정류장\n종점 방면\n이전: 이전 · 다음: 다음\n노선 순서 34',
        ),
        findsOneWidget,
      );
    },
  );

}
