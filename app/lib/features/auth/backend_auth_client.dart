import 'dart:convert';

import 'package:http/http.dart' as http;

import 'token_pair.dart';

class BackendAuthClient {
  BackendAuthClient({
    required this.apiBaseUrl,
    http.Client? client,
    this.timeout = const Duration(seconds: 15),
  }) : _client = client ?? http.Client();

  final Uri apiBaseUrl;
  final Duration timeout;
  final http.Client _client;

  Future<TokenPair> login(String idToken) =>
      _requestTokenPair('/auth/google', {'idToken': idToken});

  Future<String> appleChallenge() async {
    final response = await _post('/auth/apple/challenge', {});
    if (response.statusCode != 200) {
      throw BackendAuthException(
        'Apple 로그인 준비에 실패했어요. 다시 시도해 주세요.',
        statusCode: response.statusCode,
      );
    }
    try {
      final decoded = jsonDecode(response.body);
      final nonce = decoded is Map<String, dynamic> ? decoded['nonce'] : null;
      if (nonce is! String || !RegExp(r'^[A-Za-z0-9_-]{43}$').hasMatch(nonce)) {
        throw const FormatException();
      }
      return nonce;
    } on FormatException {
      throw const BackendAuthException('서버 인증 응답이 올바르지 않아요.');
    }
  }

  Future<TokenPair> loginWithApple({
    required String identityToken,
    required String authorizationCode,
    required String nonce,
  }) => _requestTokenPair('/auth/apple', {
    'identityToken': identityToken,
    'authorizationCode': authorizationCode,
    'nonce': nonce,
  });

  Future<TokenPair> refresh(String refreshToken) =>
      _requestTokenPair('/auth/refresh', {'refreshToken': refreshToken});

  Future<void> logout(String refreshToken) async {
    final response = await _post('/auth/logout', {
      'refreshToken': refreshToken,
    });
    if (response.statusCode != 204) {
      throw BackendAuthException(
        '서버 로그아웃이 실패했어요. 다시 시도해 주세요.',
        statusCode: response.statusCode,
      );
    }
  }

  Future<TokenPair> _requestTokenPair(
    String path,
    Map<String, String> body,
  ) async {
    final response = await _post(path, body);
    if (response.statusCode != 200) {
      throw BackendAuthException(
        '서버 인증 요청이 실패했어요. 다시 시도해 주세요.',
        statusCode: response.statusCode,
      );
    }

    try {
      final decoded = jsonDecode(response.body);
      if (decoded is! Map<String, dynamic>) throw const FormatException();
      final accessToken = decoded['accessToken'];
      final refreshToken = decoded['refreshToken'];
      if (accessToken is! String ||
          accessToken.trim().isEmpty ||
          refreshToken is! String ||
          refreshToken.trim().isEmpty) {
        throw const FormatException();
      }
      return TokenPair(accessToken: accessToken, refreshToken: refreshToken);
    } on FormatException {
      throw const BackendAuthException('서버 인증 응답이 올바르지 않아요.');
    }
  }

  Future<http.Response> _post(String path, Map<String, String> body) {
    // Bound the HTTP result before parsing tokens or changing session state.
    // This ignores late results; it does not cancel server work or retry POSTs.
    return _client
        .post(
          apiBaseUrl.resolve(path),
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode(body),
        )
        .timeout(timeout);
  }
}

class BackendAuthException implements Exception {
  const BackendAuthException(this.message, {this.statusCode});

  final String message;
  final int? statusCode;
}
