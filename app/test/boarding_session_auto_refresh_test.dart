import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/testing.dart';
import 'package:stopbell/features/boarding/boarding_session.dart';
import 'package:stopbell/features/boarding/boarding_session_auto_refresh.dart';
import 'package:stopbell/features/boarding/boarding_session_controller.dart';

import 'boarding_session_test_fakes.dart';
import 'boarding_test_fakes.dart';

void main() {
  testWidgets(
    'visible session polls every 20 seconds and pauses for lifecycle and disposal',
    (tester) async {
      final transport = MockClient((_) async => boardingJson({}, 204));
      final auth = await sessionAuth(transport);
      final api = FakeBoardingSessions()..currentSession = sessionFixture();
      final controller = BoardingSessionController(
        auth: auth,
        client: api,
        storage: MemoryBoardingIntents(),
      );
      controller.session = sessionFixture();
      await tester.pumpWidget(
        MaterialApp(
          home: BoardingSessionAutoRefresh(
            controller: controller,
            child: const Text('detail'),
          ),
        ),
      );
      await tester.pump();
      expect(api.detailCalls, 1);
      await tester.pump(const Duration(seconds: 19));
      expect(api.detailCalls, 1);
      api.currentSession = sessionFixture(revision: 6, gap: 2);
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(api.detailCalls, 2);
      expect(controller.session!.remainingOccurrenceGap, 2);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      await tester.pump(const Duration(seconds: 40));
      expect(api.detailCalls, 2);
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pump();
      expect(api.detailCalls, 3);
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 40));
      expect(api.detailCalls, 3);
      controller.dispose();
      auth.dispose();
      transport.close();
    },
  );
  testWidgets(
    'slow requests do not overlap and late hidden response is ignored',
    (tester) async {
      final transport = MockClient((_) async => boardingJson({}, 204));
      final auth = await sessionAuth(transport);
      final api = FakeBoardingSessions();
      final response = Completer<BoardingSession>();
      api.onFind = () => response.future;
      final controller = BoardingSessionController(
        auth: auth,
        client: api,
        storage: MemoryBoardingIntents(),
      );
      controller.session = sessionFixture();
      await tester.pumpWidget(
        MaterialApp(
          home: BoardingSessionAutoRefresh(
            controller: controller,
            child: const Text('detail'),
          ),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(seconds: 40));
      expect(api.detailCalls, 1);
      await tester.pumpWidget(const SizedBox());
      response.complete(sessionFixture(revision: 9));
      await tester.pump();
      expect(controller.session!.revision, 5);
      controller.dispose();
      auth.dispose();
      transport.close();
    },
  );
  testWidgets('manual read blocks periodic GET and resets the interval', (
    tester,
  ) async {
    final transport = MockClient((_) async => boardingJson({}, 204));
    final auth = await sessionAuth(transport);
    final api = FakeBoardingSessions()..currentSession = sessionFixture();
    final controller = BoardingSessionController(
      auth: auth,
      client: api,
      storage: MemoryBoardingIntents(),
    );
    controller.session = sessionFixture();
    await tester.pumpWidget(
      MaterialApp(
        home: BoardingSessionAutoRefresh(
          controller: controller,
          child: const Text('detail'),
        ),
      ),
    );
    await tester.pump();
    final response = Completer<BoardingSession>();
    api.onFind = () => response.future;
    final manual = controller.refreshManually();
    expect(api.detailCalls, 2);
    await tester.pump(const Duration(seconds: 20));
    expect(api.detailCalls, 2);
    response.complete(sessionFixture());
    await manual;
    api.onFind = null;
    await tester.pump(const Duration(seconds: 19));
    expect(api.detailCalls, 2);
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(api.detailCalls, 3);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
    auth.dispose();
    transport.close();
  });
  testWidgets(
    'failed auto read retries and terminal response stops the timer',
    (tester) async {
      final transport = MockClient((_) async => boardingJson({}, 204));
      final auth = await sessionAuth(transport);
      final api = FakeBoardingSessions();
      api.onFind = () async => throw StateError('offline');
      final controller = BoardingSessionController(
        auth: auth,
        client: api,
        storage: MemoryBoardingIntents(),
      );
      controller.session = sessionFixture();
      await tester.pumpWidget(
        MaterialApp(
          home: BoardingSessionAutoRefresh(
            controller: controller,
            child: const Text('detail'),
          ),
        ),
      );
      await tester.pump();
      expect(controller.autoRefreshFailed, isTrue);
      expect(controller.session!.revision, 5);
      api.onFind = () async =>
          sessionFixture(status: 'COMPLETED', evaluation: 'ENDED', revision: 6);
      await tester.pump(const Duration(seconds: 20));
      await tester.pump();
      expect(controller.session!.isTerminal, isTrue);
      expect(controller.autoRefreshFailed, isFalse);
      await tester.pump(const Duration(seconds: 40));
      expect(api.detailCalls, 2);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
      auth.dispose();
      transport.close();
    },
  );
  testWidgets('logout during an automatic read ignores its late response', (
    tester,
  ) async {
    final transport = MockClient((_) async => boardingJson({}, 204));
    final auth = await sessionAuth(transport);
    final api = FakeBoardingSessions();
    final response = Completer<BoardingSession>();
    api.onFind = () => response.future;
    final controller = BoardingSessionController(
      auth: auth,
      client: api,
      storage: MemoryBoardingIntents(),
    );
    controller.session = sessionFixture();
    await tester.pumpWidget(
      MaterialApp(
        home: BoardingSessionAutoRefresh(
          controller: controller,
          child: const Text('detail'),
        ),
      ),
    );
    await tester.pump();
    await auth.logout();
    response.complete(sessionFixture(revision: 9));
    await tester.pump();
    expect(controller.session, isNull);
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
    auth.dispose();
    transport.close();
  });
}
