import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_candidates_view.dart';

import 'boarding_test_fakes.dart';

void main() {
  Future<void> show(WidgetTester tester, Map<String, dynamic> json) async {
    tester.view.physicalSize = const Size(1000, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: BoardingCandidatesView(
              result: BoardingCandidates.fromJson(json),
              receivedAt: DateTime.utc(2026, 10, 20, 2),
            ),
          ),
        ),
      ),
    );
  }

  testWidgets(
    'compact cards expose the radio state, hold reason and server clock',
    (tester) async {
      final semantics = tester.ensureSemantics();
      final taps = <BoardingCandidate>[];
      final result = BoardingCandidates.fromJson(
        candidatesJson(
          status: 'PARTIAL',
          candidates: [
            {
              ...candidateJson(),
              'observedAt': '2026-10-20T01:02:03Z',
              'providerDataTime': '2026-10-20T00:55:00Z',
            },
            {
              ...candidateJson(number: '서울70사5678'),
              'limitations': ['IDENTITY_CONFLICT'],
            },
          ],
        ),
      );

      Future<void> show(
        BoardingCandidates candidates, {
        BoardingCandidate? selected,
      }) => tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.light,
          home: Scaffold(
            body: SingleChildScrollView(
              child: BoardingCandidatesView.compact(
                result: candidates,
                receivedAt: DateTime.utc(2026, 10, 20, 1, 4),
                selected: selected,
                progressLabel: (candidate) =>
                    candidate == result.candidates.first
                    ? (
                        stopName: '검증된 참고 정류장',
                        text: '남은 정류장 확인 필요',
                        endpoint: null,
                      )
                    : (stopName: null, text: '진행 위치 미확인', endpoint: null),
                onSelect: taps.add,
              ),
            ),
          ),
        ),
      );

      await show(result);
      final first = find.byKey(const ValueKey('candidate-0'));
      final held = find.byKey(const ValueKey('candidate-1'));
      final time = MaterialLocalizations.of(tester.element(first))
          .formatTimeOfDay(
            TimeOfDay.fromDateTime(
              result.candidates.first.observedAt.toLocal(),
            ),
            alwaysUse24HourFormat: true,
          );
      expect(find.text('$time 조회'), findsNWidgets(2));
      expect(find.textContaining('서버 조회 기준'), findsNothing);
      expect(find.text('차량 선택 도움말'), findsNothing);
      expect(find.textContaining('제공기관 원본 시각'), findsNothing);
      expect(find.textContaining('일부 차량 정보'), findsNothing);
      expect(find.text('선택 보류 · 차량 식별 정보 불일치'), findsOneWidget);
      expect(find.text('최근 위치 · 검증된 참고 정류장'), findsOneWidget);
      expect(find.text('남은 정류장 확인 필요'), findsOneWidget);
      expect(find.text('진행 위치 미확인'), findsOneWidget);
      expect(
        tester.getSemantics(first),
        isSemantics(
          isInMutuallyExclusiveGroup: true,
          isChecked: false,
          isEnabled: true,
          hasTapAction: true,
        ),
      );
      expect(
        tester.getSemantics(held),
        isSemantics(isEnabled: false, hasTapAction: false),
      );
      await tester.tap(held);
      expect(taps, isEmpty);
      await tester.tap(first);
      expect(taps, [result.candidates.first]);
      await show(result, selected: taps.single);
      expect(tester.getSemantics(first), isSemantics(isChecked: true));

      await show(
        BoardingCandidates.fromJson(candidatesJson(status: 'UNKNOWN')),
      );
      expect(find.textContaining('목록의 상태를 확인할 수 없어요'), findsOneWidget);
      expect(
        tester.getSemantics(first),
        isSemantics(isEnabled: false, hasTapAction: false),
      );
      await tester.tap(first);
      expect(taps, hasLength(1));
      semantics.dispose();
    },
  );

  testWidgets(
    'deadline partial explains both held reasons and allows only valid selection',
    (tester) async {
      final selected = <BoardingCandidate>[];
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: BoardingCandidatesView(
                result: BoardingCandidates.fromJson(
                  candidatesJson(
                    status: 'PARTIAL',
                    candidates: [
                      candidateJson(),
                      for (final reason in [
                        'DETAIL_DEADLINE_NOT_STARTED',
                        'DETAIL_DEADLINE_INCOMPLETE',
                      ])
                        {
                          ...candidateJson(
                            confirmation: 'HELD',
                            tracking: 'HELD',
                          ),
                          'candidateRef': null,
                          'selectionKey': null,
                          'progress': null,
                          'boardingOccurrenceGap': null,
                          'reasonCode': reason,
                          'trackingReasonCode': reason,
                          'limitations': [reason],
                        },
                    ],
                  ),
                ),
                receivedAt: DateTime.utc(2026),
                onSelect: selected.add,
              ),
            ),
          ),
        ),
      );
      expect(selected, isEmpty);
      expect(find.textContaining('상세 조회를 시작하지 못했어요'), findsOneWidget);
      expect(find.textContaining('상세 정보를 확인하지 못했어요'), findsOneWidget);
      final buttons = tester
          .widgetList<OutlinedButton>(find.byType(OutlinedButton))
          .toList();
      expect(buttons.first.onPressed, isNotNull);
      expect(buttons.skip(1).every((b) => b.onPressed == null), isTrue);
      await tester.ensureVisible(find.text('이 차량 선택'));
      await tester.tap(find.text('이 차량 선택'));
      expect(selected, hasLength(1));
    },
  );

  testWidgets(
    'TAGO tracking hold explains the disabled button even with verified vehicle details',
    (tester) async {
      final selected = <BoardingCandidate>[];
      final tago = {
        ...candidateJson(tracking: 'HELD'),
        'trackingReasonCode': 'TAGO_ALIGHT_UNVERIFIED',
        'limitations': ['TAGO_ALIGHT_UNVERIFIED', 'PROVIDER_TIME_MISSING'],
      };
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: BoardingCandidatesView(
                result: BoardingCandidates.fromJson(
                  candidatesJson(candidates: [tago]),
                ),
                receivedAt: DateTime.utc(2026),
                onSelect: selected.add,
              ),
            ),
          ),
        ),
      );
      expect(find.text('차량 식별 · 확인 가능'), findsOneWidget);
      expect(find.text('하차 추적 지원 · 보류'), findsOneWidget);
      expect(find.textContaining('이 노선의 하차 알람 추적은 아직 검증되지 않아'), findsOneWidget);
      expect(find.text('제공기관 원본 시각 · 미확인'), findsOneWidget);
      expect(find.textContaining('현재 선택 가능한 차량이 없어요'), findsOneWidget);
      final button = find.widgetWithText(OutlinedButton, '선택 불가');
      expect(tester.widget<OutlinedButton>(button).onPressed, isNull);
      await tester.ensureVisible(button);
      await tester.tap(button);
      expect(selected, isEmpty);
    },
  );

  testWidgets(
    'multiple candidates keep server order, full numbers and no automatic selection or session action',
    (tester) async {
      await show(
        tester,
        candidatesJson(
          candidates: [
            candidateJson(number: '서울70사9999'),
            candidateJson(number: '서울70사1234'),
          ],
        ),
      );
      expect(
        tester.getTopLeft(find.text('서울70사9999')).dy,
        lessThan(tester.getTopLeft(find.text('서울70사1234')).dy),
      );
      expect(find.text('선택 가능'), findsNWidgets(2));
      expect(find.textContaining('조건부 지원'), findsNWidgets(2));
      expect(find.textContaining('원하는 차량을 직접 선택'), findsOneWidget);
      expect(find.textContaining('노선 방문 정류장 배열 기준'), findsNWidgets(2));
      expect(find.textContaining('private-reference'), findsNothing);
      expect(find.byType(RadioListTile<int>), findsNothing);
      expect(find.byType(FilledButton), findsNothing);
      await tester.tap(find.text('서울70사1234'));
      await tester.pump();
      expect(find.text('선택 가능'), findsNWidgets(2));
    },
  );

  testWidgets(
    'partial retains held unidentified stale conflicting items with separate tracking support',
    (tester) async {
      await show(
        tester,
        candidatesJson(
          status: 'PARTIAL',
          candidates: [
            {
              ...candidateJson(
                number: null,
                confirmation: 'HELD',
                tracking: 'HELD',
              ),
              'progress': null,
              'reasonCode': 'IDENTITY_CONFLICT',
              'limitations': [
                'VEHICLE_NUMBER_MISSING',
                'STALE_OBSERVATION',
                'POSITION_UNAVAILABLE',
                'TAGO_ALIGHT_UNVERIFIED',
              ],
            },
          ],
        ),
      );
      expect(find.textContaining('일부 차량 정보는 확인되지 않았어요'), findsNothing);
      expect(find.text('차량번호 확인 불가'), findsOneWidget);
      expect(find.text('차량 식별 · 확인 보류'), findsOneWidget);
      expect(find.text('하차 추적 지원 · 보류'), findsOneWidget);
      expect(find.text('진행 위치 미확인'), findsOneWidget);
      for (final text in ['서로 일치하지 않아요', '오래되어 다시 확인', '추적은 아직 검증되지']) {
        expect(find.textContaining(text), findsOneWidget);
      }
      expect(find.textContaining('대조 가능'), findsNothing);
    },
  );

  testWidgets(
    'unknown statuses stay held, empty and partial zero are distinguished',
    (tester) async {
      await show(
        tester,
        candidatesJson(
          status: 'NEW',
          candidates: [candidateJson(confirmation: 'NEW', tracking: 'NEW')],
        ),
      );
      expect(find.textContaining('목록의 상태를 확인할 수 없어요'), findsOneWidget);
      expect(find.text('선택 보류'), findsOneWidget);
      expect(find.text('하차 추적 지원 · 미확인'), findsOneWidget);
      await show(tester, candidatesJson(status: 'EMPTY'));
      expect(find.text('현재 확인 가능한 차량 후보가 없어요.'), findsOneWidget);
      await show(tester, candidatesJson(status: 'PARTIAL', candidates: []));
      expect(find.textContaining('전체 목록이 아닐 수 있어요'), findsNothing);
      expect(find.text('현재 확인 가능한 차량 후보가 없어요.'), findsNothing);
    },
  );

  testWidgets(
    'progress meanings and observation/provider/receipt times remain distinct',
    (tester) async {
      await show(
        tester,
        candidatesJson(
          candidates: [
            for (final kind in [
              'ROUTE_PROGRESS',
              'ARRIVAL_OBSERVED',
              'MOVING_REFERENCE',
            ])
              {
                ...candidateJson(),
                'providerDataTime': '2026-10-20T00:59:00Z',
                'progress': {
                  'occurrenceId': 22,
                  'stopName': '기준 정류장',
                  'order': 9,
                  'kind': kind,
                },
              },
          ],
        ),
      );
      expect(find.textContaining('노선 진행 참고 정보'), findsOneWidget);
      expect(find.textContaining('제공기관의 도착 기록'), findsOneWidget);
      expect(find.textContaining('이동 중 제공된 진행 기준 정보'), findsOneWidget);
      expect(
        find.text(
          '서버 확인 시각 · ${boardingTime(DateTime.utc(2026, 10, 20, 1, 2, 3))}',
        ),
        findsNWidgets(3),
      );
      expect(
        find.text(
          '제공기관 원본 시각 · ${boardingTime(DateTime.utc(2026, 10, 20, 0, 59))}',
        ),
        findsNWidgets(3),
      );
      expect(
        find.text('화면 수신 시각 · ${boardingTime(DateTime.utc(2026, 10, 20, 2))}'),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'freshness and identity holds block selection without hiding same-number candidates',
    (tester) async {
      final selected = <BoardingCandidate>[];
      final result = BoardingCandidates.fromJson(
        candidatesJson(
          candidates: [
            for (final reason in [
              'IDENTITY_CONFLICT',
              'FUTURE_OBSERVATION',
              'STALE_OBSERVATION',
            ])
              {
                ...candidateJson(),
                'candidateRef': reason,
                'limitations': [reason],
              },
          ],
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.light,
          home: Scaffold(
            body: SingleChildScrollView(
              child: BoardingCandidatesView(
                result: result,
                receivedAt: DateTime.utc(2026),
                onSelect: selected.add,
              ),
            ),
          ),
        ),
      );
      expect(find.text('서울70사1234'), findsNWidgets(3));
      expect(find.text('선택 보류'), findsNWidgets(3));
      for (var index = 0; index < 3; index++) {
        expect(find.text('후보 ${index + 1}'), findsOneWidget);
      }
      for (final reason in ['일치하지 않아요', '시각이 미래', '오래되어 다시 확인']) {
        expect(find.textContaining(reason), findsOneWidget);
      }
      expect(
        tester
            .widgetList<OutlinedButton>(find.byType(OutlinedButton))
            .every((button) => button.onPressed == null),
        isTrue,
      );
      expect(selected, isEmpty);
    },
  );

  testWidgets(
    'small iPhone and large text can scroll through long numbers names and every reason without overflow',
    (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final result = BoardingCandidates.fromJson(
        candidatesJson(
          candidates: [
            {
              ...candidateJson(number: '서울특별시전체차량번호가길어도생략하지않음1234567890'),
              'progress': {
                'occurrenceId': 22,
                'stopName': '매우 긴 목적 정류장 이름과 환승센터',
                'order': 9,
                'kind': 'ROUTE_PROGRESS',
              },
              'limitations': [
                'IDENTITY_CONFLICT',
                'STALE_OBSERVATION',
                'NEW_REASON',
                'REVALIDATION_REQUIRED',
              ],
            },
          ],
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: StopBellTheme.light,
          darkTheme: StopBellTheme.dark,
          themeMode: ThemeMode.dark,
          home: MediaQuery(
            data: const MediaQueryData(textScaler: TextScaler.linear(2)),
            child: Scaffold(
              body: SingleChildScrollView(
                child: BoardingCandidatesView(
                  result: result,
                  receivedAt: DateTime.utc(2026),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.ensureVisible(find.textContaining('최신 정보를 다시 검증'));
      await tester.pumpAndSettle();
      expect(find.textContaining('최신 정보를 다시 검증').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
