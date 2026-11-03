import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';
import 'package:stopbell/features/auth/apple_auth_service.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';

const nonce = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ';
const identity = 'fixture-id-token';
const code = 'fixture-code';

AuthorizationCredentialAppleID credential({
  String? token = identity,
  String authorizationCode = code,
}) => AuthorizationCredentialAppleID(
  userIdentifier: null,
  givenName: null,
  familyName: null,
  email: null,
  state: null,
  identityToken: token,
  authorizationCode: authorizationCode,
);

void main() {
  test(
    'server nonce is passed unchanged to native request and backend',
    () async {
      final requests = <http.Request>[];
      final client = MockClient((request) async {
        requests.add(request);
        if (request.url.path.endsWith('/challenge')) {
          return http.Response(jsonEncode({'nonce': nonce}), 200);
        }
        expect(jsonDecode(request.body), {
          'identityToken': identity,
          'authorizationCode': code,
          'nonce': nonce,
        });
        return http.Response(
          jsonEncode({'accessToken': 'access', 'refreshToken': 'refresh'}),
          200,
        );
      });
      addTearDown(client.close);
      final service = AppleAuthService(
        backend: BackendAuthClient(
          apiBaseUrl: Uri.parse('https://example.test'),
          client: client,
        ),
        isSupported: () => true,
        credentialProvider: (value) async {
          expect(value, nonce);
          return credential();
        },
      );
      final pair = await service.login();
      expect(pair.accessToken, 'access');
      expect(pair.refreshToken, 'refresh');
      expect(requests.map((r) => r.url.path), [
        '/auth/apple/challenge',
        '/auth/apple',
      ]);
    },
  );

  for (final status in [401, 404, 503]) {
    test('challenge $status stops before native authentication', () async {
      var nativeCalls = 0;
      final client = MockClient((_) async => http.Response('', status));
      addTearDown(client.close);
      final service = AppleAuthService(
        backend: BackendAuthClient(
          apiBaseUrl: Uri.parse('https://example.test'),
          client: client,
        ),
        isSupported: () => true,
        credentialProvider: (_) async {
          nativeCalls++;
          return credential();
        },
      );
      await expectLater(
        service.login(),
        throwsA(
          isA<BackendAuthException>().having(
            (e) => e.statusCode,
            'status',
            status,
          ),
        ),
      );
      expect(nativeCalls, 0);
    });
    test('backend Apple login $status is not retried', () async {
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        return request.url.path.endsWith('/challenge')
            ? http.Response(jsonEncode({'nonce': nonce}), 200)
            : http.Response('', status);
      });
      addTearDown(client.close);
      final service = AppleAuthService(
        backend: BackendAuthClient(
          apiBaseUrl: Uri.parse('https://example.test'),
          client: client,
        ),
        isSupported: () => true,
        credentialProvider: (_) async => credential(),
      );
      await expectLater(
        service.login(),
        throwsA(
          isA<BackendAuthException>().having(
            (e) => e.statusCode,
            'status',
            status,
          ),
        ),
      );
      expect(calls, 2);
    });
  }

  for (final testCase in [
    (AuthorizationErrorCode.canceled, true),
    (AuthorizationErrorCode.failed, false),
  ]) {
    test('native ${testCase.$1} is classified safely', () async {
      final client = MockClient(
        (_) async => http.Response(jsonEncode({'nonce': nonce}), 200),
      );
      addTearDown(client.close);
      final service = AppleAuthService(
        backend: BackendAuthClient(
          apiBaseUrl: Uri.parse('https://example.test'),
          client: client,
        ),
        isSupported: () => true,
        credentialProvider: (_) async =>
            throw SignInWithAppleAuthorizationException(
              code: testCase.$1,
              message: 'sensitive',
            ),
      );
      await expectLater(
        service.login(),
        throwsA(
          testCase.$2
              ? isA<AppleLoginCanceledException>()
              : isA<LoginException>(),
        ),
      );
    });
  }

  for (final invalid in [
    credential(token: null),
    credential(authorizationCode: ''),
  ]) {
    test('missing native credential is rejected before exchange', () async {
      var calls = 0;
      final client = MockClient((_) async {
        calls++;
        return http.Response(jsonEncode({'nonce': nonce}), 200);
      });
      addTearDown(client.close);
      final service = AppleAuthService(
        backend: BackendAuthClient(
          apiBaseUrl: Uri.parse('https://example.test'),
          client: client,
        ),
        isSupported: () => true,
        credentialProvider: (_) async => invalid,
      );
      await expectLater(service.login(), throwsA(isA<LoginException>()));
      expect(calls, 1);
    });
  }

  test('unsupported platform stops before network', () async {
    var calls = 0;
    final client = MockClient((_) async {
      calls++;
      return http.Response('', 500);
    });
    addTearDown(client.close);
    final service = AppleAuthService(
      backend: BackendAuthClient(
        apiBaseUrl: Uri.parse('https://example.test'),
        client: client,
      ),
      isSupported: () => false,
    );
    await expectLater(service.login(), throwsA(isA<LoginException>()));
    expect(calls, 0);
  });

  test('malformed challenge stops before native authentication', () async {
    var nativeCalls = 0;
    final client = MockClient(
      (_) async => http.Response('{"nonce":"invalid"}', 200),
    );
    addTearDown(client.close);
    final service = AppleAuthService(
      backend: BackendAuthClient(
        apiBaseUrl: Uri.parse('https://example.test'),
        client: client,
      ),
      isSupported: () => true,
      credentialProvider: (_) async {
        nativeCalls++;
        return credential();
      },
    );
    await expectLater(service.login(), throwsA(isA<BackendAuthException>()));
    expect(nativeCalls, 0);
  });

  test(
    'challenge network failure stops before native authentication',
    () async {
      var nativeCalls = 0;
      final client = MockClient(
        (_) async => throw http.ClientException('offline'),
      );
      addTearDown(client.close);
      final service = AppleAuthService(
        backend: BackendAuthClient(
          apiBaseUrl: Uri.parse('https://example.test'),
          client: client,
        ),
        isSupported: () => true,
        credentialProvider: (_) async {
          nativeCalls++;
          return credential();
        },
      );
      await expectLater(service.login(), throwsA(isA<http.ClientException>()));
      expect(nativeCalls, 0);
    },
  );

  test('new attempt requests a new nonce after exchange failure', () async {
    const nextNonce = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq';
    final seen = <String>[];
    var challenges = 0;
    final client = MockClient((request) async {
      if (request.url.path.endsWith('/challenge')) {
        challenges++;
        return http.Response(
          jsonEncode({'nonce': challenges == 1 ? nonce : nextNonce}),
          200,
        );
      }
      return challenges == 1
          ? http.Response('', 503)
          : http.Response(
              jsonEncode({'accessToken': 'a', 'refreshToken': 'r'}),
              200,
            );
    });
    addTearDown(client.close);
    final service = AppleAuthService(
      backend: BackendAuthClient(
        apiBaseUrl: Uri.parse('https://example.test'),
        client: client,
      ),
      isSupported: () => true,
      credentialProvider: (value) async {
        seen.add(value);
        return credential();
      },
    );
    await expectLater(service.login(), throwsA(isA<BackendAuthException>()));
    expect((await service.login()).refreshToken, 'r');
    expect(seen, [nonce, nextNonce]);
  });

  testWidgets('exchange timeout is not retried and late response is ignored', (
    tester,
  ) async {
    final pending = Completer<http.Response>();
    var calls = 0;
    final client = MockClient((request) async {
      calls++;
      return request.url.path.endsWith('/challenge')
          ? http.Response(jsonEncode({'nonce': nonce}), 200)
          : pending.future;
    });
    addTearDown(client.close);
    final service = AppleAuthService(
      backend: BackendAuthClient(
        apiBaseUrl: Uri.parse('https://example.test'),
        client: client,
      ),
      isSupported: () => true,
      credentialProvider: (_) async => credential(),
    );
    final failure = expectLater(
      service.login(),
      throwsA(isA<TimeoutException>()),
    );
    await tester.pump();
    await tester.pump(const Duration(seconds: 15));
    await failure;
    pending.complete(
      http.Response(
        jsonEncode({'accessToken': 'late', 'refreshToken': 'late'}),
        200,
      ),
    );
    await tester.pump();
    expect(calls, 2);
  });
}
