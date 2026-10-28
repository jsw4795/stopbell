import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_confirmation_view.dart';
import 'package:stopbell/features/boarding/boarding_home.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';
import 'package:stopbell/features/boarding/boarding_session_view.dart';

import 'boarding_session_test_fakes.dart';
import 'boarding_test_fakes.dart';

void main() {
  late AuthSession auth;
  late FakeBoardingSessions api;
  late MemoryBoardingIntents storage;
  late List<Map<String, dynamic>> candidateValues;
  final contexts = <(int, int, int?)>[];
  setUp(() async {
    final transport = MockClient((_) async => boardingJson({}, 204));
    addTearDown(transport.close);
    auth = await sessionAuth(transport);
    addTearDown(auth.dispose);
    api = FakeBoardingSessions();
    storage = MemoryBoardingIntents();
    contexts.clear();
    candidateValues = [freshCandidate()];
  });
  Future<void> pump(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1000, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: BoardingHome(
            authSession: auth,
            searchRoutes: (_) async => [boardingRoute],
            findStops: (_) async => boardingStops,
            findCandidates:
                ({
                  required routeId,
                  required targetStopOccurrenceId,
                  boardingStopOccurrenceId,
                  expectedGeneration,
                  isRequestCurrent,
                }) async {
                  contexts.add((
                    routeId,
                    targetStopOccurrenceId,
                    boardingStopOccurrenceId,
                  ));
                  return BoardingCandidates.fromJson({
                    ...candidatesJson(candidates: candidateValues),
                    'observedAt': DateTime.now().toUtc().toIso8601String(),
                  });
                },
            onBack: () {},
            sessionClient: api,
            intentStorage: storage,
            beforeCreate: (_) async =>
                true, // Fake successful device readiness.
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> tap(WidgetTester tester, String text) async {
    final finder = find.text(text);
    await tester.ensureVisible(finder);
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  Future<void> candidates(WidgetTester tester) async {
    await tester.enterText(find.byType(TextField), '7000');
    await tap(tester, '검색');
    await tester.tap(find.byKey(const ValueKey(754)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('occurrence-22')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('find-candidates')));
    await tester.pumpAndSettle();
  }

  Future<void> select(WidgetTester tester) => tap(tester, '이 차량 번호 직접 대조');
  Future<void> confirm(WidgetTester tester, {bool reconfirm = false}) async {
    await tester.enterText(
      find.byKey(const ValueKey('confirmed-vehicle-number')),
      '서울70사1234',
    );
    await tap(tester, reconfirm ? '동일 차량 재확인 요청' : '탑승 차량 확정');
  }

  testWidgets(
    'explicit candidate selection and full number comparison precede POST; fixture success enters detail',
    (tester) async {
      await pump(tester);
      await candidates(tester);
      expect(api.creations, isEmpty);
      expect(find.byType(BoardingConfirmationView), findsNothing);
      await select(tester);
      expect(api.creations, isEmpty);
      expect(find.textContaining('차량·경로·진행 검증에 성공하면'), findsOneWidget);
      for (final number in ['1234', '서울70사1234 ']) {
        await tester.enterText(
          find.byKey(const ValueKey('confirmed-vehicle-number')),
          number,
        );
        await tap(tester, '탑승 차량 확정');
        expect(api.creations, isEmpty);
        expect(find.textContaining('전체 차량번호가 일치하지'), findsOneWidget);
      }
      await confirm(tester);
      expect(api.creations, hasLength(1));
      expect(find.byType(BoardingSessionView), findsOneWidget);
      expect(find.text('검증된 노선 방문 지점 간격: 3'), findsOneWidget);
      expect(find.textContaining('기기 시간'), findsWidgets);
    },
  );
  testWidgets(
    'HELD and missing full number stay visible but cannot be selected',
    (tester) async {
      candidateValues = [
        freshCandidate(confirmation: 'HELD'),
        {...freshCandidate(), 'vehicleNumber': null, 'candidateRef': null},
      ];
      await pump(tester);
      await candidates(tester);
      final buttons = tester.widgetList<OutlinedButton>(
        find.widgetWithText(OutlinedButton, '확정 불가 · 차량 정보 또는 추적 지원 확인 보류'),
      );
      expect(buttons, hasLength(2));
      expect(buttons.every((b) => b.onPressed == null), isTrue);
      expect(api.creations, isEmpty);
    },
  );
  testWidgets(
    'changing draft candidate/context discards entered number without POST',
    (tester) async {
      await pump(tester);
      await candidates(tester);
      await select(tester);
      await tester.enterText(
        find.byKey(const ValueKey('confirmed-vehicle-number')),
        '서울70사1234',
      );
      await tap(tester, '다른 후보 선택 · 다시 조회');
      await tester.tap(find.byKey(const ValueKey('occurrence-11')));
      await tester.pump();
      candidateValues = [
        {...freshCandidate(), 'candidateRef': 'second-reference'},
      ];
      await tester.tap(find.byKey(const ValueKey('find-candidates')));
      await tester.pumpAndSettle();
      await select(tester);
      expect(
        tester
            .widget<TextField>(
              find.byKey(const ValueKey('confirmed-vehicle-number')),
            )
            .controller!
            .text,
        isEmpty,
      );
      expect(contexts.last, (754, 11, null));
      expect(api.creations, isEmpty);
    },
  );
  testWidgets('stale selected candidate cannot POST after entry', (
    tester,
  ) async {
    candidateValues = [
      {
        ...freshCandidate(),
        'observedAt': DateTime.now()
            .toUtc()
            .subtract(const Duration(seconds: 61))
            .toIso8601String(),
      },
    ];
    await pump(tester);
    await candidates(tester);
    await select(tester);
    await confirm(tester);
    expect(api.creations, isEmpty);
    expect(find.textContaining('후보가 오래됐어요'), findsOneWidget);
  });
  testWidgets(
    'unsupported production code never displays fabricated TRACKING',
    (tester) async {
      api.onCreate = () async => throw const BoardingSessionException(
        SessionFailure.http,
        statusCode: 409,
        code: 'BOARDING_TRACKING_UNSUPPORTED',
      );
      await pump(tester);
      await candidates(tester);
      await select(tester);
      await confirm(tester);
      expect(find.byType(BoardingSessionView), findsNothing);
      expect(find.textContaining('실제 추적을 시작할 수 없어요'), findsOneWidget);
      expect(storage.records, isEmpty);
    },
  );
  testWidgets(
    'pending creation disables duplicate button and preserves durable body',
    (tester) async {
      final result = Completer<BoardingSession>();
      api.onCreate = () => result.future;
      await pump(tester);
      await candidates(tester);
      await select(tester);
      await tester.enterText(
        find.byKey(const ValueKey('confirmed-vehicle-number')),
        '서울70사1234',
      );
      await tap(tester, '탑승 차량 확정');
      expect(api.creations, hasLength(1));
      expect(storage.records, hasLength(1));
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, '원래 생성 요청 그대로 재시도'),
            )
            .onPressed,
        isNull,
      );
      result.complete(sessionFixture());
      await tester.pumpAndSettle();
      expect(find.byType(BoardingSessionView), findsOneWidget);
    },
  );
  testWidgets(
    'uncertain POST, empty lookup and explicit retry reuse same original identity',
    (tester) async {
      api.onCreate = () async =>
          throw const BoardingSessionException(SessionFailure.timeout);
      await pump(tester);
      await candidates(tester);
      await select(tester);
      await confirm(tester);
      final original = Map.of(api.creations.single);
      expect(find.text('원래 생성 요청 결과 확인'), findsOneWidget);
      expect(find.byType(BoardingConfirmationView), findsNothing);
      await tap(tester, '결과 다시 조회');
      expect(api.creations, hasLength(1));
      expect(find.textContaining('서버 처리 중'), findsOneWidget);
      api.onCreate = () async =>
          sessionFixture(status: 'EXPIRED', evaluation: 'ENDED', gap: null);
      await tap(tester, '원래 생성 요청 그대로 재시도');
      expect(api.creations.last, original);
      expect(find.textContaining('EXPIRED ·'), findsOneWidget);
    },
  );
  testWidgets(
    'restart prioritizes durable terminal recovery without automatic POST',
    (tester) async {
      await storage.save(intentFixture(auth));
      api.requestSession = sessionFixture(
        status: 'COMPLETED',
        evaluation: 'ENDED',
        gap: null,
      );
      await pump(tester);
      expect(api.requestCalls, 1);
      expect(api.currentCalls, 0);
      expect(api.creations, isEmpty);
      expect(find.textContaining('COMPLETED ·'), findsOneWidget);
      expect(find.textContaining('실제 승객 하차 확인을 뜻하지'), findsOneWidget);
    },
  );
  testWidgets('storage failure prevents POST and explains recovery safety', (
    tester,
  ) async {
    storage.failSave = true;
    await pump(tester);
    await candidates(tester);
    await select(tester);
    await confirm(tester);
    expect(api.creations, isEmpty);
    expect(find.textContaining('안전하게 저장하지 못해 전송하지'), findsOneWidget);
  });
  testWidgets(
    'current session query error has retry and never opens duplicate creation',
    (tester) async {
      api.onCurrent = () async =>
          throw const BoardingSessionException(SessionFailure.network);
      await pump(tester);
      expect(find.byType(TextField), findsNothing);
      expect(find.text('결과 다시 조회'), findsOneWidget);
      api.onCurrent = null;
      api.currentSession = sessionFixture(
        evaluation: 'RECOVERY_PENDING',
        gap: null,
      );
      await tap(tester, '결과 다시 조회');
      expect(find.byType(BoardingSessionView), findsOneWidget);
      expect(find.textContaining('RECOVERY_PENDING'), findsOneWidget);
      expect(find.textContaining('검증된 노선 방문 지점 간격'), findsNothing);
    },
  );
  for (final evaluation in ['RECOVERY_PENDING', 'HELD']) {
    testWidgets(
      '$evaluation hides historical non-null gap and distinguishes waiting',
      (tester) async {
        api.currentSession = sessionFixture(
          status: evaluation == 'HELD' ? 'SUSPENDED' : 'TRACKING',
          evaluation: evaluation,
          reason: evaluation == 'HELD' ? 'TEMPORARY_PROVIDER_FAILURE' : null,
          gap: 999,
        );
        await pump(tester);
        expect(find.textContaining('검증된 노선 방문 지점 간격'), findsNothing);
        expect(find.textContaining('999'), findsNothing);
        expect(find.textContaining('과거 검증 근거 시각'), findsOneWidget);
        if (evaluation == 'HELD') {
          expect(find.textContaining('새로운 정보를 기다리고'), findsOneWidget);
          expect(find.text('동일 차량 다시 확인'), findsNothing);
        }
      },
    );
  }
  for (final status in ['COMPLETED', 'CANCELLED', 'EXPIRED']) {
    testWidgets(
      '$status displays actual terminal state and no active actions',
      (tester) async {
        // scope=current never returns terminal. Recover it via original UUID.
        await storage.save(intentFixture(auth));
        api.requestSession = sessionFixture(
          status: status,
          evaluation: 'ENDED',
          gap: null,
        );
        await pump(tester);
        expect(find.textContaining('$status ·'), findsOneWidget);
        expect(find.text('추적 취소'), findsNothing);
        expect(find.text('동일 차량 다시 확인'), findsNothing);
      },
    );
  }
  testWidgets(
    'cancellation requires explicit dialog confirmation and honors current revision',
    (tester) async {
      api.currentSession = sessionFixture(revision: 8);
      await pump(tester);
      await tap(tester, '추적 취소');
      expect(api.cancellations, isEmpty);
      await tap(tester, '계속 추적');
      expect(api.cancellations, isEmpty);
      await tap(tester, '추적 취소');
      await tap(tester, '추적 취소 확인');
      expect(api.cancellations, [(123, 8)]);
      expect(find.textContaining('CANCELLED ·'), findsOneWidget);
    },
  );
  testWidgets(
    'cancel timeout refreshes actual EXPIRED instead of claiming cancellation',
    (tester) async {
      api.currentSession = sessionFixture();
      api.onCancel = () async =>
          throw const BoardingSessionException(SessionFailure.timeout);
      api.onFind = () async => sessionFixture(
        status: 'EXPIRED',
        evaluation: 'ENDED',
        revision: 6,
        gap: null,
      );
      await pump(tester);
      await tap(tester, '추적 취소');
      await tap(tester, '추적 취소 확인');
      expect(api.detailCalls, 1);
      expect(find.textContaining('EXPIRED ·'), findsOneWidget);
      expect(find.textContaining('CANCELLED ·'), findsNothing);
    },
  );
  testWidgets(
    'unknown cancellation with failed refresh hides cached gap and disables further mutation',
    (tester) async {
      api.currentSession = sessionFixture();
      api.onCancel = () async =>
          throw const BoardingSessionException(SessionFailure.timeout);
      api.onFind = () async =>
          throw const BoardingSessionException(SessionFailure.network);
      await pump(tester);
      await tap(tester, '추적 취소');
      await tap(tester, '추적 취소 확인');
      expect(find.textContaining('변경 결과 미확인'), findsOneWidget);
      expect(find.textContaining('검증된 노선 방문 지점 간격'), findsNothing);
      expect(
        tester
            .widget<OutlinedButton>(
              find.widgetWithText(OutlinedButton, '추적 취소'),
            )
            .onPressed,
        isNull,
      );
    },
  );
  testWidgets(
    'restored suspended session without IDs asks exact route/occurrence reselection',
    (tester) async {
      api.currentSession = sessionFixture(
        status: 'SUSPENDED',
        evaluation: 'HELD',
        reason: 'VEHICLE_MISSING',
      );
      await pump(tester);
      await tap(tester, '동일 차량 다시 확인');
      expect(find.textContaining('기존 선택 정보를 복원할 수 없어요'), findsOneWidget);
      expect(contexts, isEmpty);
      await candidates(tester);
      await select(tester);
      expect(
        tester
            .widget<TextField>(
              find.byKey(const ValueKey('confirmed-vehicle-number')),
            )
            .controller!
            .text,
        isEmpty,
      );
      await confirm(tester, reconfirm: true);
      expect(api.reconfirmations, [(123, 5, 'fresh-reference', '서울70사1234')]);
      expect(api.creations, isEmpty);
      expect(find.textContaining('RECOVERY_PENDING'), findsOneWidget);
    },
  );
  testWidgets(
    'same-auth retained context fetches new candidate and user retypes full number',
    (tester) async {
      await pump(tester);
      await candidates(tester);
      await select(tester);
      await confirm(tester);
      api.onFind = () async => sessionFixture(
        status: 'SUSPENDED',
        evaluation: 'HELD',
        revision: 6,
        reason: 'CONTINUITY_GAP_EXCEEDED',
      );
      await tap(tester, '상태 새로고침');
      candidateValues = [
        {...freshCandidate(), 'candidateRef': 'fresh-reconfirmation-reference'},
      ];
      await tap(tester, '동일 차량 다시 확인');
      expect(contexts.last, (754, 22, null));
      await select(tester);
      expect(
        tester
            .widget<TextField>(
              find.byKey(const ValueKey('confirmed-vehicle-number')),
            )
            .controller!
            .text,
        isEmpty,
      );
      await confirm(tester, reconfirm: true);
      expect(api.reconfirmations, [
        (123, 6, 'fresh-reconfirmation-reference', '서울70사1234'),
      ]);
      expect(api.creations, hasLength(1));
    },
  );
  testWidgets(
    'late GET cannot revert cancelled screen and multiple refreshes are bounded by epoch',
    (tester) async {
      api.currentSession = sessionFixture();
      await pump(tester);
      final old = Completer<BoardingSession>();
      api.onFind = () => old.future;
      await tap(tester, '상태 새로고침');
      await tap(tester, '추적 취소');
      await tap(tester, '추적 취소 확인');
      old.complete(sessionFixture(revision: 5));
      await tester.pumpAndSettle();
      expect(find.textContaining('CANCELLED ·'), findsOneWidget);
    },
  );
  testWidgets('logout during POST and disposal never show late old session', (
    tester,
  ) async {
    final response = Completer<BoardingSession>();
    api.onCreate = () => response.future;
    await pump(tester);
    await candidates(tester);
    await select(tester);
    await confirm(tester);
    await auth.logout();
    await tester.pumpAndSettle();
    expect(find.byType(BoardingSessionView), findsNothing);
    await tester.pumpWidget(const SizedBox());
    response.complete(sessionFixture());
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(storage.records, hasLength(1));
  });
}

Map<String, dynamic> freshCandidate({String confirmation = 'AVAILABLE'}) => {
  ...candidateJson(confirmation: confirmation),
  'candidateRef': confirmation == 'AVAILABLE' ? 'fresh-reference' : null,
  'observedAt': DateTime.now().toUtc().toIso8601String(),
};
