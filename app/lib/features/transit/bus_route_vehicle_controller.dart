import 'dart:async';

import 'package:flutter/foundation.dart';

import 'bus_route_vehicle.dart';
import 'bus_route_vehicle_client.dart';

enum BusRouteVehicleLoad { idle, loading, loaded, error }

/// 화면 진입 시 1회와 수동 새로고침만 조회한다(자동 polling 없음).
/// 실패해도 마지막 성공 결과는 유지한다.
class BusRouteVehicleController extends ChangeNotifier {
  BusRouteVehicleController({
    required this.routeId,
    required this.find,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  static const staleAfter = Duration(minutes: 2);

  final int routeId;
  final FindBusRouteVehicles find;
  final DateTime Function() _now;

  BusRouteVehicleLoad _load = BusRouteVehicleLoad.idle;
  BusRouteVehicles? _data;
  DateTime? _fetchedAt;
  int _generation = 0;
  bool _disposed = false;

  BusRouteVehicleLoad get load => _load;
  bool get loading => _load == BusRouteVehicleLoad.loading;
  bool get failed => _load == BusRouteVehicleLoad.error;
  BusRouteVehicles? get data => _data;
  DateTime? get fetchedAt => _fetchedAt;

  bool isOutdated(DateTime now) =>
      _fetchedAt != null && now.difference(_fetchedAt!) >= staleAfter;

  Future<void> refresh() async {
    if (_disposed || loading) return;
    final generation = ++_generation;
    _load = BusRouteVehicleLoad.loading;
    notifyListeners();
    try {
      final result = await find(routeId);
      if (_disposed || generation != _generation) return;
      _data = result;
      _fetchedAt = _now();
      _load = BusRouteVehicleLoad.loaded;
    } catch (_) {
      if (_disposed || generation != _generation) return;
      _load = BusRouteVehicleLoad.error;
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
