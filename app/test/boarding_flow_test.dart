import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/boarding/boarding_candidate_client.dart';
import 'package:stopbell/features/notification/notification_lifecycle.dart';
import 'package:stopbell/features/transit/bus_route_search_client.dart';
import 'package:stopbell/features/transit/bus_route_stop_client.dart';
import 'package:stopbell/main.dart';

import 'boarding_test_fakes.dart';
import 'boarding_session_test_fakes.dart' show sessionAuth, sessionJwt;

void main() {
  late List<http.Request> requests;
  late AuthSession auth;
  late NotificationLifecycle notifications;
  late Future<http.Response> Function(http.Request) candidateResponse;

  Future<void> pump(WidgetTester tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    tester.view.physicalSize = const Size(1000, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    requests = [];
    candidateResponse = (_) async => boardingJson(candidatesJson());
    final transport = MockClient((request) async {
      requests.add(request);
      switch (request.url.path) {
        case '/auth/logout':
          return http.Response('', 204);
        case '/auth/refresh':
          return boardingJson({
            'accessToken': sessionJwt('1', 'refreshed'),
            'refreshToken': 'rotated-refresh',
          });
        case '/api/v1/alarms':
          return boardingJson([]);
        case '/api/v1/alarms/15':
          return boardingJson({
            'id': 15,
            'transitType': 'BUS',
            'status': 'ACTIVE',
            'routeNumber': '7000',
            'stopName': '도착 알림 목적지',
            'notifyOneStopBefore': false,
            'notifyOneStopAfter': false,
          });
        case '/api/v1/bus-routes':
          return boardingJson([
            {'id': 754, 'routeNumber': '7000', 'regionName': '서울'},
            {'id': 1390, 'routeNumber': '7000', 'regionName': '다른 지역'},
          ]);
        case '/api/v1/bus-routes/754/stops':
        case '/api/v1/bus-routes/1390/stops':
          return boardingJson([
            for (final stop in boardingStops)
              {
                'id': stop.id,
                'name': stop.name,
                'order': stop.order,
                'destinationName': stop.destinationName,
                'previousStopName': stop.previousStopName,
                'nextStopName': stop.nextStopName,
                'canNotifyOneStopBefore': true,
                'canNotifyOneStopAfter': true,
              },
          ]);
        case '/api/v1/bus-routes/754/boarding-candidates':
        case '/api/v1/bus-routes/1390/boarding-candidates':
          return candidateResponse(request);
        default:
          fail('Unexpected request ${request.method} ${request.url.path}');
      }
    });
    addTearDown(transport.close);
    auth = await sessionAuth(transport);
    notifications = NotificationLifecycle(auth);
    final api = AuthenticatedApiClient(
      apiBaseUrl: boardingBaseUrl,
      authSession: auth,
      client: transport,
    );
    await tester.pumpWidget(
      StopBellApplication(
        authSession: auth,
        notifications: notifications,
        searchRoutes: BusRouteSearchClient(api).search,
        findStops: BusRouteStopClient(api).findStops,
        alarmClient: AlarmApiClient(api),
        findBoardingCandidates: BoardingCandidateClient(api).find,
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> search(WidgetTester tester, int id) async {
    await tester.enterText(find.byType(TextField), '7000');
    await tester.tap(find.text('검색'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(ValueKey(id)));
    await tester.pumpAndSettle();
  }

  Future<void> start(WidgetTester tester) async {
    await tester.tap(find.text('하차 알림 · 차량 후보 찾기'));
    await tester.pumpAndSettle();
    await search(tester, 1390);
    await tester.tap(find.byKey(const ValueKey('occurrence-22')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('find-candidates')));
    await tester.pump();
  }

  testWidgets(
    'production entry reuses route search and stops, exact IDs, JWT refresh and candidate selection only',
    (tester) async {
      await pump(tester);
      var calls = 0;
      candidateResponse = (request) async {
        expect(request.url.path, '/api/v1/bus-routes/1390/boarding-candidates');
        expect(request.url.queryParameters, {'targetStopOccurrenceId': '22'});
        expect(
          request.headers['authorization'],
          calls == 0
              ? 'Bearer ${sessionJwt('1')}'
              : 'Bearer ${sessionJwt('1', 'refreshed')}',
        );
        return calls++ == 0
            ? boardingJson({}, 401)
            : boardingJson(candidatesJson());
      };
      await start(tester);
      await tester.pumpAndSettle();
      expect(find.text('서울70사1234'), findsOneWidget);
      expect(find.textContaining('목적지 2정거장 전(기본)'), findsOneWidget);
      expect(find.textContaining('1정거장 전(선택)'), findsOneWidget);
      expect(find.textContaining('하차 준비 알림을 받을 수 있어요'), findsOneWidget);
      expect(find.textContaining('목록에서 차량을 직접 선택해 등록하세요'), findsOneWidget);
      expect(find.textContaining('등록 후 바로 추적을 시작'), findsOneWidget);
      expect(find.textContaining('차량 위치 확인 중'), findsOneWidget);
      expect(find.textContaining('도달하거나 통과하면 알리는 기능'), findsNothing);
      expect(find.textContaining('진행 검증 전에는 대기로 표시'), findsNothing);
      expect(
        requests.where((r) => r.url.path == '/auth/refresh'),
        hasLength(1),
      );
      await tester.tap(find.text('서울70사1234'));
      await tester.pumpAndSettle();
      expect(
        requests.where(
          (r) => r.method == 'POST' && r.url.path.startsWith('/api/'),
        ),
        isEmpty,
      );
      await tester.ensureVisible(find.text('노선 다시 선택'));
      await tester.tap(find.text('노선 다시 선택'));
      await tester.pumpAndSettle();
      await search(tester, 754);
      expect(find.text('서울70사1234'), findsNothing);
      expect(
        tester
            .widget<FilledButton>(find.byKey(const ValueKey('find-candidates')))
            .onPressed,
        isNull,
      );
    },
  );

  testWidgets(
    'existing alarm notification tap leaves candidate screen and opens authenticated alarm detail',
    (tester) async {
      await pump(tester);
      await start(tester);
      await tester.pumpAndSettle();
      notifications.receive({
        'type': 'ALARM_EVENT',
        'alarmId': '15',
        'eventType': 'ARRIVED',
        'notificationEventId': '10',
      });
      await tester.pumpAndSettle();
      expect(find.text('알람 상세'), findsOneWidget);
      expect(find.textContaining('도착 알림 목적지'), findsOneWidget);
      expect(find.text('서울70사1234'), findsNothing);
      expect(requests.last.url.path, '/api/v1/alarms/15');
    },
  );

  testWidgets(
    'return to existing arrival alarm route/stop flow keeps creation options',
    (tester) async {
      await pump(tester);
      await tester.tap(find.text('하차 알림 · 차량 후보 찾기'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('버스 도착 알림으로'));
      await tester.pumpAndSettle();
      expect(find.text('아직 만든 알람이 없습니다.'), findsOneWidget);
      await tester.tap(find.text('새 알림 만들기'));
      await tester.pumpAndSettle();
      await search(tester, 754);
      await tester.tap(find.byKey(const ValueKey(22)));
      await tester.pump();
      expect(find.text('알람 시작'), findsOneWidget);
      expect(find.byType(SwitchListTile), findsNWidgets(2));
    },
  );

  testWidgets(
    'logout and new login while candidate response is pending never restore old candidates',
    (tester) async {
      await pump(tester);
      final pending = Completer<http.Response>();
      candidateResponse = (_) => pending.future;
      await start(tester);
      expect(find.text('차량 후보를 조회하고 있어요.'), findsOneWidget);
      await tester.tap(find.text('로그아웃'));
      await tester.pumpAndSettle();
      expect(find.text('Google로 계속하기'), findsOneWidget);
      await tester.tap(find.text('Google로 계속하기'));
      await tester.pumpAndSettle();
      expect(auth.accessToken, 'access-b');
      pending.complete(boardingJson(candidatesJson()));
      await tester.pumpAndSettle();
      expect(find.text('서울70사1234'), findsNothing);
      expect(find.text('하차 알림 · 차량 후보 찾기'), findsOneWidget);
      expect(find.text('아직 만든 알람이 없습니다.'), findsOneWidget);
    },
  );
}
