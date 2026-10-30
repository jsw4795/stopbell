import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_home.dart';
import 'package:stopbell/features/alarm/alarm_detail_screen.dart';
import 'package:stopbell/features/notification/notification_lifecycle.dart';
import 'package:stopbell/features/notification/push_activation_prompt.dart';
import 'package:stopbell/features/notification/push_permission_service.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_screen.dart';

import 'alarm_screen_test.dart' show StubAlarms, sample, stop, withStatus;
import 'notification_test_fakes.dart';

Future<void> selectStop(WidgetTester tester) async {
  await tester.tap(find.text('새 알림 만들기'));
  await tester.pumpAndSettle();
  await tester.enterText(find.byType(TextField), '7000');
  await tester.tap(find.text('검색'));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey(754)));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(ValueKey(stop.id)));
  await tester.pump();
}

void main() {
  late PushFixture f;
  late StubAlarms client;
  late List<String> calls;
  setUp(() {
    f = PushFixture();
    calls = [];
    client = StubAlarms()
      ..list = (() async => [])
      ..detail = ((_) async => sample)
      ..createAlarm = (id, before, after) async {
        expect(id, stop.id);
        calls.add('create');
        return sample;
      }
      ..activateAlarm = (id) async {
        expect(id, sample.id);
        calls.add('activate');
        return withStatus(AlarmStatus.active);
      };
  });
  tearDown(() => f.auth.dispose());

  Future<void> pumpHome(
    WidgetTester tester, {
    Future<bool> Function(BuildContext)? prepare,
    NotificationLifecycle? notifications,
  }) async {
    await f.initialize();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AlarmHome(
            client: client,
            authSession: f.auth,
            searchRoutes: (_) async => [
              const BusRoute(id: 754, routeNumber: '7000', regionName: '수원시'),
            ],
            findStops: (_) async => [stop],
            beforeActivate: prepare,
            notifications: notifications,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await selectStop(tester);
  }

  Future<void> start(WidgetTester tester) async {
    await tester.tap(find.text('알람 시작'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 350));
  }

  void expectNoMutation() {
    expect(client.createCalls, 0);
    expect(client.activateCalls, 0);
  }

  void expectRetryable(WidgetTester tester) {
    expectNoMutation();
    expect(find.byType(BusRouteStopScreen), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.textContaining('알람 생성 요청이 서버에 반영됐을 수 있습니다'), findsNothing);
    expect(find.text('알람 목록 보기'), findsNothing);
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '알람 시작'))
          .onPressed,
      isNotNull,
    );
  }

  testWidgets('first start prepares push once before create and activate', (
    tester,
  ) async {
    await pumpHome(
      tester,
      prepare: (_) async {
        calls.add('push');
        return true;
      },
    );
    await tester.tap(find.text('알람 시작'));
    await tester.pumpAndSettle();
    expect(client.createCalls, 1);
    expect(client.activateCalls, 1);
    expect(calls, ['push', 'create', 'activate']);
    expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('null hook preserves creation in environments without push', (
    tester,
  ) async {
    await pumpHome(tester);
    await start(tester);
    await tester.pumpAndSettle();
    expect(calls, ['create', 'activate']);
    expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
  });

  testWidgets('partial success verifies original ID before manual activation', (
    tester,
  ) async {
    var preparations = 0;
    client.activateAlarm = (_) async => throw TimeoutException('activation');
    await pumpHome(
      tester,
      prepare: (_) async {
        preparations++;
        return true;
      },
    );
    await start(tester);
    await tester.pumpAndSettle();
    expect(preparations, 1);
    expect(client.createCalls, 1);
    expect(client.activateCalls, 1);
    expect(client.detailIds, [sample.id]);
    expect(find.text('조회 시점의 서버 상태입니다.'), findsOneWidget);
    expect(find.text('상태: INACTIVE · 비활성'), findsOneWidget);
    expect(
      tester.widget<AlarmDetailScreen>(find.byType(AlarmDetailScreen)).alarmId,
      sample.id,
    );
    client.activateAlarm = (id) async {
      expect(id, sample.id);
      return withStatus(AlarmStatus.active);
    };
    await tester.tap(find.text('활성화'));
    await tester.pumpAndSettle();
    expect(preparations, 2);
    expect(client.createCalls, 1);
    expect(client.activateCalls, 2);
    expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
  });

  for (final result in ['cancel', 'exception']) {
    testWidgets(
      '$result before POST releases loading and permits a fresh attempt',
      (tester) async {
        var preparations = 0;
        await pumpHome(
          tester,
          prepare: (_) async {
            preparations++;
            if (preparations == 1) {
              if (result == 'exception') throw TimeoutException('push failure');
              return false;
            }
            return true;
          },
        );
        await start(tester);
        await tester.pumpAndSettle();
        expectRetryable(tester);
        expect(preparations, 1);
        if (result == 'exception') {
          expect(find.textContaining('알림 수신 준비를 확인하지 못했습니다'), findsOneWidget);
        }
        await start(tester);
        await tester.pumpAndSettle();
        expect(preparations, 2);
        expect(client.createCalls, 1);
        expect(client.activateCalls, 1);
      },
    );
  }

  testWidgets(
    'delayed push preparation blocks duplicate input and all mutations',
    (tester) async {
      final pending = Completer<bool>();
      var preparations = 0;
      await pumpHome(
        tester,
        prepare: (_) {
          preparations++;
          return pending.future;
        },
      );
      // Invoke the captured callback again before the next build too.
      final press = tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '알람 시작'))
          .onPressed!;
      await tester.tap(find.text('알람 시작'));
      press();
      await tester.pump();
      await tester.tap(find.text('알람 시작'));
      expect(preparations, 1);
      expectNoMutation();
      expect(
        tester.widget<ListTile>(find.byKey(ValueKey(stop.id))).onTap,
        isNull,
      );
      expect(
        tester.widget<IconButton>(find.byType(IconButton)).onPressed,
        isNull,
      );
      expect(
        tester
            .widgetList<SwitchListTile>(find.byType(SwitchListTile))
            .every((tile) => tile.onChanged == null),
        isTrue,
      );
      pending.complete(true);
      await tester.pumpAndSettle();
      expect(preparations, 1);
      expect(client.createCalls, 1);
      expect(client.activateCalls, 1);
    },
  );

  for (final permission in [
    PushPermission.authorized,
    PushPermission.provisional,
    PushPermission.notDetermined,
  ]) {
    testWidgets('$permission prepares registration before first POST', (
      tester,
    ) async {
      f.provider.status = permission;
      client.createAlarm = (_, _, _) async {
        expect(f.devices.enabled, isTrue);
        expect(f.devices.requests, hasLength(1));
        return sample;
      };
      await pumpHome(
        tester,
        prepare: (context) => prepareAlarmPush(context, f.service),
      );
      await start(tester);
      if (permission == PushPermission.notDetermined) {
        expectNoMutation();
        expect(find.text('버스 도착 알림 받기'), findsOneWidget);
        expect(find.textContaining('하차'), findsNothing);
        expect(f.provider.requests, 0);
        await tester.tap(find.text('계속'));
      }
      await tester.pumpAndSettle();
      expect(
        f.provider.requests,
        permission == PushPermission.notDetermined ? 1 : 0,
      );
      expect(f.provider.registrations, 1);
      expect(client.createCalls, 1);
      expect(client.activateCalls, 1);
      expect(find.text('이 기기의 알림 수신 준비를 완료했습니다.'), findsOneWidget);
      expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
    });
  }

  testWidgets('notDetermined later cancels before registration and can retry', (
    tester,
  ) async {
    f.provider.status = PushPermission.notDetermined;
    await pumpHome(
      tester,
      prepare: (context) => prepareAlarmPush(context, f.service),
    );
    await start(tester);
    await tester.tap(find.text('나중에'));
    await tester.pumpAndSettle();
    expectRetryable(tester);
    expect(f.provider.requests, 0);
    expect(f.provider.registrations, 0);
    expect(f.devices.requests, isEmpty);
    expect(find.byType(AlertDialog), findsNothing);
    await start(tester);
    expect(find.text('버스 도착 알림 받기'), findsOneWidget);
    await tester.tap(find.text('계속'));
    await tester.pumpAndSettle();
    expect(f.provider.requests, 1);
    expect(client.createCalls, 1);
    expect(client.activateCalls, 1);
  });

  for (final failure in ['denied', 'provider', 'backend']) {
    for (final proceed in [false, true]) {
      testWidgets('$failure requires explicit choice: proceed=$proceed', (
        tester,
      ) async {
        if (failure == 'denied') f.provider.status = PushPermission.denied;
        if (failure == 'provider') f.provider.fail = true;
        if (failure == 'backend') {
          f.devices.mutation = (_, _) async => throw StateError('offline');
        }
        await pumpHome(
          tester,
          prepare: (context) => prepareAlarmPush(context, f.service),
        );
        await start(tester);
        expectNoMutation();
        expect(find.text('이 기기의 알림 수신 준비가 필요합니다'), findsOneWidget);
        expect(find.text('이 기기의 알림 수신 준비를 완료했습니다.'), findsNothing);
        expect(find.textContaining('하차'), findsNothing);
        if (failure == 'denied') {
          expect(find.textContaining('버스 도착 알림'), findsOneWidget);
          await tester.tap(find.text('설정 열기'));
          await tester.pump();
          expect(f.provider.settings, 1);
          expect(f.provider.requests, 0);
          expect(f.devices.requests, isEmpty);
          expectNoMutation();
        }
        if (failure == 'backend') {
          expect(find.textContaining('서버에 기기 등록을 완료하지 못했습니다'), findsOneWidget);
        }
        await tester.tap(find.text(proceed ? '알람 활성화' : '취소'));
        await tester.pumpAndSettle();
        if (proceed) {
          expect(client.createCalls, 1);
          expect(client.activateCalls, 1);
          expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
        } else {
          expectRetryable(tester);
        }
      });
    }
  }

  for (final action in [
    'logout',
    'other-user',
    'same-user-login',
    'navigation',
    'dispose',
  ]) {
    testWidgets('$action while preparing invalidates even a late true hook', (
      tester,
    ) async {
      final pending = Completer<bool>();
      final lifecycle = NotificationLifecycle(f.auth);
      addTearDown(lifecycle.dispose);
      final detail = Completer<Alarm>();
      client.detail = (_) => detail.future;
      await pumpHome(
        tester,
        prepare: (_) => pending.future,
        notifications: lifecycle,
      );
      await start(tester);
      if (action == 'logout') {
        await f.auth.logout();
      } else if (action == 'other-user' || action == 'same-user-login') {
        await f.switchUser(action == 'other-user' ? 'B' : 'A');
      } else if (action == 'navigation') {
        lifecycle.receive({
          'type': 'ALARM_EVENT',
          'alarmId': '15',
          'eventType': 'ARRIVED',
          'notificationEventId': '456',
        });
        // The old widget is still mounted while the notification lookup waits.
        expect(find.byType(BusRouteStopScreen), findsOneWidget);
      } else {
        await tester.pumpWidget(const SizedBox());
      }
      // Do not pump after auth/navigation invalidation: guard must work before disposal.
      pending.complete(true);
      await tester.pumpAndSettle();
      expectNoMutation();
      if (action == 'navigation') {
        detail.complete(sample);
        await tester.pumpAndSettle();
        expect(find.text('상태: INACTIVE · 비활성'), findsOneWidget);
      }
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    });
  }

  testWidgets(
    'session changes while denied dialog is open cannot turn stale into consent',
    (tester) async {
      f.provider.status = PushPermission.denied;
      await pumpHome(
        tester,
        prepare: (context) => prepareAlarmPush(context, f.service),
      );
      await start(tester);
      await f.switchUser('B');
      await tester.tap(find.text('알람 활성화'));
      await tester.pumpAndSettle();
      expectNoMutation();
      expect(find.text('알람 상세'), findsNothing);
    },
  );

  testWidgets('session changes during device preparation prevent POST', (
    tester,
  ) async {
    final pending = Completer<String>();
    f.provider.registration = () => pending.future;
    await pumpHome(
      tester,
      prepare: (context) => prepareAlarmPush(context, f.service),
    );
    await start(tester);
    expect(f.provider.registrations, 1);
    await f.switchUser('B');
    pending.complete('FID-A');
    await tester.pumpAndSettle();
    expectNoMutation();
    expect(f.devices.requests, isEmpty);
  });

  for (final stage in ['create', 'activate']) {
    testWidgets(
      'navigation during $stage ignores old result and blocks further activation',
      (tester) async {
        final pending = Completer<Alarm>();
        if (stage == 'create') client.createAlarm = (_, _, _) => pending.future;
        if (stage == 'activate') client.activateAlarm = (_) => pending.future;
        final lifecycle = NotificationLifecycle(f.auth);
        addTearDown(lifecycle.dispose);
        final detail = Completer<Alarm>();
        client.detail = (_) => detail.future;
        await pumpHome(
          tester,
          prepare: (_) async => true,
          notifications: lifecycle,
        );
        await start(tester);
        expect(client.createCalls, 1);
        lifecycle.receive({
          'type': 'ALARM_EVENT',
          'alarmId': '15',
          'eventType': 'ARRIVED',
          'notificationEventId': '456',
        });
        pending.complete(
          stage == 'create' ? sample : withStatus(AlarmStatus.active),
        );
        await tester.pump();
        expect(client.activateCalls, stage == 'create' ? 0 : 1);
        expect(find.text('알람 상세'), findsNothing);
        detail.complete(sample);
        await tester.pumpAndSettle();
        expect(find.text('상태: INACTIVE · 비활성'), findsOneWidget);
        await tester.pumpWidget(const SizedBox());
      },
    );
  }

  for (final action in ['logout', 'other-user']) {
    testWidgets(
      '$action during create prevents activation of the late result',
      (tester) async {
        final pending = Completer<Alarm>();
        client.createAlarm = (_, _, _) => pending.future;
        await pumpHome(tester, prepare: (_) async => true);
        await start(tester);
        expect(client.createCalls, 1);
        if (action == 'logout') {
          await f.auth.logout();
        } else {
          await f.switchUser('B');
        }
        pending.complete(sample);
        await tester.pumpAndSettle();
        expect(client.createCalls, 1);
        expect(client.activateCalls, 0);
        expect(find.text('알람 상세'), findsNothing);
      },
    );
  }

  testWidgets(
    'route replacement invalidates pending preparation and preserves new selection',
    (tester) async {
      final pending = Completer<bool>();
      var preparations = 0;
      Widget screen(int routeId) => MaterialApp(
        home: Scaffold(
          body: BusRouteStopScreen(
            route: BusRoute(
              id: routeId,
              routeNumber: '7000',
              regionName: '수원시',
            ),
            findStops: (_) async => [stop],
            alarmClient: client,
            beforeActivate: (_) {
              preparations++;
              return preparations == 1 ? pending.future : Future.value(true);
            },
            onBack: () {},
            onCreated: (_, {bool activationUnverified = false}) {},
            onShowAlarms: () {},
          ),
        ),
      );
      await tester.pumpWidget(screen(754));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(ValueKey(stop.id)));
      await tester.pump();
      await start(tester);
      await tester.pumpWidget(screen(1390));
      await tester.pumpAndSettle();
      expect(find.text('알람 시작'), findsNothing);
      await tester.tap(find.byKey(ValueKey(stop.id)));
      await tester.pump();
      pending.complete(true);
      await tester.pumpAndSettle();
      expectRetryable(tester);
      await start(tester);
      await tester.pumpAndSettle();
      expect(preparations, 2);
      expect(client.createCalls, 1);
      expect(client.activateCalls, 1);
    },
  );

  for (final status in [AlarmStatus.inactive, AlarmStatus.followUp]) {
    for (final proceed in [false, true]) {
      testWidgets(
        'existing $status activation retains denied choice: proceed=$proceed',
        (tester) async {
          await f.initialize();
          f.provider.status = PushPermission.denied;
          var preparations = 0;
          await tester.pumpWidget(
            MaterialApp(
              home: Scaffold(
                body: AlarmDetailScreen(
                  alarmId: sample.id,
                  client: client,
                  createdAlarm: withStatus(status),
                  onBack: () {},
                  beforeActivate: (context) {
                    preparations++;
                    return prepareAlarmPush(context, f.service);
                  },
                ),
              ),
            ),
          );
          await tester.tap(
            find.text(status == AlarmStatus.inactive ? '활성화' : '다시 활성화'),
          );
          await tester.pump();
          await tester.pump(const Duration(milliseconds: 350));
          expect(client.activateCalls, 0);
          await tester.tap(find.text(proceed ? '알람 활성화' : '취소'));
          await tester.pumpAndSettle();
          expect(preparations, 1);
          expect(client.createCalls, 0);
          expect(client.activateCalls, proceed ? 1 : 0);
          expect(
            find.text(
              '상태: ${proceed ? AlarmStatus.active.label : status.label}',
            ),
            findsOneWidget,
          );
        },
      );
    }
  }
  for (final action in [
    'logout',
    'other-user',
    'same-user-login',
    'navigation',
  ]) {
    for (final phase in [
      'mutation-success',
      'mutation-failure',
      'recovery',
      'delete',
      'preparation',
    ]) {
      testWidgets(
        '$action during detail $phase discards late results before rebuild',
        (tester) async {
          final lifecycle = NotificationLifecycle(f.auth);
          addTearDown(lifecycle.dispose);
          final pending = Completer<Alarm>();
          final deletion = Completer<void>();
          final preparation = Completer<bool>();
          var prepareCalls = 0;
          if (phase == 'preparation') {
            client.activateAlarm = (_) async => sample;
          }
          await pumpHome(
            tester,
            notifications: lifecycle,
            prepare: (_) async {
              prepareCalls++;
              return prepareCalls == 1 ? true : preparation.future;
            },
          );
          await start(tester);
          await tester.pumpAndSettle();
          final lookupsBefore = client.detailIds.length;
          client.detail = (_) => pending.future;
          client.deactivateAlarm = (_) => phase == 'recovery'
              ? Future.error(TimeoutException('mutation lost'))
              : pending.future;
          if (phase == 'delete') {
            client.deleteAlarm = (_) => deletion.future;
            await tester.tap(find.text('삭제'));
            await tester.pumpAndSettle();
            await tester.tap(find.widgetWithText(TextButton, '삭제').last);
          } else {
            await tester.tap(
              find.text(phase == 'preparation' ? '활성화' : '비활성화'),
            );
          }
          await tester.pump();
          final readsBeforeInvalidation = client.detailIds.length;
          if (action == 'logout') {
            await f.auth.logout();
          } else if (action == 'other-user' || action == 'same-user-login') {
            await f.switchUser(action == 'other-user' ? 'B' : 'A');
          } else {
            // Invalidate the navigation epoch while the old screen is still mounted.
            client.detail = (id) async => const Alarm(
              id: 99,
              transitType: AlarmTransitType.bus,
              status: AlarmStatus.followUp,
              routeNumber: 'other route',
              stopName: 'other stop',
              notifyOneStopBefore: false,
              notifyOneStopAfter: true,
            );
            lifecycle.receive({
              'type': 'ALARM_EVENT',
              'alarmId': '99',
              'eventType': 'ARRIVED',
              'notificationEventId': '999',
            });
          }
          if (phase == 'mutation-failure') {
            pending.completeError(TimeoutException('late failure'));
          } else {
            pending.complete(sample);
          }
          deletion.complete();
          preparation.complete(true);
          await tester.pumpAndSettle();
          expect(client.createCalls, 1);
          expect(client.activateCalls, 1);
          expect(
            client.deactivateCalls,
            phase == 'delete' || phase == 'preparation' ? 0 : 1,
          );
          expect(client.deleteCalls, phase == 'delete' ? 1 : 0);
          expect(
            readsBeforeInvalidation,
            lookupsBefore + (phase == 'recovery' ? 1 : 0),
          );
          expect(
            client.detailIds.length,
            readsBeforeInvalidation + (action == 'navigation' ? 1 : 0),
          );
          expect(find.text('상태: INACTIVE · 비활성'), findsNothing);
          if (action == 'navigation') {
            expect(find.text('other route · other stop'), findsOneWidget);
            expect(find.text('상태: FOLLOW_UP · 한 정거장 후 추적 중'), findsOneWidget);
          } else {
            expect(find.byType(AlarmDetailScreen), findsNothing);
          }
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox());
        },
      );
    }
  }

  testWidgets(
    'lost activation and failed lookup preserve created ID without POST retry',
    (tester) async {
      client.activateAlarm = (_) async =>
          throw TimeoutException('activation lost');
      client.detail = (_) async => throw TimeoutException('lookup failed');
      await pumpHome(tester, prepare: (_) async => true);
      await start(tester);
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<AlarmDetailScreen>(find.byType(AlarmDetailScreen))
            .alarmId,
        sample.id,
      );
      expect(find.text('상태: 확인 필요'), findsOneWidget);
      expect(find.text('상태: INACTIVE · 비활성'), findsNothing);
      expect(client.detailIds, [sample.id]);
      await tester.pump(const Duration(seconds: 30));
      expect(client.createCalls, 1);
      expect(client.activateCalls, 1);
      client.detail = (_) async => withStatus(AlarmStatus.active);
      await tester.tap(find.text('현재 상태 다시 조회'));
      await tester.pumpAndSettle();
      expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
      expect(client.detailIds, [sample.id, sample.id]);
      expect(client.createCalls, 1);
      expect(client.activateCalls, 1);
    },
  );
}
