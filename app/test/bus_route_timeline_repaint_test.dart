import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/core/theme/stopbell_theme.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';
import 'package:stopbell/features/transit/bus_route_timeline.dart';
import 'package:stopbell/features/transit/bus_route_vehicle.dart';

void main() {
  testWidgets('selection and vehicle changes repaint without text assertion', (tester) async {
    final stops = [
      for (var i = 1; i <= 12; i++)
        BusRouteStopOccurrence(
          id: i, name: '아주 긴 정류장 이름이 두 줄 이상으로 넘어가는 경우 $i',
          order: i, canNotifyOneStopBefore: true, canNotifyOneStopAfter: true),
    ];
    BusRouteVehicle v(int occ, int seat) => BusRouteVehicle(
        vehicleNumber: '경기70아5678', occurrenceId: occ,
        position: BusVehiclePosition.atStop,
        seatStatus: BusVehicleSeatStatus.available,
        seatRemaining: seat, congestion: 2);
    Widget app(int? target, int? boarding, List<BusRouteVehicle> vs) =>
        MaterialApp(
          theme: StopBellTheme.light,
          home: Scaffold(
            body: BusRouteTimeline(
              stops: stops, targetId: target, boardingId: boarding,
              vehicles: vs, onSelect: (_) {}),
          ),
        );
    await tester.pumpWidget(app(null, null, [v(3, 5)]));
    await tester.pumpWidget(app(3, 2, [v(3, 5)]));
    await tester.pumpWidget(app(3, 2, [v(3, 6)]));
    await tester.pumpWidget(app(4, 2, [v(4, 6)]));
    await tester.pumpWidget(app(null, null, const []));
    await tester.pumpWidget(app(5, 3, [v(5, 6)]));
    expect(tester.takeException(), isNull);
  });
}
