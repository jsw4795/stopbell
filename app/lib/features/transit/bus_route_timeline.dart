import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import 'bus_route_stop_occurrence.dart';
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
  });

  final List<BusRouteStopOccurrence> stops;
  final ValueChanged<BusRouteStopOccurrence>? onSelect;
  final int? targetId;
  final int? boardingId;
  final Key Function(int id)? rowKey;
  final bool Function(BusRouteStopOccurrence)? isSelectable;
  final bool selectionLocked;

  @override
  State<BusRouteTimeline> createState() => _BusRouteTimelineState();
}

class _BusRouteTimelineState extends State<BusRouteTimeline> {
  late final _scroll = ScrollController(keepScrollOffset: false)
    ..addListener(_updateDirection);
  late var _turn = _verifiedTurn(widget.stops);
  late var _counts = _nameCounts(widget.stops);
  _TimelineLayout? _layout;
  bool _inbound = false;

  @override
  void didUpdateWidget(covariant BusRouteTimeline oldWidget) {
    super.didUpdateWidget(oldWidget);
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

  String? _rowDetails(int index) {
    final stop = widget.stops[index];
    final details = [
      if (index == _turn?.index) '회차지점',
      if ((_counts[stopDisplayName(stop.name).name] ?? 0) > 1)
        '노선 순서 ${stop.order}',
    ];
    return details.isEmpty ? null : details.join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final turn = _turn;
    final theme = Theme.of(context);
    final stopColors = StopBellColors.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (turn != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 10),
            child: Row(
              children: [
                Expanded(
                  child: _DirectionButton(
                    label: turn.outbound,
                    active: !_inbound,
                    onPressed: () => _goTo(0),
                  ),
                ),
                const SizedBox(width: 8),
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
                width: math.max(0.0, constraints.maxWidth - _StopRow.gutters),
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
                  return _StopRow(
                    key: widget.rowKey?.call(stop.id) ?? ValueKey(stop.id),
                    label: layout.labels[index],
                    nonstop: stopDisplayName(stop.name).isNonstop,
                    selectionLocked: widget.selectionLocked,
                    first: index == 0,
                    last: index == widget.stops.length - 1,
                    turn: index == turn?.index,
                    target: stop.id == widget.targetId,
                    boarding: stop.id == widget.boardingId,
                    unavailable:
                        widget.isSelectable != null &&
                        !widget.isSelectable!(stop),
                    onTap:
                        widget.onSelect == null ||
                            (widget.isSelectable != null &&
                                !widget.isSelectable!(stop))
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
  TextHeightBehavior? heightBehavior,
  bool bold,
});

/// The exact extent used by the sliver is measured once per text layout.
/// Index offsets and the viewport share these extents, including wrapped rows.
class _TimelineLayout {
  _TimelineLayout(
    this.metrics,
    List<(({String name, bool isNonstop}), String?)> rows,
  ) {
    final painter = TextPainter(
      textScaler: metrics.scaler,
      textDirection: metrics.direction,
      locale: metrics.locale,
      textHeightBehavior: metrics.heightBehavior,
    );
    for (final (display, detail) in rows) {
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
        ],
      );
      painter.text = span;
      painter.layout(maxWidth: metrics.width);
      final extent = math.max(58.0, painter.height.ceilToDouble() + 20);
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
    return Semantics(
      selected: active,
      child: TextButton(
        onPressed: onPressed,
        style: TextButton.styleFrom(
          backgroundColor: active
              ? colors.primaryContainer
              : colors.surfaceContainerLow,
          foregroundColor: active
              ? colors.onPrimaryContainer
              : colors.onSurfaceVariant,
          minimumSize: const Size(0, 48),
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        ),
        child: Text('$label 방면', textAlign: TextAlign.center),
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
    required this.onTap,
    required this.unavailable,
  });

  static const gutters = 96.0;
  final TextSpan label;
  final bool nonstop;
  final bool selectionLocked;
  final bool first;
  final bool last;
  final bool turn;
  final bool target;
  final bool boarding;
  final VoidCallback? onTap;
  final bool unavailable;

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
    final visibleLabel = nonstop && selected
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
          ? '정류장 선택 완료. 다시 선택하려면 정류장 다시 선택 버튼을 누르세요.'
          : unavailable
          ? '승차 정류장 이후의 정류장을 선택해 주세요.'
          : null,
      child: Material(
        color: selected
            ? Color.alphaBlend(accent.withValues(alpha: 0.10), colors.surface)
            : colors.surface,
        child: InkWell(
          onTap: onTap,
          child: Row(
            children: [
              SizedBox(
                width: 56,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    if (!first || !last)
                      Align(
                        alignment: first
                            ? Alignment.bottomCenter
                            : last
                            ? Alignment.topCenter
                            : Alignment.center,
                        child: FractionallySizedBox(
                          heightFactor: first || last ? 0.5 : 1,
                          child: ColoredBox(
                            color: colors.primary.withValues(alpha: 0.75),
                            child: const SizedBox(width: 2),
                          ),
                        ),
                      ),
                    Center(
                      child: Container(
                        width: turn ? 24 : 16,
                        height: turn ? 24 : 16,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: selected || turn ? accent : colors.surface,
                          border: Border.all(color: accent, width: 2),
                        ),
                        child: turn
                            ? Icon(
                                Icons.turn_left,
                                size: 15,
                                color: nonstop && !selected
                                    ? colors.surface
                                    : colors.onPrimary,
                              )
                            : null,
                      ),
                    ),
                  ],
                ),
              ),
              Expanded(
                child: Opacity(
                  opacity: unavailable && !selected ? 0.75 : 1,
                  child: Text.rich(
                    visibleLabel,
                    style: const TextStyle(inherit: false),
                    textScaler: MediaQuery.textScalerOf(context),
                    locale: Localizations.maybeLocaleOf(context),
                    textHeightBehavior: DefaultTextStyle.of(context)
                        .textHeightBehavior,
                  ),
                ),
              ),
              SizedBox(
                width: 40,
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    if (target)
                      Icon(
                        Icons.flag_outlined,
                        size: 18,
                        color: stopColors.targetStop,
                        semanticLabel: '목표 정류장',
                      ),
                    if (boarding)
                      Icon(
                        Icons.directions_bus_outlined,
                        size: 18,
                        color: stopColors.boardingStop,
                        semanticLabel: '승차 정류장',
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
