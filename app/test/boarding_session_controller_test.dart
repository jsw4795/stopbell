import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/testing.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/boarding/boarding_creation_intent.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';
import 'package:stopbell/features/boarding/boarding_session_controller.dart';

import 'boarding_session_test_fakes.dart';
import 'boarding_test_fakes.dart';

void main() {
  late AuthSession auth;
  late FakeBoardingSessions api;
  late MemoryBoardingIntents storage;
  late BoardingSessionController c;
  late BoardingStorage tokens;
  late MockClient transport;
  Future<void> start() async => c.initialize();
  Future<void> create() =>
      c.create(sessionCandidate(), '서울70사1234', sessionContext);
  setUp(() async {
    transport = MockClient((_) async => boardingJson({}, 204));
    tokens = BoardingStorage()
      ..pair = TokenPair(accessToken: sessionJwt('1'), refreshToken: 'refresh');
    auth = await sessionAuth(transport, tokens: tokens);
    api = FakeBoardingSessions();
    storage = MemoryBoardingIntents();
    c = BoardingSessionController(
      auth: auth,
      client: api,
      storage: storage,
      now: () => sessionNow,
    );
  });
  tearDown(() {
    if (c.current) c.dispose();
    auth.dispose();
    transport.close();
  });
  test(
    'preparation OFF survives durable storage and exact uncertain retries',
    () async {
      await start();
      api.onCreate = () async =>
          throw const BoardingSessionException(SessionFailure.timeout);
      await c.create(
        sessionCandidate(),
        '서울70사1234',
        sessionContext,
        prepareNotificationEnabled: false,
      );
      expect(api.creations.single['prepareNotificationEnabled'], isFalse);
      final original = storage.records.values.single;
      expect(
        BoardingCreationIntent.fromJson(original.toJson())
            .prepareNotificationEnabled,
        isFalse,
      );
      expect(c.pending!.prepareNotificationEnabled, isFalse);
      final body = Map.of(api.creations.single);
      api.onCreate = () async => BoardingSession.fromJson({
        ...sessionJson(),
        'prepareNotificationEnabled': false,
      });
      await c.retryCreation();
      expect(api.creations.last, body);
      expect(c.session!.prepareNotificationEnabled, isFalse);
      expect(storage.records, isEmpty);
    },
  );
  test(
    'startup restores current open session without assuming READY or IDs',
    () async {
      api.currentSession = sessionFixture(
        evaluation: 'RECOVERY_PENDING',
        gap: null,
      );
      await start();
      expect(c.session!.evaluationStatus, BoardingEvaluation.recoveryPending);
      expect(c.selectionContext, isNull);
      expect(c.canCreate, isFalse);
      expect(api.creations, isEmpty);
    },
  );
  test(
    'current query error is never absence or permission to create',
    () async {
      api.onCurrent = () async =>
          throw const BoardingSessionException(SessionFailure.network);
      await start();
      expect(c.initialized, isFalse);
      expect(c.lookupFailed, isTrue);
      expect(c.canCreate, isFalse);
      await create();
      expect(api.creations, isEmpty);
      api.onCurrent = null;
      await c.initialize();
      expect(c.canCreate, isTrue);
    },
  );
  test(
    'durable original intent precedes POST and suppresses duplicate buttons',
    () async {
      await start();
      final save = Completer<void>();
      storage.beforeSave = () => save.future;
      final sending = create();
      await Future<void>.value();
      expect(api.creations, isEmpty);
      expect(c.busy, isTrue);
      await create();
      save.complete();
      final response = Completer<BoardingSession>();
      api.onCreate = () async {
        expect(storage.records, hasLength(1));
        expect(storage.records.values.single.body, api.creations.single);
        return response.future;
      };
      await Future<void>.delayed(Duration.zero);
      expect(c.creationState, BoardingCreationState.submitting);
      await create();
      expect(api.creations, hasLength(1));
      response.complete(sessionFixture());
      await sending;
      expect(c.creationState, BoardingCreationState.resolved);
      expect(c.session!.id, 123);
      expect(storage.records, isEmpty);
    },
  );
  test(
    'candidate expiring during durable save is discarded before POST',
    () async {
      c.dispose();
      var time = sessionNow;
      c = BoardingSessionController(
        auth: auth,
        client: api,
        storage: storage,
        now: () => time,
      );
      await start();
      final saved = Completer<void>();
      storage.beforeSave = () => saved.future;
      final result = create();
      time = sessionNow.add(const Duration(seconds: 61));
      saved.complete();
      await result;
      expect(api.creations, isEmpty);
      expect(storage.records, isEmpty);
      expect(c.creationState, BoardingCreationState.notStarted);
    },
  );
  test(
    'unrecognized 4xx cannot erase a possibly accepted creation intent',
    () async {
      await start();
      api.onCreate = () async => throw const BoardingSessionException(
        SessionFailure.http,
        statusCode: 408,
        code: 'UNKNOWN_TIMEOUT',
      );
      await create();
      expect(c.needsRecovery, isTrue);
      expect(c.canCreate, isFalse);
    },
  );
  test(
    'late current GET after logout never assigns previous session',
    () async {
      final response = Completer<BoardingSession?>();
      api.onCurrent = () => response.future;
      final result = start();
      await Future<void>.delayed(Duration.zero);
      await auth.logout();
      response.complete(sessionFixture());
      await result;
      expect(c.session, isNull);
    },
  );
  test(
    'logout during conflict recovery never assigns previous session',
    () async {
      await start();
      final response = Completer<BoardingSession?>();
      api.onCurrent = () => response.future;
      api.onCreate = () async => throw const BoardingSessionException(
        SessionFailure.http,
        statusCode: 409,
        code: 'BOARDING_SESSION_CONFLICT',
      );
      final result = create();
      await Future<void>.delayed(Duration.zero);
      await auth.logout();
      response.complete(sessionFixture());
      await result;
      expect(c.session, isNull);
    },
  );
  test('permission expiry refreshes only the identical selected vehicle before a new request', () async {
    c.dispose();
    var time = sessionNow;
    c = BoardingSessionController(
      auth: auth,
      client: api,
      storage: storage,
      now: () => time,
    );
    await start();
    var refreshes = 0;
    await c.create(
      sessionCandidate(),
      '',
      sessionContext,
      preparePush: () async {
        time = time.add(const Duration(seconds: 61));
        return true;
      },
      refreshSelected: (selected) async {
        refreshes++;
        return sessionCandidate(
          reference: 'refreshed-selected',
          observedAt: time,
        );
      },
    );
    expect(refreshes, 1);
    expect(api.creations.single['candidateRef'], 'refreshed-selected');
    expect(api.creations.single, isNot(contains('confirmedVehicleNumber')));
  });
  test('permission expiry cannot substitute a different vehicle with the same display number', () async {
    c.dispose();
    var time = sessionNow;
    c = BoardingSessionController(
      auth: auth,
      client: api,
      storage: storage,
      now: () => time,
    );
    await start();
    await c.create(
      sessionCandidate(),
      '',
      sessionContext,
      preparePush: () async {
        time = time.add(const Duration(seconds: 61));
        return true;
      },
      refreshSelected: (selected) async => sessionCandidate(
        selectionKey: 'different-provider-vehicle-id',
        reference: 'different-ref',
        observedAt: time,
      ),
    );
    expect(api.creations, isEmpty);
    expect(storage.records, isEmpty);
  });
  test('registered pending state is restored after screen disposal without a duplicate POST', () async {
    await start();
    api.onCreate = () async => sessionFixture(
      status: 'TRACKING',
      evaluation: 'VERIFICATION_PENDING',
      reason: 'BASELINE_UNVERIFIED',
      gap: null,
    );
    await create();
    expect(c.session!.isOpen, isTrue);
    final pending = c.session!;
    c.dispose();
    api.onCurrent = () async => pending;
    c = BoardingSessionController(
      auth: auth,
      client: api,
      storage: storage,
      now: () => sessionNow,
    );
    await c.initialize();
    expect(c.session!.status, BoardingSessionStatus.tracking);
    expect(c.canCreate, isFalse);
    expect(api.creations, hasLength(1));
  });
  test('save failure prevents sending a new POST', () async {
    await start();
    storage.failSave = true;
    await create();
    expect(api.creations, isEmpty);
    expect(c.creationState, BoardingCreationState.notStarted);
    expect(c.message, contains('전송하지 않았어요'));
  });
  test(
    'registration uses the selected candidate without a manual number field',
    () async {
      await start();
      await c.create(sessionCandidate(), '', sessionContext);
      expect(api.creations, hasLength(1));
      expect(api.creations.single, isNot(contains('confirmedVehicleNumber')));
      expect(c.session!.vehicleNumber, '서울70사1234');
    },
  );
  test('HELD, UNKNOWN, unsupported and stale references are blocked', () async {
    await start();
    for (final candidate in [
      sessionCandidate(confirmation: 'HELD'),
      sessionCandidate(confirmation: 'FUTURE'),
      sessionCandidate(tracking: 'HELD'),
      sessionCandidate(tracking: 'FUTURE'),
      sessionCandidate(
        observedAt: sessionNow.subtract(const Duration(seconds: 61)),
      ),
      sessionCandidate(observedAt: sessionNow.add(const Duration(seconds: 1))),
    ]) {
      await c.create(candidate, '서울70사1234', sessionContext);
    }
    expect(api.creations, isEmpty);
  });
  for (final failure in [
    SessionFailure.timeout,
    SessionFailure.network,
    SessionFailure.parsing,
  ]) {
    test(
      '$failure preserves uncertain intent and original retry body',
      () async {
        await start();
        api.onCreate = () async => throw BoardingSessionException(failure);
        await create();
        final body = Map.of(api.creations.single);
        expect(c.creationState, BoardingCreationState.outcomeUnknown);
        expect(c.canCreate, isFalse);
        expect(storage.records, hasLength(1));
        await c.create(
          sessionCandidate(reference: 'different'),
          '서울70사1234',
          sessionContext,
        );
        expect(api.creations, hasLength(1));
        expect(c.pending!.body, body);
        await c.recover();
        expect(api.requestCalls, 1);
        expect(c.session, isNull);
        expect(c.needsRecovery, isTrue);
        expect(c.message, contains('서버 처리 중'));
        api.onCreate = () async =>
            sessionFixture(status: 'EXPIRED', evaluation: 'ENDED', gap: null);
        await c.retryCreation();
        expect(api.creations, hasLength(2));
        expect(api.creations.last, body);
        expect(c.session!.status, BoardingSessionStatus.expired);
        expect(storage.records, isEmpty);
      },
    );
  }
  test('a retry 4xx never erases an earlier uncertain POST identity', () async {
    await start();
    api.onCreate = () async =>
        throw const BoardingSessionException(SessionFailure.timeout);
    await create();
    final uuid = c.pending!.clientRequestId;
    api.onCreate = () async => throw const BoardingSessionException(
      SessionFailure.http,
      statusCode: 409,
      code: 'BOARDING_CANDIDATE_CHANGED',
    );
    await c.retryCreation();
    expect(c.needsRecovery, isTrue);
    expect(c.pending!.clientRequestId, uuid);
    expect(c.canCreate, isFalse);
  });
  test('restart recovers terminal response by UUID before current or POST', () async {
    final intent = intentFixture(auth);
    await storage.save(intent);
    c.dispose();
    // A new AuthSession restores the same durable login identity after restart.
    final restored = await sessionAuth(transport, tokens: tokens);
    addTearDown(restored.dispose);
    expect(restored.sessionIdentity, auth.sessionIdentity);
    c = BoardingSessionController(
      auth: restored,
      client: api,
      storage: storage,
      now: () => sessionNow,
    );
    api.requestSession = sessionFixture(
      status: 'COMPLETED',
      evaluation: 'ENDED',
      gap: null,
    );
    await c.initialize();
    expect(api.requestCalls, 1);
    expect(api.currentCalls, 0);
    expect(api.creations, isEmpty);
    expect(c.session!.status, BoardingSessionStatus.completed);
    expect(c.selectionContext!.targetId, 22);
    expect(storage.records, isEmpty);
  });
  test(
    'restart zero results keeps unknown and does not automatically resend',
    () async {
      await storage.save(intentFixture(auth));
      await start();
      expect(c.needsRecovery, isTrue);
      expect(api.creations, isEmpty);
      expect(api.currentCalls, 0);
      expect(c.canCreate, isFalse);
    },
  );
  test('corrupt or unreadable pending storage blocks all creation', () async {
    storage.failRead = true;
    await start();
    await create();
    expect(api.currentCalls, 0);
    expect(api.creations, isEmpty);
    expect(c.lookupFailed, isTrue);
  });
  test('leftover Keychain record for other login is neither read into UI nor resent', () async {
    final old = intentFixture(auth);
    await storage.save(old);
    await auth.logout();
    tokens.pair = TokenPair(
      accessToken: sessionJwt('2'),
      refreshToken: 'new-refresh',
      sessionIdentity: 'new-login',
    );
    final other = await sessionAuth(transport, tokens: tokens);
    addTearDown(other.dispose);
    c = BoardingSessionController(
      auth: other,
      client: api,
      storage: storage,
      now: () => sessionNow,
    );
    await c.initialize();
    await c.retryCreation();
    expect(c.pending, isNull);
    expect(c.session, isNull);
    expect(api.requestCalls, 0);
    expect(api.creations, isEmpty);
    expect(storage.records[old.sessionIdentity], isNotNull);
  });
  test('mismatched user inside current login key is not recovered', () async {
    final original = intentFixture(auth);
    storage.records[auth.sessionIdentity!] = BoardingCreationIntent(
      clientRequestId: original.clientRequestId,
      candidateRef: original.candidateRef,
      selectedVehicleNumber: original.selectedVehicleNumber,
      userIdentity: 'another-user',
      sessionIdentity: auth.sessionIdentity!,
      context: sessionContext,
      observedAt: sessionNow,
    );
    await start();
    expect(api.requestCalls, 0);
    expect(c.pending, isNull);
    expect(api.creations, isEmpty);
  });
  for (final dispose in [false, true]) {
    test(
      'late create after ${dispose ? 'dispose' : 'logout'} cannot resurrect UI or erase pending',
      () async {
        await start();
        final response = Completer<BoardingSession>();
        api.onCreate = () => response.future;
        final result = create();
        await Future<void>.delayed(Duration.zero);
        if (dispose) {
          c.dispose();
        } else {
          await auth.logout();
        }
        response.complete(sessionFixture());
        await result;
        expect(c.session, isNull);
        expect(storage.records, hasLength(1));
      },
    );
  }
  test('logout while saving intent never starts POST', () async {
    await start();
    final save = Completer<void>();
    storage.beforeSave = () => save.future;
    final result = create();
    await auth.logout();
    save.complete();
    await result;
    expect(api.creations, isEmpty);
  });
  test('real unsupported response creates no fake session and clears definitive first intent', () async {
    await start();
    api.onCreate = () async => throw const BoardingSessionException(
      SessionFailure.http,
      statusCode: 409,
      code: 'BOARDING_TRACKING_UNSUPPORTED',
    );
    await create();
    expect(c.session, isNull);
    expect(c.message, contains('실제 추적을 시작할 수 없어요'));
    expect(c.pending, isNull);
  });
  test(
    'existing-session conflict recovers current without new UUID replacement',
    () async {
      await start();
      api.currentSession = sessionFixture();
      api.onCreate = () async => throw const BoardingSessionException(
        SessionFailure.http,
        statusCode: 409,
        code: 'BOARDING_SESSION_CONFLICT',
      );
      await create();
      expect(c.session!.id, 123);
      expect(api.currentCalls, 2);
      expect(api.creations, hasLength(1));
    },
  );
  test('resolved-intent cleanup failure blocks new UUID until storage cleanup succeeds', () async {
    await start();
    storage.failDelete = true;
    api.onCreate = () async =>
        sessionFixture(status: 'EXPIRED', evaluation: 'ENDED', gap: null);
    await create();
    expect(c.canCreate, isFalse);
    expect(c.pending, isNotNull);
    storage.failDelete = false;
    api.requestSession = c.session;
    await c.recover();
    expect(c.canCreate, isTrue);
  });
  test('late GET cannot undo successful cancel; duplicates blocked and current revision used', () async {
    api.currentSession = sessionFixture();
    await start();
    final old = Completer<BoardingSession>();
    api.onFind = () => old.future;
    final get = c.refresh();
    final result = Completer<BoardingSession>();
    api.onCancel = () => result.future;
    final cancel = c.cancel();
    await c.cancel();
    expect(api.cancellations, [(123, 5)]);
    result.complete(
      sessionFixture(
        status: 'CANCELLED',
        evaluation: 'ENDED',
        revision: 6,
        gap: null,
      ),
    );
    await cancel;
    old.complete(sessionFixture());
    await get;
    expect(c.session!.status, BoardingSessionStatus.cancelled);
    expect(c.session!.revision, 6);
  });
  for (final status in ['COMPLETED', 'EXPIRED']) {
    test('cancel race respects already $status response', () async {
      api.currentSession = sessionFixture();
      await start();
      api.onCancel = () async => sessionFixture(
        status: status,
        evaluation: 'ENDED',
        revision: 6,
        gap: null,
      );
      await c.cancel();
      expect(c.session!.status.name.toUpperCase(), status);
    });
  }
  for (final error in [
    const BoardingSessionException(SessionFailure.timeout),
    const BoardingSessionException(
      SessionFailure.http,
      statusCode: 409,
      code: 'BOARDING_REVISION_CONFLICT',
    ),
  ]) {
    test(
      'cancel uncertain/revision conflict GETs current authoritative state $error',
      () async {
        api.currentSession = sessionFixture();
        await start();
        api.onCancel = () async => throw error;
        api.currentSession = sessionFixture(
          status: 'EXPIRED',
          evaluation: 'ENDED',
          revision: 6,
          gap: null,
        );
        await c.cancel();
        expect(api.detailCalls, 1);
        expect(c.session!.status, BoardingSessionStatus.expired);
      },
    );
  }
  test(
    'unknown mutation plus failed GET hides trust and blocks another mutation',
    () async {
      api.currentSession = sessionFixture();
      await start();
      api.onCancel = () async =>
          throw const BoardingSessionException(SessionFailure.timeout);
      api.onFind = () async =>
          throw const BoardingSessionException(SessionFailure.network);
      await c.cancel();
      await c.cancel();
      expect(c.stateUncertain, isTrue);
      expect(api.cancellations, hasLength(1));
      api.onFind = () async => sessionFixture(
        status: 'CANCELLED',
        evaluation: 'ENDED',
        revision: 6,
        gap: null,
      );
      await c.refresh();
      expect(c.stateUncertain, isFalse);
      expect(c.session!.isTerminal, isTrue);
    },
  );
  test(
    'new GET wins over late old GET and lower revisions do not overwrite',
    () async {
      api.currentSession = sessionFixture();
      await start();
      final old = Completer<BoardingSession>();
      api.onFind = () => old.future;
      final get = c.refresh();
      api.onFind = () async => sessionFixture(revision: 9);
      await c.refresh();
      old.complete(sessionFixture(revision: 6));
      await get;
      expect(c.session!.revision, 9);
      api.onFind = () async => sessionFixture(revision: 7);
      await c.refresh();
      expect(c.session!.revision, 9);
    },
  );
  test(
    'terminal state and session identity reject stale/wrong successful detail',
    () async {
      api.currentSession = sessionFixture(
        status: 'CANCELLED',
        evaluation: 'ENDED',
        revision: 6,
        gap: null,
      );
      await start();
      api.onFind = () async => sessionFixture(revision: 10);
      await c.refresh();
      expect(c.session!.status, BoardingSessionStatus.cancelled);
      api.onFind = () async => sessionFixture(id: 999, revision: 100);
      await c.refresh();
      expect(c.session!.id, 123);
    },
  );
  test(
    'reconfirm keeps session, fresh reference, revision and actual evaluation',
    () async {
      api.currentSession = sessionFixture(
        status: 'SUSPENDED',
        evaluation: 'HELD',
        reason: 'VEHICLE_MISSING',
      );
      await start();
      await c.reconfirm(
        sessionCandidate(reference: 'new-reference'),
        '서울70사1234',
        sessionContext,
      );
      expect(api.reconfirmations, [(123, 5, 'new-reference', '서울70사1234')]);
      expect(api.creations, isEmpty);
      expect(c.session!.id, 123);
      expect(c.session!.evaluationStatus, BoardingEvaluation.recoveryPending);
      expect(c.session!.expiresAt, DateTime.utc(2026, 10, 21, 5));
    },
  );
  test(
    'reconfirm rejects a different selected vehicle and stale candidate',
    () async {
      api.currentSession = sessionFixture(
        status: 'SUSPENDED',
        evaluation: 'HELD',
        reason: 'IDENTITY_CONFLICT',
      );
      await start();
      await c.reconfirm(
        sessionCandidate(number: '서울70사9999'),
        '서울70사9999',
        sessionContext,
      );
      await c.reconfirm(
        sessionCandidate(
          observedAt: sessionNow.subtract(const Duration(minutes: 2)),
        ),
        '서울70사1234',
        sessionContext,
      );
      expect(api.reconfirmations, isEmpty);
      expect(api.creations, isEmpty);
    },
  );
  for (final code in [
    'BOARDING_RECONFIRM_REQUIRED',
    'BOARDING_CANDIDATE_CHANGED',
    'BOARDING_REVISION_CONFLICT',
    'BOARDING_TRACKING_UNSUPPORTED',
  ]) {
    test(
      'reconfirm handles $code without creating/replacing session',
      () async {
        api.currentSession = sessionFixture(
          status: 'SUSPENDED',
          evaluation: 'HELD',
          reason: 'VEHICLE_MISSING',
        );
        await start();
        api.onReconfirm = () async => throw BoardingSessionException(
          SessionFailure.http,
          statusCode: 409,
          code: code,
        );
        await c.reconfirm(sessionCandidate(), '서울70사1234', sessionContext);
        expect(c.session!.id, 123);
        expect(c.message, isNotNull);
        expect(api.creations, isEmpty);
        expect(
          api.detailCalls,
          [
                'BOARDING_RECONFIRM_REQUIRED',
                'BOARDING_REVISION_CONFLICT',
              ].contains(code)
              ? 1
              : 0,
        );
      },
    );
  }
  test(
    'reconfirm timeout retrieves cancellation and late GET cannot undo result',
    () async {
      api.currentSession = sessionFixture(
        status: 'SUSPENDED',
        evaluation: 'HELD',
        reason: 'VEHICLE_MISSING',
      );
      await start();
      final old = Completer<BoardingSession>();
      api.onFind = () => old.future;
      final get = c.refresh();
      api.onFind = () async => sessionFixture(
        status: 'CANCELLED',
        evaluation: 'ENDED',
        revision: 6,
        gap: null,
      );
      api.onReconfirm = () async =>
          throw const BoardingSessionException(SessionFailure.timeout);
      await c.reconfirm(sessionCandidate(), '서울70사1234', sessionContext);
      old.complete(sessionFixture());
      await get;
      expect(c.session!.status, BoardingSessionStatus.cancelled);
      expect(api.creations, isEmpty);
    },
  );
  for (final mutation in ['cancel', 'reconfirm', 'GET']) {
    test('logout during $mutation drops late previous-user data', () async {
      api.currentSession = sessionFixture(
        status: 'SUSPENDED',
        evaluation: 'HELD',
        reason: 'VEHICLE_MISSING',
      );
      await start();
      final response = Completer<BoardingSession>();
      api.onFind = () => response.future;
      api.onCancel = () => response.future;
      api.onReconfirm = () => response.future;
      final result = mutation == 'cancel'
          ? c.cancel()
          : mutation == 'GET'
          ? c.refresh()
          : c.reconfirm(sessionCandidate(), '서울70사1234', sessionContext);
      await auth.logout();
      response.complete(sessionFixture(revision: 6));
      await result;
      expect(c.session, isNull);
      expect(c.selectionContext, isNull);
    });
  }
  test(
    'response for another vehicle never replaces confirmed vehicle',
    () async {
      api.currentSession = sessionFixture();
      await start();
      api.onFind = () async => BoardingSession.fromJson({
        ...sessionJson(revision: 8),
        'vehicleNumber': '서울70사9999',
      });
      await c.refresh();
      expect(c.session!.vehicleNumber, '서울70사1234');
      expect(c.message, contains('응답 형식'));
    },
  );

  test(
    'GET begun before successful reconfirm cannot undo resumed response',
    () async {
      api.currentSession = sessionFixture(
        status: 'SUSPENDED',
        evaluation: 'HELD',
        reason: 'VEHICLE_MISSING',
      );
      await start();
      final old = Completer<BoardingSession>();
      api.onFind = () => old.future;
      final get = c.refresh();
      await c.reconfirm(sessionCandidate(), '서울70사1234', sessionContext);
      old.complete(
        sessionFixture(
          status: 'SUSPENDED',
          evaluation: 'HELD',
          reason: 'VEHICLE_MISSING',
        ),
      );
      await get;
      expect(c.session!.status, BoardingSessionStatus.tracking);
      expect(c.session!.revision, 6);
    },
  );
  test('uncertain cancellation followed by 404 becomes inaccessible', () async {
    api.currentSession = sessionFixture();
    await start();
    api.onCancel = () async =>
        throw const BoardingSessionException(SessionFailure.timeout);
    api.onFind = () async => throw const BoardingSessionException(
      SessionFailure.http,
      statusCode: 404,
      code: 'BOARDING_SESSION_NOT_FOUND',
    );
    await c.cancel();
    expect(c.inaccessible, isTrue);
    expect(c.stateUncertain, isTrue);
  });
  for (final mutation in ['cancel', 'reconfirm']) {
    test(
      'dispose during $mutation ignores late response and does not retry',
      () async {
        api.currentSession = sessionFixture(
          status: 'SUSPENDED',
          evaluation: 'HELD',
          reason: 'VEHICLE_MISSING',
        );
        await start();
        final response = Completer<BoardingSession>();
        api.onCancel = () => response.future;
        api.onReconfirm = () => response.future;
        final result = mutation == 'cancel'
            ? c.cancel()
            : c.reconfirm(sessionCandidate(), '서울70사1234', sessionContext);
        c.dispose();
        response.complete(sessionFixture(revision: 6));
        await result;
        expect(c.session!.revision, 5);
        expect(api.detailCalls, 0);
        expect(api.creations, isEmpty);
      },
    );
  }
  test('screen re-entry during secure save cannot miss pending intent or create a new UUID', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    FlutterSecureStorage.setMockInitialValues({});
    c.dispose();
    final secure = DelayedBoardingSecureStorage();
    final firstStore = SecureBoardingIntentStorage(storage: secure);
    c = BoardingSessionController(
      auth: auth,
      client: api,
      storage: firstStore,
      now: () => sessionNow,
    );
    await c.initialize();
    final sending = create();
    await secure.writeStarted.future;
    c.dispose();
    c = BoardingSessionController(
      auth: auth,
      client: api,
      storage: SecureBoardingIntentStorage(storage: secure),
      now: () => sessionNow,
    );
    final restoring = c.initialize();
    await Future<void>.delayed(Duration.zero);
    expect(c.initialized, isFalse);
    expect(c.canCreate, isFalse);
    secure.releaseWrite.complete();
    await sending;
    await restoring;
    expect(api.creations, isEmpty);
    expect(api.requestCalls, 1);
    expect(c.needsRecovery, isTrue);
    final original = (await firstStore.read(auth.sessionIdentity!))!;
    expect(c.pending!.body, original.body);
    await firstStore.delete(auth.sessionIdentity!);
  });
}
