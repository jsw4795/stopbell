import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';
import 'package:stopbell/features/transit/bus_route_stop_client.dart';

final baseUrl = Uri.parse('http://localhost:8080');
const pair = TokenPair(
  accessToken: 'fixture-access',
  refreshToken: 'fixture-refresh',
);

class FakeStorage implements TokenPairStorage {
  @override
  Future<TokenPair?> read() async => pair;
  @override
  Future<void> save(TokenPair pair) async {}
  @override
  Future<void> delete() async {}
}

class FakeAuthenticator implements Authenticator {
  @override
  Future<TokenPair> login() async => pair;
}

Future<BusRouteStopClient> client(http.Client transport) async {
  final auth = AuthSession(
    authenticator: FakeAuthenticator(),
    backend: BackendAuthClient(apiBaseUrl: baseUrl),
    tokenStorage: FakeStorage(),
  );
  await auth.initialize();
  return BusRouteStopClient(
    AuthenticatedApiClient(
      apiBaseUrl: baseUrl,
      authSession: auth,
      client: transport,
    ),
  );
}

Map<String, Object> stop(
  int id,
  String name,
  int order,
  bool before,
  bool after,
) => {
  'id': id,
  'name': name,
  'order': order,
  'canNotifyOneStopBefore': before,
  'canNotifyOneStopAfter': after,
};

void main() {
  test('uses the protected route ID endpoint and preserves occurrence fields and order', () async {
    final stops = await client(
      MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/v1/bus-routes/754/stops');
        expect(request.headers['authorization'], 'Bearer fixture-access');
        return http.Response(
          jsonEncode([
            stop(11, '첫 정류장', 1, false, true),
            stop(10, '재방문 정류장', 3, true, true),
            stop(20, '재방문 정류장', 7, true, false),
          ]),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    );
    final result = await stops.findStops(754);
    expect(result.map((stop) => stop.id), [11, 10, 20]);
    expect(result.map((stop) => stop.name), ['첫 정류장', '재방문 정류장', '재방문 정류장']);
    expect(result.map((stop) => stop.order), [1, 3, 7]);
    expect(result.map((stop) => stop.canNotifyOneStopBefore), [
      false,
      true,
      true,
    ]);
    expect(result.map((stop) => stop.canNotifyOneStopAfter), [
      true,
      true,
      false,
    ]);
  });

  test('200 empty response remains successful empty list', () async {
    final stops = await client(
      MockClient((_) async => http.Response('[]', 200)),
    );
    expect(await stops.findStops(1), isEmpty);
  });

  test('non-200 response fails with status', () async {
    final stops = await client(
      MockClient((_) async => http.Response('{}', 404)),
    );
    await expectLater(
      stops.findStops(1),
      throwsA(
        isA<BusRouteStopException>().having(
          (error) => error.statusCode,
          'statusCode',
          404,
        ),
      ),
    );
  });

  test(
    'malformed JSON and every required field fail instead of appearing empty',
    () async {
      final valid = stop(1, '정류장', 1, false, true);
      final invalidBodies = <String>[
        '{',
        '{}',
        for (final field in valid.keys)
          jsonEncode([
            <String, Object>{...valid}..remove(field),
          ]),
        for (final change in <Map<String, Object?>>[
          {'id': null},
          {'id': '1'},
          {'id': 0},
          {'name': null},
          {'name': 1},
          {'name': '  '},
          {'order': null},
          {'order': '1'},
          {'order': 0},
          {'canNotifyOneStopBefore': null},
          {'canNotifyOneStopBefore': 1},
          {'canNotifyOneStopAfter': null},
          {'canNotifyOneStopAfter': 'true'},
        ])
          jsonEncode([
            <String, Object?>{...valid, ...change},
          ]),
      ];
      for (final body in invalidBodies) {
        final stops = await client(
          MockClient(
            (_) async => http.Response(
              body,
              200,
              headers: {'content-type': 'application/json; charset=utf-8'},
            ),
          ),
        );
        await expectLater(
          stops.findStops(1),
          throwsA(isA<BusRouteStopException>()),
        );
      }
    },
  );
}
