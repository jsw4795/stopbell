import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:http/http.dart' as http;
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/boarding/boarding_candidate_client.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/boarding/boarding_candidates_view.dart';
import 'package:stopbell/features/boarding/boarding_stop_screen.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';

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
    await tester.tap(finder);
    await tester.pump();
  }

  Future<void> query(WidgetTester tester) async {
    await tap(tester, 'occurrence-22');
    await tap(tester, 'find-candidates');
    await tester.pumpAndSettle();
  }

  testWidgets(
    'target required, optional boarding and repeated names use exact occurrence IDs',
    (tester) async {
      await setup(tester);
      await tester.pumpWidget(screen());
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<FilledButton>(find.byKey(const ValueKey('find-candidates')))
            .onPressed,
        isNull,
      );
      expect(find.textContaining('노선 순서 3'), findsOneWidget);
      expect(find.textContaining('노선 순서 9'), findsOneWidget);
      expect(find.textContaining('이전: 이전 · 다음: 다음'), findsNWidgets(2));
      expect(find.byType(SwitchListTile), findsNothing);
      await query(tester);
      expect(queries, [(754, 22, null)]);
      expect(find.byType(BoardingCandidatesView), findsOneWidget);
      await tap(tester, 'pick-boarding');
      await tap(tester, 'occurrence-11');
      expect(find.byType(BoardingCandidatesView), findsNothing);
      expect(requestChecks.first(), isFalse);
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      expect(queries.last, (754, 22, 11));
      await tap(tester, 'pick-boarding');
      await tap(tester, 'no-boarding');
      expect(find.byType(BoardingCandidatesView), findsNothing);
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      expect(queries.last, (754, 22, null));
      await tap(tester, 'pick-target');
      await tap(tester, 'occurrence-11');
      expect(find.byType(BoardingCandidatesView), findsNothing);
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      expect(queries.last, (754, 11, null));
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
      expect(
        tester
            .widget<FilledButton>(find.byKey(const ValueKey('find-candidates')))
            .onPressed,
        isNull,
      );
      await query(tester);
      expect(queries.last, (1390, 22, null));
    },
  );

  testWidgets(
    'duplicate clicks suppressed and old target response cannot replace a new result',
    (tester) async {
      await setup(tester);
      final old = Completer<BoardingCandidates>();
      respond = () => old.future;
      await tester.pumpWidget(screen());
      await tester.pumpAndSettle();
      await tap(tester, 'occurrence-22');
      await tap(tester, 'find-candidates');
      await tap(tester, 'find-candidates');
      expect(queries, hasLength(1));
      expect(find.text('차량 후보를 조회하고 있어요.'), findsOneWidget);
      await tap(tester, 'pick-target');
      await tap(tester, 'occurrence-11');
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
      await tap(tester, 'occurrence-22');
      await tap(tester, 'find-candidates');
      await tap(tester, 'pick-boarding');
      await tap(tester, 'occurrence-11');
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
      await tester.pumpWidget(
        MaterialApp(
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
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('occurrence-22')),
        250,
      );
      await tap(tester, 'occurrence-22');
      await tester.scrollUntilVisible(
        find.byKey(const ValueKey('find-candidates')),
        250,
      );
      await tap(tester, 'find-candidates');
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('현재 확인 가능한 차량 후보가 없어요.'), 250);
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
          expect(
            tester
                .widget<FilledButton>(
                  find.byKey(const ValueKey('find-candidates')),
                )
                .onPressed,
            isNull,
          );
          expect(
            tester
                .widget<TextButton>(find.byKey(const ValueKey('pick-target')))
                .onPressed,
            isNull,
          );
          await tester.tap(find.text('정류장 목록 다시 선택'));
          await tester.pumpAndSettle();
          await query(tester);
        } else {
          await tap(tester, 'find-candidates');
          await tester.pumpAndSettle();
        }
        expect(find.text('현재 확인 가능한 차량 후보가 없어요.'), findsOneWidget);
        expect(queries.last, (754, 22, null));
      },
    );
  }
}
