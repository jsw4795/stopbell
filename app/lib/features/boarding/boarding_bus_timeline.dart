import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import '../transit/bus_route_stop_occurrence.dart';
import '../transit/bus_route_timeline.dart';
import '../transit/bus_route_vehicle.dart';
import '../transit/stop_display_name.dart';
import 'boarding_candidates.dart';

/// 후보(등록용)와 차량 위치(노선도용)를 정규화 차량번호로 연결한 버스.
class BoardingBusEntry {
  const BoardingBusEntry(this.candidate, this.vehicle);
  final BoardingCandidate candidate;
  final BusRouteVehicle vehicle;
}

/// 첫 정류장~목표 정류장 범위에 그릴 수 있는 버스([drawn])와 위치를 못 그린
/// 후보([others])로 나눈다. 목표 정류장을 지난 버스는 어느 쪽에도 넣지 않는다
/// (차량 위치가 있으면 그 occurrence, 없으면 후보의 `progress` 순번 기준).
({List<BoardingBusEntry> drawn, List<BoardingCandidate> others})
splitBoardingBuses({
  required List<BoardingCandidate> candidates,
  required List<BusRouteVehicle> vehicles,
  required List<BusRouteStopOccurrence> stops,
  required int targetId,
}) {
  final byNumber = <String, BusRouteVehicle>{};
  for (final vehicle in vehicles) {
    if (vehicle.occurrenceId != null) {
      byNumber.putIfAbsent(vehicle.normalizedNumber, () => vehicle);
    }
  }
  final indexOf = {for (final (i, s) in stops.indexed) s.id: i};
  final targetIndex = indexOf[targetId] ?? -1;
  final drawn = <BoardingBusEntry>[];
  final others = <BoardingCandidate>[];
  for (final candidate in candidates) {
    final number = candidate.vehicleNumber;
    final vehicle = number == null
        ? null
        : byNumber[normalizeVehicleNumber(number)];
    final index = vehicle == null ? null : indexOf[vehicle.occurrenceId];
    if (vehicle == null || index == null) {
      final observed = indexOf[candidate.progress?.occurrenceId];
      if (observed == null || observed <= targetIndex) others.add(candidate);
    } else if (index < targetIndex ||
        (index == targetIndex &&
            vehicle.position == BusVehiclePosition.atStop)) {
      drawn.add(BoardingBusEntry(candidate, vehicle));
    }
  }
  return (drawn: drawn, others: others);
}

/// 하차 알람 탑승 버스 선택용 세로 노선도. 첫 정류장부터 목표 정류장까지 그리고
/// 버스가 없는 긴 구간은 접는다. 정류장이 아니라 버스를 선택한다.
/// 스크롤은 바깥 스크롤 뷰에 맡기고 진입 시 승차 정류장 부근으로 맞춘다.
class BoardingBusTimeline extends StatefulWidget {
  const BoardingBusTimeline({
    super.key,
    required this.stops,
    required this.boardingId,
    required this.targetId,
    required this.buses,
    required this.selected,
    required this.canSelect,
    required this.onSelect,
    required this.vehicleColor,
  });

  final List<BusRouteStopOccurrence> stops;
  final int boardingId, targetId;
  final List<BoardingBusEntry> buses;
  final BoardingCandidate? selected;
  final bool Function(BoardingCandidate) canSelect;
  final ValueChanged<BoardingCandidate> onSelect;
  final Color vehicleColor;

  @override
  State<BoardingBusTimeline> createState() => _BoardingBusTimelineState();
}

class _BoardingBusTimelineState extends State<BoardingBusTimeline> {
  static const _railWidth = 56.0;
  static const _centerY = 22.0;
  final _boardingKey = GlobalKey();
  final _expanded = <int>{};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final row = _boardingKey.currentContext;
      if (mounted && row != null) Scrollable.ensureVisible(row, alignment: 0.3);
    });
  }

  @override
  Widget build(BuildContext context) {
    final stops = widget.stops;
    final index = {for (final (i, s) in stops.indexed) s.id: i};
    final boarding = index[widget.boardingId] ?? -1;
    final target = index[widget.targetId] ?? -1;
    if (boarding < 0 || target <= boarding) return const SizedBox.shrink();

    final atStop = <int, List<BoardingBusEntry>>{};
    final between = <int, List<BoardingBusEntry>>{};
    for (final bus in widget.buses) {
      (bus.vehicle.position == BusVehiclePosition.atStop ? atStop : between)
          .putIfAbsent(index[bus.vehicle.occurrenceId]!, () => [])
          .add(bus);
    }
    // 기준점(승차·목표)과 버스 주변만 보인다: 도착 버스는 앞·뒤 1개, 출발 버스는
    // 출발한 정류장과 다음 정류장. 나머지는 개수와 무관하게 접는다.
    final big = <int>{
      boarding,
      target,
      for (final i in atStop.keys) ...[i - 1, i, i + 1],
      for (final i in between.keys) ...[i, i + 1],
    }..removeWhere((i) => i < 0 || i > target);

    final rows = <Widget>[];
    var i = 0;
    while (i <= target) {
      if (big.contains(i)) {
        rows.add(_stopRow(i, boarding, target, true, atStop[i]));
        for (final bus in between[i] ?? const <BoardingBusEntry>[]) {
          rows.add(_betweenRow(i, bus, boarding, target));
        }
        i++;
        continue;
      }
      var end = i;
      while (end + 1 <= target && !big.contains(end + 1)) {
        end++;
      }
      final count = end - i + 1;
      if (!_expanded.contains(i)) {
        rows.add(_collapsedRow(i, count, boarding, target));
      } else {
        rows.add(_foldRow(i, count, boarding, target));
        for (var j = i; j <= end; j++) {
          rows.add(_stopRow(j, boarding, target, false, null));
        }
      }
      i = end + 1;
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: rows,
    );
  }

  /// leg(j): j번째와 j+1번째 정류장 사이 구간 색(승차~목표만 강조).
  Color? _leg(int j, int boarding, int target) {
    if (j < 0 || j >= target) return null;
    return j >= boarding
        ? StopBellColors.of(context).targetStop
        : Theme.of(context).colorScheme.outline;
  }

  /// [centered]면 마커를 행 세로 중앙에 둔다(카드 중앙을 가리키는 출발 버스용).
  Widget _rail(
    Color? top,
    Color? bottom,
    Widget? marker, {
    double markerSize = 0,
    bool centered = false,
  }) => SizedBox(
    width: _railWidth,
    child: centered
        ? Stack(
            fit: StackFit.expand,
            clipBehavior: Clip.none,
            children: [
              Column(
                children: [
                  for (final color in [top, bottom])
                    Expanded(
                      child: Align(
                        child: SizedBox(
                          width: 2,
                          height: double.infinity,
                          child: ColoredBox(color: color ?? Colors.transparent),
                        ),
                      ),
                    ),
                ],
              ),
              if (marker != null) Center(child: marker),
            ],
          )
        : Stack(
            fit: StackFit.expand,
            clipBehavior: Clip.none,
            children: [
              if (top != null)
                Positioned(
                  left: _railWidth / 2 - 1,
                  width: 2,
                  top: 0,
                  height: _centerY,
                  child: ColoredBox(color: top),
                ),
              if (bottom != null)
                Positioned(
                  left: _railWidth / 2 - 1,
                  width: 2,
                  top: _centerY,
                  bottom: 0,
                  child: ColoredBox(color: bottom),
                ),
              if (marker != null)
                Positioned(
                  top: _centerY - markerSize / 2,
                  left: 0,
                  right: 0,
                  child: Center(child: marker),
                ),
            ],
          ),
  );

  Widget _row(Widget rail, Widget content, {Color? color, Key? key}) =>
      Container(
        key: key,
        color: color,
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              rail,
              Expanded(child: content),
              const SizedBox(width: StopBellSpacing.screenPadding),
            ],
          ),
        ),
      );

  double get _cardHeight =>
      _minCardHeight * math.max(1.0, MediaQuery.textScalerOf(context).scale(1));

  /// 카드 위치: 레일에서 떨어진 왼쪽 시작선. 마커와 카드는 연결선으로 이어진다.
  static const _cardLeft = 68.0;

  /// halo(지름 40) 바깥 반경. 선택 시 확대된 마커(약 16)보다 크다.
  static const _markerReach = 20.0;
  static const _minCardHeight = 64.0;
  static const _cardGap = StopBellSpacing.sm;

  Widget _stopRow(
    int i,
    int boarding,
    int target,
    bool emphasized,
    List<BoardingBusEntry>? buses,
  ) {
    final theme = Theme.of(context);
    final stopColors = StopBellColors.of(context);
    final stop = widget.stops[i];
    final isBoarding = i == boarding, isTarget = i == target;
    final accent = isTarget
        ? stopColors.targetStop
        : isBoarding
        ? stopColors.boardingStop
        : theme.colorScheme.outline;
    final display = stopDisplayName(stop.name);
    final special = isBoarding || isTarget;
    final arrived = buses != null && buses.isNotEmpty;
    final tint = special
        ? Color.alphaBlend(
            accent.withValues(alpha: 0.10),
            theme.colorScheme.surface,
          )
        : null;
    final key = isBoarding
        ? _boardingKey
        : ValueKey('boarding-bus-stop-${stop.id}');
    final dotSize = special ? 24.0 : 12.0;
    final marker = arrived
        ? _busMarker(buses)
        : Container(
            width: dotSize,
            height: dotSize,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: special ? accent : theme.colorScheme.surface,
              border: Border.all(color: accent, width: 1.5),
            ),
            child: special
                ? Icon(
                    isTarget
                        ? Icons.flag_outlined
                        : Icons.directions_bus_outlined,
                    size: 14,
                    color: theme.colorScheme.surface,
                    semanticLabel: isTarget ? '목표 정류장' : '승차 정류장',
                  )
                : null,
          );
    final style = emphasized
        ? theme.textTheme.titleMedium!.copyWith(
            color: special ? accent : theme.colorScheme.onSurface,
            fontWeight: FontWeight.w600,
          )
        : theme.textTheme.bodySmall!.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          );
    final stopRow = _row(
      _rail(
        _leg(i - 1, boarding, target),
        _leg(i, boarding, target),
        marker,
        markerSize: arrived ? 44 : dotSize,
      ),
      Padding(
        padding: EdgeInsets.symmetric(
          vertical: emphasized ? StopBellSpacing.sm : StopBellSpacing.xs,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text.rich(
              TextSpan(
                children: [
                  if (special)
                    TextSpan(
                      text: isTarget ? '목표 · ' : '승차 · ',
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: accent,
                      ),
                    ),
                  TextSpan(text: display.name),
                  if (display.isNonstop)
                    TextSpan(text: '  미정차', style: theme.textTheme.labelSmall),
                ],
              ),
              style: style,
            ),
          ],
        ),
      ),
      color: arrived ? null : tint,
      key: arrived ? null : key,
    );
    if (!arrived) return stopRow;

    // 도착 버스: 마커는 정류장 동그라미 자리에 두고 카드는 정류장 행 위로 비켜 쌓는다.
    // 카드 k의 위치는 위에서부터 고정 높이로 정해져 연결선 좌표를 계산할 수 있다.
    final h = _cardHeight;
    final cardsEnd = buses.length * (_cardGap + h) + _cardGap;
    final markerY = cardsEnd + _centerY;
    return Container(
      key: key,
      color: tint,
      child: Stack(
        children: [
          Column(
            children: [
              SizedBox(
                height: cardsEnd,
                child: Stack(
                  children: [
                    Positioned(
                      left: _railWidth / 2 - 1,
                      width: 2,
                      top: 0,
                      bottom: 0,
                      child: ColoredBox(
                        color:
                            _leg(i - 1, boarding, target) ?? Colors.transparent,
                      ),
                    ),
                    for (final (k, bus) in buses.indexed)
                      Positioned(
                        left: _cardLeft,
                        right: StopBellSpacing.screenPadding,
                        top: _cardGap + k * (_cardGap + h),
                        height: h,
                        child: _busCard(bus, boarding, target),
                      ),
                  ],
                ),
              ),
              stopRow,
            ],
          ),
          for (final (k, bus) in buses.indexed)
            Positioned.fill(
              child: _connector(
                bus,
                (_) => Offset(_railWidth / 2, markerY),
                (_) =>
                    Offset(_cardLeft, _cardGap + k * (_cardGap + h) + h - 12),
              ),
            ),
        ],
      ),
    );
  }

  /// 출발해 다음 정류장 사이에 있는 버스: 마커는 레일 위, 카드는 오른쪽에서 짧은
  /// 수평 연결선으로 이어진다.
  Widget _betweenRow(int i, BoardingBusEntry bus, int boarding, int target) {
    final leg = _leg(i, boarding, target);
    return Stack(
      children: [
        Positioned(
          left: 0,
          top: 0,
          bottom: 0,
          width: _railWidth,
          child: _rail(leg, leg, null, centered: true),
        ),
        Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(
            _cardLeft,
            _cardGap,
            StopBellSpacing.screenPadding,
            _cardGap,
          ),
          child: SizedBox(
            height: _cardHeight,
            child: _busCard(bus, boarding, target),
          ),
        ),
        Positioned.fill(
          child: _connector(
            bus,
            (size) => Offset(_railWidth / 2, size.height / 2),
            (size) => Offset(_cardLeft, size.height / 2),
          ),
        ),
        Positioned(
          left: 0,
          top: 0,
          bottom: 0,
          width: _railWidth,
          child: Center(child: _busMarker([bus])),
        ),
      ],
    );
  }

  /// 마커와 카드를 잇는 선. 탭하면 카드와 같이 그 버스를 선택한다.
  /// 선은 마커 중심이 아니라 선택 시 확대·halo까지 포함한 마커 외곽
  /// ([_markerReach])에서 시작해 원 안으로 겹치지 않는다.
  Widget _connector(
    BoardingBusEntry bus,
    Offset Function(Size) center,
    Offset Function(Size) to,
  ) {
    Offset from(Size size) {
      final c = center(size), t = to(size);
      return c + (t - c) / (t - c).distance * _markerReach;
    }

    final selected = identical(bus.candidate, widget.selected);
    final color = widget.vehicleColor;
    return GestureDetector(
      onTap: widget.canSelect(bus.candidate)
          ? () => widget.onSelect(bus.candidate)
          : null,
      child: CustomPaint(
        painter: _ConnectorPainter(
          from: from,
          to: to,
          color: selected ? color : color.withValues(alpha: 0.55),
          width: selected ? 3 : 1.5,
        ),
      ),
    );
  }

  /// 선택 시 노선색 halo와 1.15배 확대. 마커 자체 색·모양은 그대로다.
  Widget _busMarker(List<BoardingBusEntry> buses) {
    final selected = buses.any((b) => identical(b.candidate, widget.selected));
    final target = buses
        .where((b) => widget.canSelect(b.candidate))
        .firstOrNull;
    final color = widget.vehicleColor;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: target == null ? null : () => widget.onSelect(target.candidate),
      child: SizedBox(
        width: 44,
        height: 44,
        child: Stack(
          alignment: Alignment.center,
          children: [
            AnimatedOpacity(
              duration: const Duration(milliseconds: 150),
              opacity: selected ? 1 : 0,
              child: DecoratedBox(
                key: selected ? const ValueKey('boarding-bus-halo') : null,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: color.withValues(alpha: 0.28),
                ),
                child: const SizedBox(width: 40, height: 40),
              ),
            ),
            AnimatedScale(
              duration: const Duration(milliseconds: 150),
              scale: selected ? 1.15 : 1,
              child: BusVehicleMarker(count: buses.length, color: color),
            ),
          ],
        ),
      ),
    );
  }

  Widget _collapsedRow(int start, int count, int boarding, int target) =>
      _toggleRow(start, count, boarding, target, '· · ·  정류장 $count개', true);

  /// 펼친 구간 위의 "접기" 행. 탭하면 다시 접힌다.
  Widget _foldRow(int start, int count, int boarding, int target) =>
      _toggleRow(start, count, boarding, target, '· · ·  접기', false);

  Widget _toggleRow(
    int start,
    int count,
    int boarding,
    int target,
    String label,
    bool expand,
  ) => _row(
    _rail(
      _leg(start - 1, boarding, target),
      _leg(start + count - 1, boarding, target),
      null,
    ),
    InkWell(
      key: ValueKey(
        expand ? 'boarding-bus-collapsed-$start' : 'boarding-bus-fold-$start',
      ),
      onTap: () => setState(
        () => expand ? _expanded.add(start) : _expanded.remove(start),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: StopBellSpacing.md),
        child: Text(
          label,
          style: Theme.of(context).textTheme.bodySmall
              ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
        ),
      ),
    ),
  );

  String _distance(BoardingBusEntry bus, int boarding, int target) {
    final stopIndex = widget.stops.indexWhere(
      (s) => s.id == bus.vehicle.occurrenceId,
    );
    if (stopIndex < boarding) return '승차까지 ${boarding - stopIndex}정거장';
    final left = target - stopIndex;
    return left == 0 ? '목표 정류장' : '목표까지 $left정거장';
  }

  /// 노선 유형색으로 선택을 나타내는 카드(높이는 호출부가 정한다).
  Widget _busCard(BoardingBusEntry bus, int boarding, int target) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final route = widget.vehicleColor;
    final vehicle = bus.vehicle;
    final selected = identical(bus.candidate, widget.selected);
    final enabled = widget.canSelect(bus.candidate);
    final detail = [?vehicle.seatLabel, ?vehicle.congestionLabel].join(' · ');
    final shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(StopBellRadius.card),
      side: BorderSide(
        color: selected ? route : colors.outlineVariant,
        width: selected ? 2.5 : 1,
      ),
    );
    return Semantics(
      button: true,
      enabled: enabled,
      selected: selected,
      child: Material(
        color: selected
            ? Color.alphaBlend(route.withValues(alpha: 0.10), colors.surface)
            : colors.surface,
        shape: shape,
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: ValueKey('boarding-bus-${vehicle.normalizedNumber}'),
          onTap: enabled ? () => widget.onSelect(bus.candidate) : null,
          child: Opacity(
            opacity: enabled ? 1 : 0.6,
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: StopBellSpacing.md,
                vertical: StopBellSpacing.sm,
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(Icons.directions_bus, size: 18, color: route),
                            const SizedBox(width: StopBellSpacing.xs),
                            Text(
                              vehicle.shortNumber,
                              style: theme.textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                            if (detail.isNotEmpty) ...[
                              const SizedBox(width: StopBellSpacing.sm),
                              Expanded(
                                child: Text(
                                  detail,
                                  textAlign: TextAlign.end,
                                  overflow: TextOverflow.ellipsis,
                                  style: theme.textTheme.bodySmall,
                                ),
                              ),
                            ],
                          ],
                        ),
                        Text(
                          _distance(bus, boarding, target),
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: colors.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: StopBellSpacing.sm),
                  Icon(
                    selected
                        ? Icons.check_circle
                        : Icons.radio_button_unchecked,
                    color: selected ? route : colors.outline,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ConnectorPainter extends CustomPainter {
  _ConnectorPainter({
    required this.from,
    required this.to,
    required this.color,
    required this.width,
  });

  final Offset Function(Size) from;
  final Offset Function(Size) to;
  final Color color;
  final double width;
  Offset? _a, _b;

  @override
  void paint(Canvas canvas, Size size) {
    _a = from(size);
    _b = to(size);
    canvas.drawLine(
      _a!,
      _b!,
      Paint()
        ..color = color
        ..strokeWidth = width
        ..strokeCap = StrokeCap.round,
    );
  }

  /// 선 근처(±14)만 탭을 받아 카드·마커 위를 가리지 않는다.
  @override
  bool? hitTest(Offset position) {
    final a = _a, b = _b;
    if (a == null || b == null) return false;
    final ab = b - a;
    final t =
        ((position - a).dx * ab.dx + (position - a).dy * ab.dy) /
        (ab.dx * ab.dx + ab.dy * ab.dy);
    final nearest = a + ab * t.clamp(0.0, 1.0);
    return (position - nearest).distance <= 14;
  }

  @override
  bool shouldRepaint(_ConnectorPainter old) =>
      old.color != color || old.width != width;
}
