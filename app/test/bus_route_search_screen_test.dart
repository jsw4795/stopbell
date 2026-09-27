import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_search_screen.dart';

void main() {
  testWidgets('idle and blank input do not send a request', (tester) async {
    var calls = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: BusRouteSearchScreen(
            onSelect: (_) {},
            search: (_) async {
              calls++;
              return [];
            },
          ),
        ),
      ),
    );
    expect(find.text('노선번호를 입력해 검색하세요.'), findsOneWidget);
    await tester.tap(find.text('검색'));
    await tester.enterText(find.byType(TextField), '   ');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pump();
    expect(calls, 0);
    expect(find.text('노선번호를 입력해 검색하세요.'), findsOneWidget);
  });

  testWidgets(
    'loading blocks duplicate submission, then displays every candidate',
    (tester) async {
      final pending = Completer<List<BusRoute>>();
      final queries = <String>[];
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: BusRouteSearchScreen(
              onSelect: (_) {},
              search: (query) {
                queries.add(query);
                return pending.future;
              },
            ),
          ),
        ),
      );
      await tester.enterText(find.byType(TextField), ' 7000 ');
      await tester.tap(find.text('검색'));
      await tester.pump();
      expect(find.text('노선 검색 중...'), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull,
      );
      expect(tester.widget<TextField>(find.byType(TextField)).enabled, isFalse);
      expect(queries, ['7000']);

      pending.complete(const [
        BusRoute(id: 754, routeNumber: '7000', regionName: '수원시'),
        BusRoute(id: 1390, routeNumber: '7000', regionName: '김포시'),
      ]);
      await tester.pumpAndSettle();
      expect(find.text('7000'), findsNWidgets(2));
      expect(find.text('수원시'), findsOneWidget);
      expect(find.text('김포시'), findsOneWidget);
      expect(find.byKey(const ValueKey(754)), findsOneWidget);
      expect(find.byKey(const ValueKey(1390)), findsOneWidget);
    },
  );

  testWidgets('empty response has a distinct empty state', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: BusRouteSearchScreen(search: (_) async => [], onSelect: (_) {}),
        ),
      ),
    );
    await tester.enterText(find.byType(TextField), 'ZZZ');
    await tester.tap(find.text('검색'));
    await tester.pumpAndSettle();
    expect(find.text('검색 결과가 없습니다.'), findsOneWidget);
    expect(find.text('노선 검색에 실패했습니다.'), findsNothing);
  });

  testWidgets('network failure shows retry with the last normalized query', (
    tester,
  ) async {
    final queries = <String>[];
    var fail = true;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: BusRouteSearchScreen(
            onSelect: (_) {},
            search: (query) async {
              queries.add(query);
              if (fail) throw Exception('internal detail');
              return [
                const BusRoute(id: 1, routeNumber: '7000', regionName: '서울'),
              ];
            },
          ),
        ),
      ),
    );
    await tester.enterText(find.byType(TextField), ' 7000 ');
    await tester.tap(find.text('검색'));
    await tester.pumpAndSettle();
    expect(find.text('노선 검색에 실패했습니다.'), findsOneWidget);
    expect(find.textContaining('internal detail'), findsNothing);

    await tester.enterText(find.byType(TextField), 'N26');
    fail = false;
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(queries, ['7000', '7000']);
    expect(find.text('서울'), findsOneWidget);
  });
}
