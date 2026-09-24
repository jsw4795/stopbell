import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';

void main() {
  test('Google ID Token을 /auth/google JSON 본문에 전송하고 Token Pair를 읽는다', () async {
    final client = BackendAuthClient(
      apiBaseUrl: Uri.parse('http://localhost:8080'),
      client: MockClient((request) async {
        expect(request.method, 'POST');
        expect(request.url.toString(), 'http://localhost:8080/auth/google');
        expect(request.headers['content-type'], 'application/json');
        expect(jsonDecode(request.body), {'idToken': 'google-id-token'});
        return http.Response(
          jsonEncode({'accessToken': 'access', 'refreshToken': 'refresh'}),
          200,
        );
      }),
    );

    final pair = await client.login('google-id-token');
    expect(pair.accessToken, 'access');
    expect(pair.refreshToken, 'refresh');
  });

  test('Backend 오류와 비어 있는 Token Pair는 로그인 실패다', () async {
    final responses = [
      http.Response('', 401),
      http.Response(
        jsonEncode({'accessToken': 'access', 'refreshToken': ''}),
        200,
      ),
    ];
    final client = BackendAuthClient(
      apiBaseUrl: Uri.parse('http://localhost:8080'),
      client: MockClient((_) async => responses.removeAt(0)),
    );

    await expectLater(
      client.login('id-token'),
      throwsA(isA<BackendAuthException>()),
    );
    await expectLater(
      client.login('id-token'),
      throwsA(isA<BackendAuthException>()),
    );
  });
}
