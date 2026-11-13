import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import '../transit/stop_display_name.dart';
import '../transit/bus_route_timeline.dart' show BusRouteRailSegment;
import '../transit/bus_route.dart';
import '../transit/bus_route_presentation.dart';
import '../alarm/home_overview.dart' show alarmRouteTypeColor;
import 'boarding_session.dart';
import 'boarding_session_controller.dart';

class BoardingSessionView extends StatelessWidget {
  const BoardingSessionView({
    super.key,
    required this.controller,
    required this.onBack,
    required this.onReconfirm,
    required this.onNew,
    this.boardingName,
  });
  final BoardingSessionController controller;
  final String? boardingName;
  final VoidCallback onBack, onReconfirm, onNew;
  Future<void> _cancel(BuildContext context) async {
    final expected = controller.session;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('현재 차량 추적을 취소할까요?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('계속 추적'),
          ),
          TextButton(
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('추적 취소 확인'),
          ),
        ],
      ),
    );
    // A dialog is bound to this screen/session. Revalidate after its async gap.
    if (context.mounted &&
        controller.current &&
        confirmed == true &&
        controller.session?.id == expected?.id) {
      await controller.cancel();
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = controller;
    final s = c.session!;
    final actions = <Widget>[
      if (s.canReconfirm)
        FilledButton(
          onPressed: c.busy || c.stateUncertain ? null : onReconfirm,
          child: const Text('동일 차량 다시 확인'),
        ),
      if (s.isTerminal && s.status != BoardingSessionStatus.cancelled)
        OutlinedButton(
          onPressed: c.canCreate ? onNew : null,
          child: const Text('새 차량 후보 찾기'),
        ),
      if (c.autoRefreshFailed) const Text('자동 갱신에 실패해 이전 위치을 표시하고 있어요.'),
      if (c.message != null) Text(c.message!),
      if (c.pending != null)
        OutlinedButton(
          onPressed: c.busy ? null : c.recover,
          child: const Text('저장된 생성 요청 다시 확인'),
        ),
      if (c.busy) const Text('서버 응답 확인 중...'),
    ];
    return Column(
      children: [
        Expanded(
          child: c.inaccessible
              ? SingleChildScrollView(
                  child: Column(
                    children: [
                      Row(
                        children: [
                          IconButton(
                            onPressed: c.busy ? null : onBack,
                            tooltip: '도착 알람으로',
                            icon: const Icon(Icons.arrow_back),
                          ),
                          const Expanded(child: Text('하차 알람')),
                        ],
                      ),
                      const Text('세션이 없거나 접근할 수 없어요.'),
                      OutlinedButton(
                        onPressed: c.busy || c.refreshing
                            ? null
                            : c.refreshManually,
                        child: const Text('상태 새로고침'),
                      ),
                      if (c.message != null) Text(c.message!),
                      if (c.pending != null)
                        OutlinedButton(
                          onPressed: c.busy ? null : c.recover,
                          child: const Text('저장된 생성 요청 다시 확인'),
                        ),
                      if (c.busy) const Text('서버 응답 확인 중...'),
                    ],
                  ),
                )
              : BoardingSessionDetails(
                  session: s,
                  stateUncertain: c.stateUncertain,
                  boardingName: boardingName,
                  onBack: c.busy ? null : onBack,
                  onRefresh: c.busy || c.refreshing || c.autoRefreshing
                      ? null
                      : c.refreshManually,
                  refreshing: c.refreshing,
                  refreshToken: c.manualRefreshToken,
                  initialReference: c.initialReference,
                  showRefresh: true,
                  constrained: true,
                  actions: actions,
                ),
        ),
        if (!c.inaccessible && s.isOpen)
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Theme.of(context).colorScheme.error,
                    side: BorderSide(
                      color: Theme.of(context).colorScheme.error,
                    ),
                    minimumSize: const Size(
                      0,
                      StopBellSpacing.minimumTouchSize,
                    ),
                  ),
                  onPressed: c.busy || c.stateUncertain
                      ? null
                      : () => _cancel(context),
                  child: const Text('추적 취소'),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

/// Server-returned state shared by management and notification read-only detail.
class BoardingSessionDetails extends StatelessWidget {
  const BoardingSessionDetails({
    super.key,
    required this.session,
    this.stateUncertain = false,
    this.boardingName,
    this.onRefresh,
    this.showRefresh = false,
    this.refreshing = false,
    this.refreshToken = 0,
    this.initialReference,
    this.onBack,
    this.constrained = false,
    this.actions = const [],
  });
  final VoidCallback? onRefresh, onBack;
  final bool showRefresh, constrained, refreshing;
  final int refreshToken;
  final InitialReference? initialReference;
  final BoardingSession session;
  final bool stateUncertain;
  final String? boardingName;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final s = session;
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final semantic = StopBellColors.of(context);
    final label = stateUncertain
        ? '확인 필요'
        : switch (s.status) {
            BoardingSessionStatus.tracking => '추적 중',
            BoardingSessionStatus.suspended => '보류',
            BoardingSessionStatus.completed => '종료',
            BoardingSessionStatus.cancelled => '취소',
            BoardingSessionStatus.expired => '만료',
            BoardingSessionStatus.unknown => '확인 필요',
          };
    final badgeColor =
        stateUncertain ||
            s.status == BoardingSessionStatus.suspended ||
            s.status == BoardingSessionStatus.unknown
        ? semantic.heldTracking
        : s.status == BoardingSessionStatus.tracking
        ? semantic.activeTracking
        : scheme.onSurfaceVariant;
    final routeColor = alarmRouteTypeColor(
      context,
      s.routeNumber,
      s.timeline?.provider,
      s.timeline?.routeType,
    );
    final progress =
        !stateUncertain &&
        s.status == BoardingSessionStatus.tracking &&
        s.evaluationStatus == BoardingEvaluation.ready &&
        s.remainingOccurrenceGap != null;
    final initial =
        !stateUncertain &&
        s.status == BoardingSessionStatus.tracking &&
        s.evaluationStatus == BoardingEvaluation.verificationPending &&
        (s.initialRemainingGap != null || initialReference != null);
    final notice = stateUncertain
        ? '변경 결과를 확인하지 못했어요. 상태를 다시 조회해 주세요.'
        : switch (s.status) {
            BoardingSessionStatus.suspended =>
              s.canReconfirm ? '동일 차량을 다시 확인해 주세요.' : '차량 정보 확인이 보류됐어요.',
            BoardingSessionStatus.completed => '추적이 종료됐어요. 실제 하차 확인을 뜻하지 않아요.',
            BoardingSessionStatus.cancelled => '사용자 취소로 종료됐어요.',
            BoardingSessionStatus.expired => '세션 수명 만료로 종료됐어요.',
            BoardingSessionStatus.unknown => '세션 상태를 확인해 주세요.',
            BoardingSessionStatus.tracking => switch (s.evaluationStatus) {
              BoardingEvaluation.verificationPending =>
                initial ? null : '차량 위치 확인 중',
              BoardingEvaluation.recoveryPending => '현재 차량 진행 정보를 다시 확인하고 있어요.',
              BoardingEvaluation.unknown => '진행 상태를 확인해 주세요.',
              _ => null,
            },
          };
    final summary = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        AppBar(
          key: const ValueKey('boarding-header'),
          primary: false,
          automaticallyImplyLeading: false,
          leading: constrained || onBack != null
              ? IconButton(
                  onPressed: onBack,
                  tooltip: '도착 알람으로',
                  icon: const Icon(Icons.arrow_back),
                )
              : null,
          title: const Text('하차 알람'),
          actions: [
            if (showRefresh)
              IconButton(
                onPressed: onRefresh,
                tooltip: '상태 새로고침',
                icon: _RefreshIcon(active: refreshing),
              ),
          ],
        ),
        Container(
          key: const ValueKey('boarding-route-summary'),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: scheme.primaryContainer.withValues(alpha: .55),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Row(
            children: [
              Icon(
                Icons.directions_bus_outlined,
                color: routeColor ?? scheme.primary,
                size: 22,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      s.routeNumber,
                      style: theme.textTheme.titleMedium!.copyWith(
                        fontWeight: FontWeight.bold,
                        color: routeColor,
                      ),
                    ),
                    Text(s.vehicleNumber, style: theme.textTheme.bodySmall),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              _Badge(label: label, color: badgeColor),
            ],
          ),
        ),
        if (notice != null)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(notice, style: theme.textTheme.bodySmall),
          ),
        if (progress || initial)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('${s.targetName}까지', style: theme.textTheme.bodyMedium),
                Text(
                  '${initial ? (s.initialRemainingGap ?? initialReference?.gap) : s.remainingOccurrenceGap}개 정류장 남음',
                  style: theme.textTheme.titleMedium!.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Text('노선 순서 기준 · 미정차 정류장 포함', style: theme.textTheme.bodySmall),
              ],
            ),
          ),
        ...actions,
      ],
    );
    final routeHeader = Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 8),
      child: Align(
        alignment: Alignment.centerLeft,
        child: Text('이동 경로', style: theme.textTheme.titleSmall),
      ),
    );
    Widget route({required bool scrollable}) {
      if (s.timeline != null) {
        return _TrackingTimeline(
          key: ValueKey('timeline-${s.id}'),
          session: s,
          stateUncertain: stateUncertain,
          scrollable: scrollable,
          refreshToken: refreshToken,
          initialReference: initialReference,
        );
      }
      final fallback = Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(s.targetName, style: theme.textTheme.titleMedium),
            const Text('알림 시점'),
            if (s.notificationOffsetStops != null)
              Text('${s.notificationOffsetStops}개 정류장 전'),
            const Text('승차 정류장 정보가 없어 전체 노선 구간을 표시할 수 없어요.'),
          ],
        ),
      );
      return scrollable ? SingleChildScrollView(child: fallback) : fallback;
    }

    Widget panel({required bool bounded}) {
      final body = route(scrollable: bounded);
      return Container(
        key: const ValueKey('tracking-route-panel'),
        decoration: BoxDecoration(
          color: scheme.surfaceContainerLow,
          border: Border.all(color: scheme.outlineVariant),
          borderRadius: BorderRadius.circular(16),
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            routeHeader,
            if (bounded) Flexible(child: body) else body,
          ],
        ),
      );
    }

    if (!constrained) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [summary, const SizedBox(height: 8), panel(bounded: false)],
      );
    }
    if (s.isTerminal) {
      return SingleChildScrollView(
        padding: const EdgeInsets.symmetric(
          horizontal: StopBellSpacing.screenPadding,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            summary,
            const SizedBox(height: StopBellSpacing.sm),
            panel(bounded: false),
          ],
        ),
      );
    }
    return LayoutBuilder(
      builder: (context, constraints) => Column(
        children: [
          ConstrainedBox(
            constraints: BoxConstraints(maxHeight: constraints.maxHeight * .6),
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: summary,
            ),
          ),
          const SizedBox(height: 8),
          Flexible(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: panel(bounded: true),
            ),
          ),
        ],
      ),
    );
  }
}

class _RefreshIcon extends StatefulWidget {
  const _RefreshIcon({required this.active});
  final bool active;
  @override
  State<_RefreshIcon> createState() => _RefreshIconState();
}

class _RefreshIconState extends State<_RefreshIcon>
    with SingleTickerProviderStateMixin {
  late final AnimationController _rotation = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 750),
  );
  @override
  void initState() {
    super.initState();
    if (widget.active) _rotation.repeat();
  }

  @override
  void didUpdateWidget(covariant _RefreshIcon oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.active && !oldWidget.active) _rotation.repeat();
    if (!widget.active && oldWidget.active) {
      _rotation.stop();
      _rotation.value = 0;
    }
  }

  @override
  void dispose() {
    _rotation.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => RotationTransition(
    turns: _rotation,
    child: Icon(
      Icons.refresh,
      key: widget.active ? const ValueKey('refresh-in-progress') : null,
    ),
  );
}

class _Badge extends StatelessWidget {
  const _Badge({required this.label, required this.color});
  final String label;
  final Color color;
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
    decoration: BoxDecoration(
      color: color,
      borderRadius: BorderRadius.circular(8),
    ),
    child: Text(
      label,
      style: Theme.of(context).textTheme.labelSmall!.copyWith(
        color: Theme.of(context).brightness == Brightness.dark
            ? Colors.black
            : Colors.white,
      ),
    ),
  );
}

class _TrackingTimeline extends StatefulWidget {
  const _TrackingTimeline({
    super.key,
    required this.session,
    required this.stateUncertain,
    required this.scrollable,
    required this.refreshToken,
    required this.initialReference,
  });
  final InitialReference? initialReference;
  final BoardingSession session;
  final bool stateUncertain, scrollable;
  final int refreshToken;

  @override
  State<_TrackingTimeline> createState() => _TrackingTimelineState();
}

class _TrackingTimelineState extends State<_TrackingTimeline> {
  final _markerKey = GlobalKey();
  bool _focused = false, _focusPending = false, _userScrolled = false;
  int _focusGeneration = 0;
  late int _handledRefreshToken;

  @override
  void initState() {
    super.initState();
    _handledRefreshToken = widget.refreshToken;
  }

  @override
  void didUpdateWidget(covariant _TrackingTimeline oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.refreshToken != widget.refreshToken ||
        oldWidget.session.timeline?.positionIndex !=
            widget.session.timeline?.positionIndex ||
        oldWidget.session.timeline?.positionBasis !=
            widget.session.timeline?.positionBasis ||
        oldWidget.stateUncertain != widget.stateUncertain ||
        oldWidget.session.evaluationStatus != widget.session.evaluationStatus ||
        oldWidget.session.status != widget.session.status) {
      _focusGeneration++;
      _focusPending = false;
    }
  }

  int? get _position {
    final s = widget.session;
    final t = s.timeline!;
    final usable =
        !widget.stateUncertain &&
        s.status == BoardingSessionStatus.tracking &&
        ((s.evaluationStatus == BoardingEvaluation.ready &&
                t.positionBasis == 'READY') ||
            (s.evaluationStatus == BoardingEvaluation.verificationPending &&
                t.positionBasis == 'INITIAL'));
    if (usable) return t.positionIndex;
    if (!widget.stateUncertain &&
        s.status == BoardingSessionStatus.tracking &&
        s.evaluationStatus == BoardingEvaluation.verificationPending &&
        widget.initialReference?.id == s.id) {
      return widget.initialReference?.index;
    }
    return null;
  }

  void _focusInitially(int? position) {
    final timeline = widget.session.timeline!;
    if (!widget.scrollable ||
        _focused ||
        _focusPending ||
        _userScrolled ||
        position == null ||
        position > timeline.targetIndex) {
      return;
    }
    _focusPending = true;
    final sessionId = widget.session.id;
    final generation = _focusGeneration;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || generation != _focusGeneration) return;
      _focusPending = false;
      if (!mounted ||
          _focused ||
          _userScrolled ||
          widget.session.id != sessionId ||
          _position != position) {
        return;
      }
      final marker = _markerKey.currentContext;
      if (marker == null) return;
      _focused = true;
      Scrollable.ensureVisible(marker, alignment: .3);
    });
  }

  @override
  Widget build(BuildContext context) {
    final session = widget.session;
    final timeline = session.timeline!;
    final scheme = Theme.of(context).colorScheme;
    final accent = BusRoutePresentation.forRoute(
      BusRoute(
        id: 1,
        routeNumber: session.routeNumber,
        regionName: '-',
        provider: timeline.provider,
        routeType: timeline.routeType,
      ),
      Theme.of(context).brightness,
    ).color;
    final position = _position;
    final before = position != null && position < timeline.boardingIndex;
    final initialPosition =
        timeline.positionBasis == 'INITIAL' ||
        (timeline.positionIndex == null &&
            widget.initialReference != null &&
            position != null);
    if (_handledRefreshToken != widget.refreshToken) {
      _handledRefreshToken = widget.refreshToken;
      if (widget.scrollable &&
          position != null &&
          position <= timeline.targetIndex) {
        final token = widget.refreshToken;
        final id = session.id;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!mounted ||
              widget.session.id != id ||
              widget.refreshToken != token ||
              _position != position) {
            return;
          }
          final marker = _markerKey.currentContext;
          if (marker != null) {
            _focused = true;
            Scrollable.ensureVisible(marker, alignment: .3);
          }
        });
      }
    } else {
      _focusInitially(position);
    }
    final counts = <String, int>{};
    for (final stop in timeline.stops) {
      if (stop.name != null) {
        final name = stopDisplayName(stop.name!).name;
        counts.update(name, (count) => count + 1, ifAbsent: () => 1);
      }
    }
    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (before)
          IntrinsicHeight(
            key: _markerKey,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SizedBox(
                  width: 36,
                  child: Column(
                    children: [
                      Icon(
                        Icons.directions_bus,
                        key: const ValueKey('before-boarding-bus-marker'),
                        color: accent,
                        size: 28,
                        semanticLabel: initialPosition
                            ? '등록 당시 차량 위치'
                            : '최근 차량 위치',
                      ),
                      Expanded(
                        child: BusRouteRailSegment(
                          key: const ValueKey('before-boarding-rail'),
                          color: accent,
                        ),
                      ),
                    ],
                  ),
                ),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          initialPosition ? '승차 전 참고 위치' : '승차 정류장으로 이동 중',
                          style: Theme.of(context).textTheme.titleSmall
                              ?.copyWith(color: accent),
                        ),
                        if ((initialPosition
                                ? (session.initialStopName ??
                                      widget.initialReference?.name)
                                : session.currentStopName)
                            case final String name)
                          Text(
                            '$name 부근',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        Text(
                          '승차까지 ${timeline.boardingIndex - position}개 정류장',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        Text(
                          initialPosition ? '참고용 위치예요' : '최근 위치 기준',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        for (final stop in timeline.stops)
          _TrackingRow(
            key: position == stop.index ? _markerKey : ValueKey(stop.id),
            stop: stop,
            first: stop.index == timeline.boardingIndex,
            last: stop.index == timeline.targetIndex,
            bus: position == stop.index,
            initial: initialPosition,
            passed: position != null && stop.index <= position,
            alert:
                session.notificationOffsetStops != null &&
                stop.index ==
                    timeline.targetIndex - session.notificationOffsetStops!,
            showOrder:
                (stop.index == timeline.boardingIndex ||
                    stop.index == timeline.targetIndex) &&
                stop.name != null &&
                (counts[stopDisplayName(stop.name!).name] ?? 0) > 1,
            accent: accent,
            scheme: scheme,
          ),
      ],
    );
    if (!widget.scrollable) return content;
    return NotificationListener<ScrollNotification>(
      onNotification: (notification) {
        if (notification is ScrollStartNotification &&
            notification.dragDetails != null) {
          _userScrolled = true;
        }
        return false;
      },
      child: SingleChildScrollView(
        key: const ValueKey('tracking-timeline-scroll'),
        padding: const EdgeInsets.symmetric(horizontal: 16),
        child: content,
      ),
    );
  }
}

class _TrackingRow extends StatelessWidget {
  const _TrackingRow({
    super.key,
    required this.stop,
    required this.first,
    required this.last,
    required this.bus,
    required this.initial,
    required this.passed,
    required this.alert,
    required this.showOrder,
    required this.accent,
    required this.scheme,
  });
  final BoardingTimelineStop stop;
  final bool first, last, bus, initial, passed, alert, showOrder;
  final Color accent;
  final ColorScheme scheme;
  @override
  Widget build(BuildContext context) {
    final display = stop.name == null ? null : stopDisplayName(stop.name!);
    final theme = Theme.of(context);
    return DecoratedBox(
      decoration: BoxDecoration(
        color: bus ? scheme.primaryContainer : Colors.transparent,
        borderRadius: BorderRadius.circular(StopBellRadius.field),
      ),
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(
              width: 36,
              child: Column(
                children: [
                  Icon(
                    bus
                        ? Icons.directions_bus
                        : last
                        ? Icons.flag_outlined
                        : alert
                        ? Icons.notifications_outlined
                        : Icons.circle,
                    key: bus ? const ValueKey('tracking-bus-marker') : null,
                    semanticLabel: bus
                        ? (initial ? '등록 당시 차량 위치' : '최근 차량 위치')
                        : null,
                    size: bus
                        ? 28
                        : first || last || alert
                        ? 20
                        : 10,
                    color: passed || first || last ? accent : scheme.outline,
                  ),
                  if (!last)
                    Expanded(
                      child: BusRouteRailSegment(
                        color: passed && !bus ? accent : scheme.outlineVariant,
                      ),
                    ),
                ],
              ),
            ),
            Expanded(
              child: Padding(
                padding: bus
                    ? const EdgeInsets.symmetric(vertical: 6)
                    : const EdgeInsets.only(bottom: 12),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      display?.name ?? '정류장 정보 없음',
                      style: theme.textTheme.bodyLarge?.copyWith(
                        fontWeight: first || last || bus
                            ? FontWeight.bold
                            : FontWeight.normal,
                      ),
                    ),
                    if (display?.isNonstop == true)
                      Text('미정차', style: theme.textTheme.bodySmall),
                    if (first) Text('승차 정류장', style: theme.textTheme.bodySmall),
                    if (last) Text('목표 정류장', style: theme.textTheme.bodySmall),
                    if (alert)
                      Text('하차 준비 알림 정류장', style: theme.textTheme.bodySmall),
                    if (bus)
                      Text(
                        initial ? '참고용 위치예요' : '최근 위치 기준',
                        style: theme.textTheme.bodySmall,
                      ),
                    if (showOrder)
                      Text(
                        '노선 순서 ${stop.order}',
                        style: theme.textTheme.bodySmall,
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
