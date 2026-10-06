import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/app_config.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';
import 'package:stopbell/features/transit/bus_route_search_client.dart';
import 'package:stopbell/features/transit/bus_route_stop_client.dart';
import 'package:stopbell/main.dart';

const storedPair = TokenPair(
  accessToken: 'stored-access',
  refreshToken: 'stored-refresh',
);
final baseUrl = Uri.parse('http://localhost:8080');

http.Response jsonResponse(Object body, [int status = 200]) => http.Response(
  jsonEncode(body),
  status,
  headers: const {'content-type': 'application/json; charset=utf-8'},
);

Map<String, Object> alarm(String status) => {
  'id': 15,
  'transitType': 'BUS',
  'status': status,
  'routeNumber': '7000',
  'stopName': '재방문 정류장',
  'notifyOneStopBefore': false,
  'notifyOneStopAfter': true,
};

final stops = [
  for (final (id, order, destination) in [(12345, 3, '사당역'), (54321, 7, '수원역')])
    {
      'id': id,
      'name': '재방문 정류장',
      'order': order,
      'destinationName': destination,
      'previousStopName': '이전 정류장',
      'nextStopName': '다음 정류장',
      'canNotifyOneStopBefore': true,
      'canNotifyOneStopAfter': true,
    },
];

Future<AuthSession> pumpApplication(
  WidgetTester tester,
  http.Client transport,
  SecureTokenPairStorage storage, {
  Future<String> Function()? googleIdTokenProvider,
}) async {
  final backend = BackendAuthClient(apiBaseUrl: baseUrl, client: transport);
  final session = AuthSession(
    authenticator: GoogleAuthService(
      config: const AppConfig(
        apiBaseUrl: 'http://localhost:8080',
        googleIosClientId: 'fixture-ios-client',
        googleServerClientId: 'fixture-server-client',
      ),
      backend: backend,
      googleIdTokenProvider:
          googleIdTokenProvider ?? () async => fail('Unexpected Google login'),
    ),
    backend: backend,
    tokenStorage: storage,
  );
  final apiClient = AuthenticatedApiClient(
    apiBaseUrl: baseUrl,
    authSession: session,
    client: transport,
  );
  await tester.pumpWidget(
    StopBellApplication(
      authSession: session,
      searchRoutes: BusRouteSearchClient(apiClient).search,
      findStops: BusRouteStopClient(apiClient).findStops,
      alarmClient: AlarmApiClient(apiClient),
    ),
  );
  await tester.pumpAndSettle();
  return session;
}

Future<void> startAlarm(WidgetTester tester) async {
  await tester.tap(find.text('새 알림 만들기'));
  await tester.pumpAndSettle();
  await tester.enterText(find.byType(TextField), '7000');
  await tester.tap(find.text('검색'));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey(1390)));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const ValueKey(54321)));
  await tester.pump();
  await tester.tap(find.text('한 정거장 후 알림'));
  await tester.pump();
  await tester.tap(find.text('알람 시작'));
  await tester.pump();
}

// Common pre-refresh HTTP boundary; navigation and API clients stay real.
http.Response? selectionResponse(http.Request request) {
  switch ('${request.method} ${request.url.path}') {
    case 'GET /api/v1/bus-routes':
      expect(request.url.queryParameters, {'query': '7000'});
      return jsonResponse([
        {'id': 754, 'routeNumber': '7000', 'regionName': '수원시'},
        {'id': 1390, 'routeNumber': '7000', 'regionName': '김포시'},
      ]);
    case 'GET /api/v1/bus-routes/1390/stops':
      return jsonResponse(stops);
    case 'POST /api/v1/alarms':
      expect(jsonDecode(request.body), {
        'targetStopOccurrenceId': 54321,
        'notifyOneStopBefore': false,
        'notifyOneStopAfter': true,
      });
      return jsonResponse(alarm('INACTIVE'), 201);
  }
  return null;
}

void main() {
  late SecureTokenPairStorage storage;
  setUp(() async {
    FlutterSecureStorage.setMockInitialValues({});
    storage = SecureTokenPairStorage();
    await storage.save(storedPair);
  });

  testWidgets(
    'create 뒤 activate refresh rotation은 선택과 생성 ID를 유지하고 ACTIVE 목록으로 복귀한다',
    (tester) async {
      final refresh = Completer<http.Response>();
      final activation = Completer<http.Response>();
      final requests = <String>[];
      var listCalls = 0;
      final transport = MockClient((request) async {
        final path = '${request.method} ${request.url.path}';
        requests.add(path);
        if (path == 'POST /auth/refresh') {
          expect(jsonDecode(request.body), {'refreshToken': 'stored-refresh'});
          return refresh.future;
        }
        final token = request.headers['authorization'];
        if (path == 'GET /api/v1/alarms') {
          listCalls++;
          expect(
            token,
            listCalls == 1 ? 'Bearer stored-access' : 'Bearer rotated-access',
          );
          return jsonResponse(listCalls == 1 ? [] : [alarm('ACTIVE')]);
        }
        if (path == 'POST /api/v1/alarms/15/activate') {
          if (token == 'Bearer stored-access') return jsonResponse({}, 401);
          expect(token, 'Bearer rotated-access');
          return activation.future;
        }
        expect(token, 'Bearer stored-access');
        return selectionResponse(request) ?? fail('Unexpected request: $path');
      });
      addTearDown(transport.close);
      final session = await pumpApplication(tester, transport, storage);
      final generation = session.generation;
      await startAlarm(tester);
      expect(requests.last, 'POST /auth/refresh');

      refresh.complete(
        jsonResponse({
          'accessToken': 'rotated-access',
          'refreshToken': 'rotated-refresh',
        }),
      );
      await tester.pump();
      expect(requests.last, 'POST /api/v1/alarms/15/activate');
      expect(session.generation, generation);
      expect(find.textContaining('선택한 정류장: 재방문 정류장\n수원역 방면'), findsOneWidget);
      expect(
        tester
            .widget<SwitchListTile>(
              find.widgetWithText(SwitchListTile, '한 정거장 후 알림'),
            )
            .value,
        isTrue,
      );
      expect(find.text('알람 상세'), findsNothing);

      activation.complete(jsonResponse(alarm('ACTIVE')));
      await tester.pumpAndSettle();
      expect(find.text('상태: ACTIVE · 감시 중'), findsOneWidget);
      expect(find.text('한 정거장 전 알림: 꺼짐'), findsOneWidget);
      expect(find.text('한 정거장 후 알림: 켜짐'), findsOneWidget);
      expect((await storage.read())?.refreshToken, 'rotated-refresh');
      await tester.tap(find.byTooltip('알람 목록으로'));
      await tester.pumpAndSettle();
      expect(find.text('ACTIVE · 감시 중'), findsOneWidget);
      expect(requests, [
        'GET /api/v1/alarms',
        'GET /api/v1/bus-routes',
        'GET /api/v1/bus-routes/1390/stops',
        'POST /api/v1/alarms',
        'POST /api/v1/alarms/15/activate',
        'POST /auth/refresh',
        'POST /api/v1/alarms/15/activate',
        'GET /api/v1/alarms',
      ]);
    },
  );

  testWidgets(
    'activate refresh 401은 INACTIVE 상세 복구보다 Login을 우선하고 새 login에서 생성된 알람을 조회한다',
    (tester) async {
      final refresh = Completer<http.Response>();
      final requests = <String>[];
      var googleCalls = 0;
      var listCalls = 0;
      final transport = MockClient((request) async {
        final path = '${request.method} ${request.url.path}';
        requests.add(path);
        if (path == 'POST /auth/refresh') {
          expect(jsonDecode(request.body), {'refreshToken': 'stored-refresh'});
          return refresh.future;
        }
        if (path == 'POST /auth/google') {
          expect(jsonDecode(request.body), {'idToken': 'fixture-google-id'});
          return jsonResponse({
            'accessToken': 'login-access',
            'refreshToken': 'login-refresh',
          });
        }
        final token = request.headers['authorization'];
        if (path == 'GET /api/v1/alarms') {
          listCalls++;
          expect(
            token,
            listCalls == 1 ? 'Bearer stored-access' : 'Bearer login-access',
          );
          return jsonResponse(listCalls == 1 ? [] : [alarm('INACTIVE')]);
        }
        if (path == 'GET /api/v1/alarms/15') {
          expect(token, 'Bearer login-access');
          return jsonResponse(alarm('INACTIVE'));
        }
        expect(token, 'Bearer stored-access');
        if (path == 'POST /api/v1/alarms/15/activate') {
          return jsonResponse({}, 401);
        }
        return selectionResponse(request) ?? fail('Unexpected request: $path');
      });
      addTearDown(transport.close);
      final session = await pumpApplication(
        tester,
        transport,
        storage,
        googleIdTokenProvider: () async {
          googleCalls++;
          return 'fixture-google-id';
        },
      );
      await startAlarm(tester);
      refresh.complete(jsonResponse({}, 401));
      await tester.pumpAndSettle();
      expect(session.state, AuthState.unauthenticated);
      expect(await storage.read(), isNull);
      expect(find.text('Google로 계속하기'), findsOneWidget);
      expect(find.text('알람 상세'), findsNothing);
      expect(googleCalls, 0);

      await tester.tap(find.text('Google로 계속하기'));
      await tester.pumpAndSettle();
      expect(googleCalls, 1);
      expect(find.text('INACTIVE · 비활성'), findsOneWidget);
      expect(find.text('알람 상세'), findsNothing);
      await tester.tap(find.byKey(const ValueKey(15)));
      await tester.pumpAndSettle();
      expect(find.text('상태: INACTIVE · 비활성'), findsOneWidget);
      expect(find.text('활성화'), findsOneWidget);
      expect(requests, [
        'GET /api/v1/alarms',
        'GET /api/v1/bus-routes',
        'GET /api/v1/bus-routes/1390/stops',
        'POST /api/v1/alarms',
        'POST /api/v1/alarms/15/activate',
        'POST /auth/refresh',
        'POST /auth/google',
        'GET /api/v1/alarms',
        'GET /api/v1/alarms/15',
      ]);
    },
  );
}
