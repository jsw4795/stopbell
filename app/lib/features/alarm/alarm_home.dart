import '../../shared/stopbell_home_button.dart';

import 'package:flutter/material.dart';

import '../../core/theme/stopbell_spacing.dart';

import '../transit/bus_route.dart';
import '../transit/bus_route_search_screen.dart';
import '../transit/bus_route_stop_occurrence.dart';
import '../transit/bus_route_stop_screen.dart';
import '../auth/auth_session.dart';
import '../boarding/boarding_candidate_client.dart';
import '../boarding/boarding_home.dart';
import '../boarding/boarding_notification_screen.dart';
import '../boarding/boarding_session_client.dart';
import '../boarding/boarding_creation_intent.dart';
import '../notification/notification_lifecycle.dart';
import 'alarm.dart';
import 'alarm_approach.dart';
import 'alarm_api_client.dart';
import 'alarm_creation_controller.dart';
import 'alarm_creation_intent.dart';
import 'alarm_detail_screen.dart';
import 'home_overview.dart';

class AlarmHome extends StatefulWidget {
  const AlarmHome({
    super.key,
    required this.client,
    required this.authSession,
    required this.searchRoutes,
    required this.findStops,
    this.notifications,
    this.beforeActivate,
    this.beforeBoardingCreate,
    this.findBoardingCandidates,
    this.boardingSessionClient,
    this.boardingIntentStorage,
    this.alarmIntentStorage,
    this.onOpenSettings,
  });

  final VoidCallback? onOpenSettings;
  final AlarmClient client;
  final NotificationLifecycle? notifications;
  final Future<bool> Function(BuildContext)? beforeActivate,
      beforeBoardingCreate;
  final AuthSession authSession;
  final FindBoardingCandidates? findBoardingCandidates;
  final BoardingSessionClient? boardingSessionClient;
  final BoardingIntentStorage? boardingIntentStorage;
  final AlarmIntentStorage? alarmIntentStorage;

  final Future<List<BusRoute>> Function(String query) searchRoutes;
  final Future<List<BusRouteStopOccurrence>> Function(int routeId) findStops;

  @override
  State<AlarmHome> createState() => _AlarmHomeState();
}

class _AlarmHomeState extends State<AlarmHome> {
  /// 생성 복구 후 '확인한 알람 보기' 바로가기. 기능은 유지하되 화면에는
  /// 표시하지 않는다(정상 생성 후에도 남는 문제). 복구된 알람은 목록에서 연다.
  static const _showRecoveredAlarmShortcut = false;

  int _homeRevision = 0;
  bool _homeVisible = true;
  late final String? _userIdentity, _loginIdentity;
  ScaffoldMessengerState? _messenger;
  AlarmClient? _approachClient;
  AlarmApproachLoader? _approachLoader;

  FindAlarmApproach? get _findApproach {
    final client = widget.client;
    if (!identical(client, _approachClient)) {
      _approachClient = client;
      _approachLoader = client is AlarmApiClient
          ? client.createApproachLoader()
          : null;
    }
    return _approachLoader?.find;
  }

  late final int _sessionGeneration;
  late final AlarmCreationController _creation;

  @override
  void initState() {
    super.initState();
    _sessionGeneration = widget.authSession.generation;
    _userIdentity = widget.authSession.userIdentity;
    _loginIdentity = widget.authSession.sessionIdentity;
    _creation = AlarmCreationController(
      widget.authSession,
      widget.client,
      widget.alarmIntentStorage ?? const SecureAlarmIntentStorage(),
    );
    _creation.addListener(_creationChanged);
    _creation.initialize();
    widget.authSession.addListener(_authChanged);
    widget.notifications?.addListener(_notificationChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) => _notificationChanged());
  }

  void _creationChanged() {
    if (_current) setState(() {});
  }

  bool get _current =>
      mounted &&
      widget.authSession.isCurrentGeneration(_sessionGeneration) &&
      !widget.authSession.isLoggingOut &&
      widget.authSession.userIdentity == _userIdentity &&
      widget.authSession.sessionIdentity == _loginIdentity;

  int _navigationEpoch = 0;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _messenger = ScaffoldMessenger.of(context);
    final visible = ModalRoute.of(context)?.isCurrent ?? true;
    if (visible && !_homeVisible) {
      _homeRevision++;
      _navigationEpoch++;
    }
    _homeVisible = visible;
  }

  void _authChanged() {
    if (!mounted || _current) return;
    _navigationEpoch++;
    _messenger?.clearSnackBars();
    Navigator.of(context).popUntil((route) => route.isFirst);
  }

  @override
  void dispose() {
    widget.authSession.removeListener(_authChanged);
    widget.notifications?.removeListener(_notificationChanged);
    _creation.removeListener(_creationChanged);
    _creation.dispose();
    super.dispose();
  }

  void _notificationChanged() {
    if (!_current) return;
    final notifications = widget.notifications;
    if (notifications == null) return;
    // foreground 수신은 iOS 시스템 배너·소리로 알리므로 인앱 SnackBar는 띄우지 않고 힌트만 비운다.
    notifications.foreground = null;
    final tap = notifications.takeTap();
    if (tap != null) _openNotification(tap);
  }

  Future<void> _openNotification(NotificationHint tap) async {
    final epoch = ++_navigationEpoch;
    if (tap is BoardingSessionNotification) {
      _showList();
      _openBoardingNotification(tap.boardingSessionId);
      return;
    }
    if (tap is! AlarmNotification) return;
    try {
      final alarm = await widget.client.findById(tap.alarmId);
      if (!mounted || !_current || epoch != _navigationEpoch) return;
      if (alarm.id != tap.alarmId) throw const AlarmApiException();
      _showList();
      _openAlarm(alarm.id, createdAlarm: alarm);
    } catch (_) {
      if (!mounted || !_current || epoch != _navigationEpoch) return;
      _showList();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('알람을 열 수 없어요. 삭제되었거나 접근할 수 없는 알람일 수 있어요.'),
        ),
      );
    }
  }

  void _showList() {
    if (!_current) return;
    _navigationEpoch++;
    Navigator.of(context).popUntil((route) => route.isFirst);
    setState(() => _homeRevision++);
  }

  void _back() => Navigator.of(context).maybePop();

  void _push(WidgetBuilder builder) {
    if (!_current) return;
    _navigationEpoch++;
    Navigator.of(context).push<void>(
      MaterialPageRoute<void>(
        builder: (context) => Scaffold(
          body: SafeArea(
            child: ListenableBuilder(
              listenable: widget.authSession,
              builder: (_, _) =>
                  _current ? builder(context) : const SizedBox.shrink(),
            ),
          ),
        ),
      ),
    );
  }

  void _openAlarm(
    int id, {
    Alarm? createdAlarm,
    bool activationUnverified = false,
  }) {
    final epoch = _navigationEpoch + 1;
    _push(
      (context) => AlarmDetailScreen(
        alarmId: id,
        createdAlarm: createdAlarm,
        client: widget.client,
        beforeActivate: widget.beforeActivate,
        activationUnverified: activationUnverified,
        isRequestCurrent: () =>
            _current &&
            epoch == _navigationEpoch &&
            (ModalRoute.of(context)?.isActive ?? false),
        onBack: _back,
      ),
    );
  }

  void _openBoardingNotification(int id) {
    final epoch = _navigationEpoch + 1;
    _push(
      (context) => BoardingNotificationScreen(
        auth: widget.authSession,
        client: widget.boardingSessionClient,
        sessionId: id,
        onBack: _back,
        isNavigationCurrent: () =>
            _current &&
            epoch == _navigationEpoch &&
            (ModalRoute.of(context)?.isActive ?? false),
      ),
    );
  }

  void _openArrival() {
    if (!_current || _creation.blocked) return;
    _push(
      (context) => Column(
        children: [
          AppBar(
            primary: false,
            leading: IconButton(
              tooltip: '알람 목록으로',
              onPressed: () => Navigator.of(context).maybePop(),
              icon: const Icon(Icons.arrow_back),
            ),
            title: const Text('도착 알람 · 노선 선택'),
            actions: const [StopBellHomeButton()],
          ),
          Expanded(
            child: BusRouteSearchScreen(
              search: widget.searchRoutes,
              onSelect: _openStops,
            ),
          ),
        ],
      ),
    );
  }

  void _openStops(BusRoute route) {
    final epoch = _navigationEpoch + 1;
    _push(
      (context) => BusRouteStopScreen(
        route: route,
        findStops: widget.findStops,
        alarmClient: widget.client,
        creationController: _creation,
        beforeActivate: widget.beforeActivate,
        isRequestCurrent: () =>
            _current &&
            epoch == _navigationEpoch &&
            (ModalRoute.of(context)?.isActive ?? false),
        onBack: _back,
        onShowAlarms: _showList,
        onCreated: (alarm, {bool activationUnverified = false}) {
          if (!_current || epoch != _navigationEpoch) return;
          _showList();
          _openAlarm(
            alarm.id,
            createdAlarm: alarm,
            activationUnverified: activationUnverified,
          );
        },
      ),
    );
  }

  void _openBoarding() {
    _push(
      (context) => BoardingHome(
        authSession: widget.authSession,
        searchRoutes: widget.searchRoutes,
        findStops: widget.findStops,
        findCandidates: widget.findBoardingCandidates!,
        onBack: _back,
        sessionClient: widget.boardingSessionClient,
        intentStorage: widget.boardingIntentStorage,
        beforeCreate: widget.beforeBoardingCreate,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    final epoch = _navigationEpoch;
    return Column(
      children: [
        AppBar(
          primary: false,
          title: GestureDetector(
            key: const ValueKey('header-home'),
            behavior: HitTestBehavior.opaque,
            onTap: _showList,
            child: const Text('StopBell'),
          ),
          actions: [
            IconButton(
              tooltip: '설정',
              icon: const Icon(Icons.settings_outlined),
              onPressed: widget.onOpenSettings,
            ),
          ],
        ),
        Expanded(
          child: HomeOverview(
            key: ValueKey(('home', _homeRevision, _creation.result?.id)),
            header: _creation.message != null || _creation.pending != null
                ? _creationRecovery()
                : null,
            findAlarms: () => _current && _homeVisible
                ? widget.client.findAll()
                : Future.value(const <Alarm>[]),
            findApproach: _findApproach,
            findSessions: widget.boardingSessionClient == null
                ? null
                : () => widget.boardingSessionClient!.findOverview(
                    generation: _sessionGeneration,
                    current: () =>
                        _current && epoch == _navigationEpoch && _homeVisible,
                  ),
            refreshSession: widget.boardingSessionClient == null
                ? null
                : (id) => widget.boardingSessionClient!.find(
                    id,
                    generation: _sessionGeneration,
                    current: () =>
                        _current && epoch == _navigationEpoch && _homeVisible,
                  ),
            onCreateArrival: _openArrival,
            onCreateBoarding: widget.findBoardingCandidates == null
                ? null
                : _openBoarding,
            onOpenCurrentSession: widget.findBoardingCandidates == null
                ? null
                : _openBoarding,
            onOpenEndedSession: _openBoardingNotification,
            onOpenAlarm: _openAlarm,
          ),
        ),
      ],
    );
  }

  Widget _creationRecovery() => Card(
    margin: EdgeInsets.zero,
    child: Padding(
      padding: const EdgeInsets.all(StopBellSpacing.lg),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_creation.message != null) Text(_creation.message!),
          if (!_creation.initialized)
            OutlinedButton(
              onPressed: _creation.initialize,
              child: const Text('저장된 요청 다시 확인'),
            ),
          if (_creation.pending != null) ...[
            const Text(alarmCreationUncertainMessage),
            OutlinedButton(
              onPressed: _creation.busy
                  ? null
                  : () {
                      final epoch = _navigationEpoch;
                      _creation.recover(
                        navigationCurrent: () =>
                            _current && epoch == _navigationEpoch,
                      );
                    },
              child: const Text('원래 생성 결과 조회'),
            ),
            if (_creation.canRetry)
              OutlinedButton(
                onPressed: _creation.busy
                    ? null
                    : () {
                        final epoch = _navigationEpoch;
                        _creation.recover(
                          retry: true,
                          navigationCurrent: () =>
                              _current && epoch == _navigationEpoch,
                        );
                      },
                child: const Text('원래 요청 재시도'),
              )
            else ...[
              const Text(
                '요청 시각을 신뢰할 수 없거나 보존 기간이 지났습니다. 새로 생성하면 알람이 중복될 수 있어요.',
              ),
              OutlinedButton(
                onPressed: _creation.busy ? null : _confirmNewCreation,
                child: const Text('새 알람 생성 선택'),
              ),
            ],
          ],
          if (_showRecoveredAlarmShortcut && _creation.result != null)
            OutlinedButton(
              onPressed: () {
                final alarm = _creation.result!;
                _openAlarm(alarm.id, createdAlarm: alarm);
              },
              child: const Text('확인한 알람 보기'),
            ),
        ],
      ),
    ),
  );

  Future<void> _confirmNewCreation() async {
    final epoch = _navigationEpoch;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('새 알람 생성'),
        content: const Text('이전 요청이 이미 생성되었을 수 있어 새 알람이 중복될 수 있어요. 계속할까요?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('새로 생성'),
          ),
        ],
      ),
    );
    if (_current && epoch == _navigationEpoch && confirmed == true) {
      await _creation.discardForNewCreation();
    }
  }
}
