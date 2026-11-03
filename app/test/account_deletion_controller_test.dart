import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/alarm/alarm_creation_intent.dart';
import 'package:stopbell/features/auth/account_deletion_client.dart';
import 'package:stopbell/features/auth/account_deletion_controller.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/boarding/boarding_creation_intent.dart';
import 'package:stopbell/main.dart';

import 'alarm_test_fakes.dart';
import 'auth_session_test.dart' as auth_fake;
import 'boarding_session_test_fakes.dart' show MemoryBoardingIntents;

class MemoryDeletion implements DeletionStorage {
  DeletionRecord? value;
  bool fail = false;
  @override
  Future<DeletionRecord?> read() async => value;
  @override
  Future<void> save(DeletionRecord value) async {
    if (fail) throw StateError('storage');
    this.value = value;
  }

  @override
  Future<void> delete() async {
    if (fail) throw StateError('storage');
    value = null;
  }
}

class MemoryAlarms implements AlarmIntentStorage {
  AlarmCreationIntent? value;
  @override
  Future<AlarmCreationIntent?> read(String identity) async => value;
  @override
  Future<bool> saveIfAbsent(AlarmCreationIntent intent) async {
    value = intent;
    return true;
  }

  @override
  Future<bool> deleteIfMatching(String identity, String id) async {
    value = null;
    return true;
  }
}

class FakeDeletionClient extends AccountDeletionClient {
  FakeDeletionClient() : super(apiBaseUrl: Uri.parse('https://example.test'));
  int calls = 0;
  String? sentReceipt;
  AccountDeletionResult result = const AccountDeletionResult(
    AccountDeletionStatus.pending,
  );
  AccountDeletionResult state = const AccountDeletionResult(
    AccountDeletionStatus.pending,
  );
  Completer<AccountDeletionResult>? gate;
  @override
  Future<AccountDeletionResult> delete(String token, String receipt) async {
    calls++;
    sentReceipt = receipt;
    return gate == null ? result : await gate!.future;
  }

  @override
  Future<AccountDeletionResult> status(String receipt) async => state;
}

void main() {
  late auth_fake.FakeStorage tokens;
  late auth_fake.FakeBackend backend;
  late AuthSession auth;
  late MemoryDeletion storage;
  late MemoryAlarms alarms;
  late MemoryBoardingIntents boarding;
  late FakeDeletionClient client;
  late AccountDeletionController controller;
  setUp(() async {
    tokens = auth_fake.FakeStorage(pair: auth_fake.oldPair);
    backend = auth_fake.FakeBackend();
    auth = auth_fake.httpSession(backend, tokens);
    await auth.initialize();
    storage = MemoryDeletion();
    alarms = MemoryAlarms();
    boarding = MemoryBoardingIntents();
    client = FakeDeletionClient();
    controller = AccountDeletionController(
      auth: auth,
      client: client,
      storage: storage,
      alarms: alarms,
      boarding: boarding,
    );
    addTearDown(controller.dispose);
  });
  test(
    'receipt is stored before one request and restored after restart',
    () async {
      client.gate = Completer<AccountDeletionResult>();
      final first = controller.start();
      await Future<void>.delayed(Duration.zero);
      expect(storage.value?.receipt, client.sentReceipt);
      expect(storage.value!.receipt.length, 43);
      await controller.start();
      expect(client.calls, 1);
      client.gate!.complete(
        const AccountDeletionResult(
          AccountDeletionStatus.pending,
          retryAfter: Duration(seconds: 60),
        ),
      );
      await first;
      expect(auth.currentPair, isNotNull);
      expect(controller.canRetry, false);
      final otherAuth = auth_fake.httpSession(backend, tokens);
      await otherAuth.initialize();
      final restored = AccountDeletionController(
        auth: otherAuth,
        client: client,
        storage: storage,
        alarms: alarms,
        boarding: boarding,
      );
      addTearDown(restored.dispose);
      await restored.initialize();
      expect(restored.active, true);
      expect(otherAuth.isDeletingAccount, true);
      client.state = const AccountDeletionResult(AccountDeletionStatus.deleted);
      await restored.check();
      expect(tokens.pair, isNull);
      expect(backend.logoutCalls, 0);
      expect(storage.value, isNull);
    },
  );
  test('manual Apple notice survives restart until acknowledged', () async {
    client.result = const AccountDeletionResult(
      AccountDeletionStatus.manualAppleRevocationRequired,
    );
    await controller.start();
    expect(controller.manualNotice, true);
    expect(tokens.pair, isNull);
    expect(storage.value!.manual, true);
    final restoredAuth = auth_fake.httpSession(backend, tokens);
    await restoredAuth.initialize();
    final restored = AccountDeletionController(
      auth: restoredAuth,
      client: client,
      storage: storage,
      alarms: alarms,
      boarding: boarding,
    );
    addTearDown(restored.dispose);
    await restored.initialize();
    expect(restored.manualNotice, true);
    await restored.acknowledgeManual();
    expect(storage.value, isNull);
  });
  test(
    'token deletion failure remains blocked and can retry cleanup',
    () async {
      tokens.deleteError = StateError('secure storage');
      client.result = const AccountDeletionResult(
        AccountDeletionStatus.manualAppleRevocationRequired,
      );
      await controller.start();
      expect(controller.confirmed, true);
      expect(auth.isDeletingAccount, true);
      expect(controller.message, contains('정리하지 못했습니다'));
      tokens.deleteError = null;
      await controller.finishCleanup();
      expect(controller.manualNotice, true);
    },
  );
  test('pending receipt belonging to another session is not shown', () async {
    storage.value = DeletionRecord(
      receipt: 'A' * 43,
      session: 'another-session',
    );
    await controller.initialize();
    expect(controller.active, false);
    expect(auth.isDeletingAccount, false);
  });
  for (final state in [
    AccountDeletionStatus.unauthenticated,
    AccountDeletionStatus.uncertain,
    AccountDeletionStatus.rejected,
    AccountDeletionStatus.configurationError,
  ]) {
    test('$state preserves the session and receipt', () async {
      client.result = AccountDeletionResult(state);
      await controller.start();
      expect(storage.value, isNotNull);
      expect(storage.value!.confirmed, false);
      expect(tokens.pair, isNotNull);
      expect(auth.isDeletingAccount, true);
      expect(backend.logoutCalls, 0);
    });
  }
  test('storage failure before the DELETE does not send a request', () async {
    storage.fail = true;
    await controller.start();
    expect(client.calls, 0);
    expect(auth.isDeletingAccount, false);
    expect(tokens.pair, isNotNull);
  });
  test(
    '204 with durable completion clears credentials without logout',
    () async {
      final session = auth.sessionIdentity!;
      alarms.value = AlarmCreationIntent(
        clientRequestId: 'request',
        targetStopOccurrenceId: 1,
        notifyOneStopBefore: false,
        notifyOneStopAfter: false,
        userIdentity: '1',
        sessionIdentity: session,
        createdAt: DateTime.now().toUtc(),
      );
      boarding.records[session] = BoardingCreationIntent(
        clientRequestId: 'request',
        candidateRef: 'candidate',
        selectedVehicleNumber: 'vehicle',
        userIdentity: '1',
        sessionIdentity: session,
        context: const BoardingSelectionContext(routeId: 1, targetId: 1),
        observedAt: DateTime.now().toUtc(),
      );
      client.result = const AccountDeletionResult(
        AccountDeletionStatus.deleted,
      );
      client.state = const AccountDeletionResult(AccountDeletionStatus.deleted);
      await controller.start();
      expect(auth.state, AuthState.unauthenticated);
      expect(storage.value, isNull);
      expect(tokens.pair, isNull);
      expect(alarms.value, isNull);
      expect(boarding.records, isEmpty);
      expect(backend.logoutCalls, 0);
    },
  );
  test('204 with unknown Apple result preserves an uncertain notice', () async {
    client.result = const AccountDeletionResult(AccountDeletionStatus.deleted);
    client.state = const AccountDeletionResult(AccountDeletionStatus.uncertain);
    await controller.start();
    expect(controller.manualNotice, true);
    expect(controller.appleUncertain, true);
    expect(storage.value!.appleUncertain, true);
  });
  test('late protected response is discarded once deletion starts', () async {
    final gate = Completer<http.Response>();
    final transport = MockClient((_) => gate.future);
    addTearDown(transport.close);
    final api = AuthenticatedApiClient(
      apiBaseUrl: Uri.parse('https://example.test'),
      authSession: auth,
      client: transport,
    );
    final response = api.request('GET', '/api/v1/alarms');
    final check = expectLater(response, throwsA(isA<StaleSessionException>()));
    await controller.start();
    gate.complete(http.Response('[]', 200));
    await check;
  });
  test('refresh already in flight settles before deletion and cannot revive the session', () async {
    final gate = Completer<TokenPair>();
    backend.onRefresh = (_) => gate.future;
    final refresh = auth.refreshAfterUnauthorized(
      auth_fake.oldPair.accessToken,
    );
    final request = controller.start();
    expect(auth.isDeletingAccount, true);
    gate.complete(auth_fake.newPair);
    await refresh;
    client.result = const AccountDeletionResult(
      AccountDeletionStatus.manualAppleRevocationRequired,
    );
    await request;
    expect(tokens.pair, isNull);
    expect(auth.state, AuthState.unauthenticated);
  });
  testWidgets(
    'confirmation cancel sends no request and final confirmation sends one',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: LoginScreen(
            authSession: auth,
            deletion: controller,
            searchRoutes: (_) async => [],
            findStops: (_) async => [],
            alarmClient: EmptyAlarmClient(),
          ),
        ),
      );
      await tester.pump();
      await tester.tap(find.text('회원 탈퇴'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('취소'));
      await tester.pumpAndSettle();
      expect(client.calls, 0);
      await tester.tap(find.text('회원 탈퇴'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('탈퇴 확인'));
      await tester.pumpAndSettle();
      expect(client.calls, 1);
      expect(find.textContaining('탈퇴가 진행 중'), findsOneWidget);
    },
  );
}
