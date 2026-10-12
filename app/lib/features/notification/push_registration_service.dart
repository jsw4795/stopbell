import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';

import '../auth/auth_session.dart';
import 'device_api_client.dart';
import 'installation_storage.dart';
import 'push_permission_service.dart';

enum PushReadiness {
  ready,
  permissionRequired,
  denied,
  providerFailed,
  backendFailed,
  stale,
}

/// One installation queue. Durable intents always precede network mutations.
class PushRegistrationService {
  PushRegistrationService({
    required this.auth,
    required this.storage,
    required this.devices,
    required this.provider,
  });
  final AuthSession auth;
  final InstallationStorage storage;
  final DeviceClient devices;
  final PushProvider provider;
  Future<void> _tail = Future<void>.value();
  Future<PushReadiness>? _sync;
  int _fidEpoch = 0;
  int? _syncGeneration;
  bool _resync = false;

  void fidChanged() {
    _fidEpoch++;
    _resync = true;
  }

  Future<T> _serialize<T>(Future<T> Function() action) {
    final result = _tail.then((_) => action());
    _tail = result.then<void>((_) {}, onError: (Object _, StackTrace _) {});
    return result;
  }

  ({int generation, String session, String user})? _context({
    bool logout = false,
  }) {
    if (auth.state != AuthState.authenticated ||
        (!logout && auth.isLoggingOut) ||
        auth.sessionIdentity == null ||
        auth.userIdentity == null) {
      return null;
    }
    return (
      generation: auth.generation,
      session: auth.sessionIdentity!,
      user: auth.userIdentity!,
    );
  }

  bool _current(
    ({int generation, String session, String user}) context, {
    bool logout = false,
  }) =>
      auth.isCurrentGeneration(context.generation) &&
      auth.sessionIdentity == context.session &&
      auth.userIdentity == context.user &&
      (logout || !auth.isLoggingOut);

  Future<PushReadiness> synchronize({bool explicit = false}) {
    if (!explicit && _sync != null && _syncGeneration == auth.generation) {
      return _sync!;
    }
    final context = _context();
    if (context == null) return Future.value(PushReadiness.stale);
    final result = _serialize(() async {
      final outcome = await _register(context, explicit);
      if (kDebugMode) debugPrint('StopBell push outcome: ${outcome.name}');
      return outcome;
    });
    _sync = result;
    _syncGeneration = context.generation;
    _resync = false;
    result.whenComplete(() {
      if (identical(_sync, result)) {
        _sync = null;
        if (_resync && _current(context)) synchronize();
      }
    }).ignore();
    return result;
  }

  Future<PushReadiness> _register(
    ({int generation, String session, String user}) context,
    bool explicit,
  ) async {
    try {
      if (!_current(context)) return PushReadiness.stale;
      final record = await storage.initialize();
      if (!_current(context)) return PushReadiness.stale;
      final pending = record.pending;
      if (pending != null) {
        if (pending['session'] == context.session &&
            pending['user'] == context.user) {
          if (pending['rejected'] == true) {
            if (!explicit) return PushReadiness.backendFailed;
            record.pending = null;
            await storage.save(record);
          } else {
            await _send(record, context);
            if (!_current(context)) return PushReadiness.stale;
            if (pending['kind'] == 'disable' && !explicit) {
              return PushReadiness.backendFailed;
            }
          }
        } else {
          // A new login intent may query/reconcile; never rebind an old request.
          record.pending = null;
          await storage.save(record);
        }
      }
      if (!_current(context)) return PushReadiness.stale;
      final permission = await provider.permission();
      if (!_current(context)) return PushReadiness.stale;
      if (permission == PushPermission.notDetermined) {
        return PushReadiness.permissionRequired;
      }
      if (permission == PushPermission.denied) return PushReadiness.denied;
      String fid;
      final epoch = _fidEpoch;
      try {
        fid = await provider.registeredFid();
        if (epoch != _fidEpoch || await provider.currentFid() != fid) {
          return PushReadiness.providerFailed;
        }
      } catch (_) {
        return PushReadiness.providerFailed;
      }
      if (!_current(context)) return PushReadiness.stale;
      final server = await devices.find(
        record.id,
        record.credential,
        context.generation,
      );
      if (!_current(context)) return PushReadiness.stale;
      if (epoch != _fidEpoch || await provider.currentFid() != fid) {
        return PushReadiness.providerFailed;
      }
      if (!_current(context)) return PushReadiness.stale;
      final last = record.lastRegistration;
      if (server != null &&
          server.ownedByCurrentUser &&
          server.enabled &&
          last?['fid'] == fid &&
          last?['user'] == context.user &&
          last?['revision'] == server.revision &&
          last?['generation'] == server.generation &&
          record.revision == server.revision &&
          record.generation == server.generation) {
        return PushReadiness.ready;
      }
      await _newIntent(record, context, 'register', server, fid: fid);
      if (!_current(context)) return PushReadiness.stale;
      await _send(record, context);
      if (!_current(context)) return PushReadiness.stale;
      // Backend success on an old FID is not current readiness.
      if (epoch != _fidEpoch || await provider.currentFid() != fid) {
        return PushReadiness.providerFailed;
      }
      return _current(context) ? PushReadiness.ready : PushReadiness.stale;
    } on StaleSessionException {
      return PushReadiness.stale;
    } catch (_) {
      return PushReadiness.backendFailed;
    }
  }

  Future<void> _newIntent(
    InstallationRecord record,
    ({int generation, String session, String user}) context,
    String kind,
    DeviceState? server, {
    String? fid,
  }) async {
    if (!_current(context, logout: kind == 'disable')) {
      throw const StaleSessionException();
    }
    final base = max(record.revision, server?.revision ?? 0);
    if (base == 9223372036854775807) throw const InstallationStorageException();
    record.revision = base + 1;
    record.generation = server?.generation ?? 0;
    final payload = <String, dynamic>{
      'installationId': record.id,
      'registrationRevision': record.revision,
      'expectedOwnershipGeneration': record.generation,
      if (kind == 'register') ...{
        'platform': 'IOS',
        'pushRegistrationId': fid,
        'transferOwnership': server != null && !server.ownedByCurrentUser,
      },
    };
    record.pending = {
      'kind': kind,
      'payload': payload,
      'session': context.session,
      'sessionGeneration': context.generation,
      'user': context.user,
      'accessToken': auth.currentPair!.accessToken,
    };
    await storage.save(record);
  }

  Future<void> _send(
    InstallationRecord record,
    ({int generation, String session, String user}) context,
  ) async {
    final pending = record.pending!;
    final logout = pending['kind'] == 'disable';
    if (!_current(context, logout: logout) ||
        pending['session'] != context.session ||
        pending['user'] != context.user ||
        pending['accessToken'] is! String ||
        pending['payload'] is! Map<String, dynamic> ||
        (pending['kind'] != 'register' && pending['kind'] != 'disable')) {
      throw const StaleSessionException();
    }
    final payload = pending['payload'] as Map<String, dynamic>;
    // Re-save before every attempt, including after a previous storage failure.
    await storage.save(record);
    if (!_current(context, logout: logout)) throw const StaleSessionException();
    // Current rotated JWT is allowed only inside this same durable login identity.
    // AuthenticatedApiClient captures generation and blocks cross-login refresh/retry.
    try {
      final state = await devices.mutate(
        payload,
        pending['kind'] as String,
        record.credential,
        context.generation,
      );
      if (!_current(context, logout: logout)) {
        throw const StaleSessionException();
      }
      if (state.revision != payload['registrationRevision'] ||
          state.enabled == logout ||
          state.generation !=
              (payload['expectedOwnershipGeneration'] as int) +
                  (payload['transferOwnership'] == true ? 1 : 0)) {
        throw const DeviceResponseException();
      }
      if (kDebugMode) {
        debugPrint(
          'StopBell push stage: ${logout ? 'device-disable-confirmed' : 'device-register-confirmed'}',
        );
      }
      record.revision = max(record.revision, state.revision);
      record.generation = state.generation;
      record.lastRegistration = logout
          ? null
          : {
              'fid': payload['pushRegistrationId'],
              'user': context.user,
              'revision': state.revision,
              'generation': state.generation,
            };
      record.pending = null;
      await storage.save(record);
    } on DeviceApiException catch (error) {
      if (error.definitive && _current(context, logout: logout)) {
        pending['rejected'] = true;
        // Keep the rejection to prevent an automatic conflict/takeover loop.
        await storage.save(record);
      }
      rethrow;
    }
  }

  Future<void> beforeLogout() async {
    final context = _context(logout: true);
    if (context == null) return;
    // Includes queue wait; an expired queued logout is never sent later.
    var expired = false;
    final work = _serialize(() async {
      if (expired || !_current(context, logout: true)) return;
      final record = await storage.initialize();
      if (expired || !_current(context, logout: true)) return;
      final server = await devices.find(
        record.id,
        record.credential,
        context.generation,
      );
      if (expired ||
          !_current(context, logout: true) ||
          server == null ||
          !server.ownedByCurrentUser) {
        return;
      }
      // A fresh disable supersedes unknown registration via authenticated state + higher r.
      await _newIntent(record, context, 'disable', server);
      if (expired || !_current(context, logout: true)) return;
      await _send(record, context);
    });
    try {
      await work.timeout(const Duration(seconds: 10));
    } catch (_) {
      // Local logout proceeds; only secure, original-context intent may remain.
    } finally {
      expired = true;
    }
  }
}
