import 'dart:convert';
import 'dart:math';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'boarding_candidates.dart' show boardingInstant;
import 'boarding_session.dart';
import 'boarding_session_client.dart' show isBoardingUuid;

/// Exact internal IDs from selection, never reconstructed from display names.
class BoardingSelectionContext {
  const BoardingSelectionContext({
    required this.routeId,
    required this.targetId,
    this.boardingId,
  });
  final int routeId, targetId;
  final int? boardingId;
  Map<String, Object?> toJson() => {
    'routeId': routeId,
    'targetId': targetId,
    'boardingId': boardingId,
  };
  factory BoardingSelectionContext.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    return BoardingSelectionContext(
      routeId: boardingInteger(value['routeId'], positive: true),
      targetId: boardingInteger(value['targetId'], positive: true),
      boardingId: value['boardingId'] == null
          ? null
          : boardingInteger(value['boardingId'], positive: true),
    );
  }
}

String newBoardingRequestId() {
  final random = Random.secure();
  final bytes = List.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

class BoardingCreationIntent {
  const BoardingCreationIntent({
    required this.clientRequestId,
    required this.candidateRef,
    required this.selectedVehicleNumber,
    required this.userIdentity,
    required this.sessionIdentity,
    required this.context,
    required this.observedAt,
    this.notificationOffsetStops = 2,
    this.prepareNotificationEnabled = true,
    this.legacyConfirmedVehicleNumber,
  });
  final String clientRequestId,
      candidateRef,
      selectedVehicleNumber,
      userIdentity,
      sessionIdentity;
  final String? legacyConfirmedVehicleNumber;
  final BoardingSelectionContext context;
  final DateTime observedAt;
  final bool prepareNotificationEnabled; // Stored legacy intent only.
  final int? notificationOffsetStops;
  Map<String, Object> get body => {
    'clientRequestId': clientRequestId,
    'candidateRef': candidateRef,
    'confirmedVehicleNumber': ?legacyConfirmedVehicleNumber,
    'notificationOffsetStops': ?notificationOffsetStops,
    // Make the legacy policy explicit so an unresolved old default-ON intent
    // cannot create a new-policy session when its UUID is absent on the server.
    if (notificationOffsetStops == null)
      'prepareNotificationEnabled': prepareNotificationEnabled,
  };
  Map<String, Object?> toJson() => {
    ...body,
    'selectedVehicleNumber': selectedVehicleNumber,
    'userIdentity': userIdentity,
    'sessionIdentity': sessionIdentity,
    'context': context.toJson(),
    'observedAt': observedAt.toUtc().toIso8601String(),
  };
  factory BoardingCreationIntent.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    if (value.containsKey('prepareNotificationEnabled') &&
        value['prepareNotificationEnabled'] is! bool) {
      throw const FormatException();
    }
    final offset = value['notificationOffsetStops'];
    if (offset != null &&
        (offset is! int ||
            !const [1, 2].contains(offset) ||
            value.containsKey('prepareNotificationEnabled'))) {
      throw const FormatException();
    }
    final uuid = boardingText(value['clientRequestId']);
    if (!isBoardingUuid(uuid)) throw const FormatException();
    return BoardingCreationIntent(
      clientRequestId: uuid,
      candidateRef: boardingText(value['candidateRef']),
      selectedVehicleNumber: boardingText(
        value['selectedVehicleNumber'] ?? value['confirmedVehicleNumber'],
      ),
      legacyConfirmedVehicleNumber: value['confirmedVehicleNumber'] as String?,
      userIdentity: boardingText(value['userIdentity']),
      sessionIdentity: boardingText(value['sessionIdentity']),
      context: BoardingSelectionContext.fromJson(value['context']),
      observedAt: boardingInstant(value['observedAt']),
      notificationOffsetStops: offset as int?,
      prepareNotificationEnabled:
          value['prepareNotificationEnabled'] as bool? ?? true,
    );
  }
}

abstract interface class BoardingIntentStorage {
  Future<BoardingCreationIntent?> read(String sessionIdentity);
  Future<void> save(BoardingCreationIntent intent);
  Future<void> delete(String sessionIdentity);
}

class SecureBoardingIntentStorage implements BoardingIntentStorage {
  const SecureBoardingIntentStorage({
    this.storage = const FlutterSecureStorage(),
  });
  final FlutterSecureStorage storage;
  // Session-scoped keys prevent an old asynchronous cleanup from deleting a
  // different login's record. No token, FID or Provider response is copied here.
  String _key(String identity) =>
      'boarding_creation_v1_${base64Url.encode(utf8.encode(identity))}';
  // Re-entering a screen must observe a write already started by its disposed
  // predecessor. Share this tiny storage boundary across screen instances.
  static final _pending = <String, Future<void>>{};
  Future<T> _serialize<T>(String identity, Future<T> Function() action) {
    final key = _key(identity);
    final result = (_pending[key] ?? Future<void>.value()).then(
      (_) => action(),
    );
    final tail = result.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {},
    );
    _pending[key] = tail;
    tail.whenComplete(() {
      if (identical(_pending[key], tail)) {
        _pending.remove(key);
      }
    });
    return result;
  }

  @override
  Future<BoardingCreationIntent?> read(String sessionIdentity) =>
      _serialize(sessionIdentity, () async {
        final value = await storage.read(key: _key(sessionIdentity));
        return value == null
            ? null
            : BoardingCreationIntent.fromJson(jsonDecode(value));
      });
  @override
  Future<void> save(BoardingCreationIntent intent) => _serialize(
    intent.sessionIdentity,
    () => storage.write(
      key: _key(intent.sessionIdentity),
      value: jsonEncode(intent.toJson()),
    ),
  );
  @override
  Future<void> delete(String sessionIdentity) => _serialize(
    sessionIdentity,
    () => storage.delete(key: _key(sessionIdentity)),
  );
}
