import 'package:flutter/material.dart';

import '../transit/bus_route_stop_occurrence.dart';
import '../transit/bus_route_vehicle.dart';
import '../../core/theme/stopbell_spacing.dart';
import 'alarm.dart';

typedef FindAlarmApproach = Future<String> Function(Alarm alarm);

/// 화면 조회 동안 같은 노선의 동시 요청만 공유한다. 지속 캐시는 서버가 소유한다.
class AlarmApproachLoader {
  AlarmApproachLoader({required this.findStops, required this.findVehicles});

  final Future<List<BusRouteStopOccurrence>> Function(int) findStops;
  final Future<BusRouteVehicles> Function(int) findVehicles;
  final _inFlight =
      <int, Future<(List<BusRouteStopOccurrence>, BusRouteVehicles)>>{};

  Future<(List<BusRouteStopOccurrence>, BusRouteVehicles)> _route(int id) async {
    try {
      final results = await Future.wait<Object>([findStops(id), findVehicles(id)]);
      return (
        results[0] as List<BusRouteStopOccurrence>,
        results[1] as BusRouteVehicles,
      );
    } finally {
      _inFlight.remove(id);
    }
  }

  Future<String> find(Alarm alarm) async {
    final routeId = alarm.routeId;
    final targetId = alarm.targetStopOccurrenceId;
    if (routeId == null || targetId == null) return '버스 위치 확인 불가';
    try {
      final (stops, snapshot) = await _inFlight.putIfAbsent(
        routeId,
        () => _route(routeId),
      );
      if (snapshot.status == BusRouteVehiclesStatus.stale) {
        return '버스 위치 정보가 오래됐어요';
      }
      final target = stops.indexWhere((stop) => stop.id == targetId);
      if (target < 0) return '버스 위치 확인 불가';
      int? nearest;
      var partial = snapshot.status == BusRouteVehiclesStatus.partial;
      for (final vehicle in snapshot.vehicles) {
        final position = stops.indexWhere((stop) => stop.id == vehicle.occurrenceId);
        if (position < 0) {
          partial = true;
          continue;
        }
        // 목표에 도착했거나 통과한 차량과 다음 회차는 접근 차량으로 추측하지 않는다.
        final gap = target - position;
        if (gap > 0 && (nearest == null || gap < nearest)) nearest = gap;
      }
      if (nearest != null) {
        return partial
            ? '확인된 직전 버스 $nearest정류장 전 · 일부 위치 미확인'
            : '직전 버스 $nearest정류장 전';
      }
      return partial ? '다가오는 버스 확인 불가 · 일부 위치 미확인' : '다가오는 버스 없음';
    } catch (_) {
      return '버스 위치를 불러오지 못했어요';
    }
  }
}

/// 진입·명시적 새로고침에만 조회한다. 자동 polling은 없다.
class AlarmApproachText extends StatefulWidget {
  const AlarmApproachText({
    super.key,
    required this.alarm,
    required this.findApproach,
    this.refreshKey,
  });

  final Alarm alarm;
  final FindAlarmApproach? findApproach;
  final Object? refreshKey;

  @override
  State<AlarmApproachText> createState() => _AlarmApproachTextState();
}

class _AlarmApproachTextState extends State<AlarmApproachText> {
  String _label = '버스 위치 확인 중…';
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant AlarmApproachText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.refreshKey != oldWidget.refreshKey ||
        widget.alarm.id != oldWidget.alarm.id) {
      _load();
    } else if (widget.alarm.status != oldWidget.alarm.status ||
        widget.alarm.routeId != oldWidget.alarm.routeId ||
        widget.alarm.targetStopOccurrenceId !=
            oldWidget.alarm.targetStopOccurrenceId) {
      _generation++;
      _label = '새로고침해 버스 위치를 확인해 주세요';
    }
  }

  Future<void> _load() async {
    final generation = ++_generation;
    if (widget.alarm.status != AlarmStatus.active) return;
    _label = '버스 위치 확인 중…';
    final find = widget.findApproach;
    String label;
    try {
      label = find == null ? '버스 위치 확인 불가' : await find(widget.alarm);
    } catch (_) {
      label = '버스 위치를 불러오지 못했어요';
    }
    if (!mounted || generation != _generation) return;
    setState(() => _label = label);
  }

  @override
  Widget build(BuildContext context) {
    if (widget.alarm.status != AlarmStatus.active) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: StopBellSpacing.xs),
      child: Text(
        _label,
        style: Theme.of(context).textTheme.bodySmall,
      ),
    );
  }
}
