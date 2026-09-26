import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'token_pair.dart';

abstract interface class TokenPairStorage {
  Future<void> save(TokenPair pair);

  Future<TokenPair?> read();

  Future<void> delete();
}

class SecureTokenPairStorage implements TokenPairStorage {
  SecureTokenPairStorage({FlutterSecureStorage? secureStorage})
    : _secureStorage = secureStorage ?? const FlutterSecureStorage();

  static const _storageKey = 'auth_token_pair_v1';

  final FlutterSecureStorage _secureStorage;

  @override
  Future<void> save(TokenPair pair) async {
    if (!_isValidToken(pair.accessToken) || !_isValidToken(pair.refreshToken)) {
      throw ArgumentError('Token Pair에는 비어 있지 않은 Token이 필요합니다.');
    }

    await _secureStorage.write(
      key: _storageKey,
      value: jsonEncode({
        'accessToken': pair.accessToken,
        'refreshToken': pair.refreshToken,
      }),
    );
  }

  @override
  Future<TokenPair?> read() async {
    final storedValue = await _secureStorage.read(key: _storageKey);
    if (storedValue == null) return null;

    Object? decoded;
    try {
      decoded = jsonDecode(storedValue);
    } on FormatException {
      await delete();
      return null;
    }

    if (decoded is! Map<String, dynamic>) {
      await delete();
      return null;
    }

    final accessToken = decoded['accessToken'];
    final refreshToken = decoded['refreshToken'];
    if (accessToken is! String ||
        !_isValidToken(accessToken) ||
        refreshToken is! String ||
        !_isValidToken(refreshToken)) {
      await delete();
      return null;
    }

    return TokenPair(accessToken: accessToken, refreshToken: refreshToken);
  }

  @override
  Future<void> delete() => _secureStorage.delete(key: _storageKey);

  bool _isValidToken(String token) => token.trim().isNotEmpty;
}
