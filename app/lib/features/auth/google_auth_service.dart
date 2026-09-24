import 'package:google_sign_in/google_sign_in.dart';

import '../../core/app_config.dart';
import 'backend_auth_client.dart';

abstract class Authenticator {
  Future<void> login();
}

class GoogleAuthService implements Authenticator {
  GoogleAuthService({required this.config, required this.backend});

  final AppConfig config;
  final BackendAuthClient backend;
  Future<void>? _initialization;

  @override
  Future<void> login() async {
    config.validate();

    final google = GoogleSignIn.instance;
    try {
      await (_initialization ??= google.initialize(
        clientId: config.googleIosClientId,
        serverClientId: config.googleServerClientId,
      ));
      if (!google.supportsAuthenticate()) {
        throw const LoginException('이 기기에서는 Google 로그인을 사용할 수 없습니다.');
      }

      final account = await google.authenticate();
      final idToken = account.authentication.idToken;
      if (idToken == null || idToken.trim().isEmpty) {
        throw const LoginException('Google 인증 정보를 받지 못했습니다. 다시 시도해 주세요.');
      }
      await backend.login(idToken);
    } on GoogleSignInException catch (error) {
      if (error.code == GoogleSignInExceptionCode.canceled) {
        throw const LoginException('로그인이 취소되었습니다.');
      }
      throw const LoginException('Google 로그인이 실패했습니다. 다시 시도해 주세요.');
    }
  }
}

class LoginException implements Exception {
  const LoginException(this.message);

  final String message;
}
