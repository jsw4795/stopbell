import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/app_config.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';

const oldPair = TokenPair(
  accessToken: 'old-access',
  refreshToken: 'old-refresh',
  sessionIdentity: 'fixture-restored-session',
);
const newPair = TokenPair(
  accessToken: 'new-access',
  refreshToken: 'new-refresh',
);

class FakeAuthenticator implements Authenticator {
  int calls = 0;
  @override
  Future<TokenPair> login() async {
    calls++;
    return newPair;
  }
}

class FakeStorage implements TokenPairStorage {
  FakeStorage({this.pair});
  TokenPair? pair;
  Object? saveError;
  Object? deleteError;
  int saves = 0;
  int deletes = 0;
  @override
  Future<TokenPair?> read() async => pair;
  @override
  Future<void> save(TokenPair pair) async {
    saves++;
    if (saveError case final error?) throw error;
    this.pair = pair;
  }

  @override
  Future<void> delete() async {
    deletes++;
    if (deleteError case final error?) throw error;
    pair = null;
  }
}

class FakeBackend extends BackendAuthClient {
  FakeBackend() : super(apiBaseUrl: Uri.parse('http://localhost:8080'));
  int refreshCalls = 0;
  int logoutCalls = 0;
  final logoutTokens = <String>[];
  Future<TokenPair> Function(String)? onRefresh;
  Future<void> Function(String)? onLogout;
  @override
  Future<TokenPair> refresh(String token) {
    refreshCalls++;
    return onRefresh!(token);
  }

  @override
  Future<void> logout(String token) {
    logoutCalls++;
    logoutTokens.add(token);
    return onLogout?.call(token) ?? Future<void>.value();
  }
}

AuthSession httpSession(BackendAuthClient backend, FakeStorage storage) {
  final session = AuthSession(
    authenticator: GoogleAuthService(
      config: const AppConfig(
        apiBaseUrl: 'http://localhost:8080',
        googleIosClientId: 'fixture-ios',
        googleServerClientId: 'fixture-server',
      ),
      backend: backend,
      googleIdTokenProvider: () async => 'fixture-google-id',
    ),
    backend: backend,
    tokenStorage: storage,
  );
  addTearDown(session.dispose);
  return session;
}

http.Response tokenResponse(TokenPair pair) => http.Response(
  jsonEncode({
    'accessToken': pair.accessToken,
    'refreshToken': pair.refreshToken,
  }),
  200,
);

void main() {
  testWidgets('unresponsive shared refresh releases logout at HTTP deadline', (
    tester,
  ) async {
    final response = Completer<http.Response>();
    final requests = <http.Request>[];
    final transport = MockClient((request) async {
      requests.add(request);
      return request.url.path == '/auth/refresh'
          ? response.future
          : http.Response('', 204);
    });
    addTearDown(transport.close);
    final storage = FakeStorage(pair: oldPair);
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: BackendAuthClient(
        apiBaseUrl: Uri.parse('http://localhost:8080'),
        client: transport,
      ),
      tokenStorage: storage,
    );
    addTearDown(session.dispose);
    await session.initialize();
    final failures = <Object>[];
    Future<void> refresh() => session
        .refreshAfterUnauthorized('old-access')
        .then<void>(
          (_) => fail('unresponsive refresh must fail'),
          onError: (Object error) => failures.add(error),
        );
    final first = refresh();
    final second = refresh();
    await tester.pump();
    var loggedOut = false;
    final logout = session.logout().then((_) => loggedOut = true);
    await tester.pump();
    expect(requests.map((r) => r.url.path), ['/auth/refresh']);
    expect(session.isLoggingOut, isTrue);
    await tester.pump(const Duration(seconds: 15));
    final completedAtDeadline = loggedOut;
    final failuresAtDeadline = List<Object>.of(failures);
    response.complete(
      http.Response(
        jsonEncode({
          'accessToken': 'late-access',
          'refreshToken': 'late-refresh',
        }),
        200,
      ),
    );
    await tester.pump();
    await Future.wait([first, second, logout]);

    expect(
      completedAtDeadline,
      isTrue,
      reason: 'logout still waiting for unresponsive refresh',
    );
    expect(failuresAtDeadline, hasLength(2));
    expect(failuresAtDeadline, everyElement(isA<TimeoutException>()));
    expect(requests.map((r) => r.url.path), ['/auth/refresh', '/auth/logout']);
    expect(jsonDecode(requests.last.body), {
      'refreshToken': oldPair.refreshToken,
    });
    expect(storage.saves, 0);
    expect(storage.deletes, 1);
    expect(storage.pair, isNull);
    expect(session.currentPair, isNull);
    expect(session.state, AuthState.unauthenticated);
    expect(session.isLoggingOut, isFalse);
  });

  for (final lateBeforeLogin in [true, false]) {
    testWidgets(
      'login timeout ignores late result before new login: $lateBeforeLogin',
      (tester) async {
        final lateLogin = Completer<http.Response>();
        var requests = 0;
        final transport = MockClient((request) async {
          expectSync(request.url.path, '/auth/google');
          expectSync(jsonDecode(request.body), {
            'idToken': 'fixture-google-id',
          });
          requests++;
          return requests == 1 ? lateLogin.future : tokenResponse(newPair);
        });
        addTearDown(transport.close);
        final storage = FakeStorage();
        final session = httpSession(
          BackendAuthClient(
            apiBaseUrl: Uri.parse('http://localhost:8080'),
            client: transport,
          ),
          storage,
        );
        await session.initialize();
        final initialGeneration = session.generation;
        final initialIdentity = session.sessionIdentity;
        final failed = expectLater(
          session.login(),
          throwsA(isA<TimeoutException>()),
        );
        await tester.pump();
        await tester.pump(const Duration(seconds: 15));
        await failed;
        expect(session.state, AuthState.unauthenticated);
        expect(session.currentPair, isNull);
        expect(storage.pair, isNull);
        expect(storage.saves, 0);
        expect(session.generation, initialGeneration);
        expect(session.sessionIdentity, initialIdentity);
        expect(requests, 1);

        if (lateBeforeLogin) {
          lateLogin.complete(tokenResponse(oldPair));
          await tester.pump();
          expect(session.state, AuthState.unauthenticated);
          expect(session.currentPair, isNull);
          expect(storage.pair, isNull);
          expect(storage.saves, 0);
          expect(session.generation, initialGeneration);
          expect(session.sessionIdentity, initialIdentity);
        }
        // A user starts a new login; the expired HTTP result must not replace it.
        await session.login();
        final identity = session.sessionIdentity;
        final generation = session.generation;
        expect(identity, isNot(initialIdentity));
        expect(generation, initialGeneration + 1);
        if (!lateBeforeLogin) lateLogin.complete(tokenResponse(oldPair));
        await tester.pump();
        expect(session.state, AuthState.authenticated);
        expect(session.accessToken, newPair.accessToken);
        expect(storage.pair?.refreshToken, newPair.refreshToken);
        expect(storage.saves, 1);
        expect(session.sessionIdentity, identity);
        expect(storage.pair?.sessionIdentity, identity);
        expect(session.generation, generation);
        expect(requests, 2);
      },
    );
  }

  for (final lateResult in ['success', 'HTTP 401', 'network error']) {
    testWidgets(
      'shared refresh timeout preserves pair and clears flight before late $lateResult',
      (tester) async {
        final oldRefresh = Completer<http.Response>();
        final nextRefresh = Completer<http.Response>();
        var requests = 0;
        final transport = MockClient((request) {
          expectSync(request.url.path, '/auth/refresh');
          expectSync(jsonDecode(request.body), {
            'refreshToken': oldPair.refreshToken,
          });
          requests++;
          return requests == 1 ? oldRefresh.future : nextRefresh.future;
        });
        addTearDown(transport.close);
        final storage = FakeStorage(pair: oldPair);
        final session = httpSession(
          BackendAuthClient(
            apiBaseUrl: Uri.parse('http://localhost:8080'),
            client: transport,
          ),
          storage,
        );
        await session.initialize();
        final generation = session.generation;
        final identity = session.sessionIdentity;
        final failures = <Object>[];
        Future<void> refresh() => session
            .refreshAfterUnauthorized('old-access')
            .then<void>(
              (_) => fail('unresponsive refresh must fail'),
              onError: (Object error) => failures.add(error),
            );
        final first = refresh();
        final second = refresh();
        await tester.pump();
        expect(requests, 1);
        await tester.pump(const Duration(seconds: 15));
        await Future.wait([first, second]);
        expect(failures, hasLength(2));
        expect(failures, everyElement(isA<TimeoutException>()));
        expect(failures.first, same(failures.last));
        expect(requests, 1); // No automatic POST retry.
        expect(session.currentPair, same(oldPair));
        expect(storage.pair, same(oldPair));
        expect(storage.saves, 0);
        expect(storage.deletes, 0);
        expect(session.state, AuthState.authenticated);
        expect(session.generation, generation);
        expect(session.sessionIdentity, identity);

        // A separate caller can start a new flight. This is not an automatic retry
        // or a guarantee that the old refresh token is still valid on the server.
        final next = session.refreshAfterUnauthorized('old-access');
        await tester.pump();
        expect(requests, 2);
        switch (lateResult) {
          case 'success':
            oldRefresh.complete(
              tokenResponse(
                const TokenPair(
                  accessToken: 'late-access',
                  refreshToken: 'late-refresh',
                ),
              ),
            );
          case 'HTTP 401':
            oldRefresh.complete(http.Response('', 401));
          default:
            oldRefresh.completeError(http.ClientException('late offline'));
        }
        await tester.pump();
        expect(session.currentPair, same(oldPair));
        expect(storage.pair, same(oldPair));
        expect(storage.saves, 0);
        expect(storage.deletes, 0);
        nextRefresh.complete(tokenResponse(newPair));
        await tester.pump();
        expect((await next).refreshToken, newPair.refreshToken);
        expect(storage.saves, 1);
        expect(storage.pair?.refreshToken, newPair.refreshToken);
        expect(storage.pair?.sessionIdentity, identity);
        expect(session.accessToken, newPair.accessToken);
        expect(session.generation, generation);
        expect(session.sessionIdentity, identity);
        expect(requests, 2);
      },
    );
  }

  testWidgets(
    'logout HTTP timeout keeps local logout and allows fresh login before late completion',
    (tester) async {
      final lateLogout = Completer<http.Response>();
      final requests = <http.Request>[];
      final transport = MockClient((request) async {
        requests.add(request);
        return request.url.path == '/auth/logout'
            ? lateLogout.future
            : tokenResponse(newPair);
      });
      addTearDown(transport.close);
      final storage = FakeStorage(pair: oldPair);
      final session = httpSession(
        BackendAuthClient(
          apiBaseUrl: Uri.parse('http://localhost:8080'),
          client: transport,
        ),
        storage,
      );
      await session.initialize();
      final identity = session.sessionIdentity;
      final generation = session.generation;
      final logout = session.logout();
      expect(session.logout(), same(logout));
      final failed = expectLater(
        logout,
        throwsA(isA<BackendLogoutCleanupException>()),
      );
      await tester.pump();
      expect(session.state, AuthState.unauthenticated);
      expect(session.currentPair, isNull);
      expect(storage.pair, isNull);
      expect(session.accessToken, isNull);
      expect(session.isLoggingOut, isTrue);
      await tester.pump(const Duration(seconds: 15));
      await failed;
      expect(session.isLoggingOut, isFalse);
      expect(storage.deletes, 1);
      expect(requests.map((r) => r.url.path), ['/auth/logout']);
      // Duplicate logout after failure does not send another POST.
      await session.logout();
      await session.login(); // Also verifies _loggingOut was cleared.
      final freshIdentity = session.sessionIdentity;
      expect(freshIdentity, isNot(identity));
      expect(session.generation, greaterThan(generation));
      lateLogout.complete(http.Response('', 204));
      await tester.pump();
      expect(session.state, AuthState.authenticated);
      expect(session.accessToken, newPair.accessToken);
      expect(session.sessionIdentity, freshIdentity);
      expect(storage.pair?.sessionIdentity, freshIdentity);
      expect(storage.pair?.refreshToken, newPair.refreshToken);
      expect(storage.saves, 1);
      expect(storage.deletes, 1);
      expect(requests.map((r) => r.url.path), ['/auth/logout', '/auth/google']);
    },
  );

  testWidgets(
    'logout waits for in-budget HTTP rotation and revokes rotated token without saving it',
    (tester) async {
      final response = Completer<http.Response>();
      final requests = <http.Request>[];
      final transport = MockClient((request) async {
        requests.add(request);
        return request.url.path == '/auth/refresh'
            ? response.future
            : http.Response('', 204);
      });
      addTearDown(transport.close);
      final storage = FakeStorage(pair: oldPair);
      final session = httpSession(
        BackendAuthClient(
          apiBaseUrl: Uri.parse('http://localhost:8080'),
          client: transport,
        ),
        storage,
      );
      await session.initialize();
      final failedRefresh = expectLater(
        session.refreshAfterUnauthorized('old-access'),
        throwsA(isA<StaleSessionException>()),
      );
      final logout = session.logout();
      await tester.pump();
      await tester.pump(const Duration(seconds: 14));
      expect(requests, hasLength(1));
      response.complete(tokenResponse(newPair));
      await tester.pump();
      await failedRefresh;
      await logout;
      expect(requests.map((r) => r.url.path), [
        '/auth/refresh',
        '/auth/logout',
      ]);
      expect(jsonDecode(requests.last.body), {
        'refreshToken': newPair.refreshToken,
      });
      expect(storage.saves, 0);
      expect(storage.deletes, 1);
      expect(storage.pair, isNull);
      expect(session.currentPair, isNull);
      expect(session.state, AuthState.unauthenticated);
      expect(session.isLoggingOut, isFalse);
    },
  );

  test(
    '기존 저장 Token Pair는 durable login identity를 한 번 보강하고 재시작에 보존한다',
    () async {
      final storage = FakeStorage(
        pair: const TokenPair(
          accessToken: 'legacy-access',
          refreshToken: 'legacy-refresh',
        ),
      );
      final first = AuthSession(
        authenticator: FakeAuthenticator(),
        backend: FakeBackend(),
        tokenStorage: storage,
      );
      await first.initialize();
      expect(storage.saves, 1);
      expect(storage.pair?.sessionIdentity, first.sessionIdentity);
      final restored = AuthSession(
        authenticator: FakeAuthenticator(),
        backend: FakeBackend(),
        tokenStorage: storage,
      );
      await restored.initialize();
      expect(restored.sessionIdentity, first.sessionIdentity);
      expect(storage.saves, 1);
    },
  );
  test(
    'malformed Secure Storage entry는 self-heal 뒤 unauthenticated로 복구한다',
    () async {
      FlutterSecureStorage.setMockInitialValues({});
      const secureStorage = FlutterSecureStorage();
      await secureStorage.write(
        key: 'auth_token_pair_v1',
        value: 'malformed-json',
      );
      final session = AuthSession(
        authenticator: FakeAuthenticator(),
        backend: FakeBackend(),
        tokenStorage: SecureTokenPairStorage(secureStorage: secureStorage),
      );
      await session.initialize();
      expect(session.state, AuthState.unauthenticated);
      expect(await secureStorage.read(key: 'auth_token_pair_v1'), isNull);
    },
  );

  test('startup은 빈 저장소와 정상 Pair를 복구하며 Google 로그인을 호출하지 않는다', () async {
    final auth = FakeAuthenticator();
    final backend = FakeBackend();
    final empty = AuthSession(
      authenticator: auth,
      backend: backend,
      tokenStorage: FakeStorage(),
    );
    expect(empty.state, AuthState.initializing);
    await empty.initialize();
    expect(empty.state, AuthState.unauthenticated);
    expect(empty.currentPair, isNull);

    final stored = AuthSession(
      authenticator: auth,
      backend: backend,
      tokenStorage: FakeStorage(pair: oldPair),
    );
    expect(stored.state, AuthState.initializing);
    await stored.initialize();
    expect(stored.state, AuthState.authenticated);
    expect(stored.currentPair, same(oldPair));
    expect(auth.calls, 0);
  });

  test('login은 저장 완료 뒤에만 current Pair와 인증 상태를 변경한다', () async {
    final storage = FakeStorage();
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: FakeBackend(),
      tokenStorage: storage,
    );
    await session.initialize();
    await session.login();
    expect(storage.pair?.accessToken, newPair.accessToken);
    expect(storage.pair?.refreshToken, newPair.refreshToken);
    expect(storage.pair?.sessionIdentity, session.sessionIdentity);
    expect(session.currentPair, same(newPair));
    expect(session.state, AuthState.authenticated);
  });

  test('login 저장 실패는 인증 성공이 아니다', () async {
    final storage = FakeStorage()..saveError = StateError('unavailable');
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: FakeBackend(),
      tokenStorage: storage,
    );
    await session.initialize();
    await expectLater(session.login(), throwsStateError);
    expect(session.currentPair, isNull);
    expect(session.state, AuthState.unauthenticated);
  });

  test('rotation은 새 Pair를 저장하고 current Pair를 교체한다', () async {
    final storage = FakeStorage(pair: oldPair);
    final backend = FakeBackend()
      ..onRefresh = (token) async {
        expect(token, 'old-refresh');
        return newPair;
      };
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: backend,
      tokenStorage: storage,
    );
    await session.initialize();
    expect(await session.refreshAfterUnauthorized('old-access'), same(newPair));
    expect(storage.pair?.accessToken, newPair.accessToken);
    expect(storage.pair?.refreshToken, newPair.refreshToken);
    expect(storage.pair?.sessionIdentity, session.sessionIdentity);
    expect(session.currentPair, same(newPair));
    expect(session.state, AuthState.authenticated);
    expect(backend.refreshCalls, 1);
  });

  test('refresh 401은 Pair를 삭제하고 unauthenticated로 전환한다', () async {
    final storage = FakeStorage(pair: oldPair);
    final backend = FakeBackend()
      ..onRefresh = (_) async =>
          throw const BackendAuthException('auth failed', statusCode: 401);
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: backend,
      tokenStorage: storage,
    );
    await session.initialize();
    await expectLater(
      session.refreshAfterUnauthorized('old-access'),
      throwsA(isA<BackendAuthException>()),
    );
    expect(session.currentPair, isNull);
    expect(storage.pair, isNull);
    expect(storage.deletes, 1);
    expect(session.state, AuthState.unauthenticated);
  });

  for (final failure in [
    StateError('offline'),
    const BackendAuthException('server failed', statusCode: 503),
    const BackendAuthException('bad request', statusCode: 400),
  ]) {
    test(
      'refresh transient or unspecified failure preserves Pair: ${failure.runtimeType}',
      () async {
        final storage = FakeStorage(pair: oldPair);
        final backend = FakeBackend()..onRefresh = (_) async => throw failure;
        final session = AuthSession(
          authenticator: FakeAuthenticator(),
          backend: backend,
          tokenStorage: storage,
        );
        await session.initialize();
        await expectLater(
          session.refreshAfterUnauthorized('old-access'),
          throwsA(same(failure)),
        );
        expect(session.currentPair, same(oldPair));
        expect(storage.pair, same(oldPair));
        expect(session.state, AuthState.authenticated);
      },
    );
  }

  test('rotation 저장 실패는 폐기된 old Pair를 성공 상태로 남기지 않는다', () async {
    final storage = FakeStorage(pair: oldPair)
      ..saveError = StateError('unavailable');
    final backend = FakeBackend()..onRefresh = (_) async => newPair;
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: backend,
      tokenStorage: storage,
    );
    await session.initialize();
    await expectLater(
      session.refreshAfterUnauthorized('old-access'),
      throwsA(isA<AuthSessionPersistenceException>()),
    );
    expect(storage.pair, isNull);
    expect(session.currentPair, isNull);
    expect(session.state, AuthState.unauthenticated);
  });

  test('동시 refresh는 하나의 Future를 공유하고 늦은 old 401은 새 Pair를 재사용한다', () async {
    final gate = Completer<TokenPair>();
    final backend = FakeBackend()..onRefresh = (_) => gate.future;
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: backend,
      tokenStorage: FakeStorage(pair: oldPair),
    );
    await session.initialize();
    final first = session.refreshAfterUnauthorized('old-access');
    final second = session.refreshAfterUnauthorized('old-access');
    expect(backend.refreshCalls, 1);
    gate.complete(newPair);
    expect(await first, same(newPair));
    expect(await second, same(newPair));
    expect(await session.refreshAfterUnauthorized('old-access'), same(newPair));
    expect(backend.refreshCalls, 1);
  });

  test('logout은 current refresh token을 한 번 전송하고 local pair를 제거한다', () async {
    final storage = FakeStorage(pair: oldPair);
    final backend = FakeBackend();
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: backend,
      tokenStorage: storage,
    );
    await session.initialize();
    await session.logout();
    expect(backend.logoutTokens, ['old-refresh']);
    expect(storage.pair, isNull);
    expect(storage.deletes, 1);
    expect(session.currentPair, isNull);
    expect(session.state, AuthState.unauthenticated);
    final restored = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: backend,
      tokenStorage: storage,
    );
    await restored.initialize();
    expect(restored.state, AuthState.unauthenticated);
  });

  test('duplicate logout은 진행 중인 작업을 공유하고 서버 요청을 반복하지 않는다', () async {
    final gate = Completer<void>();
    final backend = FakeBackend()..onLogout = (_) => gate.future;
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: backend,
      tokenStorage: FakeStorage(pair: oldPair),
    );
    await session.initialize();
    final first = session.logout();
    final second = session.logout();
    expect(identical(first, second), isTrue);
    await Future<void>.delayed(Duration.zero);
    expect(backend.logoutCalls, 1);
    gate.complete();
    await Future.wait([first, second]);
    await session.logout();
    expect(backend.logoutCalls, 1);
    expect(session.state, AuthState.unauthenticated);
  });

  test(
    'refresh 중 logout은 rotated token을 revoke하고 late pair를 저장하지 않는다',
    () async {
      final gate = Completer<TokenPair>();
      final storage = FakeStorage(pair: oldPair);
      final backend = FakeBackend()..onRefresh = (_) => gate.future;
      final session = AuthSession(
        authenticator: FakeAuthenticator(),
        backend: backend,
        tokenStorage: storage,
      );
      await session.initialize();
      final oldGeneration = session.generation;
      final refresh = session.refreshAfterUnauthorized('old-access');
      final logout = session.logout();
      expect(session.isCurrentGeneration(oldGeneration), isFalse);
      expect(session.accessToken, isNull);
      await expectLater(
        session.refreshAfterUnauthorized(
          'old-access',
          generation: oldGeneration,
        ),
        throwsA(isA<StaleSessionException>()),
      );
      gate.complete(newPair);
      await expectLater(refresh, throwsA(isA<StaleSessionException>()));
      await logout;
      expect(backend.refreshCalls, 1);
      expect(backend.logoutTokens, ['new-refresh']);
      expect(storage.saves, 0);
      expect(storage.pair, isNull);
      expect(session.currentPair, isNull);
      expect(session.state, AuthState.unauthenticated);
    },
  );

  for (final failure in [
    StateError('offline'),
    const BackendAuthException('server failed', statusCode: 503),
  ]) {
    test(
      'backend logout failure 뒤에도 local logout 완료: ${failure.runtimeType}',
      () async {
        final storage = FakeStorage(pair: oldPair);
        final backend = FakeBackend()..onLogout = (_) async => throw failure;
        final session = AuthSession(
          authenticator: FakeAuthenticator(),
          backend: backend,
          tokenStorage: storage,
        );
        await session.initialize();
        await expectLater(
          session.logout(),
          throwsA(isA<BackendLogoutCleanupException>()),
        );
        expect(storage.pair, isNull);
        expect(session.currentPair, isNull);
        expect(session.state, AuthState.unauthenticated);
      },
    );
  }

  test('beforeLogout hook은 현재 인증을 사용할 수 있고 실패해도 logout한다', () async {
    final backend = FakeBackend();
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: backend,
      tokenStorage: FakeStorage(pair: oldPair),
    );
    await session.initialize();
    await session.logout(
      beforeLogout: () async {
        expect(session.accessToken, 'old-access');
        expect(session.state, AuthState.authenticated);
        expect(backend.logoutCalls, 0);
        throw StateError('device offline');
      },
    );
    expect(backend.logoutCalls, 1);
    expect(session.state, AuthState.unauthenticated);
  });

  test('storage delete failure는 backend revoke 전에 중단하고 인증을 유지한다', () async {
    final storage = FakeStorage(pair: oldPair)
      ..deleteError = StateError('keychain unavailable');
    final backend = FakeBackend();
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: backend,
      tokenStorage: storage,
    );
    await session.initialize();
    await expectLater(
      session.logout(),
      throwsA(isA<AuthSessionPersistenceException>()),
    );
    expect(backend.logoutCalls, 0);
    expect(storage.pair, same(oldPair));
    expect(session.currentPair, same(oldPair));
    expect(session.state, AuthState.authenticated);
    storage.deleteError = null;
    await session.logout();
    expect(session.state, AuthState.unauthenticated);
  });

  test('logout 뒤 새 login은 새 generation으로 인증된다', () async {
    final storage = FakeStorage(pair: oldPair);
    final session = AuthSession(
      authenticator: FakeAuthenticator(),
      backend: FakeBackend(),
      tokenStorage: storage,
    );
    await session.initialize();
    final oldGeneration = session.generation;
    await session.logout();
    await session.login();
    expect(session.generation, greaterThan(oldGeneration));
    expect(session.currentPair, same(newPair));
    expect(session.state, AuthState.authenticated);
    expect(session.isCurrentGeneration(oldGeneration), isFalse);
  });
}
