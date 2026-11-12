import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_spacing.dart';
import 'bus_route_vehicle.dart';
import 'bus_route_vehicle_client.dart';
import 'bus_route_vehicle_controller.dart';

/// 앱 루트에서 차량 조회 함수를 주입한다. 없으면 차량 표시를 끈다.
class BusRouteVehiclesScope extends InheritedWidget {
  const BusRouteVehiclesScope({
    super.key,
    required this.find,
    required super.child,
  });

  final FindBusRouteVehicles? find;

  static FindBusRouteVehicles? maybeFind(BuildContext context) =>
      context.getInheritedWidgetOfExactType<BusRouteVehiclesScope>()?.find;

  @override
  bool updateShouldNotify(BusRouteVehiclesScope oldWidget) =>
      find != oldWidget.find;
}

/// 노선도 위 "HH:mm 기준 위치" · 새로고침 · 상태 안내.
class BusRouteVehicleBar extends StatefulWidget {
  const BusRouteVehicleBar({
    super.key,
    required this.controller,
    this.now,
    this.onRefresh,
    this.refreshing = false,
  });

  final BusRouteVehicleController controller;
  final DateTime Function()? now;

  /// 지정하면 새로고침·다시 시도가 controller.refresh 대신 이 콜백을 호출한다.
  final VoidCallback? onRefresh;

  /// 차량 외 데이터(하차 후보 등)를 함께 새로고침하는 중이면 true.
  final bool refreshing;

  @override
  State<BusRouteVehicleBar> createState() => _BusRouteVehicleBarState();
}

class _BusRouteVehicleBarState extends State<BusRouteVehicleBar> {
  Timer? _timer;
  bool _outdated = false;

  DateTime _now() => (widget.now ?? DateTime.now)();

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_changed);
    _syncTimer();
  }

  @override
  void didUpdateWidget(covariant BusRouteVehicleBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_changed);
      widget.controller.addListener(_changed);
      _changed();
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    widget.controller.removeListener(_changed);
    super.dispose();
  }

  // 서버 조회가 아니라 안내 문구 전환용 타이머다.
  void _changed() {
    _syncTimer();
    if (mounted) setState(() {});
  }

  void _syncTimer() {
    _timer?.cancel();
    final fetched = widget.controller.fetchedAt;
    _outdated = widget.controller.isOutdated(_now());
    if (fetched != null && !_outdated) {
      final left =
          BusRouteVehicleController.staleAfter - _now().difference(fetched);
      _timer = Timer(left, () {
        if (mounted) setState(() => _outdated = true);
      });
    }
  }

  static String _time(DateTime value) {
    final local = value.toLocal();
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(local.hour)}:${two(local.minute)}';
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final data = controller.data;
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final unplaced =
        data?.vehicles.where((v) => v.occurrenceId == null).length ?? 0;
    final title = controller.loading && data == null
        ? '차량 위치 조회 중...'
        : data == null
        ? '차량 위치'
        : '${_time(data.observedAt)} 기준 위치';
    final notes = <String>[
      if (controller.failed) '차량 정보를 불러오지 못했어요',
      if (!controller.failed && _outdated) '위치가 오래됐어요. 새로고침해 주세요',
      if (data != null && data.status == BusRouteVehiclesStatus.stale)
        '최신 위치를 받지 못해 이전 위치를 보여 드려요',
      if (data != null && data.status == BusRouteVehiclesStatus.partial)
        '일부 차량의 위치·좌석을 확인하지 못했어요',
      if (data != null &&
          data.status == BusRouteVehiclesStatus.empty &&
          data.vehicles.isEmpty)
        '지금 위치를 확인할 수 있는 차량이 없어요',
      if (unplaced > 0) '위치 확인 불가 $unplaced대',
    ];
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        StopBellSpacing.screenPadding,
        StopBellSpacing.xs,
        StopBellSpacing.xs,
        StopBellSpacing.xs,
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: theme.textTheme.labelLarge),
                for (final note in notes)
                  Text(
                    note,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: note.startsWith('차량 정보')
                          ? colors.error
                          : colors.onSurfaceVariant,
                    ),
                  ),
              ],
            ),
          ),
          if (controller.loading || widget.refreshing)
            const SizedBox(
              width: StopBellSpacing.minimumTouchSize,
              height: StopBellSpacing.minimumTouchSize,
              child: Center(
                child: SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ),
            )
          else if (controller.failed)
            TextButton(
              onPressed: widget.onRefresh ?? controller.refresh,
              child: const Text('다시 시도'),
            )
          else
            IconButton(
              tooltip: '차량 위치 새로고침',
              onPressed: widget.onRefresh ?? controller.refresh,
              icon: const Icon(Icons.refresh),
            ),
        ],
      ),
    );
  }
}
