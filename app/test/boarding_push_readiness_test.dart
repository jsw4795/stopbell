import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';
import 'package:stopbell/features/boarding/boarding_session_controller.dart';
import 'package:stopbell/features/notification/push_activation_prompt.dart';
import 'package:stopbell/features/notification/push_permission_service.dart';

import 'boarding_session_test_fakes.dart';
import 'notification_test_fakes.dart';

void main() {
  late PushFixture f;
  late FakeBoardingSessions api;
  late MemoryBoardingIntents storage;
  late BoardingSessionController controller;
  late DateTime now;
  Future<void> initialize() async {
    f = PushFixture();
    await f.initialize();
    api = FakeBoardingSessions();
    storage = MemoryBoardingIntents();
    now = sessionNow;
    controller = BoardingSessionController(
      auth: f.auth,
      client: api,
      storage: storage,
      now: () => now,
    );
    await controller.initialize();
  }

  tearDown(() {
    controller.dispose();
    f.auth.dispose();
  });
  Future<void> pump(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => FilledButton(
              onPressed: () => controller.create(
                sessionCandidate(),
                '서울70사1234',
                sessionContext,
                preparePush: () => prepareBoardingPush(context, f.service),
              ),
              child: const Text('확정'),
            ),
          ),
        ),
      ),
    );
    expect(controller.canCreate, isTrue);
    expect(controller.validCandidate(sessionCandidate(), '서울70사1234'), isTrue);
    await tester.tap(find.text('확정'));
    await tester.pumpAndSettle();
  }

  for (final permission in [
    PushPermission.authorized,
    PushPermission.provisional,
    PushPermission.notDetermined,
  ]) {
    testWidgets(
      '$permission prepares actual registration before durable creation',
      (tester) async {
        await initialize();
        f.provider.status = permission;
        api.onCreate = () async {
          expect(f.devices.enabled, isTrue);
          expect(f.devices.fid, f.provider.fid);
          expect(storage.records, hasLength(1));
          return sessionFixture();
        };
        await pump(tester);
        if (permission == PushPermission.notDetermined) {
          expect(api.creations, isEmpty);
          expect(storage.records, isEmpty);
          await tester.tap(find.text('계속'));
          await tester.pumpAndSettle();
          expect(f.provider.requests, 1);
        }
        expect(api.creations, hasLength(1), reason: controller.message);
        expect(f.devices.requests, hasLength(1));
        expect(find.text('이 기기의 알림 수신 준비를 완료했습니다.'), findsOneWidget);
      },
    );
  }
  for (final failure in ['denied', 'provider', 'backend', 'fid']) {
    testWidgets('$failure requires explicit continue without notifications', (
      tester,
    ) async {
      await initialize();
      if (failure == 'denied') f.provider.status = PushPermission.denied;
      if (failure == 'provider') f.provider.fail = true;
      if (failure == 'backend') {
        f.devices.mutation = (_, _) async => throw StateError('offline');
      }
      if (failure == 'fid') {
        f.provider.registration = () async {
          final old = f.provider.fid;
          if (old != 'new-fid') {
            f.provider.fid = 'new-fid';
            f.service.fidChanged();
          }
          return old;
        };
      }
      await pump(tester);
      expect(api.creations, isEmpty);
      expect(storage.records, isEmpty);
      expect(find.textContaining('이미 발생한 알림은 추가 전송되지'), findsOneWidget);
      expect(find.text('이 기기의 알림 수신 준비를 완료했습니다.'), findsNothing);
      await tester.tap(find.text('알림 없이 계속'));
      await tester.pumpAndSettle();
      expect(api.creations, hasLength(1), reason: controller.message);
    });
  }
  testWidgets('denied cancellation never creates UUID or request', (
    tester,
  ) async {
    await initialize();
    f.provider.status = PushPermission.denied;
    await pump(tester);
    await tester.tap(find.text('취소'));
    await tester.pumpAndSettle();
    expect(api.creations, isEmpty);
    expect(storage.records, isEmpty);
    expect(controller.busy, isFalse);
  });
  testWidgets(
    'notDetermined later does not silently consent to notification-free creation',
    (tester) async {
      await initialize();
      f.provider.status = PushPermission.notDetermined;
      await pump(tester);
      await tester.tap(find.text('나중에'));
      await tester.pumpAndSettle();
      expect(api.creations, isEmpty);
      expect(f.provider.requests, 0);
      expect(find.text('알림 없이 계속'), findsOneWidget);
      await tester.tap(find.text('취소'));
      await tester.pumpAndSettle();
      expect(storage.records, isEmpty);
    },
  );
  testWidgets(
    'candidate expires while permission/registration waits, no UUID or POST',
    (tester) async {
      await initialize();
      f.provider.status = PushPermission.notDetermined;
      await pump(tester);
      now = sessionNow.add(const Duration(seconds: 61));
      await tester.tap(find.text('계속'));
      await tester.pumpAndSettle();
      expect(f.devices.enabled, isTrue);
      expect(api.creations, isEmpty);
      expect(storage.records, isEmpty);
      expect(controller.message, contains('후보 정보가 오래됐어요'));
    },
  );
  testWidgets(
    'prepare failure is recoverable and no creation intent is written',
    (tester) async {
      await initialize();
      await controller.create(
        sessionCandidate(),
        '서울70사1234',
        sessionContext,
        preparePush: () async => throw StateError('unexpected'),
      );
      expect(api.creations, isEmpty);
      expect(storage.records, isEmpty);
      expect(controller.canCreate, isTrue);
      expect(controller.message, contains('알림 수신 준비를 확인하지 못했어요'));
    },
  );
  for (final action in ['logout', 'new-login', 'dispose']) {
    testWidgets('$action while push prepares cannot continue creation', (
      tester,
    ) async {
      await initialize();
      final fid = Completer<String>();
      f.provider.registration = () => fid.future;
      await pump(tester);
      expect(controller.busy, isTrue);
      expect(storage.records, isEmpty);
      if (action == 'logout') {
        await f.auth.logout();
      } else if (action == 'new-login') {
        await f.switchUser('A');
      } else {
        await tester.pumpWidget(const SizedBox());
      }
      fid.complete('FID-A');
      await tester.pumpAndSettle();
      expect(api.creations, isEmpty);
      expect(storage.records, isEmpty);
    });
  }
  testWidgets(
    'pending uncertain POST recovery skips push and preserves original UUID/body',
    (tester) async {
      await initialize();
      api.onCreate = () async =>
          throw const BoardingSessionException(SessionFailure.timeout);
      await pump(tester);
      final original = Map.of(api.creations.single);
      final intent = storage.records.values.single;
      f.provider.fail = true;
      final registrations = f.provider.registrations;
      await controller.recover();
      expect(
        storage.records.values.single.clientRequestId,
        intent.clientRequestId,
      );
      expect(api.creations, hasLength(1), reason: controller.message);
      expect(f.provider.registrations, registrations);
      await controller.retryCreation();
      expect(api.creations.last, original);
      expect(f.provider.registrations, registrations);
    },
  );
}
