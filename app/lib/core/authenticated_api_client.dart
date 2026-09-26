import 'package:http/http.dart' as http;

typedef AccessTokenProvider = Future<String?> Function();

class AuthenticatedApiClient {
  AuthenticatedApiClient({
    required this.apiBaseUrl,
    required this.accessTokenProvider,
    http.Client? client,
  }) : _client = client ?? http.Client();

  final Uri apiBaseUrl;
  final AccessTokenProvider accessTokenProvider;
  final http.Client _client;

  Future<http.Response> request(
    String method,
    String path, {
    Map<String, String> headers = const {},
    String? body,
  }) async {
    final accessToken = await accessTokenProvider();
    if (accessToken == null || accessToken.trim().isEmpty) {
      throw const UnauthenticatedApiException();
    }

    final request = http.Request(method, apiBaseUrl.resolve(path));
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
