import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/alarm/alarm_detail_screen.dart';
import 'package:stopbell/features/alarm/alarm_list_screen.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_stop_screen.dart';

const sample = Alarm(
  id: 15,
  transitType: AlarmTransitType.bus,
  status: AlarmStatus.inactive,
  routeNumber: '7000',
  stopName: '사색의광장',
  notifyOneStopBefore: true,
  notifyOneStopAfter: false,
);

Alarm withStatus(AlarmStatus status) => Alarm(
  id: sample.id,
  transitType: sample.transitType,
  status: status,
  routeNumber: sample.routeNumber,
  stopName: sample.stopName,
  notifyOneStopBefore: sample.notifyOneStopBefore,
  notifyOneStopAfter: sample.notifyOneStopAfter,
);

class StubAlarms extends Fake implements AlarmClient {
  Future<List<Alarm>> Function()? list;
  Future<Alarm> Function(int)? detail;
  Future<Alarm> Function(int, bool, bool)? createAlarm;
  Future<Alarm> Function(int)? activateAlarm;
  Future<Alarm> Function(int)? deactivateAlarm;
  Future<void> Function(int)? deleteAlarm;
  int createCalls = 0;
  int activateCalls = 0;
  int deleteCalls = 0;

  @override
  Future<List<Alarm>> findAll() => list!();
  @override
  Future<Alarm> findById(int id) => detail!(id);
  @override
  Future<Alarm> create({
    required int targetStopOccurrenceId,
    required bool notifyOneStopBefore,
    required bool notifyOneStopAfter,
  }) {
    createCalls++;
    return createAlarm!(
      targetStopOccurrenceId,
      notifyOneStopBefore,
      notifyOneStopAfter,
    );
  }

  @override
  Future<Alarm> activate(int id) {
    activateCalls++;
    return activateAlarm!(id);
  }

  @override
  Future<Alarm> deactivate(int id) => deactivateAlarm!(id);
  @override
  Future<void> delete(int id) {
    deleteCalls++;
    return deleteAlarm!(id);
  }
}

Widget listScreen(
  StubAlarms client, {
  void Function(int)? onSelect,
  VoidCallback? onCreate,
}) => MaterialApp(
  home: Scaffold(
    body: AlarmListScreen(
      findAll: client.findAll,
      onSelect: onSelect ?? (_) {},
      onCreate: onCreate ?? () {},
    ),
  ),
);

Widget detailScreen(
  StubAlarms client, {
  Alarm? created,
  VoidCallback? onBack,
}) => MaterialApp(
  home: Scaffold(
    body: AlarmDetailScreen(
      alarmId: 15,
      client: client,
      createdAlarm: created,
      onBack: onBack ?? () {},
    ),
  ),
);

Widget stopScreen(
  StubAlarms client,
  Future<List<BusRouteStopOccurrence>> Function(int) findStops, {
  void Function(Alarm)? onCreated,
  VoidCallback? onShowAlarms,
}) => MaterialApp(
  home: Scaffold(
    body: BusRouteStopScreen(
      route: const BusRoute(id: 754, routeNumber: '7000', regionName: '수원시'),
      findStops: findStops,
      alarmClient: client,
      onBack: () {},
      onCreated: onCreated ?? (_) {},
      onShowAlarms: onShowAlarms ?? () {},
    ),
  ),
);

const stop = BusRouteStopOccurrence(
  id: 12345,
  name: '사색의광장',
  order: 3,
  canNotifyOneStopBefore: true,
  canNotifyOneStopAfter: true,
);

void main() {
  testWidgets('list loading, error retry, server order and status labels', (
    tester,
  ) async {
    final first = Completer<List<Alarm>>();
    final client = StubAlarms();
    var calls = 0;
    client.list = () {
      calls++;
      return calls == 1
          ? first.future
          : Future.value([
              withStatus(AlarmStatus.followUp),
              withStatus(AlarmStatus.active),
              sample,
            ]);
    };
    int? selected;
    var create = 0;
    await tester.pumpWidget(
      listScreen(
        client,
        onSelect: (id) => selected = id,
        onCreate: () => create++,
      ),
    );
    expect(find.text('알람 목록 조회 중...'), findsOneWidget);
    first.completeError(Exception('private failure'));
    await tester.pumpAndSettle();
    expect(find.text('알람 목록을 불러오지 못했습니다.'), findsOneWidget);
    expect(find.textContaining('private failure'), findsNothing);
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(find.text('FOLLOW_UP · 한 정거장 후 추적 중'), findsOneWidget);
    expect(find.text('ACTIVE · 감시 중'), findsOneWidget);
    expect(find.text('INACTIVE · 비활성'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey(15)).first);
    expect(selected, 15);
    await tester.tap(find.text('새 알림 만들기'));
    expect(create, 1);
  });

  testWidgets('empty list is normal', (tester) async {
    final client = StubAlarms()..list = () async => [];
    await tester.pumpWidget(listScreen(client));
    await tester.pumpAndSettle();
    expect(find.text('아직 만든 알람이 없습니다.'), findsOneWidget);
    expect(find.text('새 알림 만들기'), findsOneWidget);
  });

  testWidgets(
    'create snapshots occurrence ID/options and blocks duplicate tap',
    (tester) async {
      final pending = Completer<Alarm>();
      final client = StubAlarms();
      client.createAlarm = (id, before, after) {
        expect(id, 12345);
        expect(before, isTrue);
        expect(after, isFalse);
        return pending.future;
      };
      Alarm? created;
      await tester.pumpWidget(
        stopScreen(
          client,
          (_) async => [stop],
          onCreated: (alarm) => created = alarm,
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey(12345)));
      await tester.pump();
      await tester.tap(find.text('한 정거장 전 알림'));
      await tester.pump();
      await tester.tap(find.text('알람 만들기'));
      await tester.pump();
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '알람 만들기'))
            .onPressed,
        isNull,
      );
      expect(
        tester
            .widget<SwitchListTile>(
              find.widgetWithText(SwitchListTile, '한 정거장 전 알림'),
            )
            .onChanged,
        isNull,
      );
      expect(client.createCalls, 1);
      pending.complete(sample);
      await tester.pumpAndSettle();
      expect(created?.status, AlarmStatus.inactive);
      expect(client.activateCalls, 0);
    },
  );

  testWidgets(
    'stale target clears selection and options, reloads same route, never retries POST',
    (tester) async {
      final client = StubAlarms();
      client.createAlarm = (_, _, _) async => throw const AlarmApiException(
        statusCode: 404,
        code: 'TARGET_STOP_OCCURRENCE_NOT_FOUND',
      );
      final ids = <int>[];
      await tester.pumpWidget(
        stopScreen(client, (id) async {
          ids.add(id);
          return [stop];
        }),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey(12345)));
      await tester.pump();
      await tester.tap(find.text('한 정거장 전 알림'));
      await tester.pump();
      await tester.tap(find.text('알람 만들기'));
      await tester.pumpAndSettle();
      expect(ids, [754, 754]);
      expect(client.createCalls, 1);
      expect(find.textContaining('정류장 정보가 변경됐습니다.'), findsOneWidget);
      expect(find.text('선택한 정류장:'), findsNothing);
      expect(find.text('알람 만들기'), findsNothing);
      await tester.tap(find.byKey(const ValueKey(12345)));
      await tester.pump();
      await tester.pump();
      expect(
        tester
            .widget<SwitchListTile>(
              find.widgetWithText(SwitchListTile, '한 정거장 전 알림'),
            )
            .value,
        isFalse,
      );
    },
  );

  testWidgets(
    'ambiguous create result gives list verification without create retry',
    (tester) async {
      final client = StubAlarms()
        ..createAlarm = (_, _, _) async => throw TimeoutException('timeout');
      var showList = 0;
      await tester.pumpWidget(
        stopScreen(client, (_) async => [stop], onShowAlarms: () => showList++),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey(12345)));
      await tester.pump();
      await tester.tap(find.text('알람 만들기'));
      await tester.pumpAndSettle();
      expect(find.textContaining('중복 생성을 피하려면 알람 목록에서'), findsOneWidget);
      expect(find.text('알람 만들기'), findsNothing);
      expect(find.text('다시 시도'), findsNothing);
      expect(client.createCalls, 1);
      await tester.tap(find.text('알람 목록 보기'));
      expect(showList, 1);
    },
  );

  testWidgets(
    'invalid current option requires user refresh without automatic POST',
    (tester) async {
      final client = StubAlarms()
        ..createAlarm = (_, _, _) async => throw const AlarmApiException(
          statusCode: 400,
          code: 'INVALID_ALARM_REQUEST',
        );
      final routes = <int>[];
      await tester.pumpWidget(
        stopScreen(client, (id) async {
          routes.add(id);
          return [stop];
        }),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey(12345)));
      await tester.pump();
      await tester.tap(find.text('알람 만들기'));
      await tester.pumpAndSettle();
      expect(find.textContaining('선택한 알림 조건을 생성할 수 없습니다.'), findsOneWidget);
      expect(client.createCalls, 1);
      await tester.tap(find.text('정류장 목록 새로고침'));
      await tester.pumpAndSettle();
      expect(routes, [754, 754]);
      expect(find.text('알람 만들기'), findsNothing);
    },
  );

  testWidgets('detail fetch retry and not found recovery', (tester) async {
    final client = StubAlarms();
    var calls = 0;
    client.detail = (_) {
      calls++;
      return calls == 1
          ? Future.error(Exception('private detail'))
          : Future.value(sample);
    };
    await tester.pumpWidget(detailScreen(client));
    await tester.pumpAndSettle();
    expect(find.text('알람을 불러오지 못했습니다.'), findsOneWidget);
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(find.text('상태: INACTIVE · 비활성'), findsOneWidget);
    expect(find.text('활성화'), findsOneWidget);
    var back = 0;
    client.detail = (_) async =>
        throw const AlarmApiException(statusCode: 404, code: 'ALARM_NOT_FOUND');
    await tester.pumpWidget(Container());
    await tester.pumpWidget(detailScreen(client, onBack: () => back++));
    await tester.pumpAndSettle();
    expect(find.text('알람을 찾을 수 없습니다.'), findsOneWidget);
    await tester.tap(find.text('알람 목록 보기'));
    expect(back, 1);
  });

  testWidgets(
    'inactive activate, active deactivate, follow-up actions and mutation failure',
    (tester) async {
      final client = StubAlarms();
      final activate = Completer<Alarm>();
      client.activateAlarm = (_) => activate.future;
      client.deactivateAlarm = (_) async => sample;
      await tester.pumpWidget(detailScreen(client, created: sample));
      await tester.tap(find.text('활성화'));
      await tester.pump();
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, '활성화'))
            .onPressed,
        isNull,
      );
      activate.complete(withStatus(AlarmStatus.active));
      await tester.pumpAndSettle();
      expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
      await tester.tap(find.text('비활성화'));
      await tester.pumpAndSettle();
      expect(find.text('상태: INACTIVE · 비활성'), findsOneWidget);

      await tester.pumpWidget(Container());
      client.activateAlarm = (_) async => throw Exception('private');
      await tester.pumpWidget(
        detailScreen(client, created: withStatus(AlarmStatus.followUp)),
      );
      expect(find.text('상태: FOLLOW_UP · 한 정거장 후 추적 중'), findsOneWidget);
      expect(find.textContaining('이전 후속 추적을 끝내고 새 감시'), findsOneWidget);
      expect(find.text('다시 활성화'), findsOneWidget);
      expect(find.text('비활성화'), findsOneWidget);
      await tester.tap(find.text('다시 활성화'));
      await tester.pumpAndSettle();
      expect(find.text('상태: FOLLOW_UP · 한 정거장 후 추적 중'), findsOneWidget);
      expect(find.text('알람 상태를 변경하지 못했습니다.'), findsOneWidget);
      await tester.tap(find.text('비활성화'));
      await tester.pumpAndSettle();
      expect(find.text('상태: INACTIVE · 비활성'), findsOneWidget);
    },
  );

  testWidgets(
    'delete asks confirmation and leaves only after completed delete',
    (tester) async {
      final client = StubAlarms();
      final pending = Completer<void>();
      client.deleteAlarm = (_) => pending.future;
      var back = 0;
      await tester.pumpWidget(
        detailScreen(client, created: sample, onBack: () => back++),
      );
      await tester.tap(find.text('삭제'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('취소'));
      await tester.pumpAndSettle();
      expect(client.deleteCalls, 0);
      await tester.tap(find.text('삭제'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(TextButton, '삭제').last);
      await tester.pump();
      expect(client.deleteCalls, 1);
      expect(back, 0);
      pending.complete();
      await tester.pumpAndSettle();
      expect(back, 1);
    },
  );

  testWidgets(
    'FOLLOW_UP restart succeeds as ACTIVE and missing mutation clears stale detail',
    (tester) async {
      final client = StubAlarms();
      client.activateAlarm = (_) async => withStatus(AlarmStatus.active);
      client.deactivateAlarm = (_) async => throw const AlarmApiException(
        statusCode: 404,
        code: 'ALARM_NOT_FOUND',
      );
      var back = 0;
      await tester.pumpWidget(
        detailScreen(
          client,
          created: withStatus(AlarmStatus.followUp),
          onBack: () => back++,
        ),
      );
      await tester.tap(find.text('다시 활성화'));
      await tester.pumpAndSettle();
      expect(client.activateCalls, 1);
      expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
      await tester.tap(find.text('비활성화'));
      await tester.pumpAndSettle();
      expect(find.text('알람을 찾을 수 없습니다.'), findsOneWidget);
      expect(find.text('상태: ACTIVE · 감시 중'), findsNothing);
      await tester.tap(find.text('알람 목록 보기'));
      expect(back, 1);
    },
  );

  testWidgets('failed delete keeps detail and does not report success', (
    tester,
  ) async {
    final client = StubAlarms()
      ..deleteAlarm = (_) async => throw Exception('private delete');
    var back = 0;
    await tester.pumpWidget(
      detailScreen(client, created: sample, onBack: () => back++),
    );
    await tester.tap(find.text('삭제'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, '삭제').last);
    await tester.pumpAndSettle();
    expect(client.deleteCalls, 1);
    expect(back, 0);
    expect(find.text('상태: INACTIVE · 비활성'), findsOneWidget);
    expect(find.text('알람을 삭제하지 못했습니다.'), findsOneWidget);
  });
}
