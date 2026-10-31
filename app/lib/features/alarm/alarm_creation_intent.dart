import 'dart:convert';
import 'dart:math';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

String newAlarmRequestId() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

class AlarmCreationIntent {
  const AlarmCreationIntent({
    required this.clientRequestId,
    required this.targetStopOccurrenceId,
    required this.notifyOneStopBefore,
    required this.notifyOneStopAfter,
    required this.userIdentity,
    required this.sessionIdentity,
    required this.createdAt,
  });

  final String clientRequestId, userIdentity, sessionIdentity;
  final int targetStopOccurrenceId;
  final bool notifyOneStopBefore, notifyOneStopAfter;
  final DateTime createdAt;

  Map<String, Object> get body => {
    'clientRequestId': clientRequestId,
    'targetStopOccurrenceId': targetStopOccurrenceId,
    'notifyOneStopBefore': notifyOneStopBefore,
    'notifyOneStopAfter': notifyOneStopAfter,
  };

  Map<String, Object> toJson() => {
    ...body,
    'userIdentity': userIdentity,
    'sessionIdentity': sessionIdentity,
    'createdAt': createdAt.toUtc().toIso8601String(),
  };

  factory AlarmCreationIntent.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    final id = value['clientRequestId'];
    final target = value['targetStopOccurrenceId'];
    final before = value['notifyOneStopBefore'];
    final after = value['notifyOneStopAfter'];
    final user = value['userIdentity'];
    final session = value['sessionIdentity'];
    final timestamp = value['createdAt'];
    if (id is! String ||
        !RegExp(
          r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
        ).hasMatch(id) ||
        target is! int ||
        target <= 0 ||
        before is! bool ||
        after is! bool ||
        user is! String ||
        user.isEmpty ||
        session is! String ||
        session.isEmpty ||
        timestamp is! String) {
      throw const FormatException();
    }
    final created = DateTime.tryParse(timestamp);
    if (created == null || !created.isUtc) throw const FormatException();
    return AlarmCreationIntent(
      clientRequestId: id,
      targetStopOccurrenceId: target,
      notifyOneStopBefore: before,
      notifyOneStopAfter: after,
      userIdentity: user,
      sessionIdentity: session,
      createdAt: created,
    );
  }
}

abstract interface class AlarmIntentStorage {
  Future<AlarmCreationIntent?> read(String sessionIdentity);
  Future<bool> saveIfAbsent(AlarmCreationIntent intent);
  Future<bool> deleteIfMatching(String sessionIdentity, String requestId);
}

class SecureAlarmIntentStorage implements AlarmIntentStorage {
  const SecureAlarmIntentStorage({this.storage = const FlutterSecureStorage()});
  final FlutterSecureStorage storage;
  static final _pending = <String, Future<void>>{};
  String _key(String identity) =>
      'alarm_creation_v1_${base64Url.encode(utf8.encode(identity))}';

  Future<T> _serialize<T>(String session, Future<T> Function(String) action) {
    final key = _key(session);
    final result = (_pending[key] ?? Future<void>.value()).then(
      (_) => action(key),
    );
    final tail = result.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {},
    );
    _pending[key] = tail;
    tail.whenComplete(() {
      if (identical(_pending[key], tail)) _pending.remove(key);
    });
    return result;
  }

  @override
  Future<AlarmCreationIntent?> read(String sessionIdentity) =>
      _serialize(sessionIdentity, (key) async {
        final value = await storage.read(key: key);
        return value == null
            ? null
            : AlarmCreationIntent.fromJson(jsonDecode(value));
      });

  @override
  Future<bool> saveIfAbsent(AlarmCreationIntent intent) =>
      _serialize(intent.sessionIdentity, (key) async {
        if (await storage.read(key: key) != null) return false;
        await storage.write(key: key, value: jsonEncode(intent.toJson()));
        return true;
      });

  @override
  Future<bool> deleteIfMatching(String sessionIdentity, String requestId) =>
      _serialize(sessionIdentity, (key) async {
        final value = await storage.read(key: key);
        if (value == null) return true;
        final intent = AlarmCreationIntent.fromJson(jsonDecode(value));
        if (intent.sessionIdentity != sessionIdentity ||
            intent.clientRequestId != requestId) {
          return false;
        }
        await storage.delete(key: key);
        return true;
      });
}
