import 'package:flutter/material.dart';

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
import 'alarm_api_client.dart';
import 'alarm_creation_controller.dart';
import 'alarm_creation_intent.dart';
import 'alarm_detail_screen.dart';
import 'alarm_list_screen.dart';

enum _Page { list, search, stops, detail, boarding, boardingNotification }

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
  });

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
  _Page _page = _Page.list;
  BusRoute? _route;
  int? _alarmId, _boardingSessionId;
  late final String? _userIdentity, _loginIdentity;
  ScaffoldMessengerState? _messenger;
  Alarm? _createdAlarm;
  bool _activationUnverified = false;
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
  }

  void _authChanged() {
    if (!mounted || _current) return;
    _navigationEpoch++;
    _messenger?.clearSnackBars();
    setState(() {
      _createdAlarm = null;
      _activationUnverified = false;
      _alarmId = null;
      _boardingSessionId = null;
    });
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
    final foreground = notifications.foreground;
    notifications.foreground = null;
    if (foreground != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            foreground is BoardingSessionNotification
                ? foreground.eventType == 'DESTINATION_REACHED'
                      ? '선택한 정류장에 도달하거나 통과했어요.'
                      : '하차 준비 알림이 도착했어요.'
                : '버스 도착 알림이 도착했습니다.',
          ),
          action: SnackBarAction(
            label: foreground is BoardingSessionNotification
                ? '세션 보기'
                : '알람 보기',
            onPressed: () {
              if (_current) {
                notifications.openForeground(foreground, _sessionGeneration);
              }
            },
          ),
        ),
      );
    }
    final tap = notifications.takeTap();
    if (tap != null) _openNotification(tap);
  }

  Future<void> _openNotification(NotificationHint tap) async {
    final epoch = ++_navigationEpoch;
    if (tap is BoardingSessionNotification) {
      setState(() {
        _boardingSessionId = tap.boardingSessionId;
        _createdAlarm = null;
        _activationUnverified = false;
        _page = _Page.boardingNotification;
      });
      return;
    }
    if (tap is! AlarmNotification) return;
    try {
      final alarm = await widget.client.findById(tap.alarmId);
      if (!mounted || !_current || epoch != _navigationEpoch) return;
      if (alarm.id != tap.alarmId) throw const AlarmApiException();
      setState(() {
        _alarmId = alarm.id;
        _activationUnverified = false;
        _createdAlarm = alarm;
        _page = _Page.detail;
      });
    } catch (_) {
      if (!mounted || !_current || epoch != _navigationEpoch) return;
      _showList();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('알람을 열 수 없습니다. 삭제되었거나 접근할 수 없는 알람일 수 있습니다.'),
        ),
      );
    }
  }

  void _showList() {
    if (!_current) return;
    _navigationEpoch++;
    setState(() {
      _page = _Page.list;
      _route = null;
      _alarmId = null;
      _createdAlarm = null;
      _activationUnverified = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    final epoch = _navigationEpoch;
    return switch (_page) {
      _Page.boardingNotification => BoardingNotificationScreen(
        key: ValueKey(('boarding', epoch)),
        auth: widget.authSession,
        client: widget.boardingSessionClient,
        sessionId: _boardingSessionId!,
        onBack: _showList,
        isNavigationCurrent: () => _current && epoch == _navigationEpoch,
      ),
      _Page.list => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_creation.message != null ||
              _creation.pending != null ||
              _creation.result != null)
            _creationRecovery(),
          if (widget.findBoardingCandidates != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: OutlinedButton(
                onPressed: () {
                  if (!_current) return;
                  setState(() {
                    _navigationEpoch++;
                    _page = _Page.boarding;
                  });
                },
                child: const Text('하차 알림 · 차량 후보 찾기'),
              ),
            ),
          const Text('버스 도착 알림', textAlign: TextAlign.center),
          Expanded(
            child: AlarmListScreen(
              findAll: widget.client.findAll,
              onSelect: (id) {
                if (!_current) return;
                setState(() {
                  _navigationEpoch++;
                  _alarmId = id;
                  _createdAlarm = null;
                  _activationUnverified = false;
                  _page = _Page.detail;
                });
              },
              onCreate: () {
                if (_current && !_creation.blocked) {
                  setState(() {
                    _navigationEpoch++;
                    _page = _Page.search;
                  });
                }
              },
            ),
          ),
        ],
      ),
      _Page.boarding => BoardingHome(
        authSession: widget.authSession,
        searchRoutes: widget.searchRoutes,
        findStops: widget.findStops,
        findCandidates: widget.findBoardingCandidates!,
        onBack: _showList,
        sessionClient: widget.boardingSessionClient,
        intentStorage: widget.boardingIntentStorage,
        beforeCreate: widget.beforeBoardingCreate,
      ),
      _Page.search => Column(
        children: [
          TextButton(onPressed: _showList, child: const Text('알람 목록으로')),
          Expanded(
            child: BusRouteSearchScreen(
              search: widget.searchRoutes,
              onSelect: (route) {
                if (!_current) return;
                setState(() {
                  _navigationEpoch++;
                  _route = route;
                  _page = _Page.stops;
                });
              },
            ),
          ),
        ],
      ),
      _Page.stops => BusRouteStopScreen(
        key: ValueKey(('stops', epoch)),
        route: _route!,
        findStops: widget.findStops,
        alarmClient: widget.client,
        creationController: _creation,
        beforeActivate: widget.beforeActivate,
        isRequestCurrent: () =>
            _current && epoch == _navigationEpoch && _page == _Page.stops,
        onBack: () {
          if (_current) {
            setState(() {
              _navigationEpoch++;
              _page = _Page.search;
            });
          }
        },
        onShowAlarms: _showList,
        onCreated: (alarm, {bool activationUnverified = false}) {
          if (!_current || epoch != _navigationEpoch) return;
          setState(() {
            _navigationEpoch++;
            _alarmId = alarm.id;
            _createdAlarm = alarm;
            _activationUnverified = activationUnverified;
            _page = _Page.detail;
          });
        },
      ),
      _Page.detail => AlarmDetailScreen(
        key: ValueKey((_alarmId, _navigationEpoch)),
        beforeActivate: widget.beforeActivate,
        activationUnverified: _activationUnverified,
        isRequestCurrent: () =>
            _current && epoch == _navigationEpoch && _page == _Page.detail,
        alarmId: _alarmId!,
        createdAlarm: _createdAlarm,
        client: widget.client,
        onBack: _showList,
      ),
    };
  }

  Widget _creationRecovery() => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 16),
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
          const Text('알람 생성 요청이 서버에 반영됐을 수 있습니다.'),
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
              '요청 시각을 신뢰할 수 없거나 보존 기간이 지났습니다. 새로 생성하면 알람이 중복될 수 있습니다.',
            ),
            OutlinedButton(
              onPressed: _creation.busy ? null : _confirmNewCreation,
              child: const Text('새 알람 생성 선택'),
            ),
          ],
        ],
        if (_creation.result != null)
          OutlinedButton(
            onPressed: () {
              final alarm = _creation.result!;
              setState(() {
                _navigationEpoch++;
                _alarmId = alarm.id;
                _createdAlarm = alarm;
                _activationUnverified = false;
                _page = _Page.detail;
              });
            },
            child: const Text('확인한 알람 보기'),
          ),
      ],
    ),
  );

  Future<void> _confirmNewCreation() async {
    final epoch = _navigationEpoch;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('새 알람 생성'),
        content: const Text('이전 요청이 이미 생성되었을 수 있어 새 알람이 중복될 수 있습니다. 계속할까요?'),
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
