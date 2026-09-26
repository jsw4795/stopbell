import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
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
final baseUrl = Uri.parse('http://localhost:8080');

class FakeStorage implements TokenPairStorage {
  FakeStorage(this.pair);
  TokenPair? pair;
  @override
  Future<TokenPair?> read() async => pair;
  @override
  Future<void> save(TokenPair pair) async => this.pair = pair;
  @override
  Future<void> delete() async => pair = null;
}

class FakeAuthenticator implements Authenticator {
  @override
  Future<TokenPair> login() async => oldPair;
}

class FakeBackend extends BackendAuthClient {
  FakeBackend() : super(apiBaseUrl: baseUrl);
  int calls = 0;
  Future<TokenPair> Function()? refreshResult;
  @override
  Future<TokenPair> refresh(String token) {
    expect(token, 'old-refresh');
    calls++;
    return refreshResult!();
  }
}

Future<AuthSession> session(FakeStorage storage, FakeBackend backend) async {
  final result = AuthSession(
    authenticator: FakeAuthenticator(),
    backend: backend,
    tokenStorage: storage,
  );
  await result.initialize();
  return result;
}

void main() {
  test(
    'current token overrides caller header and JSON body passes through',
    () async {
      final authSession = await session(FakeStorage(oldPair), FakeBackend());
      var sends = 0;
      final client = AuthenticatedApiClient(
        apiBaseUrl: baseUrl,
        authSession: authSession,
        client: MockClient((request) async {
          sends++;
          expect(request.url.path, '/api/v1/fixture');
          expect(request.headers['authorization'], 'Bearer old-access');
          expect(request.headers['content-type'], 'application/json');
          expect(request.body, '{"value":1}');
          return http.Response('{"id":1}', 201);
        }),
      );
      final response = await client.request(
        'POST',
        '/api/v1/fixture',
        headers: {
          'Content-Type': 'application/json',
          'aUtHoRiZaTiOn': 'Bearer wrong-access',
        },
        body: '{"value":1}',
      );
      expect(response.statusCode, 201);
      expect(response.body, '{"id":1}');
      expect(sends, 1);
    },
  );

  test(
    'unauthenticated request fails locally without network traffic',
    () async {
      final authSession = await session(FakeStorage(null), FakeBackend());
      var sends = 0;
      final client = AuthenticatedApiClient(
        apiBaseUrl: baseUrl,
        authSession: authSession,
        client: MockClient((_) async {
          sends++;
          return http.Response('', 200);
        }),
      );
      await expectLater(
        client.request('GET', '/api/v1/fixture'),
        throwsA(isA<UnauthenticatedApiException>()),
      );
      expect(sends, 0);
    },
  );

  test('auth endpoints cannot enter protected retry flow', () async {
    final authSession = await session(FakeStorage(oldPair), FakeBackend());
    var sends = 0;
    final client = AuthenticatedApiClient(
      apiBaseUrl: baseUrl,
      authSession: authSession,
      client: MockClient((_) async {
        sends++;
        return http.Response('', 401);
      }),
    );
    for (final path in ['/auth/google', '/auth/refresh', '/auth/logout']) {
      await expectLater(client.request('POST', path), throwsArgumentError);
    }
    expect(sends, 0);
  });

  for (final status in [200, 400, 403, 404, 409, 500]) {
    test('response $status passes through without refresh', () async {
      final backend = FakeBackend();
      final authSession = await session(FakeStorage(oldPair), backend);
      var sends = 0;
      final client = AuthenticatedApiClient(
        apiBaseUrl: baseUrl,
        authSession: authSession,
        client: MockClient((_) async {
          sends++;
          return http.Response('', status);
        }),
      );
      expect(
        (await client.request('GET', '/api/v1/fixture')).statusCode,
        status,
      );
      expect(sends, 1);
      expect(backend.calls, 0);
    });
  }

  test(
    '401 refreshes then retries once with rotated token, including a retry 401',
    () async {
      final backend = FakeBackend()..refreshResult = () async => newPair;
      final storage = FakeStorage(oldPair);
      final authSession = await session(storage, backend);
      final headers = <String>[];
      final client = AuthenticatedApiClient(
        apiBaseUrl: baseUrl,
        authSession: authSession,
        client: MockClient((request) async {
          headers.add(request.headers['authorization']!);
          expect(request.method, 'POST');
          expect(request.body, '{"value":1}');
          return http.Response('', headers.length <= 2 ? 401 : 200);
        }),
      );
      expect(
        (await client.request(
          'POST',
          '/api/v1/fixture',
          body: '{"value":1}',
        )).statusCode,
        401,
      );
      expect(headers, ['Bearer old-access', 'Bearer new-access']);
      expect(backend.calls, 1);
      expect(storage.pair, same(newPair));
      expect(
        (await client.request(
          'POST',
          '/api/v1/fixture',
          body: '{"value":1}',
        )).statusCode,
        200,
      );
      expect(headers.last, 'Bearer new-access');
      expect(backend.calls, 1);
    },
  );

  test(
    'refresh 401 clears session and does not retry protected request',
    () async {
      final backend = FakeBackend()
        ..refreshResult = () async =>
            throw const BackendAuthException('expired', statusCode: 401);
      final storage = FakeStorage(oldPair);
      final authSession = await session(storage, backend);
      var sends = 0;
      final client = AuthenticatedApiClient(
        apiBaseUrl: baseUrl,
        authSession: authSession,
        client: MockClient((_) async {
          sends++;
          return http.Response('', 401);
        }),
      );
      await expectLater(
        client.request('GET', '/api/v1/fixture'),
        throwsA(isA<BackendAuthException>()),
      );
      expect(sends, 1);
      expect(authSession.state, AuthState.unauthenticated);
      expect(storage.pair, isNull);
    },
  );

  for (final failure in [
    StateError('offline'),
    const BackendAuthException('server unavailable', statusCode: 503),
  ]) {
    test(
      'refresh failure reaches caller and keeps session: ${failure.runtimeType}',
      () async {
        final backend = FakeBackend()
          ..refreshResult = () async => throw failure;
        final storage = FakeStorage(oldPair);
        final authSession = await session(storage, backend);
        var sends = 0;
        final client = AuthenticatedApiClient(
          apiBaseUrl: baseUrl,
          authSession: authSession,
          client: MockClient((_) async {
            sends++;
            return http.Response('', 401);
          }),
        );
        await expectLater(
          client.request('GET', '/api/v1/fixture'),
          throwsA(same(failure)),
        );
        expect(sends, 1);
        expect(storage.pair, same(oldPair));
        expect(authSession.state, AuthState.authenticated);
      },
    );
  }

  test(
    'concurrent 401 responses share one refresh and each retry once',
    () async {
      final gate = Completer<TokenPair>();
      final backend = FakeBackend()..refreshResult = () => gate.future;
      final authSession = await session(FakeStorage(oldPair), backend);
      final headers = <String>[];
      final client = AuthenticatedApiClient(
        apiBaseUrl: baseUrl,
        authSession: authSession,
        client: MockClient((request) async {
          final header = request.headers['authorization']!;
          headers.add(header);
          return http.Response('', header == 'Bearer old-access' ? 401 : 200);
        }),
      );
      final requests = [
        client.request('GET', '/api/v1/a'),
        client.request('GET', '/api/v1/b'),
      ];
      await Future<void>.delayed(Duration.zero);
      expect(backend.calls, 1);
      gate.complete(newPair);
      expect((await Future.wait(requests)).map((r) => r.statusCode), [
        200,
        200,
      ]);
      expect(headers.where((h) => h == 'Bearer old-access').length, 2);
      expect(headers.where((h) => h == 'Bearer new-access').length, 2);
      expect(backend.calls, 1);
    },
  );

  test(
    'late old-token 401 uses current token without a second refresh',
    () async {
      final late = Completer<http.Response>();
      final backend = FakeBackend()..refreshResult = () async => newPair;
      final authSession = await session(FakeStorage(oldPair), backend);
      final headers = <String>[];
      final client = AuthenticatedApiClient(
        apiBaseUrl: baseUrl,
        authSession: authSession,
        client: MockClient((request) async {
          final header = request.headers['authorization']!;
          headers.add(header);
          if (request.url.path == '/api/v1/late' &&
              header == 'Bearer old-access') {
            return late.future;
          }
          return http.Response('', header == 'Bearer old-access' ? 401 : 200);
        }),
      );
      final delayed = client.request('GET', '/api/v1/late');
      await Future<void>.delayed(Duration.zero);
      expect((await client.request('GET', '/api/v1/early')).statusCode, 200);
      late.complete(http.Response('', 401));
      expect((await delayed).statusCode, 200);
      expect(backend.calls, 1);
      expect(headers.where((h) => h == 'Bearer new-access').length, 2);
    },
  );
}
