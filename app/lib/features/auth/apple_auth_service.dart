import 'package:flutter/foundation.dart';
import 'package:sign_in_with_apple/sign_in_with_apple.dart';

import 'backend_auth_client.dart';
import 'google_auth_service.dart';
import 'token_pair.dart';

typedef AppleCredentialProvider =
    Future<AuthorizationCredentialAppleID> Function(String nonce);

class AppleAuthService implements Authenticator {
  AppleAuthService({
    required this.backend,
    this.credentialProvider,
    this.isSupported,
  });

  final BackendAuthClient backend;
  final AppleCredentialProvider? credentialProvider;
  final bool Function()? isSupported;

  @override
  Future<TokenPair> login() async {
    if (!(isSupported?.call() ??
        (!kIsWeb && defaultTargetPlatform == TargetPlatform.iOS))) {
      throw const LoginException('이 기기에서는 Apple 로그인을 사용할 수 없어요.');
    }
    final nonce = await backend.appleChallenge();
    late final AuthorizationCredentialAppleID credential;
    try {
      credential =
          await (credentialProvider?.call(nonce) ??
              SignInWithApple.getAppleIDCredential(scopes: [], nonce: nonce));
    } on SignInWithAppleAuthorizationException catch (error) {
      if (error.code == AuthorizationErrorCode.canceled) {
        throw const AppleLoginCanceledException();
      }
      throw const LoginException('Apple 로그인이 실패했어요. 다시 시도해 주세요.');
    } on SignInWithAppleException {
      throw const LoginException('Apple 로그인이 실패했어요. 다시 시도해 주세요.');
    }
    final token = credential.identityToken;
    final code = credential.authorizationCode;
    if (token == null || token.trim().isEmpty || code.trim().isEmpty) {
      throw const LoginException('Apple 인증 정보를 받지 못했어요. 다시 시도해 주세요.');
    }
    return backend.loginWithApple(
      identityToken: token,
      authorizationCode: code,
      nonce: nonce,
    );
  }
}

class AppleLoginCanceledException implements Exception {
  const AppleLoginCanceledException();
}
