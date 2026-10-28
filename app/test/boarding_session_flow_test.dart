import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/boarding/boarding_candidate_client.dart';
import 'package:stopbell/features/boarding/boarding_session_client.dart';
import 'package:stopbell/features/boarding/boarding_session_view.dart';
import 'package:stopbell/features/notification/notification_lifecycle.dart';
import 'package:stopbell/features/transit/bus_route_search_client.dart';
import 'package:stopbell/features/transit/bus_route_stop_client.dart';
import 'package:stopbell/main.dart';

import 'boarding_session_test_fakes.dart';
import 'boarding_test_fakes.dart';

void main() {
  late AuthSession auth;
  late NotificationLifecycle notifications;
  late MemoryBoardingIntents storage;
  late Future<http.Response> Function(http.Request) sessionResponse;
  final requests = <http.Request>[];
  Future<void> pump(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1000, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    requests.clear();
    storage = MemoryBoardingIntents();
    sessionResponse = (request) async => boardingJson(
      request.method == 'GET' ? [] : sessionJson(),
      request.method == 'GET' ? 200 : 201,
    );
    final transport = MockClient((request) async {
      requests.add(request);
      if (request.url.path.startsWith('/api/v1/boarding-sessions')) {
        return sessionResponse(request);
      }
      switch (request.url.path) {
        case '/auth/refresh':
          return boardingJson({
            'accessToken': sessionJwt('1', 'refreshed'),
            'refreshToken': 'rotated',
          });
        case '/auth/logout':
          return boardingJson({}, 204);
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
          ]);
        case '/api/v1/bus-routes/754/stops':
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
          return boardingJson({
            ...candidatesJson(),
            'observedAt': DateTime.now().toUtc().toIso8601String(),
            'candidates': [
              {
                ...candidateJson(),
                'observedAt': DateTime.now().toUtc().toIso8601String(),
              },
            ],
          });
        default:
          fail('Unexpected ${request.method} ${request.url.path}');
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
        boardingSessionClient: BoardingSessionApiClient(api),
        boardingIntentStorage: storage,
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> tap(WidgetTester tester, String text) async {
    await tester.ensureVisible(find.text(text));
    await tester.tap(find.text(text));
    await tester.pumpAndSettle();
  }

  Future<void> prepare(WidgetTester tester) async {
    await tap(tester, '하차 알림 · 차량 후보 찾기');
    await tester.enterText(find.byType(TextField), '7000');
    await tap(tester, '검색');
    await tester.tap(find.byKey(const ValueKey(754)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('occurrence-22')));
    await tester.pump();
    await tester.tap(find.byKey(const ValueKey('find-candidates')));
    await tester.pumpAndSettle();
    await tap(tester, '이 차량 선택');
  }

  testWidgets(
    'one-stop timing reaches API and durable session without destination notification',
    (tester) async {
      await pump(tester);
      await prepare(tester);
      await tester.ensureVisible(find.text('1정거장 전'));
      await tester.tap(find.text('1정거장 전'));
      await tester.pumpAndSettle();
      sessionResponse = (request) async {
        expect(jsonDecode(request.body)['notificationOffsetStops'], 1);
        expect(storage.records.values.single.notificationOffsetStops, 1);
        return boardingJson({
          ...sessionJson(),
          'notificationOffsetStops': 1,
        }, 201);
      };
      await tap(tester, '하차 알림 등록');
      await tap(tester, '알림 없이 계속');
      expect(find.text('알림 시점: 1정거장 전 · 세션당 한 번'), findsOneWidget);
    },
  );

  testWidgets(
    'registration immediately opens tracking while location is unavailable',
    (tester) async {
      await pump(tester);
      await prepare(tester);
      sessionResponse = (request) async => boardingJson({
        ...sessionJson(
          status: 'TRACKING',
          evaluation: 'VERIFICATION_PENDING',
          reason: 'BASELINE_UNVERIFIED',
          gap: null,
        ),
        'checkpointObservedAt': null,
      }, 201);
      await tap(tester, '하차 알림 등록');
      await tap(tester, '알림 없이 계속');
      expect(find.byType(BoardingSessionView), findsOneWidget);
      expect(find.textContaining('TRACKING · 선택한 차량 추적 중'), findsOneWidget);
      expect(find.textContaining('차량 위치 확인 중'), findsWidgets);
      expect(find.textContaining('VERIFYING'), findsNothing);
      expect(find.text('추적 취소'), findsOneWidget);
      expect(
        requests.where(
          (r) =>
              r.method == 'POST' && r.url.path == '/api/v1/boarding-sessions',
        ),
        hasLength(1),
      );
    },
  );

  testWidgets(
    'production wiring creates through JWT refresh then cancels with response revision',
    (tester) async {
      await pump(tester);
      await prepare(tester);
      var creates = 0;
      sessionResponse = (request) async {
        if (request.url.path.endsWith('/cancel')) {
          expect(jsonDecode(request.body), {'expectedRevision': 7});
          return boardingJson(
            sessionJson(
              status: 'CANCELLED',
              evaluation: 'ENDED',
              revision: 8,
              gap: null,
            ),
          );
        }
        expect(storage.records, hasLength(1));
        return creates++ == 0
            ? boardingJson({}, 401)
            : boardingJson(sessionJson(revision: 7), 201);
      };
      await tap(tester, '하차 알림 등록');
      await tap(tester, '알림 없이 계속');
      expect(find.byType(BoardingSessionView), findsOneWidget);
      final posts = requests
          .where(
            (r) =>
                r.method == 'POST' && r.url.path == '/api/v1/boarding-sessions',
          )
          .toList();
      expect(posts, hasLength(2));
      expect(posts[0].body, posts[1].body);
      expect(
        jsonDecode(
          posts
              .singleWhere(
                (r) =>
                    r.headers['authorization'] ==
                    'Bearer ${sessionJwt('1', 'refreshed')}',
              )
              .body,
        )['confirmedVehicleNumber'],
        isNull,
      );
      await tap(tester, '추적 취소');
      await tap(tester, '추적 취소 확인');
      expect(find.textContaining('CANCELLED ·'), findsOneWidget);
      notifications.receive({
        'type': 'ALARM_EVENT',
        'alarmId': '15',
        'eventType': 'ARRIVED',
        'notificationEventId': '10',
      });
      await tester.pumpAndSettle();
      expect(find.text('알람 상세'), findsOneWidget);
      expect(find.byType(BoardingSessionView), findsNothing);
    },
  );
  testWidgets(
    'lost POST response restores same terminal session by original UUID without new POST',
    (tester) async {
      await pump(tester);
      await prepare(tester);
      sessionResponse = (_) async =>
          throw http.ClientException('response lost');
      await tap(tester, '하차 알림 등록');
      await tap(tester, '알림 없이 계속');
      final original = storage.records.values.single;
      sessionResponse = (request) async {
        expect(request.method, 'GET');
        expect(request.url.queryParameters, {
          'clientRequestId': original.clientRequestId,
        });
        return boardingJson([
          sessionJson(status: 'EXPIRED', evaluation: 'ENDED', gap: null),
        ]);
      };
      await tap(tester, '버스 도착 알림으로');
      await tap(tester, '하차 알림 · 차량 후보 찾기');
      expect(find.textContaining('EXPIRED ·'), findsOneWidget);
      expect(
        requests.where(
          (r) =>
              r.method == 'POST' && r.url.path == '/api/v1/boarding-sessions',
        ),
        hasLength(1),
      );
    },
  );
  testWidgets(
    'logout during pending POST discards late success, preserves record and existing Alarm screen',
    (tester) async {
      await pump(tester);
      await prepare(tester);
      final result = Completer<http.Response>();
      sessionResponse = (_) => result.future;
      await tap(tester, '하차 알림 등록');
      await tap(tester, '알림 없이 계속');
      await tap(tester, '로그아웃');
      result.complete(boardingJson(sessionJson(), 201));
      await tester.pumpAndSettle();
      expect(find.text('Google로 계속하기'), findsOneWidget);
      expect(find.byType(BoardingSessionView), findsNothing);
      expect(storage.records, hasLength(1));
    },
  );
}
