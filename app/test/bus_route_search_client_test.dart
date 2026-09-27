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
import 'package:stopbell/features/transit/bus_route_search_client.dart';

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

Future<BusRouteSearchClient> client(http.Client transport) async {
  final auth = AuthSession(
    authenticator: FakeAuthenticator(),
    backend: BackendAuthClient(apiBaseUrl: baseUrl),
    tokenStorage: FakeStorage(),
  );
  await auth.initialize();
  return BusRouteSearchClient(
    AuthenticatedApiClient(
      apiBaseUrl: baseUrl,
      authSession: auth,
      client: transport,
    ),
  );
}

void main() {
  test('empty response remains a successful empty list', () async {
    final search = await client(
      MockClient((request) async {
        expect(request.headers['authorization'], 'Bearer fixture-access');
        return http.Response('[]', 200);
      }),
    );
    expect(await search.search('7000'), isEmpty);
  });

  test(
    'keeps backend order and distinct ids for duplicate route numbers',
    () async {
      final search = await client(
        MockClient(
          (_) async => http.Response(
            jsonEncode([
              {'id': 754, 'routeNumber': '7000', 'regionName': '수원시'},
              {'id': 1390, 'routeNumber': '7000', 'regionName': '김포시'},
            ]),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          ),
        ),
      );
      final routes = await search.search('7000');
      expect(routes.map((route) => route.id), [754, 1390]);
      expect(routes.map((route) => route.routeNumber), ['7000', '7000']);
      expect(routes.map((route) => route.regionName), ['수원시', '김포시']);
    },
  );

  test('trims and safely encodes the single query parameter', () async {
    for (final query in [' 7000 ', ' N26 ', ' &?한글 ']) {
      final search = await client(
        MockClient((request) async {
          expect(request.url.path, '/api/v1/bus-routes');
          expect(request.url.queryParameters, {'query': query.trim()});
          expect(request.url.queryParametersAll.length, 1);
          return http.Response('[]', 200);
        }),
      );
      await search.search(query);
    }
  });

  test('non-200 responses fail instead of becoming empty results', () async {
    final search = await client(
      MockClient((_) async => http.Response('{"code":"INVALID_REQUEST"}', 400)),
    );
    await expectLater(
      search.search('7000'),
      throwsA(
        isA<BusRouteSearchException>().having(
          (error) => error.statusCode,
          'statusCode',
          400,
        ),
      ),
    );
  });

  test(
    'malformed JSON, response shape and candidate fail explicitly',
    () async {
      for (final body in [
        '{',
        '{}',
        '[{"id":0,"routeNumber":"7000","regionName":"서울"}]',
        '[{"id":1,"routeNumber":"7000"}]',
      ]) {
        final search = await client(
          MockClient(
            (_) async => http.Response(
              body,
              200,
              headers: {'content-type': 'application/json; charset=utf-8'},
            ),
          ),
        );
        await expectLater(
          search.search('7000'),
          throwsA(isA<BusRouteSearchException>()),
        );
      }
    },
  );
}
