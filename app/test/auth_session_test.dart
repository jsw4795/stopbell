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
  int deletes = 0;
  @override
  Future<TokenPair?> read() async => pair;
  @override
  Future<void> save(TokenPair pair) async {
    if (saveError case final error?) throw error;
    this.pair = pair;
  }

  @override
  Future<void> delete() async {
    deletes++;
    pair = null;
  }
}

class FakeBackend extends BackendAuthClient {
  FakeBackend() : super(apiBaseUrl: Uri.parse('http://localhost:8080'));
  int refreshCalls = 0;
  Future<TokenPair> Function(String)? onRefresh;
  @override
  Future<TokenPair> refresh(String token) {
    refreshCalls++;
    return onRefresh!(token);
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
}
