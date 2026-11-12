import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_view.dart';

import 'boarding_session_test_fakes.dart';

Map<String, dynamic> timelineSession({
  int? position = 20,
  String? basis = 'READY',
  String evaluation = 'READY',
  int id = 123,
  String? name = '현재 정류장',
}) => {
  ...sessionJson(id: id, evaluation: evaluation),
  'checkpointObservedAt': evaluation == 'VERIFICATION_PENDING'
      ? null
      : '2026-10-21T01:00:00Z',
  'currentStopName': basis == 'READY' ? name : null,
  if (basis == 'INITIAL')
    'initialObservation': {'stopName': name, 'remainingOccurrenceGap': 10},
  'timeline': {
    'provider': 'TAGO',
    'routeType': 'CITY_LOCAL',
    'boardingIndex': 2,
    'targetIndex': 31,
    'positionIndex': position,
    'positionBasis': basis,
    'stops': [
      for (var i = 2; i <= 31; i++)
        {
          'id': i + 100,
          'index': i,
          'order': i + 1,
          'name': i == 8 || i == 19
              ? '반복 정류장'
              : i == 20
              ? '경유 (미정차)'
              : '정류장 $i',
        },
    ],
  },
};

void main() {
  late ValueNotifier<BoardingSession> session;
  Future<void> setup(
    WidgetTester tester,
    Map<String, dynamic> json, {
    Brightness brightness = Brightness.light,
    double scale = 1,
  }) async {
    session = ValueNotifier(BoardingSession.fromJson(json));
    addTearDown(session.dispose);
    tester.view.physicalSize = const Size(320, 600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(brightness: brightness),
        home: MediaQuery(
          data: MediaQueryData(
            size: const Size(320, 600),
            textScaler: TextScaler.linear(scale),
          ),
          child: Scaffold(
            body: ValueListenableBuilder<BoardingSession>(
              valueListenable: session,
              builder: (context, value, _) => BoardingSessionDetails(
                session: value,
                constrained: true,
                onBack: () {},
                showRefresh: true,
                onRefresh: () {},
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  final scroll = find.byKey(const ValueKey('tracking-timeline-scroll'));
  double offset(WidgetTester tester) => tester
      .state<ScrollableState>(
        find.descendant(of: scroll, matching: find.byType(Scrollable)),
      )
      .position
      .pixels;
  void expectMarkerVisible(WidgetTester tester, Key key) {
    final viewport = tester.getRect(scroll);
    final marker = tester.getRect(find.byKey(key));
    expect(viewport.overlaps(marker), isTrue);
  }

  testWidgets('compact header and independently scrolled READY timeline', (
    tester,
  ) async {
    await setup(tester, timelineSession());
    final row = find.byKey(const ValueKey('boarding-header'));
    expect(
      find.descendant(of: row, matching: find.byTooltip('도착 알람으로')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: row, matching: find.text('하차 알람')),
      findsOneWidget,
    );
    expect(
      find.descendant(of: row, matching: find.byTooltip('상태 새로고침')),
      findsOneWidget,
    );
    expect(find.text('7000'), findsOneWidget);
    final panel = find.byKey(const ValueKey('tracking-route-panel'));
    expect(panel, findsOneWidget);
    expect(find.descendant(of: panel, matching: scroll), findsOneWidget);
    expect(tester.getRect(panel).contains(tester.getTopLeft(scroll)), isTrue);
    expect(find.text('3개 정류장 남음'), findsOneWidget);
    expect(find.text('최근 확인된 버스 위치'), findsNothing);
    expect(find.textContaining('부근 지나는 중'), findsNothing);
    expect(find.text('최근 위치 기준'), findsOneWidget);
    expect(offset(tester), greaterThan(0));
    expectMarkerVisible(tester, const ValueKey('tracking-bus-marker'));
    final headerY = tester.getTopLeft(row).dy;
    await tester.drag(scroll, const Offset(0, -200));
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(row).dy, headerY);
    expect(find.text('미정차'), findsOneWidget);
    expect(find.text('노선 순서 9'), findsNothing);
    expect(find.text('노선 순서 21'), findsNothing);
  });

  testWidgets('INITIAL and before-boarding marker use their own reference', (
    tester,
  ) async {
    await setup(
      tester,
      timelineSession(
        position: 20,
        basis: 'INITIAL',
        evaluation: 'VERIFICATION_PENDING',
      ),
    );
    expectMarkerVisible(tester, const ValueKey('tracking-bus-marker'));
    expect(find.text('참고용 위치예요'), findsOneWidget);
    session.value = BoardingSession.fromJson(
      timelineSession(
        id: 456,
        position: 0,
        basis: 'INITIAL',
        evaluation: 'VERIFICATION_PENDING',
      ),
    );
    await tester.pumpAndSettle();
    expectMarkerVisible(tester, const ValueKey('before-boarding-bus-marker'));
    expect(find.text('현재 정류장 부근'), findsOneWidget);
    expect(find.text('승차까지 2개 정류장'), findsOneWidget);
    expect(find.text('참고용 위치예요'), findsOneWidget);
    final bus = find.byKey(const ValueKey('before-boarding-bus-marker'));
    final firstRow = find.byKey(const ValueKey(102));
    final firstIcon = find.descendant(
      of: firstRow,
      matching: find.byType(Icon),
    );
    final rail = find.byKey(const ValueKey('before-boarding-rail'));
    expect(
      tester.getCenter(bus).dx,
      closeTo(tester.getCenter(firstIcon).dx, .1),
    );
    expect(
      tester.getBottomLeft(rail).dy,
      closeTo(tester.getTopLeft(firstIcon).dy, .1),
    );
    expect(find.ancestor(of: bus, matching: find.byType(Card)), findsNothing);
    session.value = BoardingSession.fromJson(
      timelineSession(id: 456, position: 5),
    );
    await tester.pumpAndSettle();
    expect(
      find.byKey(const ValueKey('before-boarding-bus-marker')),
      findsNothing,
    );
    expect(find.byKey(const ValueKey('tracking-bus-marker')), findsOneWidget);
  });

  testWidgets(
    'first delayed location focuses once, manual scroll survives refresh',
    (tester) async {
      await setup(
        tester,
        timelineSession(
          position: null,
          basis: null,
          evaluation: 'RECOVERY_PENDING',
        ),
      );
      expect(offset(tester), 0);
      session.value = BoardingSession.fromJson(timelineSession(position: 20));
      await tester.pumpAndSettle();
      expectMarkerVisible(tester, const ValueKey('tracking-bus-marker'));
      await tester.drag(scroll, const Offset(0, 250));
      await tester.pumpAndSettle();
      final manual = offset(tester);
      session.value = BoardingSession.fromJson(timelineSession(position: 23));
      await tester.pumpAndSettle();
      expect(offset(tester), closeTo(manual, .1));
      session.value = BoardingSession.fromJson(
        timelineSession(id: 999, position: 25),
      );
      await tester.pumpAndSettle();
      expectMarkerVisible(tester, const ValueKey('tracking-bus-marker'));
    },
  );

  testWidgets('scroll before initial location prevents later forced focus', (
    tester,
  ) async {
    await setup(
      tester,
      timelineSession(
        position: null,
        basis: null,
        evaluation: 'RECOVERY_PENDING',
      ),
    );
    await tester.drag(scroll, const Offset(0, -180));
    await tester.pumpAndSettle();
    final manual = offset(tester);
    session.value = BoardingSession.fromJson(timelineSession(position: 25));
    await tester.pumpAndSettle();
    expect(offset(tester), closeTo(manual, .1));
  });

  testWidgets(
    'before-boarding position never invents a missing name or survives a hold',
    (tester) async {
      await setup(tester, timelineSession(position: 0, name: null));
      expect(
        find.byKey(const ValueKey('before-boarding-bus-marker')),
        findsOneWidget,
      );
      expect(find.text('승차까지 2개 정류장'), findsOneWidget);
      expect(find.textContaining(' 부근'), findsNothing);
      session.value = BoardingSession.fromJson({
        ...timelineSession(position: 0),
        'status': 'SUSPENDED',
        'evaluationStatus': 'HELD',
      });
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('before-boarding-bus-marker')),
        findsNothing,
      );
      expect(find.byKey(const ValueKey('tracking-bus-marker')), findsNothing);
    },
  );

  for (final brightness in Brightness.values) {
    testWidgets('small screen with large text in $brightness', (tester) async {
      await setup(
        tester,
        timelineSession(position: 0),
        brightness: brightness,
        scale: 2,
      );
      expect(tester.takeException(), isNull);
      expect(
        find.byKey(const ValueKey('before-boarding-bus-marker')),
        findsOneWidget,
      );
    });
  }
}
