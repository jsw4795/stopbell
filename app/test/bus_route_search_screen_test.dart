import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_search_screen.dart';

void main() {
  for (final theme in [StopBellTheme.light, StopBellTheme.dark]) {
    testWidgets(
      'small screen wraps long endpoints and keeps official colors in ${theme.brightness}',
      (tester) async {
        tester.view.physicalSize = const Size(320, 640);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        BusRoute? selected;
        await tester.pumpWidget(
          MaterialApp(
            theme: theme,
            builder: (context, child) => MediaQuery(
              data: MediaQuery.of(context)
                  .copyWith(textScaler: const TextScaler.linear(2)),
              child: child!,
            ),
            home: Scaffold(
              body: BusRouteSearchScreen(
                search: (_) async => const [
                  BusRoute(
                    id: 1,
                    routeNumber: '7000',
                    regionName: '서울',
                    regionCategory: '서울',
                    provider: 'SEOUL_BUS',
                    routeType: 'TRUNK',
                  ),
                  BusRoute(
                    id: 2,
                    routeNumber: '7000',
                    regionName: '김포시',
                    regionCategory: '경기',
                    provider: 'TAGO',
                    routeType: 'VILLAGE',
                    startStopName: '구래리차고지(미정차)',
                    endStopName: '당산역광역환승센터(1번승강장)',
                  ),
                ],
                onSelect: (route) => selected = route,
              ),
            ),
          ),
        );
        await tester.enterText(find.byType(TextField), '7000');
        await tester.tap(find.text('검색'));
        await tester.pumpAndSettle();
        expect(find.text('간선'), findsOneWidget);
        expect(find.text('운행 구간 정보 없음'), findsOneWidget);
        expect(find.text('김포시'), findsNothing);
        expect(find.text('구래리차고지(미정차) ↔ 당산역광역환승센터(1번승강장)'), findsOneWidget);
        final villageCard = find.byKey(const ValueKey(2));
        final villageLabel = tester.widget<Text>(
          find.descendant(of: villageCard, matching: find.text('마을')),
        );
        final villageColor = theme.brightness == Brightness.dark
            ? const Color(0xffffd26f)
            : const Color(0xff876000);
        expect(villageLabel.style!.color, villageColor);
        final busIcon = tester.widget<Icon>(
          find.descendant(
            of: villageCard,
            matching: find.byIcon(Icons.directions_bus_outlined),
          ),
        );
        expect(busIcon.color, villageColor);
        await tester.tap(villageCard);
        expect(selected?.id, 2);
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets(
    'filters returned regions and resets on explicit search or clear',
    (tester) async {
      const seoul = BusRoute(
        id: 1,
        routeNumber: '165',
        regionName: '서울',
        regionCategory: '서울',
      );
      const namyangju = BusRoute(
        id: 2,
        routeNumber: '165',
        regionName: '남양주시',
        regionCategory: '경기',
      );
      const gimpo = BusRoute(
        id: 3,
        routeNumber: '165',
        regionName: '김포시',
        regionCategory: '경기',
      );
      const incheon = BusRoute(
        id: 4,
        routeNumber: '999',
        regionName: '인천',
        regionCategory: '인천',
      );
      final queries = <String>[];
      int? selected;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: BusRouteSearchScreen(
              onSelect: (route) => selected = route.id,
              search: (query) async {
                queries.add(query);
                return switch (query) {
                  '165' => [namyangju, seoul, gimpo],
                  '16' => [seoul],
                  _ => [incheon],
                };
              },
            ),
          ),
        ),
      );
      Finder tab(String label) => find.widgetWithText(ChoiceChip, label);
      expect(tester.widget<ChoiceChip>(tab('전체')).selected, isTrue);
      await tester.enterText(find.byType(TextField), '165');
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await tester.pumpAndSettle();
      expect(tester.widget<ChoiceChip>(tab('전체')).selected, isTrue);
      expect(tab('서울'), findsOneWidget);
      expect(tab('경기'), findsOneWidget);
      expect(tab('인천'), findsNothing);
      expect(tab('김포시'), findsNothing);
      expect(
        tester
            .widgetList<ChoiceChip>(find.byType(ChoiceChip))
            .map((chip) => (chip.label as Text).data),
        ['전체', '서울', '경기'],
      );
      expect(find.text('남양주시'), findsOneWidget);
      expect(find.text('김포시'), findsOneWidget);

      await tester.tap(tab('서울'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey(1)), findsOneWidget);
      expect(find.byKey(const ValueKey(2)), findsNothing);
      await tester.tap(tab('경기'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey(1)), findsNothing);
      expect(find.byKey(const ValueKey(2)), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey(3)));
      expect(selected, 3);
      expect(queries, ['165']);

      await tester.enterText(find.byType(TextField), '16');
      await tester.tap(find.text('검색'));
      await tester.pumpAndSettle();
      expect(tester.widget<ChoiceChip>(tab('전체')).selected, isTrue);
      expect(tab('서울'), findsOneWidget);
      expect(tab('경기'), findsNothing);

      await tester.tap(find.byTooltip('검색어 지우기'));
      await tester.pumpAndSettle();
      expect(find.byTooltip('검색어 지우기'), findsNothing);
      expect(find.text('노선번호를 입력해 검색하세요.'), findsOneWidget);
      expect(tab('서울'), findsNothing);
      expect(queries, ['165', '16']);

      await tester.enterText(find.byType(TextField), '999');
      await tester.tap(find.text('검색'));
      await tester.pumpAndSettle();
      expect(tab('인천'), findsOneWidget);
      expect(tab('서울'), findsNothing);
      expect(tab('경기'), findsNothing);
    },
  );

  testWidgets(
    'distinguishes identical route numbers and endpoints within one city',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: BusRouteSearchScreen(
              search: (_) async => [
                for (final id in [1, 2])
                  BusRoute(
                    id: id,
                    routeNumber: '300',
                    regionName: '수원시',
                    regionCategory: '경기',
                    startStopName: '기점',
                    endStopName: '종점',
                  ),
              ],
              onSelect: (_) {},
            ),
          ),
        ),
      );
      await tester.enterText(find.byType(TextField), '300');
      await tester.tap(find.text('검색'));
      await tester.pumpAndSettle();
      expect(find.text('기점 ↔ 종점'), findsNWidgets(2));
      expect(find.text('수원시 · 노선 ID 1'), findsOneWidget);
      expect(find.text('수원시 · 노선 ID 2'), findsOneWidget);
    },
  );

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
    expect(find.byType(ChoiceChip), findsOneWidget);
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
                const BusRoute(
                  id: 1,
                  routeNumber: '7000',
                  regionName: '서울',
                  regionCategory: '서울',
                ),
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
