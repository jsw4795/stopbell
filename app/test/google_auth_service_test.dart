import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/app_config.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';

const config = AppConfig(
  apiBaseUrl: 'http://localhost:8080',
  googleIosClientId: 'test-ios-client-id',
  googleServerClientId: 'test-server-client-id',
);
const backendPair = TokenPair(
  accessToken: 'test-access-token',
  refreshToken: 'test-refresh-token',
);

class FakeBackendAuthClient extends BackendAuthClient {
  FakeBackendAuthClient()
    : super(apiBaseUrl: Uri.parse('http://localhost:8080'));

  String? receivedIdToken;

  @override
  Future<TokenPair> login(String idToken) async {
    receivedIdToken = idToken;
    return backendPair;
  }
}

void main() {
  test('Google ID Token을 Backend에 전달하고 Pair를 반환한다', () async {
    final backend = FakeBackendAuthClient();
    final service = GoogleAuthService(
      config: config,
      backend: backend,
      googleIdTokenProvider: () async => 'test-google-id-token',
    );

    final pair = await service.login();

    expect(backend.receivedIdToken, 'test-google-id-token');
    expect(pair, same(backendPair));
  });
}
