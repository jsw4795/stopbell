import 'dart:async';

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_colors.dart';
import '../../core/theme/stopbell_spacing.dart';
import '../boarding/boarding_session.dart';
import '../boarding/boarding_session_auto_refresh.dart';
import '../boarding/boarding_session_client.dart';
import '../transit/bus_route.dart';
import '../transit/bus_route_presentation.dart';
import 'alarm.dart';

enum _LoadState { loading, ready, error }

/// 하차 알람 생성 카드 전용 보라 계열 강조색(시안 기준). 의미색 `busLocation`
/// (차량 위치 표시용)과 섞지 않도록 홈 생성 카드에만 사용한다.
Color _boardingCreateAccent(Brightness brightness) =>
    brightness == Brightness.dark
    ? const Color(0xffb4a5ff)
    : const Color(0xff5b47c8);

/// 검증된 공식 노선 유형색. 미분류면 null이며 기본 색을 유지한다.
Color? _routeTypeColor(
  BuildContext context,
  String routeNumber,
  String? provider,
  String? routeType,
) {
  if (provider == null || routeType == null) return null;
  final brightness = Theme.of(context).brightness;
  final presentation = BusRoutePresentation.forRoute(
    BusRoute(
      id: 1,
      routeNumber: routeNumber,
      regionName: '-',
      provider: provider,
      routeType: routeType,
    ),
    brightness,
  );
  return presentation.hasTypeColor(brightness) ? presentation.color : null;
}

/// UI-5 홈: 생성 진입 카드, 하차 추적 세션 요약, 도착 알람 목록.
///
/// 조회 전용 화면이다. 생성·활성화·취소 등 상태 변경과 생성 복구는
/// 기존 화면과 컨트롤러가 담당하며 이 위젯은 이동 콜백만 호출한다.
class HomeOverview extends StatefulWidget {
  const HomeOverview({
    super.key,
    required this.findAlarms,
    required this.onCreateArrival,
    required this.onOpenAlarm,
    this.findSessions,
    this.refreshSession,
    this.onCreateBoarding,
    this.onOpenCurrentSession,
    this.onOpenEndedSession,
    this.header,
    this.now = DateTime.now,
  });

  final Future<List<Alarm>> Function() findAlarms;
  final Future<BoardingSessionOverview> Function()? findSessions;

  /// 진행 중 세션의 정확한 ID 조회. 상세 화면 자동 갱신과 같은 주기로 사용한다.
  final Future<BoardingSession> Function(int sessionId)? refreshSession;
  final VoidCallback onCreateArrival;
  final VoidCallback? onCreateBoarding;
  final ValueChanged<int> onOpenAlarm;
  final VoidCallback? onOpenCurrentSession;
  final ValueChanged<int>? onOpenEndedSession;
  final Widget? header;
  final DateTime Function() now;

  @override
  State<HomeOverview> createState() => _HomeOverviewState();
}

class _HomeOverviewState extends State<HomeOverview>
    with WidgetsBindingObserver {
  _LoadState _alarmState = _LoadState.loading;
  _LoadState _sessionState = _LoadState.loading;
  List<Alarm> _alarms = const [];
  BoardingSessionOverview _sessions = const BoardingSessionOverview();
  int _alarmEpoch = 0, _sessionEpoch = 0;
  Timer? _sessionTimer;
  bool _foreground = true, _sessionPolling = false;

  @override
  void initState() {
    super.initState();
    final lifecycle = WidgetsBinding.instance.lifecycleState;
    _foreground = lifecycle == null || lifecycle == AppLifecycleState.resumed;
    WidgetsBinding.instance.addObserver(this);
    // 알림 탭 이동은 상위 화면의 첫 post-frame에서 처리된다. 그 뒤에 조회해
    // 이미 다른 화면으로 이동한 경우 세션 조회가 현재 문맥 검사로 생략되게 한다.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _loadAlarms();
      _loadSessions().catchError((Object _) {});
    });
  }

  @override
  void dispose() {
    _alarmEpoch++;
    _sessionEpoch++;
    _sessionTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _scheduleSessionRefresh();
    if (_foreground) _pollSession();
  }

  BoardingSession? get _trackingSession {
    final current = _sessions.current;
    return _sessionState == _LoadState.ready &&
            current?.status == BoardingSessionStatus.tracking
        ? current
        : null;
  }

  /// 표시 중인 foreground 홈에서 TRACKING 세션이 있을 때만 상세 화면과 같은
  /// 주기로 서버에 저장된 세션을 다시 읽는다. Provider 호출은 하지 않는다.
  void _scheduleSessionRefresh() {
    final active =
        mounted &&
        _foreground &&
        widget.refreshSession != null &&
        _trackingSession != null;
    if (!active) {
      _sessionTimer?.cancel();
      _sessionTimer = null;
      return;
    }
    _sessionTimer ??= Timer.periodic(
      boardingSessionAutoRefreshInterval,
      (_) => _pollSession(),
    );
  }

  Future<void> _pollSession() async {
    final refresh = widget.refreshSession;
    final tracking = _trackingSession;
    if (refresh == null ||
        tracking == null ||
        !_foreground ||
        _sessionPolling ||
        !(ModalRoute.of(context)?.isCurrent ?? true)) {
      return;
    }
    final epoch = _sessionEpoch;
    _sessionPolling = true;
    try {
      final latest = await refresh(tracking.id);
      if (!mounted || epoch != _sessionEpoch || latest.id != tracking.id) {
        return;
      }
      setState(() {
        _sessions = latest.isOpen
            ? BoardingSessionOverview(current: latest, recent: _sessions.recent)
            : BoardingSessionOverview(
                recent: [
                  latest,
                  ..._sessions.recent.where((s) => s.id != latest.id),
                ],
              );
      });
    } catch (_) {
      // 자동 갱신 실패는 마지막으로 확인한 상태를 유지하고 다음 주기에 다시 시도한다.
    } finally {
      _sessionPolling = false;
      if (mounted) _scheduleSessionRefresh();
    }
  }

  Future<void> _refresh() => Future.wait([_loadAlarms(), _loadSessions()]);

  Future<void> _loadAlarms() async {
    final epoch = ++_alarmEpoch;
    setState(() => _alarmState = _LoadState.loading);
    try {
      final alarms = await widget.findAlarms();
      if (!mounted || epoch != _alarmEpoch) return;
      setState(() {
        _alarms = alarms;
        _alarmState = _LoadState.ready;
      });
    } catch (_) {
      if (mounted && epoch == _alarmEpoch) {
        setState(() => _alarmState = _LoadState.error);
      }
    }
  }

  Future<void> _loadSessions() async {
    final find = widget.findSessions;
    if (find == null) return;
    final epoch = ++_sessionEpoch;
    setState(() => _sessionState = _LoadState.loading);
    try {
      final sessions = await find();
      if (!mounted || epoch != _sessionEpoch) return;
      setState(() {
        _sessions = sessions;
        _sessionState = _LoadState.ready;
      });
    } catch (_) {
      if (mounted && epoch == _sessionEpoch) {
        setState(() => _sessionState = _LoadState.error);
      }
    } finally {
      if (mounted) _scheduleSessionRefresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: _refresh,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(
          StopBellSpacing.screenPadding,
          StopBellSpacing.sm,
          StopBellSpacing.screenPadding,
          StopBellSpacing.xl,
        ),
        children: [
          if (widget.header != null) ...[
            widget.header!,
            const SizedBox(height: StopBellSpacing.md),
          ],
          _createCards(context),
          if (widget.findSessions != null) ..._sessionSections(context),
          const SizedBox(height: StopBellSpacing.xl),
          _SectionTitle(
            '내 도착 알람',
            icon: Icons.notifications_none_outlined,
            count: _alarmCount,
          ),
          const SizedBox(height: StopBellSpacing.sm),
          ..._alarmSection(context),
        ],
      ),
    );
  }

  int? get _alarmCount => _alarmState == _LoadState.ready && _alarms.isNotEmpty
      ? _alarms.length
      : null;

  Widget _createCards(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final arrival = _CreateCard(
      icon: Icons.notifications_active_outlined,
      title: '도착 알람',
      subtitle: '정류장에 도착하면 알려드려요',
      accent: colors.primary,
      onTap: widget.onCreateArrival,
    );
    final boarding = widget.onCreateBoarding;
    if (boarding == null) return arrival;
    final boardingCard = _CreateCard(
      icon: Icons.directions_bus_outlined,
      title: '하차 알람',
      subtitle: '내릴 정류장 전에 알려드려요',
      accent: _boardingCreateAccent(Theme.of(context).brightness),
      onTap: boarding,
    );
    return LayoutBuilder(
      builder: (context, constraints) {
        // 큰 글자나 좁은 화면에서는 두 카드를 세로로 쌓아 줄바꿈·넘침을 막는다.
        final scale = MediaQuery.textScalerOf(context).scale(16) / 16;
        if (constraints.maxWidth / scale < 360) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              arrival,
              const SizedBox(height: StopBellSpacing.sm),
              boardingCard,
            ],
          );
        }
        return IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(child: arrival),
              const SizedBox(width: StopBellSpacing.md),
              Expanded(child: boardingCard),
            ],
          ),
        );
      },
    );
  }

  List<Widget> _sessionSections(BuildContext context) {
    switch (_sessionState) {
      case _LoadState.loading:
        return const [
          SizedBox(height: StopBellSpacing.xl),
          _SectionTitle('진행 중인 하차 알람', icon: Icons.directions_bus_outlined),
          SizedBox(height: StopBellSpacing.sm),
          _InlineProgress('하차 알람 확인 중...'),
        ];
      case _LoadState.error:
        return [
          const SizedBox(height: StopBellSpacing.xl),
          const _SectionTitle(
            '진행 중인 하차 알람',
            icon: Icons.directions_bus_outlined,
          ),
          const SizedBox(height: StopBellSpacing.sm),
          _InlineError(message: '하차 알람 상태를 불러오지 못했어요.', onRetry: _loadSessions),
        ];
      case _LoadState.ready:
        // 진행 중 세션이 있으면 그것만, 없으면 가장 최근 종료 세션 하나만 보여준다.
        final current = _sessions.current;
        final ended = _sessions.recent.isEmpty ? null : _sessions.recent.first;
        final session = current ?? ended;
        if (session == null) return const [];
        return [
          const SizedBox(height: StopBellSpacing.xl),
          _SectionTitle(
            current != null ? '진행 중인 하차 알람' : '최근 종료된 하차 알람',
            icon: Icons.directions_bus_outlined,
          ),
          const SizedBox(height: StopBellSpacing.sm),
          _SessionCard(
            key: ValueKey(('session', session.id)),
            session: session,
            now: widget.now,
            onTap: current != null
                ? widget.onOpenCurrentSession
                : widget.onOpenEndedSession == null
                ? null
                : () => widget.onOpenEndedSession!(session.id),
          ),
        ];
    }
  }

  List<Widget> _alarmSection(BuildContext context) {
    switch (_alarmState) {
      case _LoadState.loading:
        return const [_InlineProgress('알람 목록 조회 중...')];
      case _LoadState.error:
        return [
          _InlineError(message: '알람 목록을 불러오지 못했어요.', onRetry: _loadAlarms),
        ];
      case _LoadState.ready:
        if (_alarms.isEmpty) {
          return [
            const _EmptyNotice(
              icon: Icons.notifications_none_outlined,
              message: '아직 만든 알람이 없어요.',
              detail: '도착 알람 카드에서 노선과 정류장을 선택해 만들 수 있어요.',
            ),
          ];
        }
        return [
          for (final alarm in _alarms)
            Padding(
              padding: const EdgeInsets.only(bottom: StopBellSpacing.sm),
              child: _AlarmCard(
                key: ValueKey(alarm.id),
                alarm: alarm,
                onTap: () => widget.onOpenAlarm(alarm.id),
              ),
            ),
        ];
    }
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.title, {required this.icon, this.count});
  final String title;
  final IconData icon;
  final int? count;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Semantics(
      header: true,
      child: Row(
        children: [
          Icon(icon, size: 22, color: theme.colorScheme.primary),
          const SizedBox(width: StopBellSpacing.sm),
          Flexible(child: Text(title, style: theme.textTheme.titleLarge)),
          if (count != null) ...[
            const SizedBox(width: StopBellSpacing.sm),
            Container(
              padding: const EdgeInsets.symmetric(
                horizontal: StopBellSpacing.sm,
                vertical: 2,
              ),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(StopBellSpacing.sm),
              ),
              child: Text(
                '$count개',
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _CreateCard extends StatelessWidget {
  const _CreateCard({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.accent,
    required this.onTap,
  });

  final IconData icon;
  final String title, subtitle;
  final Color accent;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Semantics(
      button: true,
      label: '$title 만들기',
      excludeSemantics: true,
      child: Material(
        color: Color.alphaBlend(accent.withValues(alpha: .14), colors.surface),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(StopBellRadius.card),
          side: BorderSide(color: accent.withValues(alpha: .30)),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(StopBellSpacing.md),
            child: Row(
              children: [
                Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: accent.withValues(alpha: .26),
                    borderRadius: BorderRadius.circular(StopBellRadius.button),
                  ),
                  child: Icon(icon, color: accent, size: 20),
                ),
                const SizedBox(width: StopBellSpacing.sm),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        subtitle,
                        style: theme.textTheme.labelMedium?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                Icon(
                  Icons.chevron_right,
                  size: 20,
                  color: colors.onSurfaceVariant,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 왼쪽 노선 유형색 띠 + 본문 + 이동 화살표. 큰 아이콘은 두지 않는다.
class _ListCard extends StatelessWidget {
  const _ListCard({
    required this.accent,
    required this.children,
    required this.onTap,
  });

  final Color accent;
  final List<Widget> children;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      button: onTap != null,
      child: Material(
        color: colors.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(StopBellRadius.card),
          side: BorderSide(color: colors.outlineVariant),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Container(
                  key: const ValueKey('route-accent'),
                  width: 4,
                  color: accent,
                ),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(
                      StopBellSpacing.lg,
                      StopBellSpacing.md,
                      StopBellSpacing.sm,
                      StopBellSpacing.md,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: children,
                    ),
                  ),
                ),
                if (onTap != null)
                  Padding(
                    padding: const EdgeInsets.only(right: StopBellSpacing.md),
                    child: Icon(
                      Icons.chevron_right,
                      color: colors.onSurfaceVariant,
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 버스 번호와 오른쪽 상태 칩.
class _CardHeader extends StatelessWidget {
  const _CardHeader({
    required this.routeNumber,
    required this.routeColor,
    required this.status,
  });
  final String routeNumber;
  final Color? routeColor;
  final Widget status;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // 공간이 부족하면 상태 칩이 번호 아래로 내려가도록 Wrap을 사용한다.
    return SizedBox(
      width: double.infinity,
      child: Wrap(
        alignment: WrapAlignment.spaceBetween,
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: StopBellSpacing.sm,
        runSpacing: StopBellSpacing.xs,
        children: [
          Text(
            routeNumber,
            style: theme.textTheme.headlineSmall?.copyWith(
              fontWeight: FontWeight.w700,
              color: routeColor,
            ),
          ),
          status,
        ],
      ),
    );
  }
}

/// 작은 아이콘 + 보조 정보 한 줄.
class _InfoLine extends StatelessWidget {
  const _InfoLine({required this.icon, required this.child});
  final IconData icon;
  final Widget child;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: StopBellSpacing.xs),
    child: Row(
      children: [
        Icon(
          icon,
          size: 16,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
        const SizedBox(width: StopBellSpacing.sm),
        Expanded(child: child),
      ],
    ),
  );
}

class _StatusChip extends StatelessWidget {
  const _StatusChip(this.label, this.color, {this.icon, this.dot = false});
  final String label;
  final Color color;
  final IconData? icon;
  final bool dot;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(
      horizontal: StopBellSpacing.sm,
      vertical: 3,
    ),
    decoration: BoxDecoration(
      color: color.withValues(alpha: .14),
      borderRadius: BorderRadius.circular(StopBellRadius.button),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (dot)
          Container(
            width: 7,
            height: 7,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          )
        else if (icon != null)
          Icon(icon, size: 14, color: color),
        if (dot || icon != null) const SizedBox(width: 6),
        Flexible(
          child: Text(
            label,
            style: Theme.of(context).textTheme.labelSmall
                ?.copyWith(color: color),
          ),
        ),
      ],
    ),
  );
}

class _AlarmCard extends StatelessWidget {
  const _AlarmCard({super.key, required this.alarm, required this.onTap});
  final Alarm alarm;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final semantic = StopBellColors.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    final (statusColor, statusIcon) = switch (alarm.status) {
      AlarmStatus.inactive => (muted, Icons.notifications_off_outlined),
      AlarmStatus.active => (
        semantic.activeTracking,
        Icons.notifications_active_outlined,
      ),
      AlarmStatus.followUp => (semantic.activeTracking, Icons.near_me_outlined),
    };
    final endpoints = alarm.startStopName != null && alarm.endStopName != null
        ? '${alarm.startStopName} → ${alarm.endStopName}'
        : null;
    final routeColor = _routeTypeColor(
      context,
      alarm.routeNumber,
      alarm.provider,
      alarm.routeType,
    );
    return _ListCard(
      onTap: onTap,
      accent: routeColor ?? theme.colorScheme.outlineVariant,
      children: [
        _CardHeader(
          routeNumber: alarm.routeNumber,
          routeColor: routeColor,
          status: _StatusChip(
            alarm.status.shortLabel,
            statusColor,
            icon: statusIcon,
          ),
        ),
        const SizedBox(height: StopBellSpacing.xs),
        Text.rich(
          TextSpan(
            children: [
              TextSpan(
                text: alarm.stopName,
                style: theme.textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
              TextSpan(
                text: ' 도착',
                style: theme.textTheme.bodyMedium?.copyWith(color: muted),
              ),
            ],
          ),
        ),
        if (endpoints != null)
          _InfoLine(
            icon: Icons.swap_horiz,
            child: Text(
              endpoints,
              style: theme.textTheme.bodySmall?.copyWith(color: muted),
            ),
          ),
      ],
    );
  }
}

class _SessionCard extends StatelessWidget {
  const _SessionCard({
    super.key,
    required this.session,
    required this.now,
    required this.onTap,
  });
  final BoardingSession session;
  final DateTime Function() now;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final semantic = StopBellColors.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    final (label, color) = switch (session.status) {
      BoardingSessionStatus.tracking => ('추적 중', semantic.activeTracking),
      BoardingSessionStatus.suspended => ('보류', semantic.heldTracking),
      BoardingSessionStatus.completed => ('종료', muted),
      BoardingSessionStatus.cancelled => ('취소', muted),
      BoardingSessionStatus.expired => ('만료', muted),
      BoardingSessionStatus.unknown => ('확인 필요', semantic.warning),
    };
    final ended = session.endedAt;
    final detail = session.isOpen
        ? _openDetail(session)
        : ended == null
        ? null
        : _endedAgo(now().toUtc().difference(ended.toUtc()));
    final routeColor = _routeTypeColor(
      context,
      session.routeNumber,
      session.timeline?.provider,
      session.timeline?.routeType,
    );
    final small = theme.textTheme.bodySmall?.copyWith(color: muted);
    return _ListCard(
      onTap: onTap,
      accent: routeColor ?? theme.colorScheme.outlineVariant,
      children: [
        _CardHeader(
          routeNumber: session.routeNumber,
          routeColor: routeColor,
          status: _StatusChip(label, color, dot: session.isOpen),
        ),
        const SizedBox(height: StopBellSpacing.xs),
        _InfoLine(
          icon: Icons.place_outlined,
          child: Text(
            '${session.targetName} 하차',
            style: theme.textTheme.titleSmall?.copyWith(
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
        _InfoLine(
          icon: Icons.directions_car_outlined,
          child: Text.rich(
            TextSpan(
              children: [
                TextSpan(text: session.vehicleNumber),
                if (detail != null) TextSpan(text: '  ·  $detail'),
              ],
            ),
            style: small,
          ),
        ),
      ],
    );
  }

  static String? _openDetail(BoardingSession session) {
    if (session.status == BoardingSessionStatus.suspended) {
      return session.canReconfirm ? '차량 재확인 필요' : null;
    }
    return switch (session.evaluationStatus) {
      BoardingEvaluation.ready =>
        session.remainingOccurrenceGap == null
            ? null
            : '${session.remainingOccurrenceGap}개 정류장 남음',
      BoardingEvaluation.verificationPending => '차량 위치 확인 중',
      BoardingEvaluation.recoveryPending => '진행 정보 다시 확인 중',
      _ => null,
    };
  }

  static String _endedAgo(Duration elapsed) {
    if (elapsed.inMinutes < 1) return '방금 종료';
    if (elapsed.inHours < 1) return '${elapsed.inMinutes}분 전 종료';
    return '${elapsed.inHours}시간 전 종료';
  }
}

class _InlineProgress extends StatelessWidget {
  const _InlineProgress(this.message);
  final String message;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: StopBellSpacing.lg),
    child: Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        const SizedBox.square(
          dimension: 20,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
        const SizedBox(width: StopBellSpacing.md),
        Flexible(child: Text(message)),
      ],
    ),
  );
}

class _InlineError extends StatelessWidget {
  const _InlineError({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(StopBellSpacing.lg),
      decoration: BoxDecoration(
        color: colors.errorContainer,
        borderRadius: BorderRadius.circular(StopBellRadius.card),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline, color: colors.onErrorContainer),
          const SizedBox(width: StopBellSpacing.md),
          Expanded(child: Text(message)),
          TextButton(onPressed: onRetry, child: const Text('다시 시도')),
        ],
      ),
    );
  }
}

class _EmptyNotice extends StatelessWidget {
  const _EmptyNotice({
    required this.icon,
    required this.message,
    required this.detail,
  });
  final IconData icon;
  final String message, detail;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: StopBellSpacing.lg,
        vertical: StopBellSpacing.xl,
      ),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainer,
        borderRadius: BorderRadius.circular(StopBellRadius.card),
      ),
      child: Column(
        children: [
          Icon(icon, color: theme.colorScheme.onSurfaceVariant, size: 32),
          const SizedBox(height: StopBellSpacing.sm),
          Text(message, style: theme.textTheme.bodyLarge),
          const SizedBox(height: StopBellSpacing.xs),
          Text(
            detail,
            textAlign: TextAlign.center,
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}
