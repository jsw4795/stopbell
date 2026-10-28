import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_candidates_view.dart';
import 'package:stopbell/features/boarding/boarding_confirmation_view.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_view.dart';

import 'boarding_session_test_fakes.dart';
import 'boarding_test_fakes.dart';

void main() {
  testWidgets('moving vehicle registration has no manual number input', (
    tester,
  ) async {
    String? selected;
    final moving = BoardingCandidate.fromJson({
      ...candidateJson(),
      'progress': {
        'occurrenceId': 11,
        'stopName': '승차 전 관측 지점',
        'order': 10,
        'kind': 'MOVING_REFERENCE',
      },
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: BoardingConfirmationView(
            candidate: moving,
            routeNumber: '143',
            targetName: '목적지',
            onConfirm: (number) async => selected = number,
            onBack: () {},
          ),
        ),
      ),
    );
    expect(find.byType(TextField), findsNothing);
    expect(find.textContaining('버스 도착 전에도'), findsOneWidget);
    await tester.ensureVisible(find.text('하차 알림 등록'));
    await tester.tap(find.text('하차 알림 등록'));
    await tester.pump();
    expect(selected, moving.vehicleNumber);
  });
  testWidgets(
    'pending state shows registration without claiming tracking readiness or a gap',
    (tester) async {
      final pending = BoardingSession.fromJson({
        ...sessionJson(
          status: 'TRACKING',
          evaluation: 'VERIFICATION_PENDING',
          reason: 'BASELINE_UNVERIFIED',
          gap: null,
        ),
        'checkpointObservedAt': null,
      });
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: BoardingSessionDetails(session: pending),
            ),
          ),
        ),
      );
      expect(find.textContaining('TRACKING · 선택한 차량 추적 중'), findsOneWidget);
      expect(find.textContaining('차량 위치 확인 중 · 선택한 차량'), findsOneWidget);
      expect(find.textContaining('검증된 노선 방문 지점 간격'), findsNothing);
      expect(find.textContaining('마지막 검증 checkpoint 관측 시각'), findsNothing);
    },
  );
  testWidgets(
    'candidate list distinguishes observed references before and after boarding without ETA',
    (tester) async {
      final result = BoardingCandidates.fromJson(
        candidatesJson(
          candidates: [
            {...candidateJson(number: '서울70사1111'), 'boardingOccurrenceGap': 3},
            {
              ...candidateJson(number: '서울70사2222'),
              'boardingOccurrenceGap': -1,
            },
          ],
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: BoardingCandidatesView(
                result: result,
                receivedAt: sessionNow,
              ),
            ),
          ),
        ),
      );
      expect(find.text('승차 지점 이전에 관측된 차량'), findsOneWidget);
      expect(find.textContaining('이미 지났을 수 있음'), findsOneWidget);
      expect(find.textContaining('실제 정차 수·거리·남은 시간이 아니에요'), findsNWidgets(2));
    },
  );
}
