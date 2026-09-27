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
  Future<void>? _logoutInFlight;
  bool _loggingOut = false;
  String? _rotatedRefreshTokenForLogout;
  bool _refreshRejectedForLogout = false;
  int _generation = 0;

  AuthState get state => _state;
  TokenPair? get currentPair => _pair;
  String? get accessToken => _loggingOut ? null : _pair?.accessToken;
  int get generation => _generation;
  bool get isLoggingOut => _logoutInFlight != null;
  bool isCurrentGeneration(int generation) =>
      !_loggingOut &&
      _state == AuthState.authenticated &&
      _generation == generation;

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
    if (_loggingOut || _state != AuthState.unauthenticated) {
      throw const UnauthenticatedSessionException();
    }
    final generation = _generation;
    final pair = await _authenticator.login();
    if (generation != _generation) throw const StaleSessionException();
    await _tokenStorage.save(pair);
    if (generation != _generation) throw const StaleSessionException();
    _pair = pair;
    _generation++;
    _setState(AuthState.authenticated);
  }

  Future<void> logout({Future<void> Function()? beforeLogout}) {
    final pending = _logoutInFlight;
    if (pending != null) return pending;
    if (_state != AuthState.authenticated || _pair == null) {
      return Future<void>.value();
    }
    final logout = _logout(beforeLogout);
    _logoutInFlight = logout;
    logout.whenComplete(() {
      if (identical(_logoutInFlight, logout)) _logoutInFlight = null;
    }).ignore();
    notifyListeners();
    return logout;
  }

  Future<void> _logout(Future<void> Function()? beforeLogout) async {
    final startingRefreshToken = _pair!.refreshToken;
    if (beforeLogout != null) {
      try {
        await beforeLogout();
      } catch (_) {
        // Device cleanup must not keep the local session indefinitely.
      }
    }
    _loggingOut = true;
    _generation++;
    notifyListeners();

    final pendingRefresh = _refreshInFlight;
    if (pendingRefresh != null) {
      try {
        await pendingRefresh;
      } catch (_) {
        // A rotated token is captured separately even when its old result is stale.
      }
    }
    final rotatedDuringLogout = _rotatedRefreshTokenForLogout != null;
    final refreshToken =
        _rotatedRefreshTokenForLogout ??
        _pair?.refreshToken ??
        startingRefreshToken;
    _rotatedRefreshTokenForLogout = null;

    try {
      await _tokenStorage.delete();
    } catch (_) {
      if (rotatedDuringLogout || _refreshRejectedForLogout) {
        // The previous persisted token may already be invalid. Do not expose
        // it as a usable in-memory session after a failed deletion.
        _pair = null;
        _setState(AuthState.unauthenticated);
        try {
          await _backend.logout(refreshToken);
        } catch (_) {
          // The storage failure remains the actionable error for the caller.
        }
      }
      _loggingOut = false;
      _refreshRejectedForLogout = false;
      notifyListeners();
      throw const AuthSessionPersistenceException();
    }

    _refreshRejectedForLogout = false;
    _pair = null;
    _setState(AuthState.unauthenticated);
    try {
      await _backend.logout(refreshToken);
    } catch (_) {
      throw const BackendLogoutCleanupException();
    } finally {
      _loggingOut = false;
    }
  }

  /// Reuses a completed rotation when the response belongs to an older token.
  Future<TokenPair> refreshAfterUnauthorized(
    String usedAccessToken, {
    int? generation,
  }) async {
    if (_loggingOut ||
        (generation != null && !isCurrentGeneration(generation))) {
      throw const StaleSessionException();
    }
    final pair = _pair;
    if (pair == null) throw const UnauthenticatedSessionException();
    if (pair.accessToken != usedAccessToken) return pair;

    final pending = _refreshInFlight;
    if (pending != null) return pending;

    final refresh = _rotate(pair, _generation);
    _refreshInFlight = refresh;
    try {
      return await refresh;
    } finally {
      if (identical(_refreshInFlight, refresh)) _refreshInFlight = null;
    }
  }

  Future<TokenPair> _rotate(TokenPair oldPair, int generation) async {
    late final TokenPair rotated;
    try {
      rotated = await _backend.refresh(oldPair.refreshToken);
    } on BackendAuthException catch (error) {
      if (error.statusCode == 401 && _loggingOut) {
        _refreshRejectedForLogout = true;
      }
      if (error.statusCode == 401 && isCurrentGeneration(generation)) {
        _pair = null;
        _generation++;
        _setState(AuthState.unauthenticated);
        await _tokenStorage.delete();
      }
      rethrow;
    }

    if (!isCurrentGeneration(generation)) {
      if (_loggingOut) _rotatedRefreshTokenForLogout = rotated.refreshToken;
      throw const StaleSessionException();
    }

    try {
      await _tokenStorage.save(rotated);
    } catch (_) {
      if (!isCurrentGeneration(generation)) {
        if (_loggingOut) _rotatedRefreshTokenForLogout = rotated.refreshToken;
        throw const StaleSessionException();
      }
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
    if (!isCurrentGeneration(generation)) {
      if (_loggingOut) _rotatedRefreshTokenForLogout = rotated.refreshToken;
      throw const StaleSessionException();
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

class StaleSessionException implements Exception {
  const StaleSessionException();
}

class BackendLogoutCleanupException implements Exception {
  const BackendLogoutCleanupException();
}
