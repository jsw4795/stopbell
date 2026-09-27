import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';

const oldPair = TokenPair(
  accessToken: 'old-access',
  refreshToken: 'old-refresh',
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

void main() {
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
    expect(storage.pair, same(newPair));
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
    expect(storage.pair, same(newPair));
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
