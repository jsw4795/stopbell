import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_timeline.dart';

const outbound = '회차 환승센터';
const inbound = '차고지';

BusRouteStopOccurrence stop(
  int id,
  String name,
  String? destination, {
  int? order,
}) => BusRouteStopOccurrence(
  id: id,
  name: name,
  order: order ?? id * 2,
  destinationName: destination,
  canNotifyOneStopBefore: id != 1,
  canNotifyOneStopAfter: true,
);

final shortRoute = [
  stop(1, '공원', outbound),
  stop(2, outbound, outbound),
  stop(3, outbound, inbound),
  stop(4, '공원', inbound),
  stop(5, inbound, inbound),
];

final list = find.byType(ListView);
final going = find.text('$outbound 방면');
final returning = find.text('$inbound 방면');

Finder row(int id) => find.byKey(ValueKey(id));

bool highlighted(WidgetTester tester, Finder label) => tester
    .widgetList<Semantics>(
      find.ancestor(of: label, matching: find.byType(Semantics)),
    )
    .any((item) => item.properties.selected == true);

Widget timeline(List<BusRouteStopOccurrence> stops, {double scale = 1}) =>
    MaterialApp(
      theme: ThemeData.dark(),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(scale)),
        child: child!,
      ),
      home: Scaffold(
        body: BusRouteTimeline(stops: stops, onSelect: (_) {}),
      ),
    );

void main() {
  testWidgets(
    'single traversal preserves order and uses the first return occurrence even with a repeated turn name',
    (tester) async {
      await tester.pumpWidget(timeline(shortRoute));
      await tester.pumpAndSettle();
      expect(list, findsOneWidget);
      expect(highlighted(tester, going), isTrue);
      expect(highlighted(tester, returning), isFalse);
      final rows = shortRoute
          .map((stop) => tester.getRect(row(stop.id)))
          .toList();
      for (var i = 1; i < rows.length; i++) {
        expect(rows[i].top, rows[i - 1].bottom);
      }
      expect(
        find.descendant(of: row(2), matching: find.textContaining('회차지점')),
        findsNothing,
      );
      expect(
        find.descendant(of: row(3), matching: find.textContaining('회차지점')),
        findsOneWidget,
      );

      await tester.tap(returning);
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(row(3)).dy, tester.getTopLeft(list).dy);
      expect(highlighted(tester, returning), isTrue);

      await tester.tap(going);
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(row(1)).dy, tester.getTopLeft(list).dy);
      expect(highlighted(tester, going), isTrue);
    },
  );

  testWidgets(
    'scroll determines direction in both directions and holds through tiny boundary jitter',
    (tester) async {
      await tester.pumpWidget(timeline(shortRoute));
      await tester.pumpAndSettle();
      final boundary =
          tester.getTopLeft(row(3)).dy - tester.getTopLeft(list).dy;
      final position = tester
          .state<ScrollableState>(find.byType(Scrollable))
          .position;
      position.jumpTo(boundary - 10);
      await tester.pump();
      expect(highlighted(tester, going), isTrue);
      position.jumpTo(boundary);
      await tester.pump();
      expect(highlighted(tester, returning), isTrue);
      position.jumpTo(boundary - 1);
      await tester.pump();
      expect(highlighted(tester, returning), isTrue);
      await tester.drag(list, const Offset(0, 70));
      await tester.pumpAndSettle();
      expect(highlighted(tester, going), isTrue);
      await tester.drag(list, const Offset(0, -200));
      await tester.pumpAndSettle();
      expect(highlighted(tester, returning), isTrue);
    },
  );

  testWidgets(
    'long wrapped route jumps exactly, latest tap wins and a drag interrupts',
    (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final stops = [
        for (var id = 1; id < 450; id++)
          stop(
            id,
            id == 377
                ? outbound
                : id == 449
                ? inbound
                : (id.isEven ? '환승센터 방면 아주 긴 정류장 이름 · $id' : '정류장 $id'),
            id < 377 ? outbound : inbound,
          ),
      ];
      await tester.pumpWidget(timeline(stops, scale: 2));
      await tester.pumpAndSettle();
      expect(row(377), findsNothing);
      await tester.tap(returning);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      await tester.drag(list, const Offset(0, 80));
      await tester.pumpAndSettle();
      expect(highlighted(tester, going), isTrue);
      expect(row(377), findsNothing);

      await tester.tap(returning);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      await tester.tap(going);
      await tester.pump();
      await tester.tap(returning);
      await tester.pumpAndSettle();
      expect(
        tester.getTopLeft(row(377)).dy,
        closeTo(tester.getTopLeft(list).dy, 0.01),
      );
      expect(highlighted(tester, returning), isTrue);
      expect(tester.takeException(), isNull);
    },
  );

  for (final (label, stops) in [
    ('missing', [stop(1, '공원', null), ...shortRoute.skip(1)]),
    (
      'one direction',
      [for (final item in shortRoute) stop(item.id, item.name, inbound)],
    ),
    (
      'multiple transitions',
      [
        stop(1, '공원', outbound),
        stop(2, outbound, inbound),
        stop(3, '공원', outbound),
        stop(4, inbound, inbound),
      ],
    ),
    (
      'mismatched turn',
      [
        stop(1, '공원', outbound),
        stop(2, '다른 이름', inbound),
        stop(3, inbound, inbound),
      ],
    ),
    (
      'turn at terminal',
      [
        stop(1, '공원', outbound),
        stop(2, '경유지', outbound),
        stop(3, inbound, inbound),
      ],
    ),
    ('out-of-order', [shortRoute[1], shortRoute[0], ...shortRoute.skip(2)]),
  ]) {
    testWidgets(
      '$label metadata keeps all occurrences and hides unverified direction controls',
      (tester) async {
        await tester.pumpWidget(timeline(stops));
        await tester.pumpAndSettle();
        expect(going, findsNothing);
        expect(returning, findsNothing);
        expect(find.textContaining('회차지점'), findsNothing);
        for (final item in stops) {
          expect(row(item.id).hitTestable(), findsOneWidget);
        }
      },
    );
  }
}
