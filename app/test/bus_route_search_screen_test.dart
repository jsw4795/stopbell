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
        await tester.tap(find.byTooltip('검색'));
        await tester.pumpAndSettle();
        expect(find.text('간선'), findsOneWidget);
        expect(find.text('운행 구간 정보 없음'), findsOneWidget);
        expect(find.text('김포시'), findsNothing);
        expect(find.text('구래리차고지(미정차) ↔ 당산역광역환승센터(1번승강장)'), findsOneWidget);
        final fieldRect = tester.getRect(find.byType(TextField));
        final regionRect = tester.getRect(find.byType(DropdownButton<String>));
        final searchRect = tester.getRect(find.byTooltip('검색'));
        expect(regionRect.right, lessThan(fieldRect.left));
        expect(fieldRect.right, lessThan(searchRect.left));
        expect(regionRect.center.dy, closeTo(fieldRect.center.dy, 1));
        expect(searchRect.center.dy, closeTo(fieldRect.center.dy, 1));
        expect(searchRect.right, lessThanOrEqualTo(320));
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
      DropdownButton<String> regionFilter() =>
          tester.widget(find.byType(DropdownButton<String>));
      List<String?> regions() =>
          regionFilter().items!.map((item) => item.value).toList();
      Future<void> selectRegion(String label) async {
        await tester.tap(find.byType(DropdownButton<String>));
        await tester.pumpAndSettle();
        await tester.tap(find.text(label).last);
        await tester.pumpAndSettle();
      }

      expect(find.byType(DropdownButton<String>), findsNothing);
      await tester.enterText(find.byType(TextField), '165');
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await tester.pumpAndSettle();
      expect(regionFilter().value, isNull);
      expect(regions(), [null, '서울', '경기']);
      expect(find.text('남양주시'), findsOneWidget);
      expect(find.text('김포시'), findsOneWidget);

      await selectRegion('서울');
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey(1)), findsOneWidget);
      expect(find.byKey(const ValueKey(2)), findsNothing);
      await selectRegion('경기');
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey(1)), findsNothing);
      expect(find.byKey(const ValueKey(2)), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey(3)));
      expect(selected, 3);
      await selectRegion('전체');
      expect(regionFilter().value, isNull);
      expect(find.byKey(const ValueKey(1)), findsOneWidget);
      expect(find.byKey(const ValueKey(2)), findsOneWidget);
      expect(queries, ['165']);

      await tester.enterText(find.byType(TextField), '16');
      await tester.tap(find.byTooltip('검색'));
      await tester.pumpAndSettle();
      expect(regionFilter().value, isNull);
      expect(regions(), [null, '서울']);

      await tester.tap(find.byTooltip('검색어 지우기'));
      await tester.pumpAndSettle();
      expect(find.byTooltip('검색어 지우기'), findsNothing);
      expect(find.text('노선번호를 입력해 검색하세요.'), findsOneWidget);
      expect(find.byType(DropdownButton<String>), findsNothing);
      expect(queries, ['165', '16']);

      await tester.enterText(find.byType(TextField), '999');
      await tester.tap(find.byTooltip('검색'));
      await tester.pumpAndSettle();
      expect(regions(), [null, '인천']);
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
      await tester.tap(find.byTooltip('검색'));
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
    expect(
      tester.getTopLeft(find.text('노선번호를 입력해 검색하세요.')).dy -
          tester.getBottomLeft(find.byTooltip('검색')).dy,
      greaterThan(50),
    );
    expect(
      tester.getCenter(find.text('노선번호를 입력해 검색하세요.')).dy,
      lessThan(tester.view.physicalSize.height / 2),
    );
    await tester.tap(find.byTooltip('검색'));
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
      await tester.tap(find.byTooltip('검색'));
      await tester.pump();
      expect(find.text('노선 검색 중...'), findsOneWidget);
      expect(find.byType(DropdownButton<String>), findsNothing);
      expect(
        tester
            .widget<IconButton>(
              find
                  .ancestor(
                    of: find.byTooltip('검색'),
                    matching: find.byType(IconButton),
                  )
                  .first,
            )
            .onPressed,
        isNull,
      );
      expect(tester.widget<TextField>(find.byType(TextField)).enabled, isFalse);
      await tester.tap(find.byTooltip('검색'));
      await tester.testTextInput.receiveAction(TextInputAction.search);
      await tester.pump();
      expect(queries, ['7000']);

      pending.complete(const [
        BusRoute(
          id: 754,
          routeNumber: '7000',
          regionName: '수원시',
          regionCategory: '경기',
        ),
        BusRoute(
          id: 1390,
          routeNumber: '7000',
          regionName: '김포시',
          regionCategory: '경기',
        ),
      ]);
      await tester.pumpAndSettle();
      final regionFilter = tester.widget<DropdownButton<String>>(
        find.byType(DropdownButton<String>),
      );
      expect(regionFilter.value, isNull);
      expect(regionFilter.items!.map((item) => item.value), [null, '경기']);
      expect(find.text('7000'), findsNWidgets(2));
      expect(find.text('수원시'), findsOneWidget);
      expect(find.text('김포시'), findsOneWidget);
      expect(find.byKey(const ValueKey(754)), findsOneWidget);
      expect(find.byKey(const ValueKey(1390)), findsOneWidget);
    },
  );

  testWidgets('large empty guidance remains scrollable on a short screen', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 240);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      MaterialApp(
        theme: StopBellTheme.light,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: const TextScaler.linear(3)),
          child: child!,
        ),
        home: Scaffold(
          body: BusRouteSearchScreen(search: (_) async => [], onSelect: (_) {}),
        ),
      ),
    );
    final guidanceScroll = find.byType(SingleChildScrollView);
    expect(find.text('노선번호를 입력해 검색하세요.'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.enterText(find.byType(TextField), '999');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pumpAndSettle();
    expect(find.text('검색 결과가 없어요.'), findsOneWidget);
    final scrollable = find.descendant(
      of: guidanceScroll,
      matching: find.byType(Scrollable),
    );
    expect(
      tester.state<ScrollableState>(scrollable).position.maxScrollExtent,
      greaterThan(0),
    );
    await tester.drag(guidanceScroll, const Offset(0, -100));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('empty response has a distinct empty state', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: BusRouteSearchScreen(search: (_) async => [], onSelect: (_) {}),
        ),
      ),
    );
    await tester.enterText(find.byType(TextField), 'ZZZ');
    await tester.tap(find.byTooltip('검색'));
    await tester.pumpAndSettle();
    expect(find.text('검색 결과가 없어요.'), findsOneWidget);
    expect(find.text('노선 검색에 실패했어요.'), findsNothing);
    expect(find.byType(DropdownButton<String>), findsNothing);
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
    await tester.tap(find.byTooltip('검색'));
    await tester.pumpAndSettle();
    expect(find.text('노선 검색에 실패했어요.'), findsOneWidget);
    expect(find.byType(DropdownButton<String>), findsNothing);
    expect(find.textContaining('internal detail'), findsNothing);

    await tester.enterText(find.byType(TextField), 'N26');
    fail = false;
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(queries, ['7000', '7000']);
    expect(find.text('노선 검색에 실패했어요.'), findsNothing);
    expect(find.text('다시 시도'), findsNothing);
    final routeCard = find.byKey(const ValueKey(1));
    expect(routeCard, findsOneWidget);
    expect(
      find.descendant(of: routeCard, matching: find.text('7000')),
      findsOneWidget,
    );
    final regionFilter = tester.widget<DropdownButton<String>>(
      find.byType(DropdownButton<String>),
    );
    expect(regionFilter.value, isNull);
    expect(regionFilter.items!.map((item) => item.value), [null, '서울']);
    await tester.tap(find.byType(DropdownButton<String>));
    await tester.pumpAndSettle();
    expect(find.text('서울'), findsOneWidget);
    await tester.tap(find.text('서울'));
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<DropdownButton<String>>(find.byType(DropdownButton<String>))
          .value,
      '서울',
    );
    expect(routeCard, findsOneWidget);
    expect(queries, ['7000', '7000']);
  });
}
