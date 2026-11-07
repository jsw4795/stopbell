import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/alarm/alarm_creation_intent.dart';
import 'package:stopbell/features/auth/account_deletion_client.dart';
import 'package:stopbell/features/auth/account_deletion_controller.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/boarding/boarding_creation_intent.dart';
import 'package:stopbell/main.dart';

import 'alarm_test_fakes.dart';
import 'auth_session_test.dart' as auth_fake;
import 'boarding_session_test_fakes.dart' show MemoryBoardingIntents;

class MemoryDeletion implements DeletionStorage {
  final records = <String, DeletionRecord>{};
  String? notice;
  DeletionRecord? legacy;
  bool fail = false;
  bool failDelete = false;
  DeletionRecord? get value => records['1'];
  set value(DeletionRecord? record) {
    if (record == null) {
      records.remove('1');
    } else {
      records['1'] = record;
    }
  }

  @override
  Future<DeletionRecord?> read(String account, String session) async {
    if (legacy?.session == session && records[account] == null) {
      records[account] = legacy!;
      legacy = null;
    }
    return records[account];
  }

  @override
  Future<(String, DeletionRecord)?> readNotice() async =>
      notice == null ? null : (notice!, records[notice!]!);
  @override
  Future<DeletionRecord> saveIfAbsent(
    String account,
    DeletionRecord value,
  ) async {
    if (fail) throw StateError('storage');
    return records.putIfAbsent(account, () => value);
  }

  @override
  Future<void> save(String account, DeletionRecord value) async {
    if (fail) throw StateError('storage');
    if (records[account]?.receipt != value.receipt) throw StateError('changed');
    records[account] = value;
    if (value.confirmed) notice = account;
  }

  @override
  Future<void> delete(String account, String receipt) async {
    if (fail || failDelete) throw StateError('storage');
    if (records[account]?.receipt != receipt) throw StateError('changed');
    records.remove(account);
    if (notice == account) notice = null;
  }
}

String tokenFor(String id) =>
    'header.${base64Url.encode(utf8.encode(jsonEncode({'sub': id}))).replaceAll('=', '')}.signature';
TokenPair pairFor(String id, {String? session}) => TokenPair(
  accessToken: tokenFor(id),
  refreshToken: 'refresh-$id',
  sessionIdentity: session,
);

class AccountAuthenticator implements Authenticator {
  AccountAuthenticator(this.id);
  final String id;
  @override
  Future<TokenPair> login() async => pairFor(id);
}

class FailingMigrationStorage extends FlutterSecureStorage {
  bool fail = true;
  @override
  Future<void> write({
    required String key,
    required String? value,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    if (fail && key.startsWith('account_deletion_v2_')) {
      throw StateError('write');
    }
    await super.write(key: key, value: value);
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
    tokens = auth_fake.FakeStorage(
      pair: pairFor('1', session: 'fixture-restored-session'),
    );
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
    storage.legacy = DeletionRecord(
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
  test('confirmed cleanup failure remains recoverable after credentials are removed', () async {
    storage.failDelete = true;
    client.result = const AccountDeletionResult(AccountDeletionStatus.deleted);
    client.state = const AccountDeletionResult(AccountDeletionStatus.deleted);
    await controller.start();
    expect(tokens.pair, isNull);
    expect(controller.confirmed, true);
    expect(controller.message, contains('정리하지 못했습니다'));
    final restoredAuth = auth_fake.httpSession(backend, tokens);
    await restoredAuth.initialize();
    storage.failDelete = false;
    final restored = AccountDeletionController(
      auth: restoredAuth,
      client: client,
      storage: storage,
      alarms: alarms,
      boarding: boarding,
    );
    addTearDown(restored.dispose);
    await restored.initialize();
    expect(storage.value, isNull);
    expect(restored.active, false);
  });

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
    await controller.initialize();
    final gate = Completer<TokenPair>();
    backend.onRefresh = (_) => gate.future;
    final refresh = auth.refreshAfterUnauthorized(tokenFor('1'));
    final request = controller.start();
    expect(auth.isDeletingAccount, true);
    gate.complete(pairFor('1'));
    await refresh;
    client.result = const AccountDeletionResult(
      AccountDeletionStatus.manualAppleRevocationRequired,
    );
    await request;
    expect(tokens.pair, isNull);
    expect(auth.state, AuthState.unauthenticated);
  });
  test('account switch isolates receipts and restores the same account on a new session', () async {
    await controller.initialize();
    client.result = const AccountDeletionResult(AccountDeletionStatus.rejected);
    await controller.start();
    final first = storage.records['1']!;
    expect(controller.message, contains('서버에서'));
    auth.cancelAccountDeletionPreparation();
    await auth.logout();
    await auth.login(authenticator: AccountAuthenticator('2'));
    await controller.initialize();
    expect(controller.active, false);
    expect(controller.message, isNull);
    client.result = const AccountDeletionResult(AccountDeletionStatus.pending);
    await controller.start();
    final second = storage.records['2']!;
    expect(client.calls, 2);
    expect(client.sentReceipt, second.receipt);
    expect(second.receipt, isNot(first.receipt));
    expect(storage.records['1']!.receipt, first.receipt);
    auth.cancelAccountDeletionPreparation();
    await auth.logout();
    await auth.login(authenticator: AccountAuthenticator('1'));
    await controller.initialize();
    expect(controller.active, true);
    expect(storage.records['1']!.receipt, first.receipt);
    expect(auth.sessionIdentity, isNot(first.session));
    await controller.start();
    expect(client.calls, 2);
    await controller.retry();
    expect(client.sentReceipt, first.receipt);
  });

  test(
    'late response from previous account cannot change the new account',
    () async {
      await controller.initialize();
      client.gate = Completer<AccountDeletionResult>();
      final oldRequest = controller.start();
      await Future<void>.delayed(Duration.zero);
      final oldReceipt = storage.records['1']!.receipt;
      auth.cancelAccountDeletionPreparation();
      await auth.logout();
      await auth.login(authenticator: AccountAuthenticator('2'));
      await controller.initialize();
      expect(controller.message, isNull);
      client.gate!.complete(
        const AccountDeletionResult(AccountDeletionStatus.rejected),
      );
      await oldRequest;
      expect(controller.message, isNull);
      expect(controller.status, isNull);
      expect(controller.active, false);
      expect(storage.records['1']!.receipt, oldReceipt);
    },
  );

  test('secure records survive new storage instances and migrate only a matching legacy session', () async {
    final legacy = DeletionRecord(receipt: 'A' * 43, session: 'session-a');
    FlutterSecureStorage.setMockInitialValues({
      'account_deletion_v1': jsonEncode(legacy.toJson()),
    });
    const secure = FlutterSecureStorage();
    const first = SecureDeletionStorage(origin: 'https://example.test');
    expect(await first.read('2', 'session-b'), isNull);
    final second = DeletionRecord(receipt: 'B' * 43, session: 'session-b');
    await first.saveIfAbsent('2', second);
    expect(await secure.read(key: 'account_deletion_v1'), isNotNull);
    expect((await first.read('1', 'session-a'))!.receipt, legacy.receipt);
    expect(await secure.read(key: 'account_deletion_v1'), isNull);
    const restarted = SecureDeletionStorage(origin: 'https://example.test');
    expect((await restarted.read('1', 'new-session'))!.receipt, legacy.receipt);
    expect((await restarted.read('2', 'new-session'))!.receipt, second.receipt);
  });

  test('failed legacy migration retains original and concurrent creation cannot overwrite', () async {
    final legacy = DeletionRecord(receipt: 'A' * 43, session: 'session-a');
    final raw = jsonEncode(legacy.toJson());
    FlutterSecureStorage.setMockInitialValues({'account_deletion_v1': raw});
    final failing = FailingMigrationStorage();
    final secure = SecureDeletionStorage(
      origin: 'https://example.test',
      storage: failing,
    );
    await expectLater(secure.read('1', 'session-a'), throwsStateError);
    expect(await failing.read(key: 'account_deletion_v1'), raw);
    failing.fail = false;
    expect((await secure.read('1', 'session-a'))!.receipt, legacy.receipt);
    final first = DeletionRecord(receipt: 'B' * 43, session: 'b');
    final second = DeletionRecord(receipt: 'C' * 43, session: 'c');
    final results = await Future.wait([
      secure.saveIfAbsent('2', first),
      const SecureDeletionStorage(origin: 'https://example.test')
          .saveIfAbsent('2', second),
    ]);
    expect(results[0].receipt, first.receipt);
    expect(results[1].receipt, first.receipt);
    expect((await secure.read('2', 'b'))!.receipt, first.receipt);
  });

  test(
    'same user ID on different backend origins has independent receipts',
    () async {
      FlutterSecureStorage.setMockInitialValues({});
      const first = SecureDeletionStorage(origin: 'https://first.test');
      const second = SecureDeletionStorage(origin: 'https://second.test');
      final a = DeletionRecord(receipt: 'A' * 43, session: 'a');
      final b = DeletionRecord(receipt: 'B' * 43, session: 'b');
      await first.saveIfAbsent('1', a);
      expect(await second.read('1', 'b'), isNull);
      await second.saveIfAbsent('1', b);
      expect((await first.read('1', 'a'))!.receipt, a.receipt);
      expect((await second.read('1', 'b'))!.receipt, b.receipt);
    },
  );

  test('manual notices for multiple accounts survive and are cleaned independently', () async {
    FlutterSecureStorage.setMockInitialValues({});
    const secure = SecureDeletionStorage(origin: 'https://example.test');
    final a = DeletionRecord(receipt: 'A' * 43, session: 'a');
    final b = DeletionRecord(receipt: 'B' * 43, session: 'b');
    await secure.saveIfAbsent('1', a);
    await secure.saveIfAbsent('2', b);
    final manualA = DeletionRecord(
      receipt: a.receipt,
      session: a.session,
      confirmed: true,
      manual: true,
    );
    final manualB = DeletionRecord(
      receipt: b.receipt,
      session: b.session,
      confirmed: true,
      manual: true,
    );
    await secure.save('1', manualA);
    await secure.save('2', manualB);
    expect(
      (await const SecureDeletionStorage(
        origin: 'https://example.test',
      ).readNotice())!.$1,
      '1',
    );
    await secure.delete('1', a.receipt);
    expect(
      (await const SecureDeletionStorage(
        origin: 'https://example.test',
      ).readNotice())!.$1,
      '2',
    );
    await secure.delete('2', b.receipt);
    expect(await secure.readNotice(), isNull);
  });

  testWidgets('logged out screen does not offer account deletion', (
    tester,
  ) async {
    await auth.logout();
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
    expect(find.text('회원 탈퇴'), findsNothing);
  });

  testWidgets(
    'confirmation cancel sends no request and final confirmation sends one',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.dark,
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
      final confirm = tester.widget<TextButton>(
        find.widgetWithText(TextButton, '탈퇴 확인'),
      );
      expect(
        confirm.style?.foregroundColor?.resolve({}),
        StopBellTheme.dark.colorScheme.error,
      );
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
