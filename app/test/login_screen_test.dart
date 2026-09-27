import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/main.dart';

import 'alarm_test_fakes.dart';

const pair = TokenPair(
  accessToken: 'fixture-access',
  refreshToken: 'fixture-refresh',
);

class FakeAuthenticator implements Authenticator {
  int calls = 0;
  final Completer<TokenPair> result = Completer<TokenPair>();

  @override
  Future<TokenPair> login() {
    calls++;
    return result.future;
  }
}

class FakeStorage implements TokenPairStorage {
  FakeStorage({this.pair, this.readGate});
  TokenPair? pair;
  final Completer<void>? readGate;
  @override
  Future<TokenPair?> read() async {
    await readGate?.future;
    return pair;
  }

  @override
  Future<void> save(TokenPair pair) async => this.pair = pair;
  @override
  Future<void> delete() async => pair = null;
}

class FakeBackend extends BackendAuthClient {
  FakeBackend() : super(apiBaseUrl: Uri.parse('http://localhost:8080'));
  final Completer<void> logoutGate = Completer<void>();
  int logoutCalls = 0;
  @override
  Future<void> logout(String refreshToken) {
    logoutCalls++;
    expect(refreshToken, 'fixture-refresh');
    return logoutGate.future;
  }
}

AuthSession session(
  FakeAuthenticator authenticator,
  FakeStorage storage, {
  BackendAuthClient? backend,
}) => AuthSession(
  authenticator: authenticator,
  backend:
      backend ??
      BackendAuthClient(apiBaseUrl: Uri.parse('http://localhost:8080')),
  tokenStorage: storage,
);

StopBellApplication app(
  AuthSession authSession, {
  Future<List<BusRoute>> Function(String)? searchRoutes,
  Future<List<BusRouteStopOccurrence>> Function(int)? findStops,
  AlarmClient? alarmClient,
}) => StopBellApplication(
  authSession: authSession,
  searchRoutes: searchRoutes ?? (String _) async => <BusRoute>[],
  findStops: findStops ?? (int _) async => <BusRouteStopOccurrence>[],
  alarmClient: alarmClient ?? EmptyAlarmClient(),
);

const createdAlarm = Alarm(
  id: 15,
  transitType: AlarmTransitType.bus,
  status: AlarmStatus.inactive,
  routeNumber: '7000',
  stopName: '첫 정류장',
  notifyOneStopBefore: false,
  notifyOneStopAfter: false,
);

class FlowAlarms extends Fake implements AlarmClient {
  Future<List<Alarm>> Function() list = () async => [];
  Future<Alarm> Function(int) detail = (_) async => createdAlarm;
  Future<Alarm> Function(int, bool, bool) createAlarm = (_, _, _) async =>
      createdAlarm;
  Future<Alarm> Function(int) activateAction = (_) async => createdAlarm;
  int createCalls = 0;
  int activateCalls = 0;

  @override
  Future<List<Alarm>> findAll() => list();
  @override
  Future<Alarm> findById(int id) => detail(id);
  @override
  Future<Alarm> create({
    required int targetStopOccurrenceId,
    required bool notifyOneStopBefore,
    required bool notifyOneStopAfter,
  }) {
    createCalls++;
    return createAlarm(
      targetStopOccurrenceId,
      notifyOneStopBefore,
      notifyOneStopAfter,
    );
  }

  @override
  Future<Alarm> activate(int id) async {
    activateCalls++;
    return activateAction(id);
  }
}

void main() {
  testWidgets('route candidate opens its stops and back returns to search', (
    tester,
  ) async {
    final ids = <int>[];
    await tester.pumpWidget(
      app(
        session(FakeAuthenticator(), FakeStorage(pair: pair)),
        searchRoutes: (_) async => const [
          BusRoute(id: 754, routeNumber: '7000', regionName: '수원시'),
        ],
        findStops: (id) async {
          ids.add(id);
          return const [
            BusRouteStopOccurrence(
              id: 11,
              name: '첫 정류장',
              order: 1,
              canNotifyOneStopBefore: false,
              canNotifyOneStopAfter: true,
            ),
          ];
        },
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('새 알림 만들기'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '7000');
    await tester.tap(find.text('검색'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('수원시'));
    await tester.pumpAndSettle();
    expect(ids, [754]);
    expect(find.text('7000'), findsOneWidget);
    expect(find.text('수원시'), findsOneWidget);
    expect(find.text('첫 정류장'), findsOneWidget);
    await tester.tap(find.byTooltip('노선 다시 선택'));
    await tester.pumpAndSettle();
    expect(find.text('노선번호를 입력해 검색하세요.'), findsOneWidget);
  });

  testWidgets('logout during Stop fetch keeps Login even after late result', (
    tester,
  ) async {
    final backend = FakeBackend();
    final pending = Completer<List<BusRouteStopOccurrence>>();
    await tester.pumpWidget(
      app(
        session(FakeAuthenticator(), FakeStorage(pair: pair), backend: backend),
        searchRoutes: (_) async => const [
          BusRoute(id: 754, routeNumber: '7000', regionName: '수원시'),
        ],
        findStops: (_) => pending.future,
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('새 알림 만들기'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '7000');
    await tester.tap(find.text('검색'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('수원시'));
    await tester.pump();
    expect(find.text('정류장 조회 중...'), findsOneWidget);
    await tester.tap(find.text('로그아웃'));
    await tester.pump();
    backend.logoutGate.complete();
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);
    pending.complete(const [
      BusRouteStopOccurrence(
        id: 11,
        name: '늦은 정류장',
        order: 1,
        canNotifyOneStopBefore: false,
        canNotifyOneStopAfter: false,
      ),
    ]);
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);
    expect(find.text('늦은 정류장'), findsNothing);
  });

  testWidgets('startup 복구 중 표시하고 저장된 Pair로 로그인 상태를 복구한다', (tester) async {
    final auth = FakeAuthenticator();
    final gate = Completer<void>();
    final appSession = session(auth, FakeStorage(pair: pair, readGate: gate));
    await tester.pumpWidget(app(appSession));
    expect(find.text('인증 상태 확인 중...'), findsOneWidget);
    gate.complete();
    await tester.pumpAndSettle();
    expect(find.text('아직 만든 알람이 없습니다.'), findsOneWidget);
    expect(auth.calls, 0);
  });

  testWidgets('진행 중 중복 로그인을 막고 Backend 실패 후 재시도할 수 있다', (tester) async {
    final auth = FakeAuthenticator();
    await tester.pumpWidget(app(session(auth, FakeStorage())));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Google로 계속하기'));
    await tester.pump();
    expect(auth.calls, 1);
    expect(find.text('로그인 중...'), findsOneWidget);
    expect(
      tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
      isNull,
    );

    auth.result.completeError(const BackendAuthException('서버 로그인이 실패했습니다.'));
    await tester.pump();
    expect(find.text('노선번호를 입력해 검색하세요.'), findsNothing);
    expect(find.text('서버 로그인이 실패했습니다.'), findsOneWidget);
    expect(
      tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
      isNotNull,
    );
  });

  testWidgets('Google과 Backend 로그인 및 저장 뒤에만 성공을 표시한다', (tester) async {
    final auth = FakeAuthenticator();
    final storage = FakeStorage();
    await tester.pumpWidget(app(session(auth, storage)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Google로 계속하기'));
    await tester.pump();
    expect(find.text('노선번호를 입력해 검색하세요.'), findsNothing);
    auth.result.complete(pair);
    await tester.pumpAndSettle();
    expect(storage.pair, same(pair));
    expect(find.text('아직 만든 알람이 없습니다.'), findsOneWidget);
  });

  testWidgets('Logout 버튼은 중복 입력을 막고 완료 뒤 Google login을 표시한다', (tester) async {
    final backend = FakeBackend();
    final storage = FakeStorage(pair: pair);
    await tester.pumpWidget(
      app(session(FakeAuthenticator(), storage, backend: backend)),
    );
    await tester.pumpAndSettle();
    expect(find.text('로그아웃'), findsOneWidget);
    await tester.tap(find.text('로그아웃'));
    await tester.pump();
    expect(find.text('로그아웃 중...'), findsOneWidget);
    expect(
      tester.widget<ElevatedButton>(find.byType(ElevatedButton)).onPressed,
      isNull,
    );
    expect(backend.logoutCalls, 1);
    backend.logoutGate.complete();
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);
    expect(storage.pair, isNull);
  });

  testWidgets('검색 중 AuthSession 로그아웃 뒤에는 Login 화면을 유지한다', (tester) async {
    final backend = FakeBackend();
    final searchResult = Completer<List<BusRoute>>();
    await tester.pumpWidget(
      app(
        session(FakeAuthenticator(), FakeStorage(pair: pair), backend: backend),
        searchRoutes: (_) => searchResult.future,
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('새 알림 만들기'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '7000');
    await tester.tap(find.text('검색'));
    await tester.pump();
    expect(find.text('노선 검색 중...'), findsOneWidget);

    await tester.tap(find.text('로그아웃'));
    await tester.pump();
    backend.logoutGate.complete();
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);

    searchResult.complete(const [
      BusRoute(id: 1, routeNumber: '7000', regionName: '서울'),
    ]);
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);
    expect(find.text('서울'), findsNothing);
  });

  testWidgets(
    'create opens INACTIVE detail without activate and back refreshes list',
    (tester) async {
      final alarms = FlowAlarms();
      var lists = 0;
      alarms.list = () async {
        lists++;
        return lists == 1 ? [] : [createdAlarm];
      };
      await tester.pumpWidget(
        app(
          session(FakeAuthenticator(), FakeStorage(pair: pair)),
          alarmClient: alarms,
          searchRoutes: (_) async => const [
            BusRoute(id: 754, routeNumber: '7000', regionName: '수원시'),
          ],
          findStops: (_) async => const [
            BusRouteStopOccurrence(
              id: 12345,
              name: '첫 정류장',
              order: 1,
              canNotifyOneStopBefore: false,
              canNotifyOneStopAfter: true,
            ),
          ],
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('새 알림 만들기'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '7000');
      await tester.tap(find.text('검색'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('수원시'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey(12345)));
      await tester.pump();
      await tester.tap(find.text('알람 만들기'));
      await tester.pumpAndSettle();
      expect(alarms.createCalls, 1);
      expect(alarms.activateCalls, 0);
      expect(find.text('상태: INACTIVE · 비활성'), findsOneWidget);
      await tester.tap(find.byTooltip('알람 목록으로'));
      await tester.pumpAndSettle();
      expect(lists, 2);
      expect(find.text('7000 · 첫 정류장'), findsOneWidget);
    },
  );

  testWidgets(
    'logout during Alarm list fetch discards late list and new login starts fresh',
    (tester) async {
      final backend = FakeBackend();
      final auth = FakeAuthenticator();
      final sessionForTest = session(
        auth,
        FakeStorage(pair: pair),
        backend: backend,
      );
      final pending = Completer<List<Alarm>>();
      final alarms = FlowAlarms();
      var calls = 0;
      alarms.list = () {
        calls++;
        return calls == 1 ? pending.future : Future.value([]);
      };
      await tester.pumpWidget(app(sessionForTest, alarmClient: alarms));
      await tester.pump();
      expect(find.text('알람 목록 조회 중...'), findsOneWidget);
      await tester.tap(find.text('로그아웃'));
      await tester.pump();
      backend.logoutGate.complete();
      await tester.pumpAndSettle();
      pending.complete([createdAlarm]);
      await tester.pumpAndSettle();
      expect(find.text('Google로 계속하기'), findsOneWidget);
      expect(find.text('7000 · 첫 정류장'), findsNothing);
      await tester.tap(find.text('Google로 계속하기'));
      auth.result.complete(pair);
      await tester.pumpAndSettle();
      expect(find.text('아직 만든 알람이 없습니다.'), findsOneWidget);
      expect(calls, 2);
    },
  );

  testWidgets('logout during Alarm detail fetch never restores detail', (
    tester,
  ) async {
    final backend = FakeBackend();
    final pending = Completer<Alarm>();
    final alarms = FlowAlarms()
      ..list = (() async => [createdAlarm])
      ..detail = ((_) => pending.future);
    await tester.pumpWidget(
      app(
        session(FakeAuthenticator(), FakeStorage(pair: pair), backend: backend),
        alarmClient: alarms,
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey(15)));
    await tester.pump();
    expect(find.text('알람 조회 중...'), findsOneWidget);
    await tester.tap(find.text('로그아웃'));
    await tester.pump();
    backend.logoutGate.complete();
    await tester.pumpAndSettle();
    pending.complete(createdAlarm);
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);
    expect(find.text('알람 상세'), findsNothing);
  });

  testWidgets('logout during create never opens old Alarm detail', (
    tester,
  ) async {
    final backend = FakeBackend();
    final pending = Completer<Alarm>();
    final alarms = FlowAlarms()..createAlarm = ((_, _, _) => pending.future);
    await tester.pumpWidget(
      app(
        session(FakeAuthenticator(), FakeStorage(pair: pair), backend: backend),
        alarmClient: alarms,
        searchRoutes: (_) async => const [
          BusRoute(id: 754, routeNumber: '7000', regionName: '수원시'),
        ],
        findStops: (_) async => const [
          BusRouteStopOccurrence(
            id: 12345,
            name: '첫 정류장',
            order: 1,
            canNotifyOneStopBefore: false,
            canNotifyOneStopAfter: true,
          ),
        ],
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('새 알림 만들기'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '7000');
    await tester.tap(find.text('검색'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('수원시'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey(12345)));
    await tester.pump();
    await tester.tap(find.text('알람 만들기'));
    await tester.pump();
    await tester.tap(find.text('로그아웃'));
    await tester.pump();
    backend.logoutGate.complete();
    await tester.pumpAndSettle();
    pending.complete(createdAlarm);
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);
    expect(find.text('알람 상세'), findsNothing);
  });

  testWidgets('logout during activation discards late mutation response', (
    tester,
  ) async {
    final backend = FakeBackend();
    final pending = Completer<Alarm>();
    final alarms = FlowAlarms()
      ..list = (() async => [createdAlarm])
      ..detail = ((_) async => createdAlarm)
      ..activateAction = ((_) => pending.future);
    await tester.pumpWidget(
      app(
        session(FakeAuthenticator(), FakeStorage(pair: pair), backend: backend),
        alarmClient: alarms,
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey(15)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('활성화'));
    await tester.pump();
    expect(alarms.activateCalls, 1);
    await tester.tap(find.text('로그아웃'));
    await tester.pump();
    backend.logoutGate.complete();
    await tester.pumpAndSettle();
    pending.complete(createdAlarm);
    await tester.pumpAndSettle();
    expect(find.text('Google로 계속하기'), findsOneWidget);
    expect(find.text('알람 상세'), findsNothing);
  });
}
