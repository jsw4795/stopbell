import 'dart:async';

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_home.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/boarding/boarding_notification_screen.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';
import 'package:stopbell/features/notification/notification_lifecycle.dart';
import 'package:stopbell/features/notification/push_permission_service.dart';
import 'package:stopbell/features/notification/push_registration_service.dart';
import 'package:stopbell/main.dart';

import 'notification_lifecycle_test.dart' show TapAlarms, alarm, valid;
import 'notification_test_fakes.dart';
import 'boarding_session_test_fakes.dart';
import 'boarding_test_fakes.dart' show boardingJson, boardingBaseUrl;

const sessionHint = <String, dynamic>{
  'type': 'BOARDING_SESSION_EVENT',
  'boardingSessionId': '123',
  'eventType': 'PREPARE_ALIGHT',
  'notificationEventId': '456',
};

class TestReceiver implements NotificationReceiver {
  final taps = StreamController<RemoteMessage>.broadcast(sync: true);
  final foreground = StreamController<RemoteMessage>.broadcast(sync: true);
  final initial = Completer<RemoteMessage?>();
  int queries = 0;
  @override
  Stream<RemoteMessage> get opened => taps.stream;
  @override
  Stream<RemoteMessage> get messages => foreground.stream;
  @override
  Future<RemoteMessage?> initialMessage() {
    queries++;
    return initial.future;
  }

  Future<void> close() async {
    await taps.close();
    await foreground.close();
  }
}

class RestoringTokens extends MemoryTokens {
  final result = Completer<TokenPair?>();
  @override
  Future<TokenPair?> read() => result.future;
}

class RetryFirebaseProvider extends FirebasePushProvider {
  int attempts = 0;
  @override
  Future<void> initialize() async {
    if (++attempts == 1) throw StateError('fixture initialization failure');
  }

  @override
  Future<PushPermission> permission({bool request = false}) async =>
      PushPermission.denied;
  @override
  Stream<String> get idChanges => const Stream.empty();
}

void main() {
  for (final type in [
    'ONE_STOP_BEFORE',
    'ARRIVED',
    'PASSED',
    'ONE_STOP_AFTER',
  ]) {
    test('Alarm $type payload remains supported', () {
      final hint = NotificationHint.parse({...valid, 'eventType': type});
      expect(hint, isA<AlarmNotification>());
      expect(hint!.resourceId, 15);
      expect(hint.eventId, '456');
    });
  }
  test('Session payload has only its own resource ID and fixed event type', () {
    final hint =
        NotificationHint.parse(sessionHint) as BoardingSessionNotification;
    expect(hint.boardingSessionId, 123);
    expect(hint.eventId, '456');
    expect(hint.eventType, 'PREPARE_ALIGHT');
    expect(AlarmNotification.parse(sessionHint), isNull);
    expect(hint.toString(), isNot(contains('123')));
  });
  for (final field in ['boardingSessionId', 'notificationEventId']) {
    for (final value in <Object?>[
      null,
      0,
      123,
      -1,
      true,
      '',
      '0',
      '-1',
      ' 1',
      '1 ',
      '1\n',
      '1.0',
      '+1',
      '01',
      '9223372036854775808',
    ]) {
      test(
        'reject $field = ${value.runtimeType}: ${value.toString().replaceAll('\n', 'newline')}',
        () {
          expect(
            NotificationHint.parse({...sessionHint, field: value}),
            isNull,
          );
        },
      );
    }
    test('reject missing $field', () {
      expect(NotificationHint.parse({...sessionHint}..remove(field)), isNull);
    });
  }
  for (final bad in [
    {...sessionHint, 'alarmId': '123'},
    {...valid, 'boardingSessionId': '15'},
    {...sessionHint, 'alarmId': null},
    {...valid, 'boardingSessionId': null},
    {...sessionHint, 'eventType': 'ARRIVED'},
    {...sessionHint, 'eventType': 1},
    {...sessionHint, 'eventType': 'UNKNOWN'},
    {...sessionHint}..remove('eventType'),
    {...sessionHint, 'type': 'UNKNOWN'},
    {...sessionHint}..remove('type'),
    {...sessionHint, 'type': 'ALARM_EVENT'},
  ]) {
    test('reject source/type contradiction $bad', () {
      expect(NotificationHint.parse(bad), isNull);
    });
  }

  group('receiver lifecycle', () {
    late PushFixture f;
    late TestReceiver receiver;
    late NotificationLifecycle lifecycle;
    setUp(() async {
      f = PushFixture();
      await f.initialize();
      receiver = TestReceiver();
      lifecycle = NotificationLifecycle(f.auth, receiver: receiver);
    });
    tearDown(() async {
      lifecycle.dispose();
      f.auth.dispose();
      await receiver.close();
    });
    test(
      'one registration for start concurrency, initial/opened logical dedupe',
      () async {
        final start = lifecycle.start();
        final second = lifecycle.start();
        expect(identical(start, second), isTrue);
        receiver.initial.complete(
          const RemoteMessage(data: sessionHint, messageId: 'initial'),
        );
        await start;
        expect(
          (lifecycle.takeTap() as BoardingSessionNotification)
              .boardingSessionId,
          123,
        );
        receiver.taps.add(
          const RemoteMessage(
            data: sessionHint,
            messageId: 'different-message-id',
          ),
        );
        expect(lifecycle.takeTap(), isNull);
        await lifecycle.start();
        expect(receiver.queries, 1);
      },
    );
    test('foreground channel does not block explicit tap and source keys do not collide', () async {
      receiver.initial.complete(null);
      await lifecycle.start();
      receiver.foreground.add(const RemoteMessage(data: sessionHint));
      expect(lifecycle.foreground, isA<BoardingSessionNotification>());
      expect(lifecycle.takeTap(), isNull);
      lifecycle.openForeground(lifecycle.foreground!, f.auth.generation);
      expect(lifecycle.takeTap(), isA<BoardingSessionNotification>());
      receiver.taps.add(RemoteMessage(data: {...valid, 'alarmId': '123'}));
      expect(lifecycle.takeTap(), isA<AlarmNotification>());
      lifecycle.receive(sessionHint, inForeground: true, messageId: 'new-id');
      expect(lifecycle.takeTap(), isNull);
    });
    test(
      'authentication change clears pending, foreground and per-login dedupe',
      () async {
        lifecycle.receive(sessionHint);
        lifecycle.receive(sessionHint, inForeground: true);
        await f.switchUser('B');
        expect(lifecycle.takeTap(), isNull);
        expect(lifecycle.foreground, isNull);
        await f.switchUser('A');
        expect(lifecycle.takeTap(), isNull);
        lifecycle.receive(sessionHint);
        expect(lifecycle.takeTap(), isA<BoardingSessionNotification>());
      },
    );
    test(
      'logout hook drops foreground and tap before generation increments',
      () async {
        await f.auth.logout(
          beforeLogout: () async {
            lifecycle.receive(sessionHint);
            lifecycle.receive(sessionHint, inForeground: true);
            expect(lifecycle.takeTap(), isNull);
            expect(lifecycle.foreground, isNull);
          },
        );
      },
    );
    test(
      'initial query completing after account switch cannot bind to new login',
      () async {
        final start = lifecycle.start();
        await f.switchUser('B');
        await f.switchUser('A');
        receiver.initial.complete(const RemoteMessage(data: sessionHint));
        await start;
        expect(lifecycle.takeTap(), isNull);
      },
    );

    test('failed initial platform query stays fail-closed without blocking live delivery', () async {
      final start = lifecycle.start();
      receiver.initial.completeError(StateError('fixture platform failure'));
      await start;
      await lifecycle.start();
      expect(receiver.queries, 1);
      expect(lifecycle.takeTap(), isNull);
      receiver.taps.add(const RemoteMessage(data: sessionHint));
      expect(lifecycle.takeTap(), isA<BoardingSessionNotification>());
    });
    test('same login refresh keeps hint context', () async {
      lifecycle.receive(sessionHint);
      await f.auth.refreshAfterUnauthorized(
        f.auth.accessToken!,
        generation: f.auth.generation,
      );
      expect(lifecycle.takeTap(), isA<BoardingSessionNotification>());
    });
    test('refresh rejection invalidates pending hint', () async {
      lifecycle.receive(sessionHint);
      f.backend.rotation = (_) async =>
          throw const BackendAuthException('rejected', statusCode: 401);
      try {
        await f.auth.refreshAfterUnauthorized(f.auth.accessToken!);
      } catch (_) {}
      expect(lifecycle.takeTap(), isNull);
    });
  });

  for (final restore in [true, false]) {
    test(
      'initial message before auth restore: persisted login=$restore',
      () async {
        final tokens = RestoringTokens();
        final auth = AuthSession(
          authenticator: UserAuthenticator(),
          backend: TestAuthBackend(),
          tokenStorage: tokens,
        );
        final receiver = TestReceiver();
        final lifecycle = NotificationLifecycle(auth, receiver: receiver);
        final start = lifecycle.start();
        receiver.initial.complete(const RemoteMessage(data: sessionHint));
        await Future<void>.delayed(Duration.zero);
        tokens.result.complete(restore ? userPair('A') : null);
        await start;
        if (restore) {
          expect(lifecycle.takeTap(), isA<BoardingSessionNotification>());
        } else {
          await auth.login();
          expect(lifecycle.takeTap(), isNull);
        }
        lifecycle.dispose();
        auth.dispose();
        await receiver.close();
      },
    );
  }
  test(
    'dispose while initial message waits never notifies or navigates',
    () async {
      final f = PushFixture();
      await f.initialize();
      final receiver = TestReceiver();
      final lifecycle = NotificationLifecycle(f.auth, receiver: receiver);
      final start = lifecycle.start();
      lifecycle.dispose();
      receiver.initial.complete(const RemoteMessage(data: sessionHint));
      await start;
      expect(lifecycle.takeTap(), isNull);
      f.auth.dispose();
      await receiver.close();
    },
  );

  test(
    'unauthenticated startup cannot transfer delayed initial to a later login',
    () async {
      final f = PushFixture();
      await f.initialize(login: false);
      final receiver = TestReceiver();
      final lifecycle = NotificationLifecycle(f.auth, receiver: receiver);
      final start = lifecycle.start();
      await f.auth.login();
      receiver.initial.complete(const RemoteMessage(data: sessionHint));
      await start;
      expect(lifecycle.takeTap(), isNull);
      lifecycle.dispose();
      f.auth.dispose();
      await receiver.close();
    },
  );
  testWidgets(
    'timed-out setup retries cached initial future without duplicate listeners or query',
    (tester) async {
      final f = PushFixture();
      await f.initialize();
      final receiver = TestReceiver();
      final lifecycle = NotificationLifecycle(f.auth, receiver: receiver);
      Object? failure;
      final first = lifecycle.start().catchError((Object e) {
        failure = e;
      });
      await tester.pump(const Duration(seconds: 9));
      await first;
      expect(failure, isA<TimeoutException>());
      final retry = lifecycle.start();
      receiver.initial.complete(const RemoteMessage(data: sessionHint));
      await retry;
      expect(lifecycle.takeTap(), isA<BoardingSessionNotification>());
      expect(receiver.queries, 1);
      receiver.taps.add(const RemoteMessage(data: sessionHint));
      expect(lifecycle.takeTap(), isNull);
      lifecycle.dispose();
      f.auth.dispose();
      await receiver.close();
    },
  );
  testWidgets(
    'queued initial waits for Home mounting and duplicate opened never reloads',
    (tester) async {
      final f = PushFixture();
      await f.initialize();
      final receiver = TestReceiver();
      final lifecycle = NotificationLifecycle(f.auth, receiver: receiver);
      receiver.initial.complete(const RemoteMessage(data: sessionHint));
      await lifecycle.start();
      final api = FakeBoardingSessions()..currentSession = sessionFixture();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AlarmHome(
              client: TapAlarms(),
              authSession: f.auth,
              searchRoutes: (_) async => [],
              findStops: (_) async => [],
              notifications: lifecycle,
              boardingSessionClient: api,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(api.detailCalls, 1);
      expect(api.currentCalls, 0);
      receiver.taps.add(
        const RemoteMessage(data: sessionHint, messageId: 'opened'),
      );
      await tester.pumpAndSettle();
      expect(api.detailCalls, 1);
      await tester.pumpWidget(const SizedBox());
      lifecycle.dispose();
      f.auth.dispose();
      await receiver.close();
    },
  );
  testWidgets(
    'Firebase initialization retry on resume connects one receiver and initial navigation',
    (tester) async {
      final f = PushFixture();
      await f.initialize();
      final provider = RetryFirebaseProvider();
      final receiver = TestReceiver();
      receiver.initial.complete(const RemoteMessage(data: sessionHint));
      final lifecycle = NotificationLifecycle(f.auth, receiver: receiver);
      final api = FakeBoardingSessions()..currentSession = sessionFixture();
      final push = PushRegistrationService(
        auth: f.auth,
        storage: f.storage,
        devices: f.devices,
        provider: provider,
      );
      await tester.pumpWidget(
        StopBellApplication(
          authSession: f.auth,
          push: push,
          notifications: lifecycle,
          searchRoutes: (_) async => [],
          findStops: (_) async => [],
          alarmClient: TapAlarms(),
          boardingSessionClient: api,
        ),
      );
      await tester.pumpAndSettle();
      expect(provider.attempts, 1);
      expect(receiver.queries, 0);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(provider.attempts, 2);
      expect(receiver.queries, 1);
      expect(api.detailCalls, 1);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(receiver.queries, 1);
      expect(api.detailCalls, 1);
      await tester.pumpWidget(const SizedBox());
      await receiver.close();
    },
  );
  group('authenticated notification navigation', () {
    late PushFixture f;
    late NotificationLifecycle notifications;
    late TapAlarms alarms;
    late BoardingSessionApiClient sessions;
    late MemoryBoardingIntents storage;
    late Future<http.Response> Function(http.Request) response;
    final requests = <http.Request>[];
    setUp(() async {
      f = PushFixture();
      await f.initialize();
      notifications = NotificationLifecycle(f.auth);
      alarms = TapAlarms();
      storage = MemoryBoardingIntents();
      requests.clear();
      response = (_) async => boardingJson(sessionJson());
      final transport = MockClient((request) async {
        requests.add(request);
        return response(request);
      });
      addTearDown(transport.close);
      sessions = BoardingSessionApiClient(
        AuthenticatedApiClient(
          apiBaseUrl: boardingBaseUrl,
          authSession: f.auth,
          client: transport,
        ),
        timeout: const Duration(seconds: 1),
      );
    });
    Future<void> pump(WidgetTester tester, {bool deliver = true}) async {
      tester.view.physicalSize = const Size(1000, 1600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AlarmHome(
              key: ValueKey(f.auth.generation),
              client: alarms,
              authSession: f.auth,
              searchRoutes: (_) async => [],
              findStops: (_) async => [],
              boardingSessionClient: sessions,
              boardingIntentStorage: storage,
              notifications: notifications,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      if (deliver) {
        notifications.receive(sessionHint);
        await tester.pumpAndSettle();
      }
    }

    tearDown(() {
      notifications.dispose();
      f.auth.dispose();
    });
    for (final (status, evaluation) in [
      ('TRACKING', 'READY'),
      ('TRACKING', 'RECOVERY_PENDING'),
      ('SUSPENDED', 'HELD'),
      ('COMPLETED', 'ENDED'),
      ('CANCELLED', 'ENDED'),
      ('EXPIRED', 'ENDED'),
      ('FUTURE', 'FUTURE'),
    ]) {
      testWidgets(
        'exact target with $status/$evaluation uses read-only authoritative detail',
        (tester) async {
          await storage.save(intentFixture(f.auth));
          final original = storage.records.values.single;
          response = (_) async => boardingJson(
            sessionJson(status: status, evaluation: evaluation, gap: 999),
          );
          await pump(tester);
          expect(requests.map((r) => '${r.method} ${r.url}'), [
            'GET ${boardingBaseUrl.resolve('/api/v1/boarding-sessions/123')}',
          ]);
          expect(storage.records.values.single.toJson(), original.toJson());
          expect(
            find.textContaining(
              status == 'FUTURE' ? '알 수 없는 세션 상태' : '$status ·',
            ),
            findsOneWidget,
          );
          expect(find.text('추적 취소'), findsNothing);
          expect(find.text('동일 차량 다시 확인'), findsNothing);
          expect(
            find.textContaining('검증된 노선 방문 지점 간격'),
            status == 'TRACKING' && evaluation == 'READY'
                ? findsOneWidget
                : findsNothing,
          );
        },
      );
    }
    for (final error in ['404', '503', 'timeout', 'malformed', 'wrong-id']) {
      testWidgets('$error hides session data and retry uses same exact ID', (
        tester,
      ) async {
        response = (_) async => switch (error) {
          '404' => boardingJson({'code': 'BOARDING_SESSION_NOT_FOUND'}, 404),
          '503' => boardingJson({}, 503),
          'timeout' => Completer<http.Response>().future,
          'malformed' => boardingJson({'id': 123}),
          _ => boardingJson(sessionJson(id: 200)),
        };
        await pump(tester);
        if (error == 'timeout') {
          await tester.pump(const Duration(seconds: 2));
          await tester.pumpAndSettle();
        }
        expect(find.textContaining('차량 서울'), findsNothing);
        expect(
          find.text(
            error == '404'
                ? '이 하차 알림의 세션을 열 수 없어요. 세션이 없거나 현재 계정에서 접근할 수 없어요.'
                : '세션 상태를 확인하지 못했어요. 다시 조회해 주세요.',
          ),
          findsOneWidget,
        );
        response = (_) async =>
            boardingJson(sessionJson(status: 'COMPLETED', evaluation: 'ENDED'));
        await tester.tap(find.text('다시 조회'));
        await tester.pumpAndSettle();
        expect(find.textContaining('COMPLETED ·'), findsOneWidget);
        expect(
          requests.map((r) => r.url.path),
          everyElement('/api/v1/boarding-sessions/123'),
        );
      });
    }

    for (final event in [
      'ONE_STOP_BEFORE',
      'ARRIVED',
      'PASSED',
      'ONE_STOP_AFTER',
    ]) {
      testWidgets('Alarm $event tap retains exact Alarm API path', (
        tester,
      ) async {
        await pump(tester, deliver: false);
        notifications.receive({...valid, 'eventType': event});
        await tester.pumpAndSettle();
        expect(alarms.lookups, 1);
        expect(requests, isEmpty);
        expect(find.text('알람 상세'), findsOneWidget);
      });
    }
    testWidgets(
      'foreground requires user action, logical duplicate never queues another banner',
      (tester) async {
        await pump(tester, deliver: false);
        notifications.receive(sessionHint, inForeground: true, messageId: 'a');
        await tester.pumpAndSettle();
        notifications.receive(sessionHint, inForeground: true, messageId: 'b');
        await tester.pumpAndSettle();
        expect(requests, isEmpty);
        expect(find.text('하차 준비 알림이 도착했어요.'), findsOneWidget);
        await tester.tap(find.text('세션 보기'));
        await tester.pumpAndSettle();
        expect(requests, hasLength(1));
        expect(find.byType(BoardingNotificationScreen), findsOneWidget);
        notifications.receive(sessionHint);
        await tester.pumpAndSettle();
        expect(requests, hasLength(1));
        await tester.pump(const Duration(seconds: 5));
        await tester.pumpAndSettle();
        expect(find.text('하차 준비 알림이 도착했어요.'), findsNothing);
      },
    );
    for (final order in [
      'alarm-session',
      'session-alarm',
      'session-session',
      'home',
      'dispose',
      'logout',
      'A-B-A',
      'same-user',
    ]) {
      testWidgets('late response cannot overwrite $order navigation', (
        tester,
      ) async {
        final delayed = Completer<http.Response>();
        final delayedAlarm = Completer<Alarm>();
        response = (_) => delayed.future;
        await pump(tester, deliver: false);
        if (order == 'alarm-session') {
          alarms.detail = (_) => delayedAlarm.future;
          notifications.receive(valid);
          await tester.pump();
          response = (_) async => boardingJson(sessionJson());
          notifications.receive(sessionHint);
          await tester.pumpAndSettle();
          delayedAlarm.complete(alarm);
          await tester.pumpAndSettle();
          expect(find.byType(BoardingNotificationScreen), findsOneWidget);
        } else {
          notifications.receive(sessionHint);
          await tester.pump();
          switch (order) {
            case 'session-alarm':
              notifications.receive(valid);
              break;
            case 'session-session':
              response = (_) async => boardingJson(sessionJson(id: 200));
              notifications.receive({
                ...sessionHint,
                'boardingSessionId': '200',
                'notificationEventId': '457',
              });
              break;
            case 'home':
              await tester.tap(find.text('버스 도착 알림으로'));
              break;
            case 'dispose':
              await tester.pumpWidget(const SizedBox());
              break;
            case 'logout':
              await f.auth.logout();
              break;
            case 'A-B-A':
              await f.switchUser('B');
              await f.switchUser('A');
              break;
            case 'same-user':
              await f.switchUser('A');
              break;
          }
          await tester.pumpAndSettle();
          delayed.complete(boardingJson(sessionJson()));
          await tester.pumpAndSettle();
          if (order == 'session-alarm') {
            expect(find.text('알람 상세'), findsOneWidget);
          } else if (order == 'session-session') {
            expect(requests.last.url.path, '/api/v1/boarding-sessions/200');
            expect(find.textContaining('차량 서울'), findsOneWidget);
          } else {
            expect(find.byType(BoardingNotificationScreen), findsNothing);
          }
        }
        expect(tester.takeException(), isNull);
      });
    }
    testWidgets('old foreground banner and action disappear on logout', (
      tester,
    ) async {
      await pump(tester, deliver: false);
      notifications.receive(sessionHint, inForeground: true);
      await tester.pumpAndSettle();
      await f.auth.logout();
      await tester.pumpAndSettle();
      expect(find.text('세션 보기'), findsNothing);
      expect(requests, isEmpty);
    });
    testWidgets('detail refresh remains within same login JWT rotation', (
      tester,
    ) async {
      var calls = 0;
      response = (_) async =>
          calls++ == 0 ? boardingJson({}, 401) : boardingJson(sessionJson());
      await pump(tester);
      expect(f.backend.refreshCalls, 1);
      expect(requests, hasLength(2));
      expect(
        requests.last.headers['authorization'],
        'Bearer ${f.auth.accessToken}',
      );
      expect(find.textContaining('TRACKING ·'), findsOneWidget);
    });
    testWidgets('refresh failure removes exact-ID detail before any retry', (
      tester,
    ) async {
      f.backend.rotation = (_) async =>
          throw const BackendAuthException('rejected', statusCode: 401);
      response = (_) async => boardingJson({}, 401);
      await pump(tester);
      expect(f.auth.state, AuthState.unauthenticated);
      expect(find.textContaining('차량 서울'), findsNothing);
      expect(requests, hasLength(1));
    });
  });
}
