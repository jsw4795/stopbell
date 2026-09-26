import 'dart:convert';

import 'package:http/http.dart' as http;

import 'token_pair.dart';

class BackendAuthClient {
  BackendAuthClient({required this.apiBaseUrl, http.Client? client})
    : _client = client ?? http.Client();

  final Uri apiBaseUrl;
  final http.Client _client;

  Future<TokenPair> login(String idToken) async {
    final response = await _client.post(
      apiBaseUrl.resolve('/auth/google'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'idToken': idToken}),
    );

    if (response.statusCode != 200) {
      throw const BackendAuthException('서버 로그인이 실패했습니다. 다시 시도해 주세요.');
    }

    try {
      final body = jsonDecode(response.body);
      if (body is! Map<String, dynamic>) {
        throw const FormatException();
      }
      final accessToken = body['accessToken'];
      final refreshToken = body['refreshToken'];
      if (accessToken is! String ||
          accessToken.trim().isEmpty ||
          refreshToken is! String ||
          refreshToken.trim().isEmpty) {
        throw const FormatException();
      }
      return TokenPair(accessToken: accessToken, refreshToken: refreshToken);
    } on FormatException {
      throw const BackendAuthException('서버 로그인 응답이 올바르지 않습니다.');
    }
  }
}

class BackendAuthException implements Exception {
  const BackendAuthException(this.message);

  final String message;
}
