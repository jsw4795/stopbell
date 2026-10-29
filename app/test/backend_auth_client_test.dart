import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';

void main() {
  final baseUrl = Uri.parse('http://localhost:8080');

  for (final operation in ['login', 'refresh', 'logout']) {
    testWidgets('$operation no response times out and ignores late success', (
      tester,
    ) async {
      final response = Completer<http.Response>();
      var requests = 0;
      final transport = MockClient((_) {
        requests++;
        return response.future;
      });
      addTearDown(transport.close);
      final client = BackendAuthClient(apiBaseUrl: baseUrl, client: transport);
      final Future<Object?> pending = switch (operation) {
        'login' => client.login('fixture-id'),
        'refresh' => client.refresh('fixture-refresh'),
        _ => client.logout('fixture-refresh'),
      };
      var completions = 0;
      Object? failure;
      final settled = pending.then<void>(
        (_) => completions++,
        onError: (Object error) {
          failure = error;
          completions++;
        },
      );
      await tester.pump();
      await tester.pump(const Duration(seconds: 15));
      final completedAtDeadline = completions == 1;
      final deadlineFailure = failure;
      response.complete(
        operation == 'logout'
            ? http.Response('', 204)
            : http.Response(
                jsonEncode({
                  'accessToken': 'late-access',
                  'refreshToken': 'late-refresh',
                }),
                200,
              ),
      );
      await tester.pump();
      await settled;

      expect(
        completedAtDeadline,
        isTrue,
        reason: '$operation still pending after 15 seconds',
      );
      expect(deadlineFailure, isA<TimeoutException>());
      expect(failure, same(deadlineFailure));
      expect(completions, 1);
      expect(requests, 1);
      for (final secret in [
        'fixture-id',
        'fixture-refresh',
        'late-access',
        'late-refresh',
      ]) {
        expect(failure.toString(), isNot(contains(secret)));
      }
    });

    for (final lateFailure in ['HTTP 401', 'network error']) {
      testWidgets('$operation ignores late $lateFailure with custom timeout', (
        tester,
      ) async {
        final response = Completer<http.Response>();
        var requests = 0;
        final transport = MockClient((_) {
          requests++;
          return response.future;
        });
        addTearDown(transport.close);
        const timeout = Duration(seconds: 2);
        final client = BackendAuthClient(
          apiBaseUrl: baseUrl,
          client: transport,
          timeout: timeout,
        );
        final Future<Object?> pending = switch (operation) {
          'login' => client.login('fixture-id'),
          'refresh' => client.refresh('fixture-refresh'),
          _ => client.logout('fixture-refresh'),
        };
        var completed = false;
        final result = expectLater(
          pending.whenComplete(() => completed = true),
          throwsA(isA<TimeoutException>()),
        );
        await tester.pump();
        await tester.pump(const Duration(seconds: 1));
        expect(completed, isFalse);
        await tester.pump(const Duration(seconds: 1));
        await result;
        expect(completed, isTrue);
        if (lateFailure == 'HTTP 401') {
          response.complete(http.Response('fixture-refresh', 401));
        } else {
          response.completeError(http.ClientException('late network failure'));
        }
        await tester.pump();
        expect(requests, 1);
        expect(tester.takeException(), isNull);
      });
    }
  }

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
