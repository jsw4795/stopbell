import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';

void main() {
  final baseUrl = Uri.parse('http://localhost:8080');

  test('login sends Google ID token JSON and returns a Token Pair', () async {
    final client = BackendAuthClient(
      apiBaseUrl: baseUrl,
      client: MockClient((request) async {
        expect(request.method, 'POST');
        expect(request.url.path, '/auth/google');
        expect(request.headers['content-type'], 'application/json');
        expect(request.headers.containsKey('authorization'), isFalse);
        expect(jsonDecode(request.body), {'idToken': 'fixture-id'});
        return http.Response(
          jsonEncode({'accessToken': 'access', 'refreshToken': 'refresh'}),
          200,
        );
      }),
    );

    final pair = await client.login('fixture-id');
    expect(pair.accessToken, 'access');
    expect(pair.refreshToken, 'refresh');
  });

  test(
    'refresh sends current refresh token and returns rotated pair',
    () async {
      final client = BackendAuthClient(
        apiBaseUrl: baseUrl,
        client: MockClient((request) async {
          expect(request.method, 'POST');
          expect(request.url.path, '/auth/refresh');
          expect(request.headers['content-type'], 'application/json');
          expect(request.headers.containsKey('authorization'), isFalse);
          expect(jsonDecode(request.body), {'refreshToken': 'fixture-refresh'});
          return http.Response(
            jsonEncode({
              'accessToken': 'new-access',
              'refreshToken': 'new-refresh',
            }),
            200,
          );
        }),
      );

      final pair = await client.refresh('fixture-refresh');
      expect(pair.accessToken, 'new-access');
      expect(pair.refreshToken, 'new-refresh');
    },
  );

  test('refresh preserves 401 and 5xx status without leaking tokens', () async {
    final responses = [http.Response('', 401), http.Response('', 503)];
    final client = BackendAuthClient(
      apiBaseUrl: baseUrl,
      client: MockClient((_) async => responses.removeAt(0)),
    );

    for (final status in [401, 503]) {
      await expectLater(
        client.refresh('fixture-refresh'),
        throwsA(
          isA<BackendAuthException>()
              .having((error) => error.statusCode, 'statusCode', status)
              .having(
                (error) => error.message.contains('fixture-refresh'),
                'leak',
                isFalse,
              ),
        ),
      );
    }
  });

  test('network failure is distinct from HTTP failure', () async {
    final client = BackendAuthClient(
      apiBaseUrl: baseUrl,
      client: MockClient((_) async => throw http.ClientException('offline')),
    );

    await expectLater(
      client.refresh('fixture-refresh'),
      throwsA(isA<http.ClientException>()),
    );
  });

  test('malformed Token Pair is rejected', () async {
    final responses = [
      http.Response('not-json', 200),
      http.Response(jsonEncode({'accessToken': 'access'}), 200),
      http.Response(
        jsonEncode({'accessToken': ' ', 'refreshToken': 'refresh'}),
        200,
      ),
    ];
    final client = BackendAuthClient(
      apiBaseUrl: baseUrl,
      client: MockClient((_) async => responses.removeAt(0)),
    );

    for (var index = 0; index < 3; index++) {
      await expectLater(
        client.refresh('fixture-refresh'),
        throwsA(
          isA<BackendAuthException>().having(
            (error) => error.statusCode,
            'statusCode',
            isNull,
          ),
        ),
      );
    }
  });

  test('logout sends refresh token and accepts 204', () async {
    final client = BackendAuthClient(
      apiBaseUrl: baseUrl,
      client: MockClient((request) async {
        expect(request.method, 'POST');
        expect(request.url.path, '/auth/logout');
        expect(request.headers['content-type'], 'application/json');
        expect(request.headers.containsKey('authorization'), isFalse);
        expect(jsonDecode(request.body), {'refreshToken': 'fixture-refresh'});
        return http.Response('', 204);
      }),
    );

    await client.logout('fixture-refresh');
  });
}
