import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_candidates_view.dart';
import 'package:stopbell/features/boarding/boarding_confirmation_view.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_view.dart';
import 'package:stopbell/features/transit/bus_route.dart';

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
    await tester.pumpAndSettle();
    await tester.tap(find.text('하차 알림 등록'));
    await tester.pump();
    expect(selected, moving.vehicleNumber);
  });
  for (final mode in [ThemeMode.light, ThemeMode.dark]) {
    testWidgets(
      'confirmation scrolls and prevents double submission in $mode',
      (tester) async {
        tester.view.physicalSize = const Size(320, 568);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        const number = '서울특별시차량전체번호가길어도생략하지않음1234567890';
        const boardingName = '매우 긴 승차 정류장 이름과 환승센터\n상행 방면';
        const targetName = '매우 긴 하차 목표 정류장 이름과 환승센터\n하행 방면';
        final pending = Completer<void>();
        final submissions = <String>[];
        final offsets = <int>[];
        await tester.pumpWidget(
          MaterialApp(
            theme: StopBellTheme.light,
            darkTheme: StopBellTheme.dark,
            themeMode: mode,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context)
                  .copyWith(textScaler: const TextScaler.linear(2)),
              child: child!,
            ),
            home: Scaffold(
              body: BoardingConfirmationView(
                candidate: BoardingCandidate.fromJson(
                  candidateJson(number: number),
                ),
                routeNumber: 'M2352',
                route: const BusRoute(
                  id: 754,
                  routeNumber: 'M2352',
                  regionName: '경기',
                  provider: 'TAGO',
                  routeType: 'CITY_METRO_EXPRESS',
                ),
                boardingName: boardingName,
                targetName: targetName,
                onConfirm: (number) {
                  submissions.add(number);
                  return pending.future;
                },
                onBack: () {},
                onNotificationOffsetChanged: offsets.add,
              ),
            ),
          ),
        );
        expect(find.text(number), findsOneWidget);
        expect(find.text('광역급행형시내'), findsOneWidget);
        for (final label in [boardingName, targetName, '노선 방문 지점 1개 전']) {
          final content = find.text(label);
          await Scrollable.ensureVisible(
            tester.element(content),
            alignment: .25,
          );
          await tester.pumpAndSettle();
          expect(content.hitTestable(), findsOneWidget);
        }
        final group = find.byType(RadioGroup<int>);
        expect(tester.widget<RadioGroup<int>>(group).groupValue, 2);
        await tester.tap(find.text('노선 방문 지점 1개 전'));
        await tester.pump();
        expect(tester.widget<RadioGroup<int>>(group).groupValue, 1);
        expect(offsets, [1]);
        final register = find.widgetWithText(FilledButton, '하차 알림 등록');
        await tester.ensureVisible(register);
        await tester.pumpAndSettle();
        await tester.tap(register);
        await tester.tap(register);
        await tester.pump();
        final waiting = find.widgetWithText(FilledButton, '확인 중…');
        expect(tester.widget<FilledButton>(waiting).onPressed, isNull);
        expect(submissions, [number]);
        pending.complete();
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      },
    );
  }
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
      expect(find.textContaining('선택한 차량 추적 중'), findsOneWidget);
      expect(find.textContaining('차량 위치 확인 중 · 위치 근거'), findsOneWidget);
      expect(find.textContaining('검증된 노선 방문 지점 간격'), findsNothing);
      expect(find.textContaining('마지막 검증 관측 시각'), findsNothing);
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
      expect(find.text('승차 지점 이전의 관측 기준'), findsOneWidget);
      expect(find.textContaining('승차 지점 이후의 관측 기준'), findsOneWidget);
      expect(find.textContaining('노선 방문 지점 배열 기준'), findsNWidgets(2));
    },
  );
}
