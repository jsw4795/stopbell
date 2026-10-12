import 'dart:convert';
import 'dart:math';

import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../auth/token_pair_storage.dart';

String installationRandomId() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

abstract interface class InstallationPersistence {
  Future<String?> read();
  Future<void> write(String value);
  Future<({String id, bool initialized})> marker();
  Future<void> commitMarker();
}

class SecureInstallationPersistence implements InstallationPersistence {
  static const channel = MethodChannel('com.stopbell.stopbell/push');
  static const key = 'push_installation_v1';
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  @override
  Future<String?> read() => _storage.read(key: key);
  @override
  Future<void> write(String value) => _storage.write(key: key, value: value);
  @override
  Future<({String id, bool initialized})> marker() async {
    final value = await channel.invokeMapMethod<String, dynamic>(
      'installationMarker',
    );
    if (value?['id'] is! String || value?['initialized'] is! bool) {
      throw const InstallationStorageException();
    }
    return (
      id: value!['id'] as String,
      initialized: value['initialized'] as bool,
    );
  }

  @override
  Future<void> commitMarker() =>
      channel.invokeMethod<void>('commitInstallationMarker');
}

class InstallationRecord {
  InstallationRecord({
    required this.marker,
    required this.id,
    required this.credential,
    this.revision = 0,
    this.generation = 0,
    this.pending,
    this.lastRegistration,
  });
  final String marker;
  final String id;
  final String credential;
  int revision;
  int generation;
  Map<String, dynamic>? pending;
  Map<String, dynamic>? lastRegistration;

  Map<String, dynamic> toJson() => {
    'marker': marker,
    'id': id,
    'credential': credential,
    'revision': revision,
    'generation': generation,
    'pending': pending,
    'lastRegistration': lastRegistration,
  };
}

class InstallationStorage {
  InstallationStorage(this.persistence, this.tokens);
  final InstallationPersistence persistence;
  final TokenPairStorage tokens;
  Future<InstallationRecord>? _initialization;

  Future<InstallationRecord> initialize() {
    final pending = _initialization;
    if (pending != null) return pending;
    final work = _load();
    _initialization = work;
    work.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {
        if (identical(_initialization, work)) _initialization = null;
      },
    );
    return work;
  }

  Future<InstallationRecord> _load() async {
    try {
      final marker = await persistence.marker();
      final raw = await persistence.read();
      Map<String, dynamic>? value;
      if (raw != null) {
        final decoded = jsonDecode(raw);
        if (decoded is! Map<String, dynamic> || decoded['marker'] is! String) {
          throw const InstallationStorageException();
        }
        value = decoded;
      }
      if (value == null && marker.initialized) {
        throw const InstallationStorageException();
      }
      if (value == null || value['marker'] != marker.id) {
        // App-container marker is gone on reinstall; Keychain may survive.
        // Clear tokens before marking bootstrap complete; retry is safe on failure.
        await tokens.delete();
        final random = Random.secure();
        final record = InstallationRecord(
          marker: marker.id,
          id: installationRandomId(),
          credential: base64UrlEncode(
            List<int>.generate(32, (_) => random.nextInt(256)),
          ).replaceAll('=', ''),
        );
        await save(record);
        await persistence.commitMarker();
        return record;
      }
      final id = value['id'];
      final credential = value['credential'];
      if (id is! String ||
          !RegExp(
            r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
          ).hasMatch(id) ||
          credential is! String ||
          !RegExp(r'^[A-Za-z0-9_-]{43}$').hasMatch(credential) ||
          base64UrlEncode(base64Url.decode('$credential='))
                  .replaceAll('=', '') !=
              credential ||
          (value['revision'] != null &&
              (value['revision'] is! int || (value['revision'] as int) < 0)) ||
          (value['generation'] != null &&
              (value['generation'] is! int ||
                  (value['generation'] as int) < 0)) ||
          (value['pending'] != null &&
              value['pending'] is! Map<String, dynamic>) ||
          (value['lastRegistration'] != null &&
              value['lastRegistration'] is! Map<String, dynamic>)) {
        throw const InstallationStorageException();
      }
      _validatePending(value['pending'], id);
      final record = InstallationRecord(
        marker: marker.id,
        id: id,
        credential: credential,
        revision:
            value['revision'] as int? ??
            (value['pending']?['payload']?['registrationRevision'] as int? ??
                0),
        generation: value['generation'] as int? ?? 0,
        pending: value['pending'] as Map<String, dynamic>?,
        lastRegistration: value['lastRegistration'] as Map<String, dynamic>?,
      );
      if (!marker.initialized) await persistence.commitMarker();
      return record;
    } catch (_) {
      throw const InstallationStorageException();
    }
  }

  void _validatePending(dynamic pending, String id) {
    if (pending == null) return;
    final kind = pending['kind'];
    final payload = pending['payload'];
    if ((kind != 'register' && kind != 'disable') ||
        pending['session'] is! String ||
        (pending['session'] as String).isEmpty ||
        pending['user'] is! String ||
        (pending['user'] as String).isEmpty ||
        pending['accessToken'] is! String ||
        (pending['accessToken'] as String).isEmpty ||
        pending['sessionGeneration'] is! int ||
        pending['sessionGeneration'] < 0 ||
        payload is! Map<String, dynamic> ||
        payload['installationId'] != id ||
        payload['registrationRevision'] is! int ||
        payload['registrationRevision'] < 0 ||
        payload['expectedOwnershipGeneration'] is! int ||
        payload['expectedOwnershipGeneration'] < 0 ||
        kind == 'register' &&
            (payload['platform'] != 'IOS' ||
                payload['transferOwnership'] is! bool ||
                payload['pushRegistrationId'] is! String ||
                (payload['pushRegistrationId'] as String).isEmpty ||
                (payload['pushRegistrationId'] as String).length > 255 ||
                RegExp(r'\s')
                    .hasMatch(payload['pushRegistrationId'] as String))) {
      throw const InstallationStorageException();
    }
  }

  Future<void> save(InstallationRecord record) async {
    try {
      // A single secure record prevents partially saved identity/credential/intents.
      await persistence.write(jsonEncode(record.toJson()));
    } catch (_) {
      throw const InstallationStorageException();
    }
  }
}

class InstallationStorageException implements Exception {
  const InstallationStorageException();
}
