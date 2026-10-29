import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_stop_screen.dart';

const _pair = TokenPair(
  accessToken: 'old-access',
  refreshToken: 'old-refresh',
  sessionIdentity: 'fixture-session',
);
const _rotated = TokenPair(
  accessToken: 'new-access',
  refreshToken: 'new-refresh',
);
const _deadline = Duration(seconds: 15);

http.Response _json(Object value, [int status = 200]) => http.Response(
  jsonEncode(value),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

http.Response _alarm([int status = 201]) => _json({
  'id': 15,
  'transitType': 'BUS',
  'status': 'INACTIVE',
  'routeNumber': '7000',
  'stopName': '사색의광장',
  'notifyOneStopBefore': true,
  'notifyOneStopAfter': false,
}, status);

class _Storage implements TokenPairStorage {
  TokenPair? pair = _pair;
  @override
  Future<TokenPair?> read() async => pair;
  @override
  Future<void> save(TokenPair value) async => pair = value;
  @override
  Future<void> delete() async => pair = null;
}

class _Authenticator implements Authenticator {
  TokenPair pair = _rotated;
  @override
  Future<TokenPair> login() async => pair;
}

class _Fixture {
  final requests = <http.Request>[];
  final storage = _Storage();
  final authenticator = _Authenticator();
  final refreshRequests = <http.Request>[];
  int refreshes = 0;
  late AuthSession auth;
  late AlarmApiClient client;
  late Future<http.Response> Function(http.Request) respond;
  Future<http.Response> Function()? refresh;
  Future<http.Response> Function()? logout;

  Future<void> initialize() async {
    final baseUrl = Uri.parse('http://localhost:8080');
    final transport = MockClient((request) async {
      switch (request.url.path) {
        case '/auth/refresh':
          refreshes++;
          refreshRequests.add(request);
          return refresh?.call() ??
              _json({
                'accessToken': _rotated.accessToken,
                'refreshToken': _rotated.refreshToken,
              });
        case '/auth/logout':
          return logout?.call() ?? http.Response('', 204);
        default:
          requests.add(request);
          return respond(request);
      }
    });
    addTearDown(transport.close);
    auth = AuthSession(
      authenticator: authenticator,
      backend: BackendAuthClient(apiBaseUrl: baseUrl, client: transport),
      tokenStorage: storage,
    );
    addTearDown(auth.dispose);
    await auth.initialize();
    client = AlarmApiClient(
      AuthenticatedApiClient(
        apiBaseUrl: baseUrl,
        authSession: auth,
        client: transport,
      ),
    );
  }

  Future<Object?> call([String operation = 'create']) => switch (operation) {
    'create' => client.create(
      targetStopOccurrenceId: 12345,
      notifyOneStopBefore: true,
      notifyOneStopAfter: false,
    ),
    'activate' => client.activate(15),
    'deactivate' => client.deactivate(15),
    'delete' => client.delete(15),
    'list' => client.findAll(),
    _ => client.findById(15),
  };
}

void main() {
  testWidgets('create timeout then late 401 never refreshes or replays', (
    tester,
  ) async {
    final lateResponse = Completer<http.Response>();
    final f = _Fixture()..respond = (_) => lateResponse.future;
    await f.initialize();
    final result = expectLater(f.call(), throwsA(isA<TimeoutException>()));
    await tester.pump();
    expect(f.requests, hasLength(1));
    await tester.pump(_deadline);
    await result;
    // A replay succeeds, so the pre-fix failure reports actual request counts.
    f.respond = (_) async => _alarm();
    lateResponse.complete(http.Response('', 401));
    await tester.pump();
    expect(
      {'alarm': f.requests.length, 'refresh': f.refreshes},
      {'alarm': 1, 'refresh': 0},
    );
    expect(f.auth.accessToken, _pair.accessToken);
    await tester.pump(_deadline);
    expect(f.requests, hasLength(1));
  });

  for (final operation in [
    'activate',
    'deactivate',
    'delete',
    'list',
    'detail',
  ]) {
    testWidgets('$operation timeout then late 401 never refreshes or replays', (
      tester,
    ) async {
      final response = Completer<http.Response>();
      final f = _Fixture()..respond = (_) => response.future;
      await f.initialize();
      final result = expectLater(
        f.call(operation),
        throwsA(isA<TimeoutException>()),
      );
      await tester.pump(_deadline);
      await result;
      response.complete(http.Response('', 401));
      await tester.pump();
      expect(f.requests, hasLength(1));
      expect(f.refreshes, 0);
      await tester.pump(_deadline);
      expect(f.requests, hasLength(1));
    });
  }

  testWidgets('no response still times out at the default 15 second deadline', (
    tester,
  ) async {
    final f = _Fixture()..respond = (_) => Completer<http.Response>().future;
    await f.initialize();
    var finished = false;
    final result = expectLater(
      f.call(),
      throwsA(isA<TimeoutException>()),
    ).then((_) => finished = true);
    await tester.pump(const Duration(milliseconds: 14999));
    expect(finished, isFalse);
    await tester.pump(const Duration(milliseconds: 1));
    await result;
    expect(finished, isTrue);
    expect(f.requests, hasLength(1));
    expect(f.refreshes, 0);
  });

  testWidgets('401 within deadline replays exact create with rotated token', (
    tester,
  ) async {
    final f = _Fixture()
      ..respond = (request) async =>
          request.headers['authorization'] == 'Bearer old-access'
          ? http.Response('', 401)
          : _alarm();
    await f.initialize();
    final identity = f.auth.sessionIdentity;
    final generation = f.auth.generation;
    expect(
      (await f.client.create(
        targetStopOccurrenceId: 12345,
        notifyOneStopBefore: true,
        notifyOneStopAfter: false,
      )).id,
      15,
    );
    expect(f.requests, hasLength(2));
    expect(f.refreshes, 1);
    expect(f.refreshRequests.single.method, 'POST');
    expect(jsonDecode(f.refreshRequests.single.body), {
      'refreshToken': _pair.refreshToken,
    });
    final first = f.requests.first;
    final replay = f.requests.last;
    expect(replay.method, first.method);
    expect(replay.url, first.url);
    expect(replay.body, first.body);
    expect(replay.headers['content-type'], first.headers['content-type']);
    expect(first.headers['authorization'], 'Bearer old-access');
    expect(replay.headers['authorization'], 'Bearer new-access');
    expect(f.storage.pair?.refreshToken, _rotated.refreshToken);
    expect(f.auth.sessionIdentity, identity);
    expect(f.auth.generation, generation);
  });

  testWidgets('timeout during shared refresh blocks only the expired request', (
    tester,
  ) async {
    final initial = Completer<http.Response>();
    final refresh = Completer<http.Response>();
    final f = _Fixture()
      ..refresh = (() => refresh.future)
      ..respond = (request) async =>
          request.headers['authorization'] == 'Bearer old-access'
          ? initial.future
          : _alarm(200);
    await f.initialize();
    final identity = f.auth.sessionIdentity;
    final generation = f.auth.generation;
    final expired = expectLater(f.call(), throwsA(isA<TimeoutException>()));
    await tester.pump();
    // Start Refresh at t=1 so A's t=15 deadline precedes auth HTTP's t=16.
    await tester.pump(const Duration(seconds: 1));
    initial.complete(http.Response('', 401));
    await tester.pump();
    expect(f.refreshes, 1);
    await tester.pump(const Duration(seconds: 9));
    // B uses the same AlarmApiClient and still has ten seconds left when A expires.
    final current = f.client.findById(15);
    await tester.pump();
    expect(f.requests, hasLength(2));
    expect(f.refreshes, 1);
    await tester.pump(const Duration(seconds: 5));
    await expired;
    refresh.complete(
      _json({
        'accessToken': _rotated.accessToken,
        'refreshToken': _rotated.refreshToken,
      }),
    );
    await tester.pump();
    expect((await current).id, 15);
    expect(f.requests.map((r) => r.method), ['POST', 'GET', 'GET']);
    expect(f.requests.last.headers['authorization'], 'Bearer new-access');
    expect(f.refreshes, 1);
    expect(f.auth.state, AuthState.authenticated);
    expect(f.auth.generation, generation);
    expect(f.auth.sessionIdentity, identity);
    expect(f.storage.pair?.refreshToken, _rotated.refreshToken);
  });

  testWidgets(
    'backend refresh deadline fails still-current B as well as expired A',
    (tester) async {
      final refresh = Completer<http.Response>();
      final f = _Fixture()
        ..refresh = (() => refresh.future)
        ..respond = (_) async => http.Response('', 401);
      await f.initialize();
      final first = expectLater(f.call(), throwsA(isA<TimeoutException>()));
      await tester.pump();
      await tester.pump(const Duration(seconds: 10));
      var secondFinished = false;
      final second = expectLater(
        f.client.findById(15),
        throwsA(isA<TimeoutException>()),
      ).then((_) => secondFinished = true);
      await tester.pump();
      expect(f.refreshes, 1);
      expect(secondFinished, isFalse);
      // A and Refresh expire at t=15; B's own Alarm deadline would be t=25.
      await tester.pump(const Duration(seconds: 5));
      await first;
      await second;
      expect(secondFinished, isTrue);
      refresh.complete(
        _json({
          'accessToken': _rotated.accessToken,
          'refreshToken': _rotated.refreshToken,
        }),
      );
      await tester.pump();
      expect(f.requests.map((r) => r.method), ['POST', 'GET']);
      expect(f.refreshes, 1);
      expect(f.storage.pair, same(_pair));
      expect(f.auth.currentPair, same(_pair));
      expect(f.auth.state, AuthState.authenticated);
    },
  );

  testWidgets('late 401 of timed out A does not invalidate pending B', (
    tester,
  ) async {
    final a = Completer<http.Response>();
    final b = Completer<http.Response>();
    final f = _Fixture()
      ..respond = (request) => request.method == 'POST' ? a.future : b.future;
    await f.initialize();
    final expired = expectLater(f.call(), throwsA(isA<TimeoutException>()));
    await tester.pump(const Duration(seconds: 10));
    final current = f.client.findById(15);
    await tester.pump(const Duration(seconds: 5));
    await expired;
    a.complete(http.Response('', 401));
    await tester.pump();
    b.complete(_alarm(200));
    await tester.pump();
    expect((await current).id, 15);
    expect(f.requests, hasLength(2));
    expect(f.refreshes, 0);
    expect(f.auth.accessToken, _pair.accessToken);
  });

  for (final state in [
    'logging out',
    'different account',
    'same account login',
  ]) {
    testWidgets(
      'late 401 while $state never switches or replays old mutation',
      (tester) async {
        final lateResponse = Completer<http.Response>();
        final logout = Completer<http.Response>();
        final f = _Fixture()..respond = (_) => lateResponse.future;
        await f.initialize();
        final oldIdentity = f.auth.sessionIdentity;
        final result = expectLater(
          f.call(),
          throwsA(isA<StaleSessionException>()),
        );
        await tester.pump();
        Future<void>? pendingLogout;
        if (state == 'logging out') {
          f.logout = () => logout.future;
          pendingLogout = f.auth.logout();
          await tester.pump();
          expect(f.auth.isLoggingOut, isTrue);
        } else {
          await f.auth.logout();
          if (state == 'same account login') f.authenticator.pair = _pair;
          await f.auth.login();
          expect(f.auth.sessionIdentity, isNot(oldIdentity));
        }
        lateResponse.complete(http.Response('', 401));
        await tester.pump();
        await result;
        expect(f.requests, hasLength(1));
        expect(f.refreshes, 0);
        if (pendingLogout != null) {
          logout.complete(http.Response('', 204));
          await pendingLogout;
        } else {
          f.respond = (_) async => _alarm(200);
          expect((await f.client.findById(15)).id, 15);
          expect(
            f.requests.last.headers['authorization'],
            'Bearer ${f.authenticator.pair.accessToken}',
          );
        }
      },
    );
  }

  for (final phase in ['refresh', 'replay']) {
    testWidgets('one 15 second budget includes initial request and $phase', (
      tester,
    ) async {
      final first = Completer<http.Response>();
      final refresh = Completer<http.Response>();
      final replay = Completer<http.Response>();
      final f = _Fixture()
        ..refresh = (() => refresh.future)
        ..respond = (request) =>
            request.headers['authorization'] == 'Bearer old-access'
            ? first.future
            : replay.future;
      await f.initialize();
      var finished = false;
      final result = expectLater(
        f.call(),
        throwsA(isA<TimeoutException>()),
      ).then((_) => finished = true);
      await tester.pump(const Duration(seconds: 10));
      first.complete(http.Response('', 401));
      await tester.pump();
      expect(f.refreshes, 1);
      await tester.pump(const Duration(seconds: 4));
      void completeRefresh() => refresh.complete(
        _json({
          'accessToken': _rotated.accessToken,
          'refreshToken': _rotated.refreshToken,
        }),
      );
      if (phase == 'replay') {
        completeRefresh();
        await tester.pump();
        expect(f.requests, hasLength(2));
      }
      await tester.pump(const Duration(milliseconds: 999));
      expect(finished, isFalse);
      await tester.pump(const Duration(milliseconds: 1));
      await result;
      expect(finished, isTrue);
      if (phase == 'refresh') completeRefresh();
      // Even a late retry 401 cannot start another refresh or replay.
      replay.complete(http.Response('', 401));
      await tester.pump();
      expect(f.requests, hasLength(phase == 'refresh' ? 1 : 2));
      expect(f.refreshes, 1);
      expect(f.auth.accessToken, _rotated.accessToken);
    });
  }

  testWidgets('shared refresh 401 after timeout keeps auth rejection policy', (
    tester,
  ) async {
    final initial = Completer<http.Response>();
    final refresh = Completer<http.Response>();
    final f = _Fixture()
      ..refresh = (() => refresh.future)
      ..respond = (_) => initial.future;
    await f.initialize();
    final result = expectLater(f.call(), throwsA(isA<TimeoutException>()));
    await tester.pump();
    // The Alarm times out first; Refresh is still inside its own HTTP budget.
    await tester.pump(const Duration(seconds: 1));
    initial.complete(http.Response('', 401));
    await tester.pump();
    expect(f.refreshes, 1);
    await tester.pump(const Duration(seconds: 14));
    await result;
    refresh.complete(http.Response('', 401));
    await tester.pump();
    expect(f.requests, hasLength(1));
    expect(f.refreshes, 1);
    expect(f.auth.state, AuthState.unauthenticated);
    expect(f.storage.pair, isNull);
  });

  testWidgets(
    'late create success preserves unknown outcome without activation',
    (tester) async {
      final response = Completer<http.Response>();
      final f = _Fixture()..respond = (_) => response.future;
      await f.initialize();
      var created = 0;
      var showList = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: BusRouteStopScreen(
              route: const BusRoute(
                id: 754,
                routeNumber: '7000',
                regionName: '수원시',
              ),
              findStops: (_) async => [
                const BusRouteStopOccurrence(
                  id: 12345,
                  name: '사색의광장',
                  order: 3,
                  canNotifyOneStopBefore: true,
                  canNotifyOneStopAfter: true,
                ),
              ],
              alarmClient: f.client,
              onBack: () {},
              onCreated: (_) => created++,
              onShowAlarms: () => showList++,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey(12345)));
      await tester.pump();
      await tester.tap(find.text('알람 시작'));
      await tester.pump();
      await tester.pump(_deadline);
      expect(find.textContaining('중복 생성을 피하려면 알람 목록에서'), findsOneWidget);
      response.complete(_alarm());
      await tester.pumpAndSettle();
      expect(find.textContaining('중복 생성을 피하려면 알람 목록에서'), findsOneWidget);
      expect(find.text('알람 시작'), findsNothing);
      expect(find.text('다시 시도'), findsNothing);
      expect(created, 0);
      expect(f.requests, hasLength(1));
      expect(f.refreshes, 0);
      await tester.tap(find.text('알람 목록 보기'));
      expect(showList, 1);
    },
  );
}
