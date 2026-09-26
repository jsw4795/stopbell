import 'package:flutter/foundation.dart';

import 'backend_auth_client.dart';
import 'google_auth_service.dart';
import 'token_pair.dart';
import 'token_pair_storage.dart';

enum AuthState { initializing, authenticated, unauthenticated }

/// Owns the in-memory and persisted token pair for the current app session.
class AuthSession extends ChangeNotifier {
  factory AuthSession({
    required Authenticator authenticator,
    required BackendAuthClient backend,
    required TokenPairStorage tokenStorage,
  }) => AuthSession._(authenticator, backend, tokenStorage);

  AuthSession._(this._authenticator, this._backend, this._tokenStorage);

  final Authenticator _authenticator;
  final BackendAuthClient _backend;
  final TokenPairStorage _tokenStorage;

  AuthState _state = AuthState.initializing;
  TokenPair? _pair;
  Future<TokenPair>? _refreshInFlight;
  Future<void>? _initialization;

  AuthState get state => _state;
  TokenPair? get currentPair => _pair;
  String? get accessToken => _pair?.accessToken;

  Future<void> initialize() => _initialization ??= _restore();

  Future<void> _restore() async {
    try {
      _pair = await _tokenStorage.read();
      _setState(
        _pair == null ? AuthState.unauthenticated : AuthState.authenticated,
      );
    } catch (_) {
      _pair = null;
      _setState(AuthState.unauthenticated);
      rethrow;
    }
  }

  Future<void> login() async {
    final pair = await _authenticator.login();
    await _tokenStorage.save(pair);
    _pair = pair;
    _setState(AuthState.authenticated);
  }

  /// Reuses a completed rotation when the response belongs to an older token.
  Future<TokenPair> refreshAfterUnauthorized(String usedAccessToken) async {
    final pair = _pair;
    if (pair == null) throw const UnauthenticatedSessionException();
    if (pair.accessToken != usedAccessToken) return pair;

    final pending = _refreshInFlight;
    if (pending != null) return pending;

    final refresh = _rotate(pair);
    _refreshInFlight = refresh;
    try {
      return await refresh;
    } finally {
      if (identical(_refreshInFlight, refresh)) _refreshInFlight = null;
    }
  }

  Future<TokenPair> _rotate(TokenPair oldPair) async {
    late final TokenPair rotated;
    try {
      rotated = await _backend.refresh(oldPair.refreshToken);
    } on BackendAuthException catch (error) {
      if (error.statusCode == 401) {
        _pair = null;
        _setState(AuthState.unauthenticated);
        await _tokenStorage.delete();
      }
      rethrow;
    }

    try {
      await _tokenStorage.save(rotated);
    } catch (_) {
      // The old refresh token may already be invalid. Never report it as usable.
      _pair = null;
      _setState(AuthState.unauthenticated);
      try {
        await _tokenStorage.delete();
      } catch (_) {
        // The fixed error below does not expose tokens or storage internals.
      }
      throw const AuthSessionPersistenceException();
    }
    _pair = rotated;
    _setState(AuthState.authenticated);
    return rotated;
  }

  void _setState(AuthState next) {
    _state = next;
    notifyListeners();
  }
}

class UnauthenticatedSessionException implements Exception {
  const UnauthenticatedSessionException();
}

class AuthSessionPersistenceException implements Exception {
  const AuthSessionPersistenceException();
}
