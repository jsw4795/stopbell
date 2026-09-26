import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/app_config.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';

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

class FakeTokenPairStorage implements TokenPairStorage {
  TokenPair? savedPair;
  Object? saveError;

  @override
  Future<void> save(TokenPair pair) async {
    if (saveError case final error?) throw error;
    savedPair = pair;
  }

  @override
  Future<TokenPair?> read() async => savedPair;

  @override
  Future<void> delete() async => savedPair = null;
}

void main() {
  test('Backend Token Pair를 받은 뒤 Secure Storage에 저장한다', () async {
    final backend = FakeBackendAuthClient();
    final storage = FakeTokenPairStorage();
    final service = GoogleAuthService(
      config: config,
      backend: backend,
      tokenStorage: storage,
      googleIdTokenProvider: () async => 'test-google-id-token',
    );

    await service.login();

    expect(backend.receivedIdToken, 'test-google-id-token');
    expect(storage.savedPair, same(backendPair));
  });

  test('Secure Storage 저장 실패를 로그인 성공으로 완료하지 않는다', () async {
    final storage = FakeTokenPairStorage()
      ..saveError = StateError('secure storage unavailable');
    final service = GoogleAuthService(
      config: config,
      backend: FakeBackendAuthClient(),
      tokenStorage: storage,
      googleIdTokenProvider: () async => 'test-google-id-token',
    );

    await expectLater(service.login(), throwsA(isA<StateError>()));
  });
}
