class AppConfig {
  const AppConfig({
    required this.apiBaseUrl,
    required this.googleIosClientId,
    required this.googleServerClientId,
  });

  factory AppConfig.fromEnvironment() => const AppConfig(
    apiBaseUrl: String.fromEnvironment('API_BASE_URL'),
    googleIosClientId: String.fromEnvironment('GOOGLE_IOS_CLIENT_ID'),
    googleServerClientId: String.fromEnvironment('GOOGLE_SERVER_CLIENT_ID'),
  );

  final String apiBaseUrl;
  final String googleIosClientId;
  final String googleServerClientId;

  void validate() {
    final missing = <String>[
      if (apiBaseUrl.trim().isEmpty) 'API_BASE_URL',
      if (googleIosClientId.trim().isEmpty) 'GOOGLE_IOS_CLIENT_ID',
      if (googleServerClientId.trim().isEmpty) 'GOOGLE_SERVER_CLIENT_ID',
    ];
    if (missing.isNotEmpty) {
      throw AppConfigException('설정 누락: ${missing.join(', ')}');
    }

    final uri = Uri.tryParse(apiBaseUrl);
    if (uri == null ||
        !uri.hasAuthority ||
        (uri.scheme != 'http' && uri.scheme != 'https')) {
      throw const AppConfigException('API_BASE_URL 형식이 올바르지 않습니다.');
    }
  }
}

class AppConfigException implements Exception {
  const AppConfigException(this.message);

  final String message;
}
