import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:stopbell/features/boarding/boarding_creation_intent.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';

import 'boarding_session_test_fakes.dart';

void main() {
  for (final status in [
    'TRACKING',
    'SUSPENDED',
    'COMPLETED',
    'CANCELLED',
    'EXPIRED',
  ]) {
    test('pending registration has no verified checkpoint or gap and remains cancellable', () {
      final session = BoardingSession.fromJson({
        ...sessionJson(
          status: 'TRACKING',
          evaluation: 'VERIFICATION_PENDING',
          reason: 'BASELINE_UNVERIFIED',
          gap: null,
        ),
        'checkpointObservedAt': null,
      });
      expect(session.status, BoardingSessionStatus.tracking);
      expect(session.isOpen, isTrue);
      expect(session.isTerminal, isFalse);
      expect(session.checkpointObservedAt, isNull);
      expect(session.remainingOccurrenceGap, isNull);
      expect(
        () => BoardingSession.fromJson({
          ...sessionJson(status: 'TRACKING'),
          'checkpointObservedAt': null,
        }),
        throwsFormatException,
      );
    });
    test('legacy persisted request preserves its exact retry body after manual input removal', () {
      final old = {
        'clientRequestId': '8be1dffa-8e2c-4547-b136-d99bfd84e45c',
        'candidateRef': 'old-ref',
        'confirmedVehicleNumber': '서울70사1234',
        'userIdentity': '1',
        'sessionIdentity': 'login-a',
        'context': sessionContext.toJson(),
        'observedAt': sessionNow.toIso8601String(),
      };
      final restored = BoardingCreationIntent.fromJson(old);
      expect(restored.body, {
        'clientRequestId': old['clientRequestId'],
        'candidateRef': 'old-ref',
        'confirmedVehicleNumber': '서울70사1234',
      });
      expect(
        BoardingCreationIntent.fromJson(restored.toJson()).body,
        restored.body,
      );
    });
    test('parses $status and terminal/open identity', () {
      final evaluation = status == 'TRACKING'
          ? 'READY'
          : status == 'SUSPENDED'
          ? 'HELD'
          : 'ENDED';
      final s = BoardingSession.fromJson(
        sessionJson(status: status, evaluation: evaluation),
      );
      expect(s.status.name.toUpperCase(), status);
      expect(s.isOpen, status == 'TRACKING' || status == 'SUSPENDED');
      expect(s.endedAt == null, s.isOpen);
    });
  }
  for (final evaluation in ['READY', 'RECOVERY_PENDING', 'HELD', 'ENDED']) {
    test('parses $evaluation without inventing nullable values', () {
      final status = evaluation == 'HELD'
          ? 'SUSPENDED'
          : evaluation == 'ENDED'
          ? 'EXPIRED'
          : 'TRACKING';
      final s = BoardingSession.fromJson(
        sessionJson(status: status, evaluation: evaluation, gap: null),
      );
      expect(s.remainingOccurrenceGap, isNull);
      expect(s.providerDataTime, isNull);
      expect(s.checkpointObservedAt!.isUtc, isTrue);
      expect(s.expiresAt.isUtc, isTrue);
      expect(s.checkpointObservedAt!.microsecond, 456);
    });
  }
  test('legacy VERIFYING response uses tracking presentation with closed evaluation', () {
    final session = BoardingSession.fromJson({
      ...sessionJson(
        status: 'VERIFYING',
        evaluation: 'VERIFICATION_PENDING',
        reason: 'BASELINE_UNVERIFIED',
        gap: null,
      ),
      'checkpointObservedAt': null,
    });
    expect(session.status, BoardingSessionStatus.tracking);
    expect(session.evaluationStatus, BoardingEvaluation.verificationPending);
    expect(session.isOpen, isTrue);
  });
  test('UTC Instant handles offsets and optional provider timestamp', () {
    final s = BoardingSession.fromJson({
      ...sessionJson(),
      'providerDataTime': '2026-10-21T10:00:00+09:00',
    });
    expect(s.providerDataTime, DateTime.utc(2026, 10, 21, 1));
  });
  for (final entry in <String, Object?>{
    'id': 0,
    'revision': -1,
    'targetOrder': 0,
    'remainingOccurrenceGap': '3',
    'checkpointObservedAt': '2026-02-30T10:00:00Z',
    'expiresAt': '2026-10-21T10:00:00',
    'routeNumber': '',
    'status': null,
  }.entries) {
    test(
      'rejects malformed ${entry.key}',
      () => expect(
        () => BoardingSession.fromJson({
          ...sessionJson(),
          entry.key: entry.value,
        }),
        throwsFormatException,
      ),
    );
  }
  test('missing required fields and non-object JSON are rejected', () {
    for (final value in [
      null,
      [],
      1,
      {},
      {'id': 1},
    ]) {
      expect(() => BoardingSession.fromJson(value), throwsFormatException);
    }
  });
  test('unknown enums are safe and never actionable', () {
    final s = BoardingSession.fromJson({
      ...sessionJson(),
      'status': 'FUTURE',
      'evaluationStatus': 'FUTURE',
      'reasonCode': 'FUTURE',
    });
    expect(s.status, BoardingSessionStatus.unknown);
    expect(s.evaluationStatus, BoardingEvaluation.unknown);
    expect(s.reasonCode, BoardingReason.unknown);
    expect(s.isOpen, isFalse);
    expect(s.canReconfirm, isFalse);
  });
  test('inconsistent lifecycle response is rejected', () {
    expect(
      () => BoardingSession.fromJson(sessionJson(status: 'SUSPENDED')),
      throwsFormatException,
    );
    expect(
      () => BoardingSession.fromJson({
        ...sessionJson(status: 'CANCELLED', evaluation: 'ENDED'),
        'endedAt': null,
      }),
      throwsFormatException,
    );
  });
  test(
    'reason code distinguishes automatic waiting and user reconfirmation',
    () {
      for (final reason in [
        'VEHICLE_MISSING',
        'IDENTITY_CONFLICT',
        'PROGRESS_REGRESSION',
        'TRAVERSAL_AMBIGUOUS',
        'CONTINUITY_GAP_EXCEEDED',
      ]) {
        expect(
          sessionFixture(
            status: 'SUSPENDED',
            evaluation: 'HELD',
            reason: reason,
          ).canReconfirm,
          isTrue,
        );
      }
      for (final reason in [
        'TEMPORARY_PROVIDER_FAILURE',
        'STALE_PROGRESS',
        'METADATA_CHANGED',
      ]) {
        expect(
          sessionFixture(
            status: 'SUSPENDED',
            evaluation: 'HELD',
            reason: reason,
          ).canReconfirm,
          isFalse,
        );
      }
    },
  );
  test('request IDs have secure UUID v4 layout and independent intentions', () {
    final ids = List.generate(100, (_) => newBoardingRequestId());
    expect(ids.toSet(), hasLength(100));
    for (final id in ids) {
      expect(isBoardingUuid(id), isTrue);
      expect(id[14], '4');
      expect('89ab', contains(id[19]));
    }
  });
  test(
    'secure storage roundtrip uses login-scoped keys with minimal data',
    () async {
      TestWidgetsFlutterBinding.ensureInitialized();
      FlutterSecureStorage.setMockInitialValues({});
      const storage = SecureBoardingIntentStorage();
      final intent = BoardingCreationIntent(
        clientRequestId: newBoardingRequestId(),
        candidateRef: 'opaque',
        selectedVehicleNumber: '서울70사1234',
        userIdentity: '1',
        sessionIdentity: 'login-a',
        context: sessionContext,
        observedAt: sessionNow,
      );
      await storage.save(intent);
      expect((await storage.read('login-a'))!.toJson(), intent.toJson());
      expect(await storage.read('login-b'), isNull);
      final raw = await const FlutterSecureStorage().readAll();
      expect(raw, hasLength(1));
      expect(jsonDecode(raw.values.single), hasLength(7));
      expect(raw.values.single, isNot(contains('Token')));
      expect(raw.values.single, isNot(contains('FID')));
      await storage.delete('login-b');
      expect(await storage.read('login-a'), isNotNull);
      await storage.delete('login-a');
      expect(await storage.read('login-a'), isNull);
    },
  );
  test(
    're-entered storage read waits for a durable write from prior instance',
    () async {
      TestWidgetsFlutterBinding.ensureInitialized();
      FlutterSecureStorage.setMockInitialValues({});
      final secure = DelayedBoardingSecureStorage();
      final first = SecureBoardingIntentStorage(storage: secure);
      final second = SecureBoardingIntentStorage(storage: secure);
      final intent = BoardingCreationIntent(
        clientRequestId: newBoardingRequestId(),
        candidateRef: 'opaque',
        selectedVehicleNumber: '서울70사1234',
        userIdentity: '1',
        sessionIdentity: 'storage-race',
        context: sessionContext,
        observedAt: sessionNow,
      );
      final saving = first.save(intent);
      await secure.writeStarted.future;
      var readCompleted = false;
      final reading = second.read(intent.sessionIdentity).then((value) {
        readCompleted = true;
        return value;
      });
      await Future<void>.delayed(Duration.zero);
      expect(readCompleted, isFalse);
      secure.releaseWrite.complete();
      await saving;
      expect((await reading)!.clientRequestId, intent.clientRequestId);
      await second.delete(intent.sessionIdentity);
    },
  );
  test(
    'failed serialized write does not poison later storage operations',
    () async {
      FlutterSecureStorage.setMockInitialValues({});
      final secure = DelayedBoardingSecureStorage()..failWrite = true;
      final storage = SecureBoardingIntentStorage(storage: secure);
      final intent = BoardingCreationIntent(
        clientRequestId: newBoardingRequestId(),
        candidateRef: 'opaque',
        selectedVehicleNumber: '서울70사1234',
        userIdentity: '1',
        sessionIdentity: 'failed-storage-race',
        context: sessionContext,
        observedAt: sessionNow,
      );
      final saving = storage.save(intent);
      secure.releaseWrite.complete();
      await expectLater(saving, throwsStateError);
      expect(await storage.read(intent.sessionIdentity), isNull);
      secure.failWrite = false;
      await storage.save(intent);
      expect((await storage.read(intent.sessionIdentity))!.body, intent.body);
      await storage.delete(intent.sessionIdentity);
    },
  );
}
