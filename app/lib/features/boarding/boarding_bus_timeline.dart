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
  static const _minCollapsed = 4;
  static const _near = 2;
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
    final big = <int>{
      0,
      for (var i = boarding - _near; i <= boarding + _near; i++) i,
      for (var i = target - _near; i <= target + _near; i++) i,
      for (final i in [...atStop.keys, ...between.keys])
        for (var j = i - 1; j <= i + 1; j++) j,
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
      if (end - i + 1 >= _minCollapsed && !_expanded.contains(i)) {
        rows.add(_collapsedRow(i, end - i + 1, boarding, target));
      } else {
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
    final dotSize = special ? 24.0 : 12.0;
    Widget? marker = Container(
      width: dotSize,
      height: dotSize,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: special ? accent : theme.colorScheme.surface,
        border: Border.all(color: accent, width: 1.5),
      ),
      child: special
          ? Icon(
              isTarget ? Icons.flag_outlined : Icons.directions_bus_outlined,
              size: 14,
              color: theme.colorScheme.surface,
              semanticLabel: isTarget ? '목표 정류장' : '승차 정류장',
            )
          : null,
    );
    if (buses != null && buses.isNotEmpty) {
      marker = _busMarker(buses);
    }
    final style = emphasized
        ? theme.textTheme.titleMedium!.copyWith(
            color: special ? accent : theme.colorScheme.onSurface,
            fontWeight: FontWeight.w600,
          )
        : theme.textTheme.bodySmall!.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          );
    return _row(
      _rail(
        _leg(i - 1, boarding, target),
        _leg(i, boarding, target),
        marker,
        markerSize: buses != null && buses.isNotEmpty
            ? BusVehicleMarker.size
            : dotSize,
      ),
      Padding(
        padding: EdgeInsets.symmetric(
          vertical: emphasized ? StopBellSpacing.sm : StopBellSpacing.xs,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: StopBellSpacing.xs,
          children: [
            Text.rich(
              TextSpan(
                children: [
                  if (isBoarding || isTarget)
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
            for (final bus in buses ?? const <BoardingBusEntry>[])
              Padding(
                padding: const EdgeInsets.only(bottom: StopBellSpacing.xs),
                child: _chip(bus, boarding, target),
              ),
          ],
        ),
      ),
      color: special
          ? Color.alphaBlend(
              accent.withValues(alpha: 0.10),
              theme.colorScheme.surface,
            )
          : null,
      key: isBoarding ? _boardingKey : ValueKey('boarding-bus-stop-${stop.id}'),
    );
  }

  /// 출발해 다음 정류장 사이에 있는 버스: 정류장 행 아래에 레일 위 한 줄로 낀다.
  Widget _betweenRow(int i, BoardingBusEntry bus, int boarding, int target) {
    final leg = _leg(i, boarding, target);
    return _row(
      _rail(leg, leg, _busMarker([bus]), centered: true),
      Padding(
        padding: const EdgeInsets.symmetric(vertical: StopBellSpacing.sm),
        child: _chip(bus, boarding, target),
      ),
    );
  }

  Widget _collapsedRow(int start, int count, int boarding, int target) => _row(
    _rail(
      _leg(start - 1, boarding, target),
      _leg(start + count - 1, boarding, target),
      null,
    ),
    InkWell(
      key: ValueKey('boarding-bus-collapsed-$start'),
      onTap: () => setState(() => _expanded.add(start)),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: StopBellSpacing.md),
        child: Text(
          '· · ·  정류장 $count개',
          style: Theme.of(context).textTheme.bodySmall
              ?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
        ),
      ),
    ),
  );

  Widget _busMarker(List<BoardingBusEntry> buses) => BusVehicleMarker(
    count: buses.length,
    color: buses.any((b) => identical(b.candidate, widget.selected))
        ? StopBellColors.of(context).targetStop
        : widget.vehicleColor,
  );

  String _distance(BoardingBusEntry bus, int boarding, int target) {
    final stopIndex = widget.stops.indexWhere(
      (s) => s.id == bus.vehicle.occurrenceId,
    );
    if (stopIndex < boarding) return '승차까지 ${boarding - stopIndex}정거장';
    final left = target - stopIndex;
    return left == 0 ? '목표 정류장' : '목표까지 $left정거장';
  }

  /// 정류장 이름 시작선부터 오른쪽 여백까지 채우는 말풍선형 선택 카드.
  /// 꼬리는 레일의 버스 마커를 가리킨다(도착: 위쪽, 출발: 세로 중앙).
  Widget _chip(BoardingBusEntry bus, int boarding, int target) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final stopColors = StopBellColors.of(context);
    final vehicle = bus.vehicle;
    final selected = identical(bus.candidate, widget.selected);
    final enabled = widget.canSelect(bus.candidate);
    final detail = [?vehicle.seatLabel, ?vehicle.congestionLabel].join(' · ');
    final shape = BusBubbleBorder(
      tailAtTop: vehicle.position == BusVehiclePosition.atStop,
      side: BorderSide(
        color: selected ? stopColors.targetStop : colors.outlineVariant,
        width: selected ? 2 : 1,
      ),
    );
    return Semantics(
      button: true,
      enabled: enabled,
      selected: selected,
      child: Material(
        color: selected
            ? Color.alphaBlend(
                stopColors.targetStop.withValues(alpha: 0.16),
                colors.surface,
              )
            : colors.surface,
        shape: shape,
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: ValueKey('boarding-bus-${vehicle.normalizedNumber}'),
          customBorder: shape,
          onTap: enabled ? () => widget.onSelect(bus.candidate) : null,
          child: Opacity(
            opacity: enabled ? 1 : 0.6,
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 60),
              child: IntrinsicHeight(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Container(width: 4, color: widget.vehicleColor),
                    Expanded(
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
                                      Icon(
                                        Icons.directions_bus,
                                        size: 18,
                                        color: widget.vehicleColor,
                                      ),
                                      const SizedBox(width: StopBellSpacing.xs),
                                      Text(
                                        vehicle.shortNumber,
                                        style: theme.textTheme.titleMedium
                                            ?.copyWith(
                                              fontWeight: FontWeight.w700,
                                            ),
                                      ),
                                      if (detail.isNotEmpty) ...[
                                        const SizedBox(
                                          width: StopBellSpacing.sm,
                                        ),
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
                              color: selected
                                  ? stopColors.targetStop
                                  : colors.outline,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// 왼쪽 변 바깥으로 삼각 꼬리가 튀어나온 둥근 사각형(테두리·배경이 이어진다).
/// 꼬리 위치: [tailAtTop] 위쪽, 아니면 세로 중앙.
@visibleForTesting
class BusBubbleBorder extends ShapeBorder {
  const BusBubbleBorder({required this.tailAtTop, required this.side});

  static const tailWidth = 8.0;
  static const _tailHalfHeight = 7.0;
  static const _tailTopInset = 16.0;
  final bool tailAtTop;
  final BorderSide side;

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.all(side.width);

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) =>
      getOuterPath(rect.deflate(side.width), textDirection: textDirection);

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) {
    final body = Path()
      ..addRRect(
        RRect.fromRectAndRadius(
          rect,
          const Radius.circular(StopBellRadius.field),
        ),
      );
    final cy = tailAtTop ? rect.top + _tailTopInset : rect.center.dy;
    final tail = Path()
      ..moveTo(rect.left + 1, cy - _tailHalfHeight)
      ..lineTo(rect.left - tailWidth, cy)
      ..lineTo(rect.left + 1, cy + _tailHalfHeight)
      ..close();
    return Path.combine(PathOperation.union, body, tail);
  }

  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {
    canvas.drawPath(
      getOuterPath(rect, textDirection: textDirection),
      side.toPaint(),
    );
  }

  @override
  ShapeBorder scale(double t) =>
      BusBubbleBorder(tailAtTop: tailAtTop, side: side.scale(t));
}
