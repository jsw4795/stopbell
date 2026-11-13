import 'alarm_creation_test_fake.dart';

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_stop_screen.dart';
import 'package:stopbell/features/transit/bus_route_timeline.dart';

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

Widget screen(
  Future<List<BusRouteStopOccurrence>> Function(int) findStops, {
  TransitionBuilder? builder,
}) => MaterialApp(
  theme: StopBellTheme.light,
  builder: builder,
  home: Scaffold(
    body: BusRouteStopScreen(
      route: route,
      findStops: findStops,
      onBack: () {},
      alarmClient: EmptyAlarmClient(),
      creationController: ScreenCreationFake(EmptyAlarmClient()),
      onCreated: (_, {bool activationUnverified = false}) {},
      onShowAlarms: () {},
    ),
  ),
);

void main() {
  testWidgets(
    'direction buttons preserve a selected pass-through occurrence without reloading',
    (tester) async {
      const stops = [
        BusRouteStopOccurrence(
          id: 1,
          name: '출발',
          order: 1,
          destinationName: '회차',
          canNotifyOneStopBefore: false,
          canNotifyOneStopAfter: true,
        ),
        BusRouteStopOccurrence(
          id: 2,
          name: '교차로(미정차)',
          order: 2,
          destinationName: '회차',
          canNotifyOneStopBefore: true,
          canNotifyOneStopAfter: true,
        ),
        BusRouteStopOccurrence(
          id: 3,
          name: '회차',
          order: 3,
          destinationName: '종착',
          canNotifyOneStopBefore: true,
          canNotifyOneStopAfter: true,
        ),
        BusRouteStopOccurrence(
          id: 4,
          name: '종착',
          order: 4,
          destinationName: '종착',
          canNotifyOneStopBefore: true,
          canNotifyOneStopAfter: false,
        ),
      ];
      var requests = 0;
      await tester.pumpWidget(
        screen((_) async {
          requests++;
          return stops;
        }),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('교차로  미정차'), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey(2)));
      await tester.pumpAndSettle();
      await tester.tap(find.text('한 정류장 전 알림'));
      await tester.tap(find.text('종착 방면'));
      await tester.pumpAndSettle();
      expect(find.textContaining('선택한 정류장: 교차로(미정차)'), findsOneWidget);
      expect(
        tester
            .widget<SwitchListTile>(
              find.widgetWithText(SwitchListTile, '한 정류장 전 알림'),
            )
            .value,
        isTrue,
      );
      await tester.tap(find.text('회차 방면'));
      await tester.pumpAndSettle();
      expect(find.byIcon(Icons.flag_outlined).hitTestable(), findsOneWidget);
      expect(requests, 1);
    },
  );

  testWidgets('long selected name keeps arrival options and action reachable', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    const name = '평내동행정복지센터.대명루첸.평내초등학교.평내호평역환승센터';
    await tester.pumpWidget(
      screen(
        (_) async => const [
          BusRouteStopOccurrence(
            id: 1,
            name: name,
            order: 1,
            canNotifyOneStopBefore: false,
            canNotifyOneStopAfter: true,
          ),
          middle,
        ],
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(
            textScaler: const TextScaler.linear(2),
            padding: const EdgeInsets.only(bottom: 34),
          ),
          child: child!,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey(1)));
    await tester.pumpAndSettle();

    expect(find.textContaining('선택한 정류장: $name'), findsOneWidget);
    expect(find.byType(BusRouteTimeline).hitTestable(), findsOneWidget);
    final after = find.widgetWithText(SwitchListTile, '한 정류장 후 알림');
    await tester.ensureVisible(after);
    await tester.pumpAndSettle();
    await tester.tap(after.hitTestable());
    await tester.pumpAndSettle();
    expect(tester.widget<SwitchListTile>(after).value, isTrue);

    final start = find.widgetWithText(FilledButton, '알람 시작');
    await tester.ensureVisible(start);
    await tester.pumpAndSettle();
    expect(start.hitTestable(), findsOneWidget);
    expect(tester.getBottomRight(start).dy, lessThanOrEqualTo(568 - 34));
    expect(tester.takeException(), isNull);
  });

  testWidgets('loading then empty response is distinct from failure', (
    tester,
  ) async {
    final pending = Completer<List<BusRouteStopOccurrence>>();
    await tester.pumpWidget(screen((_) => pending.future));
    expect(find.text('7000'), findsOneWidget);
    expect(
      tester.getCenter(find.byIcon(Icons.directions_bus_outlined)).dx,
      lessThan(tester.getCenter(find.text('7000')).dx),
    );
    expect(find.text('수원시'), findsOneWidget);
    expect(find.text('정류장 조회 중...'), findsOneWidget);
    pending.complete([]);
    await tester.pumpAndSettle();
    expect(find.text('정류장 정보가 없어요.'), findsOneWidget);
    expect(find.text('정류장 정보를 불러오지 못했어요.'), findsNothing);
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
    expect(find.text('정류장 정보를 불러오지 못했어요.'), findsOneWidget);
    expect(find.textContaining('internal detail'), findsNothing);
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(ids, [754, 754]);
    expect(find.byKey(const ValueKey(11)), findsOneWidget);
  });

  testWidgets('tapping the selected stop again clears the selection', (
    tester,
  ) async {
    await tester.pumpWidget(screen((_) async => [first, middle, last]));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey(22)));
    await tester.pump();
    expect(find.text('알람 시작'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey(22)));
    await tester.pump();
    expect(find.text('알람 시작'), findsNothing);
  });

  testWidgets('repeated occurrences select by ID and options reset on change', (
    tester,
  ) async {
    await tester.pumpWidget(screen((_) async => [first, middle, last]));
    await tester.pumpAndSettle();
    expect(find.textContaining('재방문 정류장'), findsNWidgets(2));
    expect(find.textContaining('이전:'), findsNothing);

    await tester.tap(find.byKey(const ValueKey(22)));
    await tester.pump();
    expect(find.textContaining('종점 방면'), findsOneWidget);
    var switches = tester
        .widgetList<SwitchListTile>(find.byType(SwitchListTile))
        .toList();
    expect(switches.map((item) => item.onChanged != null), [true, true]);
    await tester.tap(find.text('한 정류장 전 알림'));
    await tester.tap(find.text('한 정류장 후 알림'));
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
    // 불가능한 '전' 옵션은 숨기고 값은 초기화된다.
    expect(find.text('한 정류장 전 알림'), findsNothing);
    expect(switches.map((item) => item.value), [false]);
    expect(find.text('한 정류장 후 알림'), findsOneWidget);
    expect(
      tester
          .widget<Semantics>(
            find
                .descendant(
                  of: find.byKey(const ValueKey(11)),
                  matching: find.byType(Semantics),
                )
                .first,
          )
          .properties
          .selected,
      isTrue,
    );
    expect(
      tester
          .widget<Semantics>(
            find
                .descendant(
                  of: find.byKey(const ValueKey(33)),
                  matching: find.byType(Semantics),
                )
                .first,
          )
          .properties
          .selected,
      isFalse,
    );

    expect(find.textContaining('이전: 없음 · 다음: 중간 정류장'), findsOneWidget);
    await tester.ensureVisible(find.byKey(const ValueKey(33)));
    await tester.tap(find.byKey(const ValueKey(33)));
    await tester.pump();
    switches = tester
        .widgetList<SwitchListTile>(find.byType(SwitchListTile))
        .toList();
    expect(find.text('한 정류장 후 알림'), findsNothing);
    expect(switches.map((item) => item.value), [false]);
    expect(find.textContaining('이전: 중간 정류장 · 다음: 없음'), findsOneWidget);
    expect(
      tester
          .widget<Semantics>(
            find
                .descendant(
                  of: find.byKey(const ValueKey(33)),
                  matching: find.byType(Semantics),
                )
                .first,
          )
          .properties
          .selected,
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
      expect(find.textContaining('노선 순서 8'), findsNothing);
      expect(find.textContaining('노선 순서 34'), findsNothing);
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

  testWidgets('불가능한 전후 알림 스위치는 숨기고 둘 다 불가능하면 영역을 숨긴다', (tester) async {
    const neither = BusRouteStopOccurrence(
      id: 50,
      name: '단일 정류장',
      order: 50,
      canNotifyOneStopBefore: false,
      canNotifyOneStopAfter: false,
    );
    await tester.pumpWidget(
      screen((_) async => [first, middle, last, neither]),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey(50)));
    await tester.pumpAndSettle();
    expect(find.byType(SwitchListTile), findsNothing);
    expect(find.text('알람 시작'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey(22)));
    await tester.pumpAndSettle();
    expect(find.byType(SwitchListTile), findsNWidgets(2));
    expect(find.text('도착하면 알려드려요'), findsOneWidget);
    final before = find.widgetWithText(SwitchListTile, '한 정류장 전 알림');
    final after = find.widgetWithText(SwitchListTile, '한 정류장 후 알림');
    expect(tester.getSize(before).height, greaterThanOrEqualTo(44));
    expect(
      tester.getTopLeft(after).dy,
      greaterThan(tester.getTopLeft(before).dy),
    );
    await tester.tap(find.text('한 정류장 전 알림'));
    await tester.pump();
    expect(tester.widget<SwitchListTile>(before).value, isTrue);
  });
  testWidgets('도착 알림 안내를 열고 배경 탭으로 즉시 닫는다', (tester) async {
    final semantics = tester.ensureSemantics();
    await tester.pumpWidget(screen((_) async => [first, middle, last]));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey(22)));
    await tester.pumpAndSettle();
    final button = find.bySemanticsLabel('도착 알림 안내');
    expect(button, findsOneWidget);
    expect(tester.getSize(button).width, greaterThanOrEqualTo(44));
    expect(tester.getSize(button).height, greaterThanOrEqualTo(44));
    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(find.text('이 정류장에 버스가 도착하면 알림을 보내드려요'), findsOneWidget);
    await tester.tapAt(const Offset(5, 100));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('이 정류장에 버스가 도착하면 알림을 보내드려요'), findsNothing);
    semantics.dispose();
  });
}
