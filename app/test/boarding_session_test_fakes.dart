import 'dart:async';
import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'package:http/http.dart' as http;
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_creation_intent.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';

import 'boarding_test_fakes.dart';

final sessionNow = DateTime.utc(2026, 10, 21, 1);
String sessionJwt(String user, [String suffix = 'a']) =>
    'header.${base64Url.encode(utf8.encode(jsonEncode({'sub': user, 'jti': suffix})))}.signature';
Future<AuthSession> sessionAuth(
  http.Client transport, {
  BoardingStorage? tokens,
  String user = '1',
}) async {
  final storage = tokens ?? BoardingStorage();
  if (tokens == null) {
    storage.pair = TokenPair(
      accessToken: sessionJwt(user),
      refreshToken: 'refresh-a',
    );
  }
  final auth = AuthSession(
    authenticator: BoardingAuthenticator(),
    backend: BackendAuthClient(apiBaseUrl: boardingBaseUrl, client: transport),
    tokenStorage: storage,
  );
  await auth.initialize();
  return auth;
}

Map<String, dynamic> sessionJson({
  String status = 'TRACKING',
  String evaluation = 'READY',
  int revision = 5,
  int id = 123,
  String? reason,
  Object? gap = 3,
}) => {
  'id': id,
  'status': status,
  'revision': revision,
  'routeNumber': '7000',
  'vehicleNumber': '서울70사1234',
  'targetName': '목적 정류장',
  'notificationOffsetStops': 2,
  'preparationNotificationStatus': 'NOT_CREATED',
  'targetOrder': 9,
  'remainingOccurrenceGap': gap,
  'checkpointObservedAt': '2026-10-21T01:00:00.123456Z',
  'providerDataTime': null,
  'evaluationStatus': evaluation,
  'reasonCode': reason,
  'expiresAt': '2026-10-21T05:00:00Z',
  'endedAt': ['COMPLETED', 'CANCELLED', 'EXPIRED'].contains(status)
      ? '2026-10-21T02:00:00Z'
      : null,
};
BoardingSession sessionFixture({
  String status = 'TRACKING',
  String evaluation = 'READY',
  int revision = 5,
  int id = 123,
  String? reason,
  Object? gap = 3,
}) => BoardingSession.fromJson(
  sessionJson(
    status: status,
    evaluation: evaluation,
    revision: revision,
    id: id,
    reason: reason,
    gap: gap,
  ),
);
BoardingCandidate sessionCandidate({
  String reference = 'fresh-reference',
  String? selectionKey = 'stable-selected-vehicle',
  String number = '서울70사1234',
  String confirmation = 'AVAILABLE',
  String tracking = 'CONDITIONAL',
  DateTime? observedAt,
}) => BoardingCandidate.fromJson({
  ...candidateJson(
    number: number,
    confirmation: confirmation,
    tracking: tracking,
  ),
  'candidateRef': reference,
  'selectionKey': selectionKey,
  'observedAt': (observedAt ?? sessionNow).toIso8601String(),
});
const sessionContext = BoardingSelectionContext(
  routeId: 754,
  targetId: 22,
  boardingId: 11,
);
BoardingCreationIntent intentFixture(AuthSession auth) =>
    BoardingCreationIntent(
      clientRequestId: '8be1dffa-8e2c-4547-b136-d99bfd84e45c',
      candidateRef: 'original-reference',
      selectedVehicleNumber: '서울70사1234',
      userIdentity: auth.userIdentity!,
      sessionIdentity: auth.sessionIdentity!,
      context: sessionContext,
      observedAt: sessionNow,
    );

class MemoryBoardingIntents implements BoardingIntentStorage {
  final records = <String, BoardingCreationIntent>{};
  bool failSave = false, failRead = false, failDelete = false;
  Future<void> Function()? beforeSave;
  @override
  Future<BoardingCreationIntent?> read(String identity) async {
    if (failRead) throw StateError('storage');
    return records[identity];
  }

  @override
  Future<void> save(BoardingCreationIntent intent) async {
    await beforeSave?.call();
    if (failSave) throw StateError('storage');
    // Exercise actual record serialization at the durable boundary.
    records[intent.sessionIdentity] = BoardingCreationIntent.fromJson(
      jsonDecode(jsonEncode(intent.toJson())),
    );
  }

  @override
  Future<void> delete(String identity) async {
    if (failDelete) throw StateError('storage');
    records.remove(identity);
  }
}

class FakeBoardingSessions implements BoardingSessionClient {
  BoardingSession? currentSession, requestSession;
  int currentCalls = 0, requestCalls = 0, detailCalls = 0;
  final creations = <Map<String, Object>>[];
  final cancellations = <(int, int)>[];
  final reconfirmations = <(int, int, String, String)>[];
  Future<BoardingSession> Function()? onCreate, onFind, onCancel, onReconfirm;
  Future<BoardingSession?> Function()? onCurrent, onRequest;
  @override
  Future<BoardingSession> create(
    Map<String, Object> body, {
    required int generation,
    required bool Function() current,
  }) async {
    creations.add(Map.of(body));
    return onCreate == null ? sessionFixture() : onCreate!();
  }

  @override
  Future<BoardingSession?> findCurrent({
    required int generation,
    required bool Function() current,
  }) async {
    currentCalls++;
    return onCurrent == null ? currentSession : onCurrent!();
  }

  @override
  Future<BoardingSession?> findRequest(
    String uuid, {
    required int generation,
    required bool Function() current,
  }) async {
    requestCalls++;
    return onRequest == null ? requestSession : onRequest!();
  }

  @override
  Future<BoardingSession> find(
    int id, {
    required int generation,
    required bool Function() current,
  }) async {
    detailCalls++;
    return onFind == null ? currentSession! : onFind!();
  }

  @override
  Future<BoardingSession> cancel(
    int id,
    int revision, {
    required int generation,
    required bool Function() current,
  }) async {
    cancellations.add((id, revision));
    return onCancel == null
        ? sessionFixture(
            status: 'CANCELLED',
            evaluation: 'ENDED',
            revision: revision + 1,
            reason: 'USER_CANCELLED',
            gap: null,
          )
        : onCancel!();
  }

  @override
  Future<BoardingSession> reconfirm(
    int id,
    int revision,
    String reference,
    String number, {
    required int generation,
    required bool Function() current,
  }) async {
    reconfirmations.add((id, revision, reference, number));
    return onReconfirm == null
        ? sessionFixture(
            revision: revision + 1,
            evaluation: 'RECOVERY_PENDING',
            gap: null,
          )
        : onReconfirm!();
  }
}

class DelayedBoardingSecureStorage extends FlutterSecureStorage {
  final writeStarted = Completer<void>();
  final releaseWrite = Completer<void>();
  bool failWrite = false;
  @override
  Future<void> write({
    required String key,
    required String? value,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    if (!writeStarted.isCompleted) {
      writeStarted.complete();
    }
    await releaseWrite.future;
    if (failWrite) {
      throw StateError('storage');
    }
    await super.write(key: key, value: value);
  }
}
