import 'package:http/http.dart' as http;

import '../features/auth/auth_session.dart';

class AuthenticatedApiClient {
  AuthenticatedApiClient({
    required this.apiBaseUrl,
    required this.authSession,
    http.Client? client,
  }) : _client = client ?? http.Client();

  final Uri apiBaseUrl;
  final AuthSession authSession;
  final http.Client _client;

  Future<http.Response> request(
    String method,
    String path, {
    Map<String, String> headers = const {},
    String? body,
  }) async {
    final requestUri = apiBaseUrl.resolve(path);
    if (requestUri.scheme != apiBaseUrl.scheme ||
        requestUri.host != apiBaseUrl.host ||
        requestUri.port != apiBaseUrl.port) {
      throw ArgumentError('Backend origin 밖의 보호 API는 요청할 수 없습니다.');
    }
    final endpoint = requestUri.path;
    if (endpoint == '/auth/google' ||
        endpoint == '/auth/refresh' ||
        endpoint == '/auth/logout') {
      throw ArgumentError('Auth endpoint는 BackendAuthClient를 사용해야 합니다.');
    }
    final accessToken = authSession.accessToken;
    if (accessToken == null || accessToken.trim().isEmpty) {
      throw const UnauthenticatedApiException();
    }

    final response = await _send(
      method,
      requestUri,
      headers,
      body,
      accessToken,
    );
    if (response.statusCode != 401) return response;

    final rotated = await authSession.refreshAfterUnauthorized(accessToken);
    return _send(method, requestUri, headers, body, rotated.accessToken);
  }

  Future<http.Response> _send(
    String method,
    Uri requestUri,
    Map<String, String> headers,
    String? body,
    String accessToken,
  ) async {
    final request = http.Request(method, requestUri);
    request.headers.addAll({
      for (final entry in headers.entries)
        if (entry.key.toLowerCase() != 'authorization') entry.key: entry.value,
    });
    request.headers['Authorization'] = 'Bearer $accessToken';
    if (body != null) request.body = body;

    return http.Response.fromStream(await _client.send(request));
  }
}

class UnauthenticatedApiException implements Exception {
  const UnauthenticatedApiException();
}
