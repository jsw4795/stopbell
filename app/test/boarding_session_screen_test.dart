import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_candidate_client.dart';
import 'package:stopbell/features/boarding/boarding_confirmation_view.dart';
import 'package:stopbell/features/boarding/boarding_home.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';
import 'package:stopbell/features/boarding/boarding_session_view.dart';

import 'boarding_session_test_fakes.dart';
import 'boarding_test_fakes.dart';

void main() {
  for (final entry in {
    'NOT_CREATED': '아직 생성되지 않음',
    'PENDING': '발송 대기 중',
    'ACCEPTED': '전송 서비스 접수 완료 · 기기 표시 미확인',
    'NO_RECIPIENT': '수신 가능한 기기 없음',
    'FAILED': '발송 실패',
    'EXPIRED': '발송 기한 만료',
  }.entries) {
    testWidgets(
      'detail separates timing, actual destination and ${entry.key}',
      (tester) async {
        final s = BoardingSession.fromJson({
          ...sessionJson(),
          'notificationOffsetStops': 1,
          'preparationNotificationStatus': entry.key,
        });
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: SingleChildScrollView(
                child: BoardingSessionDetails(session: s),
              ),
            ),
          ),
        );
        expect(find.text('목적 정류장'), findsOneWidget);
        expect(find.text('1개 지점 전'), findsOneWidget);
        expect(find.text('상세 정보'), findsNothing);
        expect(find.textContaining('목적지 알림: ON'), findsNothing);
      },
    );
  }
  testWidgets('verified name and remaining gap are independently shown', (
    tester,
  ) async {
    final session = BoardingSession.fromJson({
      ...sessionJson(),
      'currentStopName': '평내호평역',
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(children: [BoardingSessionDetails(session: session)]),
        ),
      ),
    );
    expect(find.text('평내호평역 부근 지나는 중'), findsOneWidget);
    expect(find.text('최근 관측 기준'), findsOneWidget);
    expect(find.text('3개 지점 남음'), findsOneWidget);
  });

  for (final theme in [StopBellTheme.light, StopBellTheme.dark]) {
    testWidgets(
      'detail fits a narrow screen with large text in ${theme.brightness}',
      (tester) async {
        tester.view.physicalSize = const Size(320, 600);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        await tester.pumpWidget(
          MaterialApp(
            theme: theme,
            home: MediaQuery(
              data: const MediaQueryData(
                size: Size(320, 600),
                textScaler: TextScaler.linear(2),
              ),
              child: Scaffold(
                body: ListView(
                  children: [BoardingSessionDetails(session: sessionFixture())],
                ),
              ),
            ),
          ),
        );
        await tester.drag(find.byType(ListView), const Offset(0, -1500));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(find.text('하차 알림'), findsOneWidget);
      },
    );
  }
  testWidgets('timeline uses only the supplied boarding context', (
    tester,
  ) async {
    final session = BoardingSession.fromJson({
      ...sessionJson(),
      'targetName': '아주 긴 목표 정류장 이름과 환승센터 방면',
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(
            children: [
              BoardingSessionDetails(
                session: session,
                boardingName: '선택한 승차 정류장',
              ),
            ],
          ),
        ),
      ),
    );
    expect(find.text('선택한 승차 정류장'), findsOneWidget);
    expect(find.text('아주 긴 목표 정류장 이름과 환승센터 방면'), findsOneWidget);
    expect(find.text('승차 정류장 정보 없음'), findsNothing);
    expect(find.textContaining('분 후'), findsNothing);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(children: [BoardingSessionDetails(session: session)]),
        ),
      ),
    );
    expect(find.text('승차 정류장 정보 없음'), findsOneWidget);
    expect(find.text('선택한 승차 정류장'), findsNothing);
  });
  testWidgets('uncertain response hides an otherwise valid gap', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(
            children: [
              BoardingSessionDetails(
                session: sessionFixture(gap: 3),
                stateUncertain: true,
              ),
            ],
          ),
        ),
      ),
    );
    expect(find.text('하차 알림 (확인 필요)'), findsOneWidget);
    expect(find.textContaining('검증된 노선 방문 지점 간격'), findsNothing);
  });
  testWidgets('ready without a gap does not invent progress', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ListView(
            children: [
              BoardingSessionDetails(session: sessionFixture(gap: null)),
            ],
          ),
        ),
      ),
    );
    expect(find.textContaining('검증된 노선 방문 지점 간격'), findsNothing);
  });
  late AuthSession auth;
  late FakeBoardingSessions api;
  late MemoryBoardingIntents storage;
  late List<Map<String, dynamic>> candidateValues;
  var rejectLegacyBoarding = false;
  final contexts = <(int, int, int?)>[];
  setUp(() async {
    final transport = MockClient((_) async => boardingJson({}, 204));
    addTearDown(transport.close);
    auth = await sessionAuth(transport);
    addTearDown(auth.dispose);
    api = FakeBoardingSessions();
    storage = MemoryBoardingIntents();
    contexts.clear();
    rejectLegacyBoarding = false;
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
                  if (rejectLegacyBoarding &&
                      boardingStopOccurrenceId != null) {
                    throw const BoardingCandidateException(
                      CandidateFailure.http,
                      statusCode: 400,
                      code: 'INVALID_REQUEST',
                    );
                  }
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
    await tester.tap(find.byKey(const ValueKey('occurrence-11')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('occurrence-22')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('find-candidates')));
    await tester.pumpAndSettle();
  }

  Future<void> select(WidgetTester tester) async {
    await tap(tester, '서울70사1234');
  }

  Future<void> confirm(WidgetTester tester, {bool reconfirm = false}) async {
    await tap(tester, reconfirm ? '동일 차량 재확인 요청' : '이 차량으로 하차 알림 설정');
  }

  testWidgets(
    'explicit candidate selection without manual number input precedes POST; fixture success enters detail',
    (tester) async {
      await pump(tester);
      await candidates(tester);
      expect(api.creations, isEmpty);
      expect(find.byType(BoardingConfirmationView), findsNothing);
      final next = find.byKey(const ValueKey('continue-boarding-candidate'));
      expect(tester.widget<FilledButton>(next).onPressed, isNull);
      await tap(tester, '서울70사1234');
      expect(find.byType(BoardingConfirmationView), findsNothing);
      expect(api.creations, isEmpty);
      expect(tester.widget<FilledButton>(next).onPressed, isNotNull);
      await tap(tester, '이 차량으로 하차 알림 설정');
      expect(api.creations, hasLength(1));
      expect(find.byType(BoardingConfirmationView), findsNothing);
      expect(api.creations.single, containsPair('routeId', 754));
      expect(
        api.creations.single,
        containsPair('boardingStopOccurrenceId', 11),
      );
      expect(api.creations.single, containsPair('targetStopOccurrenceId', 22));
      expect(
        api.creations.single,
        containsPair('confirmedVehicleNumber', '서울70사1234'),
      );
      expect(api.creations.single, isNot(contains('candidateRef')));
      expect(find.byType(BoardingSessionView), findsOneWidget);
      expect(find.text('3개 지점 남음'), findsOneWidget);
      expect(find.text('상세 정보'), findsNothing);
      expect(find.textContaining('승차 정류장 정보 없음'), findsNothing);
    },
  );
  testWidgets('notification offset is selected before direct registration', (
    tester,
  ) async {
    api.onCreate = () async => BoardingSession.fromJson({
      ...sessionJson(),
      'notificationOffsetStops': 1,
    });
    await pump(tester);
    await candidates(tester);
    await tap(tester, '1개 전');
    await select(tester);
    await confirm(tester);
    expect(api.creations.single['notificationOffsetStops'], 1);
    expect(find.byType(BoardingSessionView), findsOneWidget);
  });

  testWidgets('duplicate numbers cannot be selected', (tester) async {
    candidateValues = [
      freshCandidate(),
      {...freshCandidate(), 'candidateRef': 'other'},
    ];
    await pump(tester);
    await candidates(tester);
    expect(find.text('선택 보류 · 차량번호 중복'), findsNWidgets(2));
    await tester.tap(find.byKey(const ValueKey('candidate-0')));
    await tester.pump();
    expect(
      tester
          .widget<FilledButton>(
            find.byKey(const ValueKey('continue-boarding-candidate')),
          )
          .onPressed,
      isNull,
    );
    expect(api.creations, isEmpty);
  });

  testWidgets(
    'HELD and missing full number stay visible but cannot be selected',
    (tester) async {
      candidateValues = [
        freshCandidate(confirmation: 'HELD'),
        {...freshCandidate(), 'vehicleNumber': null, 'candidateRef': null},
      ];
      await pump(tester);
      await candidates(tester);
      expect(find.text('선택 보류 · 차량번호 미확인'), findsOneWidget);
      expect(find.byIcon(Icons.block_outlined), findsNWidgets(2));
      await tap(tester, '서울70사1234');
      await tap(tester, '차량번호 확인 불가');
      final next = find.byKey(const ValueKey('continue-boarding-candidate'));
      expect(tester.widget<FilledButton>(next).onPressed, isNull);
      expect(api.creations, isEmpty);
    },
  );
  testWidgets('old candidate is sent without a reference', (tester) async {
    candidateValues = [
      {
        ...freshCandidate(),
        'candidateRef': null,
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
    expect(api.creations.single, isNot(contains('candidateRef')));
    expect(find.byType(BoardingSessionView), findsOneWidget);
  });
  for (final (code, message) in [
    ('BOARDING_TARGET_NOT_AHEAD', '도달했거나 이미 통과'),
    ('BOARDING_VEHICLE_UNIDENTIFIABLE', '선택한 차량을 현재 확인할 수 없어요'),
    ('BOARDING_POSITION_UNAVAILABLE', '현재 진행 위치를 확인할 수 없어요'),
    ('TRANSIT_TEMPORARILY_UNAVAILABLE', '일시적으로 확인할 수 없어요'),
  ]) {
    testWidgets('$code preserves selection for retry', (tester) async {
      api.onCreate = () async => throw BoardingSessionException(
        SessionFailure.http,
        statusCode: code == 'TRANSIT_TEMPORARILY_UNAVAILABLE' ? 503 : 409,
        code: code,
      );
      await pump(tester);
      await candidates(tester);
      await select(tester);
      await confirm(tester);
      expect(find.textContaining(message), findsOneWidget);
      expect(find.byType(BoardingSessionView), findsNothing);
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey('continue-boarding-candidate')),
            )
            .onPressed,
        isNotNull,
      );
      expect(contexts, [(754, 22, 11)]);
      expect(storage.records, isEmpty);
      api.onCreate = () async => sessionFixture();
      await confirm(tester);
      expect(api.creations, hasLength(2));
      expect(find.byType(BoardingSessionView), findsOneWidget);
    });
  }
  testWidgets(
    'new request failure after a terminal session keeps the selection',
    (tester) async {
      await storage.save(intentFixture(auth));
      api.requestSession = sessionFixture(
        status: 'COMPLETED',
        evaluation: 'ENDED',
        gap: null,
      );
      await pump(tester);
      await tap(tester, '새 차량 후보 찾기');
      await candidates(tester);
      api.onCreate = () async => throw const BoardingSessionException(
        SessionFailure.http,
        statusCode: 409,
        code: 'BOARDING_VEHICLE_UNIDENTIFIABLE',
      );
      await select(tester);
      await confirm(tester);
      expect(find.textContaining('선택한 차량을 현재 확인할 수 없어요'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey('continue-boarding-candidate')),
            )
            .onPressed,
        isNotNull,
      );
    },
  );

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
      await tap(tester, '이 차량으로 하차 알림 설정');
      expect(api.creations, hasLength(1));
      expect(storage.records, hasLength(1));
      expect(
        tester
            .widget<FilledButton>(
              find.byKey(const ValueKey('continue-boarding-candidate')),
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
      expect(find.textContaining('세션 수명 만료'), findsOneWidget);
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
      expect(find.text('하차 알림 (종료)'), findsOneWidget);
      expect(find.textContaining('실제 하차 확인을 뜻하지'), findsOneWidget);
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
      expect(find.textContaining('현재 차량 진행 정보를 다시 확인'), findsOneWidget);
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
        expect(find.text('상세 정보'), findsNothing);
        if (evaluation == 'HELD') {
          expect(find.textContaining('차량 정보 확인이 보류'), findsOneWidget);
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
        expect(
          find.text(switch (status) {
            'COMPLETED' => '하차 알림 (종료)',
            'CANCELLED' => '하차 알림 (취소)',
            _ => '하차 알림 (만료)',
          }),
          findsOneWidget,
        );
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
      expect(find.textContaining('사용자 취소로 종료'), findsOneWidget);
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
      expect(find.textContaining('세션 수명 만료'), findsOneWidget);
      expect(find.textContaining('사용자 취소로 종료'), findsNothing);
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
      expect(find.textContaining('변경 결과를 확인하지 못했어요'), findsOneWidget);
      expect(find.textContaining('검증된 노선 방문 지점 간격'), findsNothing);
      expect(
        tester
            .widget<TextButton>(find.widgetWithText(TextButton, '추적 취소'))
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
      await tap(tester, '이 차량 선택');
      expect(find.byType(TextField), findsNothing);
      await confirm(tester, reconfirm: true);
      expect(api.reconfirmations, [(123, 5, 'fresh-reference', '서울70사1234')]);
      expect(api.creations, isEmpty);
      expect(find.textContaining('현재 차량 진행 정보를 다시 확인'), findsOneWidget);
    },
  );
  testWidgets(
    'same-auth retained context fetches new candidate and user selects the same vehicle again',
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
      await tester.tap(find.byTooltip('상태 새로고침'));
      await tester.pump();
      candidateValues = [
        {...freshCandidate(), 'candidateRef': 'fresh-reconfirmation-reference'},
      ];
      rejectLegacyBoarding = true;
      await tap(tester, '동일 차량 다시 확인');
      expect(contexts.sublist(contexts.length - 2), [
        (754, 22, 11),
        (754, 22, null),
      ]);
      await tap(tester, '이 차량 선택');
      expect(find.byType(TextField), findsNothing);
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
      await tester.tap(find.byTooltip('상태 새로고침'));
      await tester.pump();
      await tap(tester, '추적 취소');
      await tap(tester, '추적 취소 확인');
      old.complete(sessionFixture(revision: 5));
      await tester.pumpAndSettle();
      expect(find.textContaining('사용자 취소로 종료'), findsOneWidget);
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
