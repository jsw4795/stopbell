import 'alarm_creation_test_fake.dart';

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/alarm/alarm_detail_screen.dart';
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
  Future<Alarm> Function(String)? findCreationResult;
  String? creationRequestId;
  Future<Alarm> Function(int)? activateAlarm;
  Future<Alarm> Function(int)? deactivateAlarm;
  Future<Alarm> Function(int, bool, bool)? optionsAlarm;
  Future<void> Function(int)? deleteAlarm;
  int createCalls = 0;
  int activateCalls = 0;
  int deactivateCalls = 0;
  final detailIds = <int>[];
  int deleteCalls = 0;

  @override
  Future<List<Alarm>> findAll() => list!();
  @override
  Future<Alarm> findById(int id) {
    detailIds.add(id);
    return detail!(id);
  }

  @override
  Future<Alarm> create({
    String? clientRequestId,
    required int targetStopOccurrenceId,
    required bool notifyOneStopBefore,
    required bool notifyOneStopAfter,
  }) {
    creationRequestId = clientRequestId;
    createCalls++;
    return createAlarm!(
      targetStopOccurrenceId,
      notifyOneStopBefore,
      notifyOneStopAfter,
    );
  }

  @override
  Future<Alarm> findCreation(String id) => findCreationResult!(id);

  @override
  Future<Alarm> activate(int id) {
    activateCalls++;
    return activateAlarm!(id);
  }

  @override
  Future<Alarm> deactivate(int id) {
    deactivateCalls++;
    return deactivateAlarm!(id);
  }

  @override
  Future<Alarm> updateOptions(
    int id, {
    required bool notifyOneStopBefore,
    required bool notifyOneStopAfter,
  }) => optionsAlarm!(id, notifyOneStopBefore, notifyOneStopAfter);

  @override
  Future<void> delete(int id) {
    deleteCalls++;
    return deleteAlarm!(id);
  }
}

Widget detailScreen(
  StubAlarms client, {
  Alarm? created,
  int alarmId = 15,
  bool activationUnverified = false,
  bool Function()? isRequestCurrent,
  VoidCallback? onBack,
  Future<bool> Function(BuildContext)? beforeActivate,
}) => MaterialApp(
  home: Scaffold(
    body: AlarmDetailScreen(
      alarmId: alarmId,
      activationUnverified: activationUnverified,
      isRequestCurrent: isRequestCurrent,
      client: client,
      createdAlarm: created,
      beforeActivate: beforeActivate,
      onBack: onBack ?? () {},
    ),
  ),
);

Widget stopScreen(
  StubAlarms client,
  Future<List<BusRouteStopOccurrence>> Function(int) findStops, {
  void Function(Alarm, {bool activationUnverified})? onCreated,
  VoidCallback? onShowAlarms,
  Future<bool> Function(BuildContext)? beforeActivate,
}) => MaterialApp(
  home: Scaffold(
    body: BusRouteStopScreen(
      route: const BusRoute(id: 754, routeNumber: '7000', regionName: '수원시'),
      findStops: findStops,
      alarmClient: client,
      creationController: ScreenCreationFake(client),
      beforeActivate: beforeActivate,
      onBack: () {},
      onCreated: onCreated ?? (_, {bool activationUnverified = false}) {},
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
  testWidgets(
    'start creates then activates and blocks input throughout both requests',
    (tester) async {
      final pending = Completer<Alarm>();
      final activation = Completer<Alarm>();
      final active = withStatus(AlarmStatus.active);
      final calls = <String>[];
      final client = StubAlarms();
      client.activateAlarm = (id) {
        expect(id, sample.id);
        calls.add('activate');
        return activation.future;
      };
      client.createAlarm = (id, before, after) {
        calls.add('create');
        expect(id, 12345);
        expect(before, isTrue);
        expect(after, isFalse);
        return pending.future;
      };
      Alarm? created;
      await tester.pumpWidget(
        stopScreen(
          client,
          (_) async => [
            stop,
            const BusRouteStopOccurrence(
              id: 54321,
              name: '다른 정류장',
              order: 4,
              canNotifyOneStopBefore: true,
              canNotifyOneStopAfter: true,
            ),
          ],
          onCreated: (alarm, {bool activationUnverified = false}) =>
              created = alarm,
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey(12345)));
      await tester.pump();
      await tester.tap(find.text('한 정류장 전 알림'));
      await tester.pump();
      await tester.tap(find.text('알람 시작'));
      await tester.pump();
      void expectInputBlocked() {
        expect(
          tester
              .widget<FilledButton>(find.widgetWithText(FilledButton, '알람 시작'))
              .onPressed,
          isNull,
        );
        expect(
          tester
              .widgetList<SwitchListTile>(find.byType(SwitchListTile))
              .every((tile) => tile.onChanged == null),
          isTrue,
        );
        expect(
          tester
              .widget<InkWell>(
                find.descendant(
                  of: find.byKey(const ValueKey(54321)),
                  matching: find.byType(InkWell),
                ),
              )
              .onTap,
          isNull,
        );
        expect(
          tester.widget<IconButton>(find.byType(IconButton)).onPressed,
          isNull,
        );
      }

      expectInputBlocked();
      await tester.tap(find.text('알람 시작'));
      await tester.tap(find.byKey(const ValueKey(54321)));
      await tester.pump();
      expect(client.createCalls, 1);
      expect(client.activateCalls, 0);
      expect(created, isNull);
      pending.complete(sample);
      await tester.pump();
      expectInputBlocked();
      await tester.tap(find.text('알람 시작'));
      await tester.tap(find.byKey(const ValueKey(54321)));
      await tester.pump();
      expect(client.createCalls, 1);
      expect(client.activateCalls, 1);
      expect(calls, ['create', 'activate']);
      expect(created, isNull);
      activation.complete(active);
      await tester.pumpAndSettle();
      expect(created, same(active));
      expect(created?.status, AlarmStatus.active);
      expect(client.createCalls, 1);
      expect(client.activateCalls, 1);
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
      await tester.tap(find.text('한 정류장 전 알림'));
      await tester.pump();
      await tester.tap(find.text('알람 시작'));
      await tester.pumpAndSettle();
      expect(ids, [754, 754]);
      expect(client.createCalls, 1);
      expect(client.activateCalls, 0);
      expect(find.textContaining('정류장 정보가 변경됐어요.'), findsOneWidget);
      expect(find.text('선택한 정류장:'), findsNothing);
      expect(find.text('알람 시작'), findsNothing);
      await tester.tap(find.byKey(const ValueKey(12345)));
      await tester.pump();
      await tester.pump();
      expect(
        tester
            .widget<Switch>(
              find.descendant(
                of: find.widgetWithText(ListTile, '한 정류장 전 알림'),
                matching: find.byType(Switch),
              ),
            )
            .value,
        isFalse,
      );
    },
  );

  for (final failure in [
    TimeoutException('timeout'),
    const AlarmApiException(),
    Exception('network'),
    for (final status in [500, 502, 503, 504, 599, 408, 429, 409, 200])
      AlarmApiException(statusCode: status, code: 'UNKNOWN'),
    const AlarmApiException(statusCode: 400, code: 'UNKNOWN'),
    const AlarmApiException(statusCode: 404, code: 'NOT_FOUND'),
    const AlarmApiException(statusCode: 404, code: 'ALARM_NOT_FOUND'),
  ]) {
    testWidgets(
      'ambiguous create result ($failure) gives list verification without create retry',
      (tester) async {
        final client = StubAlarms()
          ..createAlarm = (_, _, _) async => throw failure;
        var createdCalls = 0;
        var showList = 0;
        var preparations = 0;
        await tester.pumpWidget(
          stopScreen(
            client,
            (_) async => [stop],
            onShowAlarms: () => showList++,
            onCreated: (_, {bool activationUnverified = false}) =>
                createdCalls++,
            beforeActivate: (_) async {
              preparations++;
              return true;
            },
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey(12345)));
        await tester.pump();
        await tester.tap(find.text('알람 시작'));
        await tester.pumpAndSettle();
        expect(find.textContaining('중복 생성을 피하려면 알람 목록에서'), findsOneWidget);
        expect(find.text('알람 시작'), findsNothing);
        expect(find.text('다시 시도'), findsNothing);
        expect(client.createCalls, 1);
        expect(client.activateCalls, 0);
        expect(createdCalls, 0);
        expect(preparations, 1);
        expect(
          tester
              .widgetList<SwitchListTile>(find.byType(SwitchListTile))
              .every((tile) => tile.onChanged == null),
          isTrue,
        );
        await tester.pump(const Duration(seconds: 30));
        expect(client.createCalls, 1);
        expect(
          tester
              .widget<InkWell>(
                find.descendant(
                  of: find.byKey(const ValueKey(12345)),
                  matching: find.byType(InkWell),
                ),
              )
              .onTap,
          isNull,
        );
        await tester.tap(find.text('알람 목록 보기'));
        expect(showList, 1);
      },
    );
  }

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
      await tester.tap(find.text('알람 시작'));
      await tester.pumpAndSettle();
      expect(find.textContaining('선택한 알람을 만들 수 없어요.'), findsOneWidget);
      expect(client.createCalls, 1);
      expect(client.activateCalls, 0);
      await tester.tap(find.text('정류장 목록 새로고침'));
      await tester.pumpAndSettle();
      expect(routes, [754, 754]);
      expect(find.text('알람 시작'), findsNothing);
    },
  );

  for (final failure in [
    const AlarmApiException(statusCode: 500, code: 'INTERNAL_ERROR'),
    TimeoutException('activation timeout'),
    Exception('network'),
  ]) {
    testWidgets(
      'activate failure ($failure) preserves created alarm and manual recovery',
      (tester) async {
        final client = StubAlarms();
        client.createAlarm = (_, _, _) async => sample;
        client.activateAlarm = (_) async => throw failure;
        Alarm? created;
        var unverified = false;
        client.detail = (id) async {
          expect(id, sample.id);
          return sample;
        };
        var createdCalls = 0;
        var preparations = 0;
        Future<bool> prepare(BuildContext _) async {
          preparations++;
          return true;
        }

        await tester.pumpWidget(
          stopScreen(
            client,
            (_) async => [stop],
            onCreated: (alarm, {bool activationUnverified = false}) {
              created = alarm;
              unverified = activationUnverified;
              createdCalls++;
            },
            beforeActivate: prepare,
          ),
        );
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const ValueKey(12345)));
        await tester.pump();
        await tester.tap(find.text('알람 시작'));
        await tester.pumpAndSettle();
        expect(client.createCalls, 1);
        expect(client.activateCalls, 1);
        expect(createdCalls, 1);
        expect(created, same(sample));
        expect(unverified, isTrue);
        expect(preparations, 1);
        expect(find.textContaining('알람 생성 요청이 서버에 반영됐을 수 있어요.'), findsNothing);
        await tester.pumpWidget(
          detailScreen(
            client,
            created: created,
            beforeActivate: prepare,
            activationUnverified: unverified,
          ),
        );
        await tester.pumpAndSettle();
        expect(client.detailIds, [sample.id]);
        expect(find.text('조회 시점의 서버 상태입니다.'), findsOneWidget);
        expect(find.text('비활성'), findsOneWidget);
        expect(find.byType(SwitchListTile), findsOneWidget);
        client.activateAlarm = (id) async {
          expect(id, sample.id);
          return withStatus(AlarmStatus.active);
        };
        await tester.tap(find.byType(SwitchListTile));
        await tester.pumpAndSettle();
        expect(find.text('감시 중'), findsOneWidget);
        expect(client.createCalls, 1);
        expect(client.activateCalls, 2);
        expect(preparations, 2);
      },
    );
  }

  testWidgets('detail shows human labels, switch row and 44pt delete', (
    tester,
  ) async {
    final client = StubAlarms();
    await tester.pumpWidget(detailScreen(client, created: sample));
    expect(find.textContaining('BUS'), findsNothing);
    expect(find.textContaining('ACTIVE'), findsNothing);
    expect(find.text('알람 사용'), findsOneWidget);
    expect(find.widgetWithText(OutlinedButton, '비활성화'), findsNothing);
    expect(
      tester.getSize(find.widgetWithText(TextButton, '삭제')).height,
      greaterThanOrEqualTo(44),
    );
  });

  testWidgets('detail toggles one-stop options only while active', (
    tester,
  ) async {
    final client = StubAlarms();
    final calls = <(bool, bool)>[];
    client.optionsAlarm = (id, before, after) async {
      calls.add((before, after));
      return Alarm(
        id: sample.id,
        transitType: sample.transitType,
        status: AlarmStatus.active,
        routeNumber: sample.routeNumber,
        stopName: sample.stopName,
        notifyOneStopBefore: before,
        notifyOneStopAfter: after,
      );
    };
    await tester.pumpWidget(
      detailScreen(client, created: withStatus(AlarmStatus.active)),
    );
    Switch row(String title) => tester.widget(
      find.descendant(
        of: find.widgetWithText(ListTile, title),
        matching: find.byType(Switch),
      ),
    );
    expect(row('한 정류장 후 알림').value, isFalse);
    await tester.tap(find.text('한 정류장 후 알림'));
    await tester.pumpAndSettle();
    expect(calls, [(true, true)]);
    expect(row('한 정류장 후 알림').value, isTrue);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpWidget(
      detailScreen(client, created: withStatus(AlarmStatus.inactive)),
    );
    expect(row('한 정류장 전 알림').onChanged, isNull);
  });

  testWidgets('detail hides one-stop options the server marks impossible', (
    tester,
  ) async {
    Alarm limited(bool? before, bool? after) => Alarm(
      id: sample.id,
      transitType: sample.transitType,
      status: AlarmStatus.active,
      routeNumber: sample.routeNumber,
      stopName: sample.stopName,
      notifyOneStopBefore: false,
      notifyOneStopAfter: false,
      canNotifyOneStopBefore: before,
      canNotifyOneStopAfter: after,
    );
    Future<void> show(Alarm alarm) async {
      await tester.pumpWidget(const SizedBox());
      await tester.pumpWidget(detailScreen(StubAlarms(), created: alarm));
    }

    await show(limited(false, true));
    expect(find.text('한 정류장 전 알림'), findsNothing);
    expect(find.text('한 정류장 후 알림'), findsOneWidget);
    await show(limited(true, false));
    expect(find.text('한 정류장 전 알림'), findsOneWidget);
    expect(find.text('한 정류장 후 알림'), findsNothing);
    await show(limited(false, false));
    expect(find.text('한 정류장 전 알림'), findsNothing);
    expect(find.text('한 정류장 후 알림'), findsNothing);
    await show(limited(null, null));
    expect(find.text('한 정류장 전 알림'), findsOneWidget);
    expect(find.text('한 정류장 후 알림'), findsOneWidget);
  });

  testWidgets('detail keeps the original option value when update fails', (
    tester,
  ) async {
    final client = StubAlarms();
    client.optionsAlarm = (_, _, _) async => throw const AlarmApiException(
      statusCode: 400,
      code: 'INVALID_ALARM_REQUEST',
    );
    await tester.pumpWidget(
      detailScreen(client, created: withStatus(AlarmStatus.active)),
    );
    await tester.tap(find.text('한 정류장 후 알림'));
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<Switch>(
            find.descendant(
              of: find.widgetWithText(ListTile, '한 정류장 후 알림'),
              matching: find.byType(Switch),
            ),
          )
          .value,
      isFalse,
    );
    expect(find.text('이 정류장에서는 사용할 수 없는 알림이에요.'), findsOneWidget);
  });

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
    expect(find.text('알람을 불러오지 못했어요.'), findsOneWidget);
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(find.text('비활성'), findsOneWidget);
    expect(find.byType(SwitchListTile), findsOneWidget);
    var back = 0;
    client.detail = (_) async =>
        throw const AlarmApiException(statusCode: 404, code: 'ALARM_NOT_FOUND');
    await tester.pumpWidget(Container());
    await tester.pumpWidget(detailScreen(client, onBack: () => back++));
    await tester.pumpAndSettle();
    expect(find.text('알람을 찾을 수 없어요.'), findsOneWidget);
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
      await tester.tap(find.byType(SwitchListTile));
      await tester.pump();
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).onChanged,
        isNull,
      );
      activate.complete(withStatus(AlarmStatus.active));
      await tester.pumpAndSettle();
      expect(find.text('감시 중'), findsOneWidget);
      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();
      expect(find.text('비활성'), findsOneWidget);

      await tester.pumpWidget(Container());
      client.activateAlarm = (_) async => throw Exception('private');
      client.detail = (_) async => withStatus(AlarmStatus.followUp);
      await tester.pumpWidget(
        detailScreen(client, created: withStatus(AlarmStatus.followUp)),
      );
      expect(find.text('한 정류장 후 추적 중'), findsOneWidget);
      expect(find.textContaining('이전 후속 추적을 끝내고 새 감시'), findsOneWidget);
      expect(find.text('다시 활성화'), findsOneWidget);
      expect(find.byType(SwitchListTile), findsOneWidget);
      await tester.tap(find.text('다시 활성화'));
      await tester.pumpAndSettle();
      expect(find.text('한 정류장 후 추적 중'), findsOneWidget);
      expect(find.text('조회 시점의 서버 상태입니다.'), findsOneWidget);
      expect(client.detailIds, [sample.id]);
      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();
      expect(find.text('비활성'), findsOneWidget);
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
      expect(find.text('감시 중'), findsOneWidget);
      await tester.tap(find.byType(SwitchListTile));
      await tester.pumpAndSettle();
      expect(find.text('알람을 찾을 수 없어요.'), findsOneWidget);
      expect(find.text('감시 중'), findsNothing);
      await tester.tap(find.text('알람 목록 보기'));
      expect(back, 1);
    },
  );

  testWidgets('failed delete keeps detail and does not report success', (
    tester,
  ) async {
    final client = StubAlarms();
    client.deleteAlarm = (_) async => throw Exception('private delete');
    client.detail = (_) async => sample;
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
    expect(find.text('비활성'), findsOneWidget);
    expect(find.text('조회 시점의 서버 상태입니다.'), findsOneWidget);
    expect(client.detailIds, [sample.id]);
  });
  for (final action in ['activate', 'deactivate', 'follow-up']) {
    for (final failure in [
      const AlarmApiException(statusCode: 500),
      TimeoutException('lost response'),
      const AlarmApiException(statusCode: 200),
    ]) {
      testWidgets(
        '$action ${failure is AlarmApiException ? failure.statusCode : failure} reads original ID and shows actual state',
        (tester) async {
          final pending = Completer<Alarm>();
          final client = StubAlarms();
          client.activateAlarm = (_) async => throw failure;
          client.deactivateAlarm = (_) async => throw failure;
          client.detail = (id) {
            expectSync(id, sample.id);
            return pending.future;
          };
          final initial = action == 'activate'
              ? sample
              : withStatus(
                  action == 'follow-up'
                      ? AlarmStatus.followUp
                      : AlarmStatus.active,
                );
          await tester.pumpWidget(detailScreen(client, created: initial));
          final label = action == 'activate'
              ? '활성화'
              : action == 'follow-up'
              ? '다시 활성화'
              : '비활성화';
          final toggle = action == 'follow-up'
              ? null
              : tester
                    .widget<SwitchListTile>(find.byType(SwitchListTile))
                    .onChanged!;
          final press = action == 'follow-up'
              ? tester
                    .widget<FilledButton>(
                      find.widgetWithText(FilledButton, label),
                    )
                    .onPressed!
              : () => toggle!(action == 'activate');
          press();
          press();
          await tester.pump();
          expect(client.detailIds, [sample.id]);
          expect(find.text('확인 필요'), findsOneWidget);
          expect(find.text('상태: ${initial.status.label}'), findsNothing);
          expect(
            tester
                .widget<TextButton>(find.widgetWithText(TextButton, '삭제'))
                .onPressed,
            isNull,
          );
          press();
          expect(client.activateCalls + client.deactivateCalls, 1);
          // The read may show a state other than the intended mutation.
          pending.complete(withStatus(AlarmStatus.followUp));
          await tester.pumpAndSettle();

          expect(find.text('한 정류장 후 추적 중'), findsOneWidget);
          expect(find.text('조회 시점의 서버 상태입니다.'), findsOneWidget);
          expect(client.activateCalls + client.deactivateCalls, 1);
          expect(client.deleteCalls, 0);
          expect(client.createCalls, 0);
          expect(
            tester
                .widget<FilledButton>(
                  find.widgetWithText(FilledButton, '다시 활성화'),
                )
                .onPressed,
            isNotNull,
          );
        },
      );
    }
  }

  for (final action in ['activate', 'deactivate', 'follow-up', 'delete']) {
    testWidgets(
      '$action timeout and failed GET lock mutations until explicit verification',
      (tester) async {
        final failure = TimeoutException('lost response');
        final client = StubAlarms();
        client.activateAlarm = (_) async => throw failure;
        client.deactivateAlarm = (_) async => throw failure;
        client.deleteAlarm = (_) async => throw failure;
        client.detail = (_) async => throw Exception('read failed');
        final initial = action == 'deactivate'
            ? withStatus(AlarmStatus.active)
            : action == 'follow-up'
            ? withStatus(AlarmStatus.followUp)
            : sample;
        await tester.pumpWidget(detailScreen(client, created: initial));
        if (action == 'delete') {
          await tester.tap(find.text('삭제'));
          await tester.pumpAndSettle();
          await tester.tap(find.widgetWithText(TextButton, '삭제').last);
        } else {
          await tester.tap(
            action == 'follow-up'
                ? find.text('다시 활성화')
                : find.byType(SwitchListTile),
          );
        }
        await tester.pumpAndSettle();
        expect(find.text('확인 필요'), findsOneWidget);
        expect(find.text('상태: ${initial.status.label}'), findsNothing);
        expect(client.detailIds, [sample.id]);
        expect(
          tester
              .widget<TextButton>(find.widgetWithText(TextButton, '삭제'))
              .onPressed,
          isNull,
        );
        for (final button in tester.widgetList<FilledButton>(
          find.byType(FilledButton),
        )) {
          expect(button.onPressed, isNull);
        }
        for (final button in tester.widgetList<OutlinedButton>(
          find.byType(OutlinedButton),
        )) {
          if ((button.child as Text).data != '현재 상태 다시 조회') {
            expect(button.onPressed, isNull);
          }
        }
        await tester.pump(const Duration(seconds: 30));
        expect(client.detailIds, [sample.id]);
        expect(
          client.activateCalls + client.deactivateCalls + client.deleteCalls,
          1,
        );
        client.detail = (_) async => withStatus(AlarmStatus.active);
        await tester.tap(find.text('현재 상태 다시 조회'));
        await tester.pumpAndSettle();
        expect(client.detailIds, [sample.id, sample.id]);
        expect(find.text('감시 중'), findsOneWidget);
        expect(
          tester.widget<SwitchListTile>(find.byType(SwitchListTile)).onChanged,
          isNotNull,
        );
        expect(
          client.activateCalls + client.deactivateCalls + client.deleteCalls,
          1,
        );
      },
    );
  }

  for (final found in [true, false]) {
    testWidgets(
      'uncertain DELETE then GET ${found ? 200 : 404} never claims deletion',
      (tester) async {
        final client = StubAlarms();
        client.deleteAlarm = (_) async =>
            throw const AlarmApiException(statusCode: 500);
        client.detail = (_) async {
          if (!found) {
            throw const AlarmApiException(
              statusCode: 404,
              code: 'ALARM_NOT_FOUND',
            );
          }
          return withStatus(AlarmStatus.active);
        };
        var back = 0;
        await tester.pumpWidget(
          detailScreen(client, created: sample, onBack: () => back++),
        );
        await tester.tap(find.text('삭제'));
        await tester.pumpAndSettle();
        await tester.tap(find.widgetWithText(TextButton, '삭제').last);
        await tester.pumpAndSettle();
        expect(back, 0);
        expect(client.detailIds, [sample.id]);
        expect(client.deleteCalls, 1);
        expect(find.textContaining('삭제 완료'), findsNothing);
        if (found) {
          expect(find.text('감시 중'), findsOneWidget);
        } else {
          expect(find.text('삭제되었거나 현재 계정에서 접근할 수 없는 알람이에요.'), findsOneWidget);
          expect(find.text('현재 상태 다시 조회'), findsOneWidget);
          await tester.tap(find.text('알람 목록 보기'));
          expect(back, 1);
        }
      },
    );
  }

  testWidgets(
    'created ID with lost activation response never displays snapshot as current',
    (tester) async {
      final client = StubAlarms()
        ..detail = (_) async => throw TimeoutException('GET');
      await tester.pumpWidget(
        detailScreen(client, created: sample, activationUnverified: true),
      );
      await tester.pumpAndSettle();
      expect(client.detailIds, [sample.id]);
      expect(find.text('비활성'), findsNothing);
      expect(find.text('확인 필요'), findsOneWidget);
      expect(
        tester.widget<SwitchListTile>(find.byType(SwitchListTile)).onChanged,
        isNull,
      );
      client.detail = (_) async => withStatus(AlarmStatus.active);
      await tester.tap(find.text('현재 상태 다시 조회'));
      await tester.pumpAndSettle();
      expect(find.text('감시 중'), findsOneWidget);
      expect(client.createCalls, 0);
      expect(client.activateCalls, 0);
    },
  );

  testWidgets('wrong-ID recovery response remains unverified', (tester) async {
    final client = StubAlarms();
    client.activateAlarm = (_) async => throw TimeoutException('activate');
    client.detail = (_) async => const Alarm(
      id: 99,
      transitType: AlarmTransitType.bus,
      status: AlarmStatus.active,
      routeNumber: 'other route',
      stopName: 'other stop',
      notifyOneStopBefore: false,
      notifyOneStopAfter: false,
    );
    await tester.pumpWidget(detailScreen(client, created: sample));
    await tester.tap(find.byType(SwitchListTile));
    await tester.pumpAndSettle();
    expect(find.textContaining('other route'), findsNothing);
    expect(find.text('확인 필요'), findsOneWidget);
    expect(client.detailIds, [sample.id]);
  });

  for (final phase in [
    'load',
    'mutation-success',
    'mutation-failure',
    'recovery',
  ]) {
    testWidgets(
      'replacing Alarm ID invalidates late $phase even after a newer mutation',
      (tester) async {
        final pending = Completer<Alarm>();
        final client = StubAlarms();
        client.detail = (_) => pending.future;
        client.activateAlarm = (_) => pending.future;
        if (phase == 'recovery') {
          client.activateAlarm = (_) async => throw TimeoutException('lost');
        }
        await tester.pumpWidget(
          detailScreen(client, created: phase == 'load' ? null : sample),
        );
        if (phase != 'load') await tester.tap(find.byType(SwitchListTile));
        await tester.pump();
        const other = Alarm(
          id: 99,
          transitType: AlarmTransitType.bus,
          status: AlarmStatus.inactive,
          routeNumber: 'new route',
          stopName: 'new stop',
          notifyOneStopBefore: false,
          notifyOneStopAfter: false,
        );
        client.activateAlarm = (id) async {
          expect(id, 99);
          return const Alarm(
            id: 99,
            transitType: AlarmTransitType.bus,
            status: AlarmStatus.active,
            routeNumber: 'new route',
            stopName: 'new stop',
            notifyOneStopBefore: false,
            notifyOneStopAfter: false,
          );
        };
        await tester.pumpWidget(
          detailScreen(client, alarmId: 99, created: other),
        );
        await tester.tap(find.byType(SwitchListTile));
        await tester.pumpAndSettle();
        if (phase == 'mutation-failure') {
          pending.completeError(TimeoutException('late failure'));
        } else {
          pending.complete(withStatus(AlarmStatus.followUp));
        }
        await tester.pumpAndSettle();
        expect(find.text('new route'), findsOneWidget);
        expect(find.text('감시 중'), findsOneWidget);
        expect(find.textContaining('7000'), findsNothing);
        expect(
          client.detailIds,
          phase == 'mutation-success' || phase == 'mutation-failure'
              ? []
              : [sample.id],
        );
        expect(tester.takeException(), isNull);
      },
    );
  }

  for (final phase in ['load', 'mutation', 'recovery', 'delete']) {
    testWidgets('disposed detail ignores late $phase without navigation', (
      tester,
    ) async {
      final pending = Completer<Alarm>();
      final deleted = Completer<void>();
      final client = StubAlarms();
      client.detail = (_) => pending.future;
      client.activateAlarm = (_) => phase == 'recovery'
          ? Future.error(TimeoutException('lost'))
          : pending.future;
      client.deleteAlarm = (_) => deleted.future;
      var back = 0;
      await tester.pumpWidget(
        detailScreen(
          client,
          created: phase == 'load' ? null : sample,
          onBack: () => back++,
        ),
      );
      if (phase == 'delete') {
        await tester.tap(find.text('삭제'));
        await tester.pumpAndSettle();
        await tester.tap(find.widgetWithText(TextButton, '삭제').last);
      } else if (phase != 'load') {
        await tester.tap(find.byType(SwitchListTile));
      }
      await tester.pump();
      await tester.pumpWidget(const SizedBox());
      pending.complete(withStatus(AlarmStatus.active));
      deleted.complete();
      await tester.pumpAndSettle();
      expect(back, 0);
      expect(tester.takeException(), isNull);
      expect(
        client.detailIds,
        phase == 'load' || phase == 'recovery' ? [sample.id] : [],
      );
    });
  }
}
