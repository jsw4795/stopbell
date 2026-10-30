import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';

import 'boarding_session_test_fakes.dart';
import 'boarding_test_fakes.dart';

void main() {
  late AuthSession auth;
  late BoardingSessionApiClient client;
  final requests = <http.Request>[];
  late Future<http.Response> Function(http.Request) respond;
  const uuid = '8be1dffa-8e2c-4547-b136-d99bfd84e45c';
  const body = <String, Object>{
    'clientRequestId': uuid,
    'candidateRef': 'opaque-original',
    'confirmedVehicleNumber': '서울70사1234',
  };
  bool current() => true;
  setUp(() async {
    requests.clear();
    respond = (_) async => boardingJson(sessionJson());
    final transport = MockClient((request) async {
      requests.add(request);
      return respond(request);
    });
    addTearDown(transport.close);
    auth = await sessionAuth(transport);
    addTearDown(auth.dispose);
    client = BoardingSessionApiClient(
      AuthenticatedApiClient(
        apiBaseUrl: boardingBaseUrl,
        authSession: auth,
        client: transport,
      ),
      timeout: const Duration(milliseconds: 20),
    );
  });
  for (final status in [200, 201]) {
    test(
      'create $status preserves original body and actual terminal response',
      () async {
        respond = (_) async => boardingJson(
          sessionJson(status: 'EXPIRED', evaluation: 'ENDED', gap: null),
          status,
        );
        final session = await client.create(
          body,
          generation: auth.generation,
          current: current,
        );
        expect(session.status, BoardingSessionStatus.expired);
        expect(requests.single.method, 'POST');
        expect(requests.single.url.path, '/api/v1/boarding-sessions');
        expect(jsonDecode(requests.single.body), body);
        expect(
          requests.single.headers['content-type'],
          contains('application/json'),
        );
        expect(
          requests.single.headers['authorization'],
          'Bearer ${auth.accessToken}',
        );
      },
    );
  }
  test(
    'detail, current, and request lookup use separate exact endpoints',
    () async {
      respond = (request) async =>
          boardingJson(request.url.hasQuery ? [sessionJson()] : sessionJson());
      await client.find(123, generation: auth.generation, current: current);
      await client.findCurrent(generation: auth.generation, current: current);
      await client.findRequest(
        uuid,
        generation: auth.generation,
        current: current,
      );
      expect(requests.map((r) => r.method), everyElement('GET'));
      expect(requests[0].url.path, '/api/v1/boarding-sessions/123');
      expect(requests[1].url.queryParameters, {'scope': 'current'});
      expect(requests[2].url.queryParameters, {'clientRequestId': uuid});
    },
  );
  test(
    'zero or one list preserves terminal UUID recovery and null current',
    () async {
      respond = (_) async => boardingJson([]);
      expect(
        await client.findCurrent(generation: auth.generation, current: current),
        isNull,
      );
      respond = (_) async =>
          boardingJson([sessionJson(status: 'COMPLETED', evaluation: 'ENDED')]);
      expect(
        (await client.findRequest(
          uuid,
          generation: auth.generation,
          current: current,
        ))!.status,
        BoardingSessionStatus.completed,
      );
    },
  );
  test(
    'cancel and reconfirm use current revision and latest reference',
    () async {
      await client.cancel(
        123,
        7,
        generation: auth.generation,
        current: current,
      );
      await client.reconfirm(
        123,
        8,
        'new-reference',
        '서울70사1234',
        generation: auth.generation,
        current: current,
      );
      expect(requests[0].url.path, '/api/v1/boarding-sessions/123/cancel');
      expect(jsonDecode(requests[0].body), {'expectedRevision': 7});
      expect(requests[1].url.path, '/api/v1/boarding-sessions/123/reconfirm');
      expect(jsonDecode(requests[1].body), {
        'expectedRevision': 8,
        'candidateRef': 'new-reference',
      });
    },
  );
  test(
    'invalid IDs, revision, UUID and blank reference never reach transport',
    () async {
      await expectLater(
        client.find(0, generation: auth.generation, current: current),
        throwsArgumentError,
      );
      expect(
        () =>
            client.cancel(1, -1, generation: auth.generation, current: current),
        throwsArgumentError,
      );
      expect(
        () => client.findRequest(
          'bad',
          generation: auth.generation,
          current: current,
        ),
        throwsArgumentError,
      );
      await expectLater(
        client.create(
          {...body, 'clientRequestId': 'bad'},
          generation: auth.generation,
          current: current,
        ),
        throwsArgumentError,
      );
      expect(
        () => client.reconfirm(
          123,
          1,
          '',
          'number',
          generation: auth.generation,
          current: current,
        ),
        throwsArgumentError,
      );
      expect(requests, isEmpty);
    },
  );
  test('401 refresh resends exactly the same mutation body once', () async {
    var postCalls = 0;
    respond = (request) async {
      if (request.url.path == '/auth/refresh') {
        return boardingJson({
          'accessToken': sessionJwt('1', 'rotated'),
          'refreshToken': 'rotated-refresh',
        });
      }
      return postCalls++ == 0
          ? boardingJson({}, 401)
          : boardingJson(sessionJson(), 201);
    };
    await client.create(body, generation: auth.generation, current: current);
    final posts = requests
        .where((r) => r.url.path == '/api/v1/boarding-sessions')
        .toList();
    expect(posts, hasLength(2));
    expect(posts[0].body, posts[1].body);
    expect(
      posts[1].headers['authorization'],
      'Bearer ${sessionJwt('1', 'rotated')}',
    );
    expect(requests.where((r) => r.url.path == '/auth/refresh'), hasLength(1));
  });
  for (final entry in {
    404: 'BOARDING_SESSION_NOT_FOUND',
    409: 'BOARDING_REVISION_CONFLICT',
    503: 'TRANSIT_TEMPORARILY_UNAVAILABLE',
    500: 'INTERNAL',
  }.entries) {
    test('preserves HTTP ${entry.key} and safe code', () async {
      respond = (_) async => boardingJson({
        'code': entry.value,
        'message': 'raw private provider message',
      }, entry.key);
      await expectLater(
        client.find(123, generation: auth.generation, current: current),
        throwsA(
          isA<BoardingSessionException>()
              .having((e) => e.statusCode, 'status', entry.key)
              .having((e) => e.code, 'code', entry.value),
        ),
      );
    });
  }
  for (final entry in {
    'empty': ('', null),
    'json': (
      jsonEncode({
        'code': 'INTERNAL_SERVER_ERROR',
        'message': 'An internal server error occurred.',
      }),
      'INTERNAL_SERVER_ERROR',
    ),
    'unknown': (
      jsonEncode({
        'code': 'UNKNOWN_SERVER_ERROR',
        'message': 'private-provider',
      }),
      'UNKNOWN_SERVER_ERROR',
    ),
  }.entries) {
    test(
      'create 500 ${entry.key} preserves uncertainty and original request UUID',
      () async {
        respond = (_) async => http.Response(entry.value.$1, 500);
        await expectLater(
          client.create(body, generation: auth.generation, current: current),
          throwsA(
            isA<BoardingSessionException>()
                .having(
                  (error) => error.failure,
                  'failure',
                  SessionFailure.http,
                )
                .having((error) => error.statusCode, 'status', 500)
                .having((error) => error.code, 'code', entry.value.$2)
                .having((error) => error.uncertain, 'uncertain', true)
                .having(
                  (error) => error.toString(),
                  'safe error',
                  isNot(contains('private-provider')),
                ),
          ),
        );
        expect(requests, hasLength(1));
        expect(jsonDecode(requests.single.body), body);
        expect(jsonDecode(requests.single.body)['clientRequestId'], uuid);
      },
    );
  }
  for (final malformed in [
    '{',
    jsonEncode({'id': 123}),
    jsonEncode([sessionJson(), sessionJson()]),
  ]) {
    test('malformed success is parsing failure $malformed', () async {
      respond = (_) async => http.Response(
        malformed,
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
      await expectLater(
        client.findCurrent(generation: auth.generation, current: current),
        throwsA(
          isA<BoardingSessionException>().having(
            (e) => e.failure,
            'failure',
            SessionFailure.parsing,
          ),
        ),
      );
    });
  }
  test(
    'wrong detail identity and malformed POST response are parsing failures',
    () async {
      respond = (_) async => boardingJson(sessionJson(id: 999));
      await expectLater(
        client.find(123, generation: auth.generation, current: current),
        throwsA(isA<BoardingSessionException>()),
      );
      respond = (_) async => boardingJson({'id': 123}, 201);
      await expectLater(
        client.create(body, generation: auth.generation, current: current),
        throwsA(
          isA<BoardingSessionException>().having(
            (e) => e.failure,
            'failure',
            SessionFailure.parsing,
          ),
        ),
      );
    },
  );
  for (final error in [
    http.ClientException('private'),
    const SocketException('private'),
  ]) {
    test(
      'network exception classified without exposing raw details $error',
      () async {
        respond = (_) async => throw error;
        await expectLater(
          client.create(body, generation: auth.generation, current: current),
          throwsA(
            isA<BoardingSessionException>().having(
              (e) => e.failure,
              'failure',
              SessionFailure.network,
            ),
          ),
        );
      },
    );
  }
  test(
    'POST timeout does not replay late 401 or refresh credentials',
    () async {
      final response = Completer<http.Response>();
      respond = (_) => response.future;
      await expectLater(
        client.create(body, generation: auth.generation, current: current),
        throwsA(
          isA<BoardingSessionException>().having(
            (e) => e.failure,
            'failure',
            SessionFailure.timeout,
          ),
        ),
      );
      response.complete(boardingJson({}, 401));
      await Future<void>.delayed(const Duration(milliseconds: 10));
      expect(requests, hasLength(1));
    },
  );
  test('screen epoch invalidation suppresses late 401 refresh', () async {
    var active = true;
    final response = Completer<http.Response>();
    respond = (_) => response.future;
    final result = client.find(
      123,
      generation: auth.generation,
      current: () => active,
    );
    active = false;
    response.complete(boardingJson({}, 401));
    await expectLater(result, throwsA(isA<StaleSessionException>()));
    expect(requests, hasLength(1));
  });
  test('auth generation change discards late successful POST', () async {
    final response = Completer<http.Response>();
    respond = (request) => request.url.path == '/auth/logout'
        ? Future.value(boardingJson({}, 204))
        : response.future;
    final result = client.create(
      body,
      generation: auth.generation,
      current: current,
    );
    await auth.logout();
    response.complete(boardingJson(sessionJson(), 201));
    await expectLater(result, throwsA(isA<StaleSessionException>()));
  });
}
