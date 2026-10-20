import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/boarding/boarding_candidate_client.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';

import 'boarding_test_fakes.dart';

void main() {
  Future<(BoardingCandidateClient, AuthSession)> client(
    http.Client transport,
  ) async {
    final auth = await boardingAuth(transport);
    addTearDown(auth.dispose);
    addTearDown(transport.close);
    return (
      BoardingCandidateClient(
        AuthenticatedApiClient(
          apiBaseUrl: boardingBaseUrl,
          authSession: auth,
          client: transport,
        ),
      ),
      auth,
    );
  }

  test(
    'uses protected route and exact query IDs, omitting optional boarding',
    () async {
      var calls = 0;
      final (api, _) = await client(
        MockClient((request) async {
          expect(request.method, 'GET');
          expect(
            request.url.path,
            '/api/v1/bus-routes/754/boarding-candidates',
          );
          expect(request.headers['authorization'], 'Bearer access-a');
          expect(request.url.queryParameters, {
            'targetStopOccurrenceId': '22',
            if (calls++ == 1) 'boardingStopOccurrenceId': '11',
          });
          return boardingJson(candidatesJson());
        }),
      );
      expect(
        (await api.find(
          routeId: 754,
          targetStopOccurrenceId: 22,
        )).candidates.single.vehicleNumber,
        '서울70사1234',
      );
      await api.find(
        routeId: 754,
        targetStopOccurrenceId: 22,
        boardingStopOccurrenceId: 11,
      );
      expect(calls, 2);
      for (final ids in [(0, 22, null), (754, -1, null), (754, 22, 0)]) {
        await expectLater(
          api.find(
            routeId: ids.$1,
            targetStopOccurrenceId: ids.$2,
            boardingStopOccurrenceId: ids.$3,
          ),
          throwsArgumentError,
        );
      }
      expect(calls, 2);
    },
  );

  for (final (raw, expected) in [
    ('AVAILABLE', CandidateSetStatus.available),
    ('EMPTY', CandidateSetStatus.empty),
    ('PARTIAL', CandidateSetStatus.partial),
  ]) {
    test('parses HTTP 200 $raw', () async {
      final (api, _) = await client(
        MockClient((_) async => boardingJson(candidatesJson(status: raw))),
      );
      expect(
        (await api.find(
          routeId: 754,
          targetStopOccurrenceId: 22,
        )).candidateSetStatus,
        expected,
      );
    });
  }

  for (final (status, code) in [
    (503, 'TRANSIT_TEMPORARILY_UNAVAILABLE'),
    (400, 'INVALID_REQUEST'),
    (404, 'TARGET_STOP_OCCURRENCE_NOT_FOUND'),
    (401, 'UNAUTHORIZED'),
  ]) {
    test('preserves HTTP $status and backend code', () async {
      final (api, _) = await client(
        MockClient(
          (request) async =>
              boardingJson({'code': code, 'message': 'error'}, status),
        ),
      );
      // 401 refresh follows the existing AuthSession policy; refresh itself also fails.
      if (status == 401) {
        await expectLater(
          api.find(routeId: 754, targetStopOccurrenceId: 22),
          throwsA(
            isA<BackendAuthException>().having(
              (e) => e.statusCode,
              'refresh status',
              401,
            ),
          ),
        );
      } else {
        await expectLater(
          api.find(routeId: 754, targetStopOccurrenceId: 22),
          throwsA(
            isA<BoardingCandidateException>()
                .having((e) => e.statusCode, 'status', status)
                .having((e) => e.code, 'code', code),
          ),
        );
      }
    });
  }

  test('malformed error body still preserves HTTP status', () async {
    final (api, _) = await client(
      MockClient((_) async => http.Response('{', 503)),
    );
    await expectLater(
      api.find(routeId: 754, targetStopOccurrenceId: 22),
      throwsA(
        isA<BoardingCandidateException>()
            .having((e) => e.statusCode, 'status', 503)
            .having((e) => e.code, 'code', isNull),
      ),
    );
  });

  test('separates parsing failure, transport failure and timeout', () async {
    for (final (handler, failure)
        in <(Future<http.Response> Function(http.Request), CandidateFailure)>[
          ((_) async => http.Response('{', 200), CandidateFailure.parsing),
          ((_) async => http.Response('{}', 200), CandidateFailure.parsing),
          (
            (_) async => throw http.ClientException('offline'),
            CandidateFailure.network,
          ),
          (
            (_) async => throw TimeoutException('timeout'),
            CandidateFailure.timeout,
          ),
        ]) {
      final (api, _) = await client(MockClient(handler));
      await expectLater(
        api.find(routeId: 754, targetStopOccurrenceId: 22),
        throwsA(
          isA<BoardingCandidateException>().having(
            (e) => e.failure,
            'failure',
            failure,
          ),
        ),
      );
    }
  });

  test(
    '401 rotates through AuthSession and retries once in the same generation',
    () async {
      final tokens = <String?>[];
      final (api, auth) = await client(
        MockClient((request) async {
          if (request.url.path == '/auth/refresh') {
            return boardingJson({
              'accessToken': 'rotated',
              'refreshToken': 'rotated-refresh',
            });
          }
          tokens.add(request.headers['authorization']);
          return tokens.length == 1
              ? boardingJson({}, 401)
              : boardingJson(candidatesJson());
        }),
      );
      final generation = auth.generation;
      await api.find(
        routeId: 754,
        targetStopOccurrenceId: 22,
        expectedGeneration: generation,
      );
      expect(tokens, ['Bearer access-a', 'Bearer rotated']);
      expect(auth.generation, generation);
    },
  );

  test('generation mismatch blocks send and late response after account change is discarded', () async {
    final pending = Completer<http.Response>();
    var calls = 0;
    final (api, auth) = await client(
      MockClient((request) async {
        if (request.url.path == '/auth/logout') return http.Response('', 204);
        calls++;
        return pending.future;
      }),
    );
    await expectLater(
      api.find(
        routeId: 754,
        targetStopOccurrenceId: 22,
        expectedGeneration: auth.generation + 1,
      ),
      throwsA(isA<StaleSessionException>()),
    );
    expect(calls, 0);
    final result = api.find(routeId: 754, targetStopOccurrenceId: 22);
    final assertion = expectLater(
      result,
      throwsA(isA<StaleSessionException>()),
    );
    await auth.logout();
    await auth.login();
    pending.complete(boardingJson(candidatesJson()));
    await assertion;
    expect(calls, 1);
  });

  test('stale screen response cannot trigger refresh or a retry', () async {
    final pending = Completer<http.Response>();
    var current = true;
    var calls = 0;
    final (api, _) = await client(
      MockClient((_) {
        calls++;
        return pending.future;
      }),
    );
    final result = api.find(
      routeId: 754,
      targetStopOccurrenceId: 22,
      isRequestCurrent: () => current,
    );
    final assertion = expectLater(
      result,
      throwsA(isA<StaleSessionException>()),
    );
    current = false;
    pending.complete(boardingJson({}, 401));
    await assertion;
    expect(calls, 1);
    await expectLater(
      api.find(
        routeId: 754,
        targetStopOccurrenceId: 22,
        isRequestCurrent: () => false,
      ),
      throwsA(isA<StaleSessionException>()),
    );
    expect(calls, 1);
  });

  testWidgets('pending transport reaches the 15 second client deadline', (
    tester,
  ) async {
    final pending = Completer<http.Response>();
    var calls = 0;
    final (api, _) = await client(
      MockClient((_) {
        calls++;
        return pending.future;
      }),
    );
    final response = api.find(routeId: 754, targetStopOccurrenceId: 22);
    final assertion = expectLater(
      response,
      throwsA(
        isA<BoardingCandidateException>().having(
          (error) => error.failure,
          'failure',
          CandidateFailure.timeout,
        ),
      ),
    );
    await tester.pump(const Duration(seconds: 15));
    await assertion;
    pending.complete(boardingJson({}, 401));
    await tester.pump();
    expect(calls, 1);
  });

  test('screen becomes stale during refresh so rotated credentials never retry old context', () async {
    final refresh = Completer<http.Response>();
    final refreshStarted = Completer<void>();
    var current = true;
    var candidateCalls = 0;
    final (api, _) = await client(
      MockClient((request) async {
        if (request.url.path == '/auth/refresh') {
          refreshStarted.complete();
          return refresh.future;
        }
        candidateCalls++;
        return boardingJson({}, 401);
      }),
    );
    final response = api.find(
      routeId: 754,
      targetStopOccurrenceId: 22,
      isRequestCurrent: () => current,
    );
    final assertion = expectLater(
      response,
      throwsA(isA<StaleSessionException>()),
    );
    await refreshStarted.future;
    current = false;
    refresh.complete(
      boardingJson({
        'accessToken': 'rotated',
        'refreshToken': 'rotated-refresh',
      }),
    );
    await assertion;
    expect(candidateCalls, 1);
  });
}
