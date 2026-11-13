import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import 'bus_route_stop_occurrence.dart';
import 'bus_route_vehicle.dart';
import 'stop_display_name.dart';

/// The stops API only supplies destinations for a verified GBIS traversal:
/// stops before turnSeq point to the turn; turnSeq and later point to the end.
({int index, String outbound, String inbound})? _verifiedTurn(
  List<BusRouteStopOccurrence> stops,
) {
  if (stops.length < 3) return null;
  final outbound = stops.first.destinationName;
  final inbound = stops.last.destinationName;
  if (outbound == null || inbound == null || outbound == inbound) return null;

  final ids = <int>{};
  var turn = 0;
  for (var index = 0; index < stops.length; index++) {
    final stop = stops[index];
    if (!ids.add(stop.id) ||
        (index > 0 && stop.order <= stops[index - 1].order)) {
      return null;
    }
    if (turn == 0 && stop.destinationName == inbound) turn = index;
    if (stop.destinationName != (turn == 0 ? outbound : inbound)) return null;
  }
  if (turn == 0 ||
      turn == stops.length - 1 ||
      stops[turn].name != outbound ||
      stops.last.name != inbound) {
    return null;
  }
  return (index: turn, outbound: outbound, inbound: inbound);
}

Map<String, int> _nameCounts(List<BusRouteStopOccurrence> stops) {
  final counts = <String, int>{};
  for (final stop in stops) {
    counts.update(
      stopDisplayName(stop.name).name,
      (count) => count + 1,
      ifAbsent: () => 1,
    );
  }
  return counts;
}

/// Keep the full disambiguation in the summary rather than on every row.
String? selectedStopContext(
  BusRouteStopOccurrence stop,
  List<BusRouteStopOccurrence> stops,
) {
  final sameName = stops.where(
    (item) =>
        stopDisplayName(item.name).name == stopDisplayName(stop.name).name,
  );
  final sameDestination = sameName.where(
    (item) => item.destinationName == stop.destinationName,
  );
  final showNeighbors =
      sameName.length > 1 &&
      (stop.destinationName == null || sameDestination.length > 1);
  final details = [
    if (stop.destinationName != null) '${stop.destinationName} 방면',
    if (showNeighbors)
      '이전: ${stop.previousStopName ?? '없음'} · 다음: ${stop.nextStopName ?? '없음'}',
    if (showNeighbors &&
        sameDestination
                .where(
                  (item) =>
                      item.previousStopName == stop.previousStopName &&
                      item.nextStopName == stop.nextStopName,
                )
                .length >
            1)
      '노선 순서 ${stop.order}',
  ];
  return details.isEmpty ? null : details.join('\n');
}

const busRouteTimelineRailWidth = 56.0;
const busRouteTimelineMinimumRowHeight = 52.0;

class BusRouteTimeline extends StatefulWidget {
  const BusRouteTimeline({
    super.key,
    required this.stops,
    required this.onSelect,
    this.targetId,
    this.boardingId,
    this.rowKey,
    this.isSelectable,
    this.selectionLocked = false,
    this.vehicles,
    this.vehicleColor,
    this.stopDirection,
  });

  final List<BusRouteStopOccurrence> stops;
  final ValueChanged<BusRouteStopOccurrence>? onSelect;
  final int? targetId;
  final int? boardingId;
  final Key Function(int id)? rowKey;
  final bool Function(BusRouteStopOccurrence)? isSelectable;
  final bool selectionLocked;
  final String? Function(BusRouteStopOccurrence)? stopDirection;

  /// 선택 입력. null이면 기존과 동일하게 그린다. occurrenceId가 없는 차량은
  /// 노선도에 찍지 않는다.
  final List<BusRouteVehicle>? vehicles;

  /// 차량 아이콘 색(노선 유형 표시색). null이면 기본 차량 위치색.
  final Color? vehicleColor;

  @override
  State<BusRouteTimeline> createState() => _BusRouteTimelineState();
}

class _BusRouteTimelineState extends State<BusRouteTimeline> {
  late final _scroll = ScrollController(keepScrollOffset: false)
    ..addListener(_updateDirection);
  late var _turn = _verifiedTurn(widget.stops);
  late var _counts = _nameCounts(widget.stops);
  late var _byOccurrence = _groupVehicles(widget.vehicles);
  _TimelineLayout? _layout;
  bool _inbound = false;

  @override
  void didUpdateWidget(covariant BusRouteTimeline oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.targetId != oldWidget.targetId ||
        widget.boardingId != oldWidget.boardingId ||
        widget.stopDirection != oldWidget.stopDirection) {
      _layout = null;
    }
    if (!identical(widget.vehicles, oldWidget.vehicles)) {
      _byOccurrence = _groupVehicles(widget.vehicles);
      _layout = null;
    }
    if (!identical(widget.stops, oldWidget.stops)) {
      _turn = _verifiedTurn(widget.stops);
      _counts = _nameCounts(widget.stops);
      _layout = null;
      _inbound = false;
    }
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _updateDirection() {
    final turn = _turn;
    final layout = _layout;
    if (turn == null || layout == null || !_scroll.hasClients) return;
    final boundary = layout.offsets[turn.index];
    // Once the turn is aligned, keep the highlight through tiny scroll jitter.
    final inbound = _scroll.offset >= boundary - (_inbound ? 2 : 0.01);
    if (_inbound != inbound) setState(() => _inbound = inbound);
  }

  void _goTo(int index) {
    if (!_scroll.hasClients || _layout == null) return;
    final offset = _layout!.offsets[index];
    if (MediaQuery.disableAnimationsOf(context)) {
      _scroll.jumpTo(offset);
    } else {
      // A new tap or a manual scroll interrupts this activity. The position
      // listener is the only source of truth, including after interruption.
      _scroll.animateTo(
        offset,
        duration: const Duration(milliseconds: 380),
        curve: Curves.easeInOutCubic,
      );
    }
  }

  static Map<int, List<BusRouteVehicle>> _groupVehicles(
    List<BusRouteVehicle>? vehicles,
  ) {
    final map = <int, List<BusRouteVehicle>>{};
    for (final vehicle in vehicles ?? const <BusRouteVehicle>[]) {
      final id = vehicle.occurrenceId;
      if (id != null) map.putIfAbsent(id, () => []).add(vehicle);
    }
    return map;
  }

  /// 이 정류장 동그라미 위치에 그릴 차량 수. 마지막 정류장은 출발 상태도 여기에 그린다.
  int _atStopCount(int index) {
    final last = index == widget.stops.length - 1;
    return (_byOccurrence[widget.stops[index].id] ?? const <BusRouteVehicle>[])
        .where((v) => last || v.position == BusVehiclePosition.atStop)
        .length;
  }

  /// 이전 정류장을 출발해 이 정류장과의 사이(레일 중앙)에 있는 차량 수.
  int _betweenCount(int index) => index == 0
      ? 0
      : (_byOccurrence[widget.stops[index - 1].id] ?? const <BusRouteVehicle>[])
            .where((v) => v.position == BusVehiclePosition.departed)
            .length;

  String? _rowDetails(int index) {
    final stop = widget.stops[index];
    final details = [
      if (index == _turn?.index) '회차지점',
      if (widget.stopDirection != null && widget.stopDirection!(stop) != null)
        widget.stopDirection!(stop)!,
      if (widget.stopDirection == null &&
          (stop.id == widget.targetId || stop.id == widget.boardingId) &&
          (_counts[stopDisplayName(stop.name).name] ?? 0) > 1 &&
          (stop.destinationName == null ||
              widget.stops
                      .where(
                        (other) =>
                            stopDisplayName(other.name).name ==
                                stopDisplayName(stop.name).name &&
                            other.destinationName == stop.destinationName,
                      )
                      .length >
                  1))
        '노선 순서 ${stop.order}',
    ];
    return details.isEmpty ? null : details.join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final turn = _turn;
    final theme = Theme.of(context);
    final stopColors = StopBellColors.of(context);
    final boardingIndex = widget.stops.indexWhere(
      (stop) => stop.id == widget.boardingId,
    );
    final targetIndex = widget.stops.indexWhere(
      (stop) => stop.id == widget.targetId,
    );
    final hasSelectedLeg = boardingIndex >= 0 && targetIndex > boardingIndex;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (turn != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(
              StopBellSpacing.screenPadding,
              StopBellSpacing.xs,
              StopBellSpacing.screenPadding,
              StopBellSpacing.sm,
            ),
            child: Row(
              children: [
                Expanded(
                  child: _DirectionButton(
                    label: turn.outbound,
                    active: !_inbound,
                    onPressed: () => _goTo(0),
                  ),
                ),
                const SizedBox(width: StopBellSpacing.sm),
                Expanded(
                  child: _DirectionButton(
                    label: turn.inbound,
                    active: _inbound,
                    onPressed: () => _goTo(turn.index),
                  ),
                ),
              ],
            ),
          ),
        const Divider(height: 1),
        Expanded(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final metrics = (
                width: math.max(
                  0.0,
                  constraints.maxWidth -
                      _StopRow.railWidth -
                      StopBellSpacing.screenPadding,
                ),
                scaler: MediaQuery.textScalerOf(context),
                direction: Directionality.of(context),
                locale: Localizations.maybeLocaleOf(context),
                title: theme.textTheme.titleMedium!.copyWith(
                  color: theme.colorScheme.onSurface,
                  fontWeight: FontWeight.w600,
                ),
                mutedTitle: theme.textTheme.titleMedium!.copyWith(
                  color: stopColors.nonstopStop,
                  fontWeight: FontWeight.w600,
                ),
                vehicle: theme.textTheme.bodySmall!.copyWith(
                  color: stopColors.busLocation,
                  fontWeight: FontWeight.w600,
                ),
                good: stopColors.success,
                caution: stopColors.warning,
                danger: theme.colorScheme.error,
                badge: theme.textTheme.labelSmall!.copyWith(
                  color: stopColors.nonstopStop,
                  fontWeight: FontWeight.w600,
                ),
                detail: theme.textTheme.bodySmall!.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                heightBehavior: DefaultTextStyle.of(context).textHeightBehavior,
                bold: MediaQuery.boldTextOf(context),
              );
              if (_layout?.metrics != metrics) {
                _layout = _TimelineLayout(metrics, [
                  for (var index = 0; index < widget.stops.length; index++)
                    (
                      stopDisplayName(widget.stops[index].name),
                      _rowDetails(index),
                      _byOccurrence[widget.stops[index].id] ?? const [],
                    ),
                ]);
                WidgetsBinding.instance.addPostFrameCallback((_) {
                  if (mounted) _updateDirection();
                });
              }
              final layout = _layout!;
              // Give short return legs enough scroll range to align the turn
              // at the top, without inserting anything between occurrences.
              final tail = turn == null
                  ? 0.0
                  : math.max(
                      0.0,
                      constraints.maxHeight -
                          (layout.offsets.last - layout.offsets[turn.index]),
                    );
              return ListView.builder(
                controller: _scroll,
                primary: false,
                padding: EdgeInsets.only(bottom: tail),
                itemCount: widget.stops.length,
                itemExtentBuilder: (index, _) => layout.extents[index],
                itemBuilder: (context, index) {
                  final stop = widget.stops[index];
                  final selectable = widget.isSelectable?.call(stop) ?? true;
                  return _StopRow(
                    key: widget.rowKey?.call(stop.id) ?? ValueKey(stop.id),
                    label: layout.labels[index],
                    nonstop: stopDisplayName(stop.name).isNonstop,
                    selectionLocked: widget.selectionLocked,
                    first: index == 0,
                    last: index == widget.stops.length - 1,
                    turn: index == turn?.index,
                    target: index == targetIndex,
                    boarding: index == boardingIndex,
                    incomingSelected:
                        hasSelectedLeg &&
                        index > boardingIndex &&
                        index <= targetIndex,
                    outgoingSelected:
                        hasSelectedLeg &&
                        index >= boardingIndex &&
                        index < targetIndex,
                    atStopCount: _atStopCount(index),
                    betweenCount: _betweenCount(index),
                    vehicleColor: widget.vehicleColor ?? stopColors.busLocation,
                    unavailable: !selectable,
                    onTap: widget.onSelect == null || !selectable
                        ? null
                        : () => widget.onSelect!(stop),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

typedef _TextMetrics = ({
  double width,
  TextScaler scaler,
  TextDirection direction,
  Locale? locale,
  TextStyle title,
  TextStyle mutedTitle,
  TextStyle badge,
  TextStyle detail,
  TextStyle vehicle,
  Color good,
  Color caution,
  Color danger,
  TextHeightBehavior? heightBehavior,
  bool bold,
});

/// The exact extent used by the sliver is measured once per text layout.
/// Index offsets and the viewport share these extents, including wrapped rows.
class _TimelineLayout {
  _TimelineLayout(
    this.metrics,
    List<(({String name, bool isNonstop}), String?, List<BusRouteVehicle>)>
    rows,
  ) {
    final painter = TextPainter(
      textScaler: metrics.scaler,
      textDirection: metrics.direction,
      locale: metrics.locale,
      textHeightBehavior: metrics.heightBehavior,
    );
    for (final (display, detail, vehicles) in rows) {
      final baseTitle = display.isNonstop ? metrics.mutedTitle : metrics.title;
      final title = metrics.bold
          ? baseTitle.copyWith(fontWeight: FontWeight.bold)
          : baseTitle;
      final span = TextSpan(
        text: display.name,
        style: title,
        children: [
          if (display.isNonstop) TextSpan(text: '  미정차 ', style: metrics.badge),
          if (detail != null)
            TextSpan(
              text: '\n$detail',
              style: metrics.bold
                  ? metrics.detail.copyWith(fontWeight: FontWeight.bold)
                  : metrics.detail,
            ),
          for (final vehicle in vehicles) ..._vehicleSpans(vehicle, metrics),
        ],
      );
      painter.text = span;
      painter.layout(maxWidth: metrics.width);
      final extent = math.max(
        busRouteTimelineMinimumRowHeight,
        painter.height.ceilToDouble() + 2 * StopBellSpacing.sm,
      );
      labels.add(span);
      extents.add(extent);
      offsets.add(offsets.last + extent);
    }
    painter.dispose();
  }

  final _TextMetrics metrics;
  final labels = <TextSpan>[];
  final extents = <double>[];
  final offsets = <double>[0];
}

class _DirectionButton extends StatelessWidget {
  const _DirectionButton({
    required this.label,
    required this.active,
    required this.onPressed,
  });

  final String label;
  final bool active;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final direction = '$label 방면';
    return Semantics(
      selected: active,
      child: Tooltip(
        message: direction,
        excludeFromSemantics: true,
        child: TextButton(
          onPressed: onPressed,
          style: TextButton.styleFrom(
            backgroundColor: active
                ? colors.primaryContainer
                : colors.surfaceContainerLow,
            foregroundColor: active
                ? colors.onPrimaryContainer
                : colors.onSurfaceVariant,
            minimumSize: const Size(0, StopBellSpacing.minimumTouchSize),
            padding: const EdgeInsets.symmetric(
              horizontal: StopBellSpacing.md,
              vertical: StopBellSpacing.xs,
            ),
            shape: StadiumBorder(
              side: BorderSide(
                color: active ? colors.primary : colors.onSurfaceVariant,
              ),
            ),
            textStyle: Theme.of(context).textTheme.labelSmall,
          ),
          child: Text(
            direction,
            textAlign: TextAlign.center,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ),
    );
  }
}

class _StopRow extends StatelessWidget {
  const _StopRow({
    super.key,
    required this.label,
    required this.nonstop,
    required this.selectionLocked,
    required this.first,
    required this.last,
    required this.turn,
    required this.target,
    required this.boarding,
    required this.incomingSelected,
    required this.outgoingSelected,
    required this.onTap,
    required this.unavailable,
    this.atStopCount = 0,
    this.betweenCount = 0,
    required this.vehicleColor,
  });

  static const railWidth = busRouteTimelineRailWidth;
  final TextSpan label;
  final bool nonstop;
  final bool selectionLocked;
  final bool first;
  final bool last;
  final bool turn;
  final bool target;
  final bool boarding;
  final bool incomingSelected;
  final bool outgoingSelected;
  final VoidCallback? onTap;
  final bool unavailable;
  final int atStopCount;
  final int betweenCount;
  final Color vehicleColor;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final stopColors = StopBellColors.of(context);
    final accent = target
        ? stopColors.targetStop
        : boarding
        ? stopColors.boardingStop
        : nonstop
        ? stopColors.nonstopStop
        : turn
        ? colors.primary
        : colors.outline;
    final selected = target || boarding;
    final visibleLabel = selected
        ? TextSpan(
            text: label.text,
            style: label.style?.copyWith(color: accent),
            children: label.children,
          )
        : label;
    return Semantics(
      selected: selected,
      enabled: !unavailable,
      hint: selectionLocked
          ? '정류장 선택 완료. 선택한 구간을 확인해 주세요.'
          : unavailable
          ? '승차 정류장 이후의 정류장을 선택해 주세요.'
          : null,
      child: Material(
        color: selected
            ? Color.alphaBlend(accent.withValues(alpha: 0.10), colors.surface)
            : colors.surface,
        child: InkWell(
          onTap: onTap,
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (!last)
                PositionedDirectional(
                  start: railWidth,
                  end: StopBellSpacing.screenPadding,
                  bottom: 0,
                  child: Divider(height: 1, color: colors.outlineVariant),
                ),
              Row(
                children: [
                  SizedBox(
                    width: railWidth,
                    child: Stack(
                      fit: StackFit.expand,
                      clipBehavior: Clip.none,
                      children: [
                        // The rail halves meet at each marker's center. Keep
                        // their geometry independent of the label and marker.
                        Column(
                          children: [
                            Expanded(
                              child: BusRouteRailSegment(
                                color: first
                                    ? null
                                    : incomingSelected
                                    ? stopColors.targetStop
                                    : colors.outline,
                              ),
                            ),
                            Expanded(
                              child: BusRouteRailSegment(
                                color: last
                                    ? null
                                    : outgoingSelected
                                    ? stopColors.targetStop
                                    : colors.outline,
                              ),
                            ),
                          ],
                        ),
                        Center(
                          child: Container(
                            width: selected || turn ? 24 : 12,
                            height: selected || turn ? 24 : 12,
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: selected || turn ? accent : colors.surface,
                              border: Border.all(color: accent, width: 1.5),
                            ),
                            child: IconTheme(
                              data: IconThemeData(
                                color: colors.surface,
                                size: 14,
                              ),
                              child: target
                                  ? const Icon(
                                      Icons.flag_outlined,
                                      semanticLabel: '목표 정류장',
                                    )
                                  : boarding
                                  ? const Icon(
                                      Icons.directions_bus_outlined,
                                      semanticLabel: '승차 정류장',
                                    )
                                  : turn
                                  ? const Icon(Icons.turn_left)
                                  : const SizedBox.shrink(),
                            ),
                          ),
                        ),
                        // 정류장 도착: 동그라미와 겹침. 출발: 이전 정류장과 이 정류장
                        // 사이(행 경계 = 레일 중앙)에 걸친다. 다음 행이 위에 그려지므로
                        // 경계 마커는 아래 행에서 위쪽으로 삐져나오게 그린다.
                        if (atStopCount > 0)
                          Center(
                            child: BusVehicleMarker(
                              count: atStopCount,
                              color: vehicleColor,
                            ),
                          ),
                        if (betweenCount > 0)
                          Positioned(
                            top: -BusVehicleMarker.size / 2,
                            left: 0,
                            right: 0,
                            child: Center(
                              child: BusVehicleMarker(
                                count: betweenCount,
                                color: vehicleColor,
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                  Expanded(
                    child: Opacity(
                      opacity: unavailable && !selected ? 0.75 : 1,
                      // 색만 바뀌는 갱신(paint 전용 경로)에서 문단 크기가 어긋나
                      // 단언이 나는 것을 피하려고 선택·밝기가 바뀌면 새로 만든다.
                      child: Text.rich(
                        key: ValueKey((selected, Theme.of(context).brightness)),
                        visibleLabel,
                        style: const TextStyle(inherit: false),
                        textScaler: MediaQuery.textScalerOf(context),
                        locale: Localizations.maybeLocaleOf(context),
                        textHeightBehavior: DefaultTextStyle.of(context)
                            .textHeightBehavior,
                      ),
                    ),
                  ),
                  const SizedBox(width: StopBellSpacing.screenPadding),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class BusRouteRailSegment extends StatelessWidget {
  const BusRouteRailSegment({super.key, required this.color});

  final Color? color;

  @override
  Widget build(BuildContext context) => color == null
      ? const SizedBox.shrink()
      : Align(
          child: SizedBox(
            width: 2,
            height: double.infinity,
            child: ColoredBox(color: color!),
          ),
        );
}

const _congestionLabels = ['여유', '보통', '혼잡', '매우혼잡'];

List<InlineSpan> _vehicleSpans(BusRouteVehicle vehicle, _TextMetrics metrics) {
  final style = metrics.bold
      ? metrics.vehicle.copyWith(fontWeight: FontWeight.bold)
      : metrics.vehicle;
  final seat = switch (vehicle.seatStatus) {
    BusVehicleSeatStatus.available => '${vehicle.seatRemaining}석',
    BusVehicleSeatStatus.none => '만석',
    BusVehicleSeatStatus.unavailable => null,
  };
  final congestion = vehicle.congestion;
  final congestionColor = switch (congestion) {
    1 => metrics.good,
    3 => metrics.caution,
    4 => metrics.danger,
    _ => metrics.detail.color,
  };
  return [
    TextSpan(
      children: [
        const TextSpan(text: '\n'),
        // 아이콘 글리프를 글자로 넣어 행 높이 측정(TextPainter)에 그대로 쓴다.
        TextSpan(
          text: String.fromCharCode(Icons.directions_bus.codePoint),
          style: TextStyle(
            fontFamily: Icons.directions_bus.fontFamily,
            package: Icons.directions_bus.fontPackage,
          ),
        ),
        TextSpan(text: ' ${vehicle.shortNumber}'),
      ],
      style: style,
    ),
    if (seat != null) TextSpan(text: ' · $seat', style: style),
    if (congestion != null)
      TextSpan(
        text: ' · ${_congestionLabels[congestion - 1]}',
        style: style.copyWith(color: congestionColor),
      ),
  ];
}

class BusVehicleMarker extends StatelessWidget {
  const BusVehicleMarker({super.key, required this.count, required this.color});

  static const size = 28.0;
  final int count;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final surface = Theme.of(context).colorScheme.surface;
    return Semantics(
      label: count > 1 ? '버스 $count대' : '버스',
      excludeSemantics: true,
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: surface,
          border: Border.all(color: color, width: 1.5),
        ),
        alignment: Alignment.center,
        child: count > 1
            ? Text(
                '$count',
                style: TextStyle(
                  color: color,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                ),
              )
            : Icon(Icons.directions_bus, size: 18, color: color),
      ),
    );
  }
}
