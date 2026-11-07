import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:http/http.dart' as http;
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/boarding/boarding_candidate_client.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_candidates_view.dart';
import 'package:stopbell/features/boarding/boarding_stop_screen.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_timeline.dart';

import 'boarding_test_fakes.dart';

void main() {
  late AuthSession auth;
  late List<(int, int, int?)> queries;
  late List<bool Function()> requestChecks;
  late Future<BoardingCandidates> Function() respond;

  Future<void> setup(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1000, 2200);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final transport = MockClient((_) async => http.Response('', 204));
    auth = await boardingAuth(transport);
    addTearDown(auth.dispose);
    addTearDown(transport.close);
    queries = [];
    requestChecks = [];
    respond = () async => BoardingCandidates.fromJson(candidatesJson());
  }

  Widget screen({
    BusRoute route = boardingRoute,
    Future<List<BusRouteStopOccurrence>> Function(int)? stops,
  }) => MaterialApp(
    theme: StopBellTheme.light,
    darkTheme: StopBellTheme.dark,
    home: Scaffold(
      body: BoardingStopScreen(
        route: route,
        authSession: auth,
        findStops: stops ?? (_) async => boardingStops,
        findCandidates:
            ({
              required routeId,
              required targetStopOccurrenceId,
              boardingStopOccurrenceId,
              expectedGeneration,
              isRequestCurrent,
            }) {
              expect(expectedGeneration, auth.generation);
              queries.add((
                routeId,
                targetStopOccurrenceId,
                boardingStopOccurrenceId,
              ));
              requestChecks.add(isRequestCurrent!);
              return respond();
            },
        onBack: () {},
      ),
    ),
  );

  Future<void> tap(WidgetTester tester, String key) async {
    final finder = find.byKey(ValueKey(key));
    await tester.ensureVisible(finder);
    await tester.pump();
    expect(finder.hitTestable(), findsOneWidget);
    await tester.tap(finder);
    await tester.pump();
  }

  Future<void> query(WidgetTester tester) async {
    await tap(tester, 'occurrence-11');
    await tap(tester, 'occurrence-22');
    await tap(tester, 'find-candidates');
    await tester.pumpAndSettle();
  }

  testWidgets('selection locks and reselect starts from boarding', (
    tester,
  ) async {
    await setup(tester);
    await tester.pumpWidget(screen());
    await tester.pumpAndSettle();
    expect(find.text('노선도에서 승차 정류장을 선택하세요.'), findsOneWidget);
    expect(find.byKey(const ValueKey('find-candidates')), findsNothing);
    expect(find.byKey(const ValueKey('pick-boarding')), findsNothing);
    expect(find.byKey(const ValueKey('pick-target')), findsNothing);
    await tap(tester, 'occurrence-11');
    expect(find.text('승차 정류장 이후의 정류장을 선택해 주세요'), findsOneWidget);
    expect(find.byKey(const ValueKey('find-candidates')), findsNothing);
    await tap(tester, 'occurrence-11');
    expect(find.byKey(const ValueKey('find-candidates')), findsNothing);
    await tap(tester, 'occurrence-22');
    expect(find.byKey(const ValueKey('reselect-stops')), findsOneWidget);
    for (final id in [11, 22]) {
      await tap(tester, 'occurrence-$id');
    }
    expect(find.textContaining('승차 정류장 · 반복 정류장'), findsOneWidget);
    expect(find.textContaining('목표 정류장 · 반복 정류장'), findsOneWidget);
    await tap(tester, 'find-candidates');
    await tester.pumpAndSettle();
    expect(queries.single, (754, 22, 11));
    await tap(tester, 'reselect-stops');
    expect(find.byType(BoardingCandidatesView), findsNothing);
    expect(find.byKey(const ValueKey('summary-boarding')), findsNothing);
    expect(find.byKey(const ValueKey('summary-target')), findsNothing);
    expect(find.text('노선도에서 승차 정류장을 선택하세요.'), findsOneWidget);
    await tap(tester, 'occurrence-11');
    await tap(tester, 'occurrence-22');
    await tap(tester, 'find-candidates');
    await tester.pumpAndSettle();
    expect(queries.last, (754, 22, 11));
  });

  for (final brightness in [Brightness.light, Brightness.dark]) {
    testWidgets('repeated stops across turn use order in $brightness', (
      tester,
    ) async {
      await setup(tester);
      tester.platformDispatcher.platformBrightnessTestValue = brightness;
      addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
      await tester.pumpWidget(
        screen(
          stops: (_) async => [
            for (final (id, order, name) in [
              (1, 10, '반복'),
              (2, 20, '회차'),
              (3, 30, '반복'),
            ])
              BusRouteStopOccurrence(
                id: id,
                name: name,
                order: order,
                canNotifyOneStopBefore: true,
                canNotifyOneStopAfter: true,
              ),
          ],
        ),
      );
      await tester.pumpAndSettle();
      await tap(tester, 'occurrence-2');
      await tap(tester, 'occurrence-1');
      await tap(tester, 'occurrence-2');
      expect(find.byKey(const ValueKey('find-candidates')), findsNothing);
      await tap(tester, 'occurrence-3');
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      expect(queries.single, (754, 3, 2));
      await tap(tester, 'reselect-stops');
      await tap(tester, 'occurrence-1');
      await tap(tester, 'occurrence-3');
      expect(find.textContaining('목표 정류장 · 반복'), findsOneWidget);
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      expect(queries.last, (754, 3, 1));
    });
  }

  testWidgets(
    'locked route keeps selections while direction and scrolling remain usable',
    (tester) async {
      await setup(tester);
      tester.view.physicalSize = const Size(390, 800);
      final stops = [
        for (var id = 1; id <= 20; id++)
          BusRouteStopOccurrence(
            id: id,
            name: id == 11
                ? '회차'
                : id == 20
                ? '종점'
                : '정류장 $id',
            order: id,
            destinationName: id < 11 ? '회차' : '종점',
            canNotifyOneStopBefore: true,
            canNotifyOneStopAfter: true,
          ),
      ];
      await tester.pumpWidget(screen(stops: (_) async => stops));
      await tester.pumpAndSettle();
      await tap(tester, 'occurrence-1');
      final restricted = tester.widgetList<Semantics>(
        find.descendant(
          of: find.byKey(const ValueKey('occurrence-1')),
          matching: find.byType(Semantics),
        ),
      );
      expect(restricted.any((s) => s.properties.enabled == false), isTrue);
      await tap(tester, 'occurrence-2');
      expect(find.byKey(const ValueKey('reselect-stops')), findsOneWidget);
      await tester.tap(find.text('회차 방면'));
      await tester.pumpAndSettle();
      final locked = tester.widgetList<Semantics>(
        find.descendant(
          of: find.byKey(const ValueKey('occurrence-1')),
          matching: find.byType(Semantics),
        ),
      );
      expect(
        locked.any((s) => s.properties.hint?.contains('선택 완료') ?? false),
        isTrue,
      );
      expect(locked.any((s) => s.properties.enabled == false), isFalse);
      for (final id in [1, 2, 3]) {
        await tap(tester, 'occurrence-$id');
      }
      expect(find.textContaining('승차 정류장 · 정류장 1'), findsOneWidget);
      expect(find.textContaining('목표 정류장 · 정류장 2'), findsOneWidget);
      await tester.tap(find.text('종점 방면'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('occurrence-11')), findsOneWidget);
      final scroll = tester
          .state<ScrollableState>(
            find.descendant(
              of: find.byType(BusRouteTimeline),
              matching: find.byType(Scrollable),
            ),
          )
          .position;
      final offset = scroll.pixels;
      await tester.drag(find.byType(ListView).first, const Offset(0, -80));
      await tester.pumpAndSettle();
      expect(scroll.pixels, greaterThan(offset));
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      expect(queries.single, (754, 2, 1));
    },
  );

  testWidgets('nonstop occurrences remain selectable as boarding and target', (
    tester,
  ) async {
    await setup(tester);
    final stops = [
      for (final (id, name) in [(1, '승차(미정차)'), (2, '목표 (미정차)')])
        BusRouteStopOccurrence(
          id: id,
          name: name,
          order: id,
          canNotifyOneStopBefore: true,
          canNotifyOneStopAfter: true,
        ),
    ];
    await tester.pumpWidget(screen(stops: (_) async => stops));
    await tester.pumpAndSettle();
    expect(find.textContaining('승차  미정차'), findsOneWidget);
    await tap(tester, 'occurrence-1');
    await tap(tester, 'occurrence-2');
    await tap(tester, 'find-candidates');
    await tester.pumpAndSettle();
    expect(queries.single, (754, 2, 1));
    expect(stops.map((s) => s.name), ['승차(미정차)', '목표 (미정차)']);
  });

  testWidgets(
    'system back restores the selected stops and scroll and ignores a late query',
    (tester) async {
      await setup(tester);
      tester.view.physicalSize = const Size(390, 800);
      var loads = 0;
      await tester.pumpWidget(
        screen(
          stops: (_) async {
            loads++;
            return [
              for (var id = 1; id <= 60; id++)
                BusRouteStopOccurrence(
                  id: id,
                  name: '정류장 $id',
                  order: id,
                  canNotifyOneStopBefore: true,
                  canNotifyOneStopAfter: true,
                ),
            ];
          },
        ),
      );
      await tester.pumpAndSettle();
      final timeline = find.byType(BusRouteTimeline);
      final scrollable = find.descendant(
        of: timeline,
        matching: find.byType(Scrollable),
      );
      final position = tester.state<ScrollableState>(scrollable).position;
      position.jumpTo(250);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('occurrence-6')));
      await tester.pump();
      await tester.tap(find.byKey(const ValueKey('occurrence-7')));
      await tester.pump();
      final selectedOffset = position.pixels;
      expect(selectedOffset, greaterThan(0));
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      expect(find.byType(BoardingCandidatesView), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.byType(BoardingCandidatesView), findsNothing);
      expect(find.text('목표 정류장 · 정류장 7'), findsOneWidget);
      expect(find.text('승차 정류장 · 정류장 6'), findsOneWidget);
      expect(
        tester.state<ScrollableState>(scrollable).position.pixels,
        selectedOffset,
      );
      expect(loads, 1);
      expect(find.byKey(const ValueKey('reselect-stops')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('occurrence-6')));
      await tester.pump();
      expect(find.text('승차 정류장 · 정류장 6'), findsOneWidget);
      expect(find.text('목표 정류장 · 정류장 7'), findsOneWidget);

      final pending = Completer<BoardingCandidates>();
      respond = () => pending.future;
      await tap(tester, 'find-candidates');
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(requestChecks.last(), isFalse);
      pending.complete(BoardingCandidates.fromJson(candidatesJson()));
      await tester.pumpAndSettle();
      expect(find.byType(BoardingCandidatesView), findsNothing);
      expect(queries, [(754, 7, 6), (754, 7, 6)]);
      expect(loads, 1);
    },
  );

  testWidgets(
    'stop loading empty error retry and route ID change clear old state',
    (tester) async {
      await setup(tester);
      final pending = Completer<List<BusRouteStopOccurrence>>();
      var calls = 0;
      Future<List<BusRouteStopOccurrence>> stops(int id) {
        calls++;
        if (calls == 1) return pending.future;
        if (calls == 2) throw Exception('offline');
        return Future.value(boardingStops);
      }

      await tester.pumpWidget(screen(stops: stops));
      expect(find.text('정류장 조회 중...'), findsOneWidget);
      pending.complete([]);
      await tester.pumpAndSettle();
      expect(find.text('정류장 정보가 없습니다.'), findsOneWidget);
      await tester.tap(find.text('정류장 다시 조회'));
      await tester.pumpAndSettle();
      expect(find.text('정류장 정보를 불러오지 못했습니다.'), findsOneWidget);
      await tester.tap(find.text('정류장 다시 조회'));
      await tester.pumpAndSettle();
      await query(tester);
      await tester.pumpWidget(
        screen(
          route: const BusRoute(
            id: 1390,
            routeNumber: '7000',
            regionName: '다른 지역',
          ),
          stops: (id) async {
            expect(id, 1390);
            return boardingStops;
          },
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(BoardingCandidatesView), findsNothing);
      expect(find.byKey(const ValueKey('find-candidates')), findsNothing);
      await query(tester);
      expect(queries.last, (1390, 22, 11));
    },
  );

  testWidgets(
    'duplicate clicks suppressed and old target response cannot replace a new result',
    (tester) async {
      await setup(tester);
      final old = Completer<BoardingCandidates>();
      respond = () => old.future;
      await tester.pumpWidget(
        screen(
          stops: (_) async => [
            ...boardingStops,
            const BusRouteStopOccurrence(
              id: 33,
              name: '새 목표',
              order: 15,
              canNotifyOneStopBefore: true,
              canNotifyOneStopAfter: true,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();
      await tap(tester, 'occurrence-11');
      await tap(tester, 'occurrence-22');
      await tap(tester, 'find-candidates');
      await tap(tester, 'find-candidates');
      expect(queries, hasLength(1));
      expect(find.text('차량 후보를 조회하고 있어요.'), findsOneWidget);
      await tap(tester, 'reselect-stops');
      await tap(tester, 'occurrence-22');
      await tap(tester, 'occurrence-33');
      expect(requestChecks.first(), isFalse);
      respond = () async => BoardingCandidates.fromJson(
        candidatesJson(candidates: [candidateJson(number: '새 차량')]),
      );
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      old.complete(
        BoardingCandidates.fromJson(
          candidatesJson(candidates: [candidateJson(number: '이전 차량')]),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('새 차량'), findsOneWidget);
      expect(find.text('이전 차량'), findsNothing);
      expect(find.textContaining('목표 정류장 · 새 목표'), findsOneWidget);
      expect(queries[1], (754, 33, 22));
      await tap(tester, 'find-candidates');
      expect(requestChecks[1](), isFalse);
      await tester.pumpAndSettle();
      expect(queries, hasLength(3));
    },
  );

  testWidgets(
    'boarding change and route change invalidate requests still in flight',
    (tester) async {
      await setup(tester);
      final old = Completer<BoardingCandidates>();
      respond = () => old.future;
      await tester.pumpWidget(screen());
      await tester.pumpAndSettle();
      await tap(tester, 'occurrence-11');
      await tap(tester, 'occurrence-22');
      await tap(tester, 'find-candidates');
      await tap(tester, 'reselect-stops');
      await tap(tester, 'occurrence-11');
      await tap(tester, 'occurrence-22');
      expect(requestChecks.first(), isFalse);
      await tap(tester, 'find-candidates');
      await tester.pumpWidget(
        screen(
          route: const BusRoute(
            id: 1390,
            routeNumber: '7000',
            regionName: '다른 지역',
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(requestChecks.last(), isFalse);
      old.complete(BoardingCandidates.fromJson(candidatesJson()));
      await tester.pumpAndSettle();
      expect(find.byType(BoardingCandidatesView), findsNothing);
    },
  );

  for (final action in ['logout', 'account change', 'dispose']) {
    testWidgets(
      'late candidates after $action are discarded and references removed',
      (tester) async {
        await setup(tester);
        final pending = Completer<BoardingCandidates>();
        respond = () => pending.future;
        await tester.pumpWidget(screen());
        await tester.pumpAndSettle();
        await tap(tester, 'occurrence-11');
        await tap(tester, 'occurrence-22');
        await tap(tester, 'find-candidates');
        if (action == 'dispose') {
          await tester.pumpWidget(const SizedBox());
        } else {
          await auth.logout();
          if (action == 'account change') await auth.login();
        }
        await tester.pump();
        expect(requestChecks.single(), isFalse);
        pending.complete(BoardingCandidates.fromJson(candidatesJson()));
        await tester.pumpAndSettle();
        expect(find.byType(BoardingCandidatesView), findsNothing);
        expect(find.text('서울70사1234'), findsNothing);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'already displayed candidates disappear immediately at logout and do not return for another account',
    (tester) async {
      await setup(tester);
      await tester.pumpWidget(screen());
      await tester.pumpAndSettle();
      await query(tester);
      expect(find.text('서울70사1234'), findsOneWidget);
      await auth.logout();
      await tester.pumpAndSettle();
      expect(find.text('서울70사1234'), findsNothing);
      await auth.login();
      await tester.pumpAndSettle();
      expect(find.byType(BoardingCandidatesView), findsNothing);
    },
  );

  testWidgets(
    'old stop list cannot overwrite new route and late stop errors after dispose are ignored',
    (tester) async {
      await setup(tester);
      final oldStops = Completer<List<BusRouteStopOccurrence>>();
      await tester.pumpWidget(screen(stops: (_) => oldStops.future));
      await tester.pumpWidget(
        screen(
          route: const BusRoute(
            id: 1390,
            routeNumber: '7000',
            regionName: '다른 지역',
          ),
        ),
      );
      await tester.pumpAndSettle();
      oldStops.complete(const []);
      await tester.pumpAndSettle();
      expect(find.text('정류장 정보가 없습니다.'), findsNothing);
      expect(find.byKey(const ValueKey('occurrence-22')), findsOneWidget);
      final later = Completer<List<BusRouteStopOccurrence>>();
      await tester.pumpWidget(
        screen(route: boardingRoute, stops: (_) => later.future),
      );
      await tester.pumpWidget(const SizedBox());
      later.completeError(Exception('late stop failure'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'small screen with large text scrolls through required and optional occurrence selection',
    (tester) async {
      await setup(tester);
      tester.view.physicalSize = const Size(320, 568);
      tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
      addTearDown(tester.platformDispatcher.clearPlatformBrightnessTestValue);
      await tester.pumpWidget(
        MaterialApp(
          darkTheme: StopBellTheme.dark,
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context)
                .copyWith(textScaler: const TextScaler.linear(2)),
            child: child!,
          ),
          home: Scaffold(
            body: BoardingStopScreen(
              route: boardingRoute,
              authSession: auth,
              findStops: (_) async => boardingStops,
              findCandidates:
                  ({
                    required routeId,
                    required targetStopOccurrenceId,
                    boardingStopOccurrenceId,
                    expectedGeneration,
                    isRequestCurrent,
                  }) async => BoardingCandidates.fromJson(
                    candidatesJson(status: 'EMPTY'),
                  ),
              onBack: () {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('노선도에서 승차 정류장을 선택하세요.').hitTestable(), findsOneWidget);
      await tap(tester, 'occurrence-11');
      await tap(tester, 'occurrence-22');
      for (final widget in [
        find.byKey(const ValueKey('summary-target')),
        find.byKey(const ValueKey('summary-boarding')),
        find.byType(BusRouteTimeline),
      ]) {
        await tester.ensureVisible(widget);
        expect(widget.hitTestable(), findsOneWidget);
        expect(Theme.of(tester.element(widget)).brightness, Brightness.dark);
      }
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('현재 확인 가능한 차량 후보가 없어요.'));
      expect(find.text('현재 확인 가능한 차량 후보가 없어요.').hitTestable(), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );

  for (final (failure, status, message) in [
    (CandidateFailure.http, 503, '버스 정보를 일시적으로'),
    (CandidateFailure.parsing, null, '응답 형식을'),
    (CandidateFailure.network, null, '통신 오류'),
    (CandidateFailure.timeout, null, '시간이 초과'),
    (CandidateFailure.http, 400, '정류장 정보가 변경'),
    (CandidateFailure.http, 404, '정류장 정보가 변경'),
  ]) {
    testWidgets(
      '$failure $status is not empty and offers current-context retry',
      (tester) async {
        await setup(tester);
        respond = () async =>
            throw BoardingCandidateException(failure, statusCode: status);
        await tester.pumpWidget(screen());
        await tester.pumpAndSettle();
        await query(tester);
        expect(find.textContaining(message), findsOneWidget);
        expect(find.text('현재 확인 가능한 차량 후보가 없어요.'), findsNothing);
        respond = () async =>
            BoardingCandidates.fromJson(candidatesJson(status: 'EMPTY'));
        if (status == 400 || status == 404) {
          expect(find.byKey(const ValueKey('find-candidates')), findsNothing);
          expect(find.byKey(const ValueKey('reselect-stops')), findsNothing);
          await tester.tap(find.text('정류장 목록 다시 선택'));
          await tester.pumpAndSettle();
          await query(tester);
        } else {
          await tap(tester, 'find-candidates');
          await tester.pumpAndSettle();
        }
        expect(find.text('현재 확인 가능한 차량 후보가 없어요.'), findsOneWidget);
        expect(queries.last, (754, 22, 11));
      },
    );
  }
}
