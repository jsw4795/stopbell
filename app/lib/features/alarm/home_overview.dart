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
          _SectionTitle('내 도착 알람', count: _alarmCount),
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
      subtitle: '정류장 도착 알림',
      background: colors.primaryContainer,
      accent: colors.primary,
      onTap: widget.onCreateArrival,
    );
    final boarding = widget.onCreateBoarding;
    if (boarding == null) return arrival;
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(child: arrival),
          const SizedBox(width: StopBellSpacing.md),
          Expanded(
            child: _CreateCard(
              icon: Icons.directions_bus_outlined,
              title: '하차 알람',
              subtitle: '버스 하차 알림',
              background: colors.secondaryContainer,
              accent: colors.onSecondaryContainer,
              onTap: boarding,
            ),
          ),
        ],
      ),
    );
  }

  List<Widget> _sessionSections(BuildContext context) {
    switch (_sessionState) {
      case _LoadState.loading:
        return const [
          SizedBox(height: StopBellSpacing.xl),
          _SectionTitle('진행 중인 하차 알람'),
          SizedBox(height: StopBellSpacing.sm),
          _InlineProgress('하차 알람 확인 중...'),
        ];
      case _LoadState.error:
        return [
          const SizedBox(height: StopBellSpacing.xl),
          const _SectionTitle('진행 중인 하차 알람'),
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
          _SectionTitle(current != null ? '진행 중인 하차 알람' : '최근 종료된 하차 알람'),
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
          _InlineError(message: '알람 목록을 불러오지 못했습니다.', onRetry: _loadAlarms),
        ];
      case _LoadState.ready:
        if (_alarms.isEmpty) {
          return [
            const _EmptyNotice(
              icon: Icons.notifications_none_outlined,
              message: '아직 만든 알람이 없습니다.',
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
  const _SectionTitle(this.title, {this.count});
  final String title;
  final int? count;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Semantics(
      header: true,
      child: Row(
        children: [
          Flexible(child: Text(title, style: theme.textTheme.titleLarge)),
          if (count != null) ...[
            const SizedBox(width: StopBellSpacing.sm),
            Text(
              '$count',
              style: theme.textTheme.titleMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
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
    required this.background,
    required this.accent,
    required this.onTap,
  });

  final IconData icon;
  final String title, subtitle;
  final Color background, accent;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Semantics(
      button: true,
      label: '$title 만들기',
      excludeSemantics: true,
      child: Material(
        color: background,
        borderRadius: BorderRadius.circular(StopBellRadius.card),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(StopBellRadius.card),
          child: Padding(
            padding: const EdgeInsets.all(StopBellSpacing.lg),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(icon, color: accent, size: 28),
                const SizedBox(height: StopBellSpacing.lg),
                Text(
                  title,
                  style: theme.textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: StopBellSpacing.xs),
                Text(
                  subtitle,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
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

class _ListCard extends StatelessWidget {
  const _ListCard({
    required this.leading,
    required this.children,
    required this.onTap,
  });

  final Widget leading;
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
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 72),
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: StopBellSpacing.lg,
                vertical: StopBellSpacing.md,
              ),
              child: Row(
                children: [
                  leading,
                  const SizedBox(width: StopBellSpacing.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: children,
                    ),
                  ),
                  if (onTap != null) ...[
                    const SizedBox(width: StopBellSpacing.sm),
                    Icon(Icons.chevron_right, color: colors.onSurfaceVariant),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _LeadingIcon extends StatelessWidget {
  const _LeadingIcon(this.icon, this.color);
  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    width: 40,
    height: 40,
    decoration: BoxDecoration(
      color: color.withValues(alpha: .12),
      borderRadius: BorderRadius.circular(StopBellRadius.button),
    ),
    child: Icon(icon, color: color, size: 22),
  );
}

class _StatusChip extends StatelessWidget {
  const _StatusChip(this.label, this.color);
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(
      horizontal: StopBellSpacing.sm,
      vertical: 2,
    ),
    decoration: BoxDecoration(
      color: color.withValues(alpha: .12),
      borderRadius: BorderRadius.circular(StopBellSpacing.sm),
    ),
    child: Text(
      label,
      style: Theme.of(context).textTheme.labelSmall?.copyWith(color: color),
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
    final statusColor = alarm.status == AlarmStatus.inactive
        ? theme.colorScheme.onSurfaceVariant
        : semantic.activeTracking;
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
      leading: _LeadingIcon(
        Icons.place_outlined,
        routeColor ?? theme.colorScheme.primary,
      ),
      children: [
        Text(
          alarm.routeNumber,
          style: theme.textTheme.labelLarge?.copyWith(
            color: routeColor ?? theme.colorScheme.primary,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 2),
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
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
        if (endpoints != null)
          Text(
            endpoints,
            style: theme.textTheme.labelMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        const SizedBox(height: StopBellSpacing.xs),
        _StatusChip(alarm.status.shortLabel, statusColor),
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
    final (label, color) = switch (session.status) {
      BoardingSessionStatus.tracking => ('추적 중', semantic.activeTracking),
      BoardingSessionStatus.suspended => ('보류', semantic.heldTracking),
      BoardingSessionStatus.completed => (
        '종료',
        theme.colorScheme.onSurfaceVariant,
      ),
      BoardingSessionStatus.cancelled => (
        '취소',
        theme.colorScheme.onSurfaceVariant,
      ),
      BoardingSessionStatus.expired => (
        '만료',
        theme.colorScheme.onSurfaceVariant,
      ),
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
    return _ListCard(
      onTap: onTap,
      leading: _LeadingIcon(
        Icons.directions_bus_outlined,
        routeColor ?? theme.colorScheme.onSurfaceVariant,
      ),
      children: [
        Text(
          session.routeNumber,
          style: theme.textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.w700,
            color: routeColor,
          ),
        ),
        const SizedBox(height: 2),
        Text(
          '${session.vehicleNumber} · ${session.targetName} 하차',
          style: theme.textTheme.bodySmall,
        ),
        const SizedBox(height: StopBellSpacing.xs),
        Wrap(
          spacing: StopBellSpacing.sm,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            _StatusChip(label, color),
            if (detail != null)
              Text(
                detail,
                style: theme.textTheme.labelMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
          ],
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
            : '${session.remainingOccurrenceGap}개 지점 남음',
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
