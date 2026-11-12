import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/home_overview.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';

import 'boarding_session_test_fakes.dart';

Alarm alarm(int id, AlarmStatus status, {String? start, String? end}) => Alarm(
  id: id,
  transitType: AlarmTransitType.bus,
  status: status,
  routeNumber: '165',
  stopName: '상봉역',
  notifyOneStopBefore: false,
  notifyOneStopAfter: false,
  startStopName: start,
  endStopName: end,
);

Widget home({
  required Future<List<Alarm>> Function() alarms,
  Future<BoardingSessionOverview> Function()? sessions,
  VoidCallback? onCreateArrival,
  VoidCallback? onCreateBoarding,
  ValueChanged<int>? onOpenAlarm,
  VoidCallback? onOpenCurrentSession,
  ValueChanged<int>? onOpenEndedSession,
  ThemeData? theme,
}) => MaterialApp(
  theme: theme ?? StopBellTheme.light,
  home: Scaffold(
    body: HomeOverview(
      findAlarms: alarms,
      findSessions: sessions,
      onCreateArrival: onCreateArrival ?? () {},
      onCreateBoarding: onCreateBoarding,
      onOpenAlarm: onOpenAlarm ?? (_) {},
      onOpenCurrentSession: onOpenCurrentSession,
      onOpenEndedSession: onOpenEndedSession,
      now: () => DateTime.utc(2026, 10, 21, 2, 30),
    ),
  ),
);

void main() {
  testWidgets('알람 목록 로딩·오류 재시도·서버 순서·상태 칩과 이동을 제공한다', (tester) async {
    final first = Completer<List<Alarm>>();
    var calls = 0;
    int? selected;
    var create = 0;
    await tester.pumpWidget(
      home(
        alarms: () {
          calls++;
          return calls == 1
              ? first.future
              : Future.value([
                  alarm(3, AlarmStatus.followUp),
                  alarm(2, AlarmStatus.active),
                  alarm(1, AlarmStatus.inactive),
                ]);
        },
        onOpenAlarm: (id) => selected = id,
        onCreateArrival: () => create++,
      ),
    );
    expect(find.text('알람 목록 조회 중...'), findsOneWidget);
    first.completeError(Exception('private failure'));
    await tester.pumpAndSettle();
    expect(find.text('알람 목록을 불러오지 못했습니다.'), findsOneWidget);
    expect(find.textContaining('private failure'), findsNothing);
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(find.text('한 정거장 후 추적 중'), findsOneWidget);
    expect(find.text('감시 중'), findsOneWidget);
    expect(find.text('비활성'), findsOneWidget);
    final cards = [
      3,
      2,
      1,
    ].map((id) => tester.getTopLeft(find.byKey(ValueKey(id))).dy).toList();
    expect(cards, [...cards]..sort());
    await tester.tap(find.byKey(const ValueKey(2)));
    expect(selected, 2);
    await tester.tap(find.text('도착 알람'));
    expect(create, 1);
    expect(find.text('하차 알람'), findsNothing);
  });

  testWidgets('빈 알람 목록은 안내를 보여주고 당겨서 새로고침하면 다시 조회한다', (tester) async {
    var calls = 0;
    await tester.pumpWidget(
      home(
        alarms: () async {
          calls++;
          return [];
        },
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('아직 만든 알람이 없습니다.'), findsOneWidget);
    expect(find.text('도착 알람'), findsOneWidget);
    await tester.fling(find.byType(ListView), const Offset(0, 400), 1000);
    await tester.pumpAndSettle();
    expect(calls, 2);
  });

  testWidgets('검증된 기종점이 있을 때만 노선 방면을 표시한다', (tester) async {
    await tester.pumpWidget(
      home(
        alarms: () async => [
          alarm(1, AlarmStatus.active, start: '평내신명.대주', end: '상봉역'),
          alarm(2, AlarmStatus.inactive),
        ],
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('평내신명.대주 → 상봉역'), findsOneWidget);
    expect(find.textContaining('→'), findsOneWidget);
    expect(find.text('상봉역 도착'), findsNWidgets(2));
    expect(find.byIcon(Icons.place_outlined), findsNWidgets(2));
  });

  testWidgets('Alarm 응답의 기종점은 선택 필드이며 한쪽만 있으면 표시하지 않는다', (tester) async {
    final base = {
      'id': 1,
      'transitType': 'BUS',
      'status': 'ACTIVE',
      'routeNumber': '165',
      'stopName': '상봉역',
      'notifyOneStopBefore': false,
      'notifyOneStopAfter': false,
    };
    expect(Alarm.fromJson(base).startStopName, isNull);
    final full = Alarm.fromJson({
      ...base,
      'startStopName': ' 기점 ',
      'endStopName': '종점',
    });
    expect((full.startStopName, full.endStopName), ('기점', '종점'));
    final partial = Alarm.fromJson({
      ...base,
      'startStopName': '기점',
      'endStopName': null,
    });
    expect((partial.startStopName, partial.endStopName), (null, null));
    expect(
      () => Alarm.fromJson({...base, 'startStopName': 3}),
      throwsFormatException,
    );
  });

  testWidgets('진행 중 세션이 있으면 그 세션 하나만 버스 번호 제목으로 표시한다', (tester) async {
    var current = 0, boarding = 0;
    final ended = <int>[];
    await tester.pumpWidget(
      home(
        alarms: () async => [],
        sessions: () async => BoardingSessionOverview(
          current: sessionFixture(id: 7, gap: 4),
          recent: [
            sessionFixture(
              id: 6,
              status: 'COMPLETED',
              evaluation: 'ENDED',
              gap: null,
            ),
          ],
        ),
        onCreateBoarding: () => boarding++,
        onOpenCurrentSession: () => current++,
        onOpenEndedSession: ended.add,
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('진행 중인 하차 알람'), findsOneWidget);
    expect(find.text('최근 종료된 하차 알람'), findsNothing);
    expect(find.byKey(const ValueKey(('session', 6))), findsNothing);
    expect(find.text('7000'), findsOneWidget);
    expect(find.textContaining('하차 알람', findRichText: true), findsNWidgets(2));
    expect(find.text('추적 중'), findsOneWidget);
    expect(find.text('4개 지점 남음'), findsOneWidget);
    expect(find.text('서울70사1234 · 목적 정류장 하차'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey(('session', 7))));
    await tester.tap(find.text('하차 알람'));
    expect(current, 1);
    expect(ended, isEmpty);
    expect(boarding, 1);
  });

  testWidgets('진행 중 세션이 없으면 가장 최근 종료 세션 하나만 표시한다', (tester) async {
    final ended = <int>[];
    await tester.pumpWidget(
      home(
        alarms: () async => [],
        sessions: () async => BoardingSessionOverview(
          recent: [
            sessionFixture(
              id: 6,
              status: 'COMPLETED',
              evaluation: 'ENDED',
              gap: null,
            ),
            sessionFixture(
              id: 5,
              status: 'CANCELLED',
              evaluation: 'ENDED',
              reason: 'USER_CANCELLED',
              gap: null,
            ),
          ],
        ),
        onOpenEndedSession: ended.add,
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('진행 중인 하차 알람'), findsNothing);
    expect(find.text('최근 종료된 하차 알람'), findsOneWidget);
    expect(find.byKey(const ValueKey(('session', 5))), findsNothing);
    expect(find.text('종료'), findsOneWidget);
    expect(find.text('30분 전 종료'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey(('session', 6))));
    expect(ended, [6]);
  });

  testWidgets('보류 세션은 보류 색상과 재확인 필요를 표시한다', (tester) async {
    await tester.pumpWidget(
      home(
        alarms: () async => [],
        sessions: () async => BoardingSessionOverview(
          current: sessionFixture(
            status: 'SUSPENDED',
            evaluation: 'HELD',
            reason: 'VEHICLE_MISSING',
            gap: null,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('보류'), findsOneWidget);
    expect(find.text('차량 재확인 필요'), findsOneWidget);
    expect(find.text('최근 종료된 하차 알람'), findsNothing);
  });

  testWidgets('세션이 없으면 세션 섹션을 숨기고 조회 실패는 따로 재시도한다', (tester) async {
    var calls = 0;
    await tester.pumpWidget(
      home(
        alarms: () async => [alarm(1, AlarmStatus.active)],
        sessions: () async {
          calls++;
          if (calls == 1) throw Exception('private');
          return const BoardingSessionOverview();
        },
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('하차 알람 상태를 불러오지 못했어요.'), findsOneWidget);
    expect(find.text('감시 중'), findsOneWidget);
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(calls, 2);
    expect(find.text('진행 중인 하차 알람'), findsNothing);
    expect(find.text('하차 알람 상태를 불러오지 못했어요.'), findsNothing);
  });

  for (final dark in [false, true]) {
    testWidgets('${dark ? '다크' : '라이트'} 테마의 작은 화면과 큰 글자에서도 넘치지 않는다', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 1.6;
      addTearDown(tester.view.reset);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await tester.pumpWidget(
        home(
          theme: dark ? StopBellTheme.dark : StopBellTheme.light,
          alarms: () async => [
            alarm(
              1,
              AlarmStatus.followUp,
              start: '아주 긴 기점 정류장 이름',
              end: '아주 긴 종점 정류장 이름',
            ),
          ],
          sessions: () async =>
              BoardingSessionOverview(current: sessionFixture()),
          onCreateBoarding: () {},
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('하차 알람'), findsOneWidget);
    });
  }
}
