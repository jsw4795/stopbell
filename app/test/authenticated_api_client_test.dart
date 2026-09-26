import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';

class FakeTokenPairStorage implements TokenPairStorage {
  TokenPair? pair;

  @override
  Future<TokenPair?> read() async => pair;

  @override
  Future<void> save(TokenPair pair) async => this.pair = pair;

  @override
  Future<void> delete() async => pair = null;
}

void main() {
  final baseUrl = Uri.parse('http://localhost:8080');

  test(
    'current Access Token overrides caller authorization and preserves headers',
    () async {
      final storage = FakeTokenPairStorage()
        ..pair = const TokenPair(
          accessToken: 'current-access',
          refreshToken: 'fixture-refresh',
        );
      var sendCount = 0;
      final client = AuthenticatedApiClient(
        apiBaseUrl: baseUrl,
        accessTokenProvider: () async => (await storage.read())?.accessToken,
        client: MockClient((request) async {
          sendCount++;
          expect(request.url.path, '/api/v1/bus-routes');
          expect(request.url.queryParameters['query'], '7000');
          expect(request.headers['authorization'], 'Bearer current-access');
          expect(request.headers['content-type'], 'application/json');
          expect(request.headers['x-request-id'], 'fixture-request');
          return http.Response('ok', 200);
        }),
      );

      final response = await client.request(
        'GET',
        '/api/v1/bus-routes?query=7000',
        headers: {
          'Content-Type': 'application/json',
          'X-Request-Id': 'fixture-request',
          'aUtHoRiZaTiOn': 'Bearer wrong-access',
        },
      );
      expect(response.statusCode, 200);
      expect(sendCount, 1);
    },
  );

  test('missing or blank Access Token fails locally without a request or token leak', () async {
    var sendCount = 0;
    String? accessToken;
    final client = AuthenticatedApiClient(
      apiBaseUrl: baseUrl,
      accessTokenProvider: () async => accessToken,
      client: MockClient((_) async {
        sendCount++;
        return http.Response('', 200);
      }),
    );

    for (accessToken in [null, '', '  ']) {
      await expectLater(
        client.request('GET', '/api/v1/bus-routes?query=7000'),
        throwsA(isA<UnauthenticatedApiException>()),
      );
    }
    expect(sendCount, 0);
  });

  test('protected 401 is returned without refresh or retry', () async {
    var tokenReads = 0;
    var sendCount = 0;
    final client = AuthenticatedApiClient(
      apiBaseUrl: baseUrl,
      accessTokenProvider: () async {
        tokenReads++;
        return 'fixture-access';
      },
      client: MockClient((_) async {
        sendCount++;
        return http.Response('', 401);
      }),
    );

    final response = await client.request(
      'GET',
      '/api/v1/bus-routes?query=7000',
    );
    expect(response.statusCode, 401);
    expect(tokenReads, 1);
    expect(sendCount, 1);
  });

  test('each request reads the current Access Token', () async {
    var accessToken = 'first';
    final headers = <String>[];
    final client = AuthenticatedApiClient(
      apiBaseUrl: baseUrl,
      accessTokenProvider: () async => accessToken,
      client: MockClient((request) async {
        headers.add(request.headers['authorization']!);
        return http.Response('', 200);
      }),
    );

    await client.request('GET', '/api/v1/bus-routes?query=7000');
    accessToken = 'second';
    await client.request('GET', '/api/v1/bus-routes?query=7000');
    expect(headers, ['Bearer first', 'Bearer second']);
  });

  test('JSON request body and response are passed through once', () async {
    var sendCount = 0;
    final client = AuthenticatedApiClient(
      apiBaseUrl: baseUrl,
      accessTokenProvider: () async => 'fixture-access',
      client: MockClient((request) async {
        sendCount++;
        expect(request.method, 'POST');
        expect(request.headers['content-type'], 'application/json');
        expect(request.body, '{"value":1}');
        return http.Response('{"id":1}', 201);
      }),
    );

    final response = await client.request(
      'POST',
      '/api/v1/fixture',
      headers: {'Content-Type': 'application/json'},
      body: '{"value":1}',
    );
    expect(response.statusCode, 201);
    expect(response.body, '{"id":1}');
    expect(sendCount, 1);
  });
}
