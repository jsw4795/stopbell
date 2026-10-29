import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
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
      expect(find.textContaining('선택 가능'), findsNWidgets(2));
      expect(find.textContaining('등록 후 진행 검증 · 대기할 수 있음'), findsNWidgets(2));
      expect(find.textContaining('차량번호 입력 없이 버스 도착 전에도 등록'), findsOneWidget);
      expect(find.textContaining('실제 정차 수·거리·남은 시간이 아니에요'), findsNWidgets(2));
      expect(find.textContaining('private-reference'), findsNothing);
      expect(find.byType(RadioListTile<int>), findsNothing);
      expect(find.byType(FilledButton), findsNothing);
      await tester.tap(find.text('서울70사1234'));
      await tester.pump();
      expect(find.textContaining('선택 가능'), findsNWidgets(2));
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
      expect(find.textContaining('일부 차량 정보는 확인되지 않았어요'), findsOneWidget);
      expect(find.text('차량번호 없음 · 식별 불가'), findsOneWidget);
      expect(find.text('차량 선택: 확인 보류'), findsOneWidget);
      expect(find.text('하차 추적 지원: 보류'), findsOneWidget);
      expect(find.text('진행 참고: 위치 정보 없음'), findsOneWidget);
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
      expect(find.text('차량 선택: 상태 미확인 · 확인 보류'), findsOneWidget);
      expect(find.text('하차 추적 지원: 알 수 없음 · 보류'), findsOneWidget);
      await show(tester, candidatesJson(status: 'EMPTY'));
      expect(find.text('현재 확인 가능한 차량 후보가 없어요.'), findsOneWidget);
      await show(tester, candidatesJson(status: 'PARTIAL', candidates: []));
      expect(find.textContaining('전체 목록이 아닐 수 있어요'), findsOneWidget);
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
      expect(find.textContaining('제공자가 도착 상태를 관측한 참고 정보'), findsOneWidget);
      expect(find.textContaining('이동 중 제공된 진행 기준 정보'), findsOneWidget);
      expect(
        find.text(
          '서버 관측 시각: ${boardingTime(DateTime.utc(2026, 10, 20, 1, 2, 3))}',
        ),
        findsNWidgets(4),
      );
      expect(
        find.text(
          'Provider 제공 시각: ${boardingTime(DateTime.utc(2026, 10, 20, 0, 59))}',
        ),
        findsNWidgets(3),
      );
      expect(
        find.text('화면 수신 시각: ${boardingTime(DateTime.utc(2026, 10, 20, 2))}'),
        findsOneWidget,
      );
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
