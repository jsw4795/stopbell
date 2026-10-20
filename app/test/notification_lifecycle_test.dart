import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/alarm/alarm_detail_screen.dart';
import 'package:stopbell/features/alarm/alarm_home.dart';
import 'package:stopbell/features/notification/notification_lifecycle.dart';
import 'package:stopbell/features/notification/push_activation_prompt.dart';
import 'package:stopbell/features/notification/push_permission_service.dart';

import 'notification_test_fakes.dart';

const valid = {
  'type': 'ALARM_EVENT',
  'alarmId': '15',
  'eventType': 'ARRIVED',
  'notificationEventId': '456',
};
const alarm = Alarm(
  id: 15,
  transitType: AlarmTransitType.bus,
  status: AlarmStatus.inactive,
  routeNumber: '7000',
  stopName: '테스트 정류장',
  notifyOneStopBefore: true,
  notifyOneStopAfter: false,
);

class TapAlarms extends Fake implements AlarmClient {
  Future<Alarm> Function(int)? detail;
  int lookups = 0;
  int activations = 0;
  @override
  Future<Alarm> findById(int id) async {
    lookups++;
    return detail == null ? alarm : await detail!(id);
  }

  @override
  Future<List<Alarm>> findAll() async => [];
  @override
  Future<Alarm> activate(int id) async {
    activations++;
    return const Alarm(
      id: 15,
      transitType: AlarmTransitType.bus,
      status: AlarmStatus.active,
      routeNumber: '7000',
      stopName: '테스트 정류장',
      notifyOneStopBefore: true,
      notifyOneStopAfter: false,
    );
  }
}

void main() {
  test('payload는 지원되는 최소 hint만 허용한다', () {
    expect(AlarmNotification.parse(valid)?.alarmId, 15);
    expect(
      AlarmNotification.parse({...valid, 'eventType': 'PASSED'})?.eventType,
      'PASSED',
    );
    for (final bad in [
      <String, dynamic>{},
      {...valid, 'type': 'route'},
      {...valid, 'alarmId': '0'},
      {...valid, 'alarmId': 15},
      {...valid, 'eventType': 'UNKNOWN'},
      {...valid, 'notificationEventId': 'x'},
      {...valid, 'alarmId': '9223372036854775808'},
    ]) {
      expect(AlarmNotification.parse(bad), isNull);
    }
  });
  test('로그인 전 tap은 폐기하고 인증 후 logical event 중복은 제거한다', () async {
    final f = PushFixture();
    await f.initialize(login: false);
    final lifecycle = NotificationLifecycle(f.auth);
    lifecycle.receive(valid, messageId: 'same');
    expect(lifecycle.takeTap(), isNull);
    await f.auth.login();
    expect(lifecycle.takeTap(), isNull);
    lifecycle.receive(valid, messageId: 'same');
    expect((lifecycle.takeTap() as AlarmNotification?)?.alarmId, 15);
    lifecycle.receive(valid, messageId: 'another');
    expect(lifecycle.takeTap(), isNull);
    lifecycle.dispose();
  });
  test('Logout 중 tap과 이전 A navigation은 B 및 새 A 세션으로 넘기지 않는다', () async {
    final f = PushFixture();
    await f.initialize();
    final lifecycle = NotificationLifecycle(f.auth);
    lifecycle.receive(valid);
    await f.auth.logout(
      beforeLogout: () async {
        lifecycle.receive({...valid, 'notificationEventId': '457'});
      },
    );
    f.authenticator.user = 'B';
    await f.auth.login();
    expect(lifecycle.takeTap(), isNull);
    await f.switchUser('A');
    expect(lifecycle.takeTap(), isNull);
    lifecycle.dispose();
  });
  testWidgets('권한 거부 안내 뒤에도 Alarm activate API를 호출할 수 있다', (tester) async {
    final f = PushFixture();
    await f.initialize();
    f.provider.status = PushPermission.denied;
    final client = TapAlarms();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AlarmDetailScreen(
            alarmId: 15,
            client: client,
            createdAlarm: alarm,
            onBack: () {},
            beforeActivate: (context) => prepareAlarmPush(context, f.service),
          ),
        ),
      ),
    );
    await tester.tap(find.text('활성화'));
    // Activation remains busy behind the permission dialog. Advance its animation only.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 350));
    expect(find.text('설정 열기'), findsOneWidget);
    expect(client.activations, 0);
    await tester.tap(find.text('알람 활성화'));
    await tester.pumpAndSettle();
    expect(client.activations, 1);
    expect(f.devices.requests, isEmpty);
    expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
  });
  testWidgets('첫 activation은 설명 후 permission 요청 및 Device 등록을 진행한다', (
    tester,
  ) async {
    final f = PushFixture();
    await f.initialize();
    f.provider.status = PushPermission.notDetermined;
    final client = TapAlarms();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AlarmDetailScreen(
            alarmId: 15,
            client: client,
            createdAlarm: alarm,
            onBack: () {},
            beforeActivate: (context) => prepareAlarmPush(context, f.service),
          ),
        ),
      ),
    );
    await tester.tap(find.text('활성화'));
    // Activation remains busy behind the permission dialog. Advance its animation only.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 350));
    expect(find.text('하차 알림 받기'), findsOneWidget);
    expect(f.provider.requests, 0);
    await tester.tap(find.text('계속'));
    await tester.pumpAndSettle();
    expect(f.provider.requests, 1);
    expect(f.devices.requests.length, 1);
    expect(client.activations, 1);
  });
  testWidgets('tap은 Backend detail 확인 뒤 열고 삭제된 알람은 안내 후 목록으로 돌아간다', (
    tester,
  ) async {
    final f = PushFixture();
    await f.initialize();
    final lifecycle = NotificationLifecycle(f.auth);
    final client = TapAlarms();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AlarmHome(
            client: client,
            authSession: f.auth,
            searchRoutes: (_) async => [],
            findStops: (_) async => [],
            notifications: lifecycle,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    lifecycle.receive(valid);
    await tester.pumpAndSettle();
    expect(client.lookups, 1);
    expect(find.text('알람 상세'), findsOneWidget);
    client.detail = (_) async =>
        throw const AlarmApiException(statusCode: 404, code: 'ALARM_NOT_FOUND');
    lifecycle.receive({...valid, 'notificationEventId': '457'});
    await tester.pumpAndSettle();
    expect(find.text('알람 상세'), findsNothing);
    expect(
      find.text('알람을 열 수 없습니다. 삭제되었거나 접근할 수 없는 알람일 수 있습니다.'),
      findsOneWidget,
    );
    await tester.pumpWidget(const SizedBox());
    lifecycle.dispose();
  });
  testWidgets('tap 상세 조회 중 Logout 이후 지연된 결과는 화면에 노출되지 않는다', (tester) async {
    final f = PushFixture();
    await f.initialize();
    final lifecycle = NotificationLifecycle(f.auth);
    final delayed = Completer<Alarm>();
    final client = TapAlarms()..detail = (_) => delayed.future;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AlarmHome(
            client: client,
            authSession: f.auth,
            searchRoutes: (_) async => [],
            findStops: (_) async => [],
            notifications: lifecycle,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    lifecycle.receive(valid);
    await tester.pump();
    await f.switchUser('B');
    delayed.complete(alarm);
    await tester.pumpAndSettle();
    expect(find.text('알람 상세'), findsNothing);
    await tester.pumpWidget(const SizedBox());
    lifecycle.dispose();
  });
  testWidgets('foreground는 작은 안내를 표시하고 현재 session의 선택에만 상세 조회한다', (
    tester,
  ) async {
    final f = PushFixture();
    await f.initialize();
    final lifecycle = NotificationLifecycle(f.auth);
    final client = TapAlarms();
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: AlarmHome(
            client: client,
            authSession: f.auth,
            searchRoutes: (_) async => [],
            findStops: (_) async => [],
            notifications: lifecycle,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    lifecycle.receive(valid, inForeground: true);
    await tester.pumpAndSettle();
    expect(client.lookups, 0);
    expect(find.text('버스 도착 알림이 도착했습니다.'), findsOneWidget);
    await tester.tap(find.text('알람 보기'));
    await tester.pumpAndSettle();
    expect(client.lookups, 1);
    expect(find.text('알람 상세'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    lifecycle.dispose();
  });
}
