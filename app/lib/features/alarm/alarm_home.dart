import 'package:flutter/material.dart';

import '../transit/bus_route.dart';
import '../transit/bus_route_search_screen.dart';
import '../transit/bus_route_stop_occurrence.dart';
import '../transit/bus_route_stop_screen.dart';
import '../auth/auth_session.dart';
import '../boarding/boarding_candidate_client.dart';
import '../boarding/boarding_home.dart';
import '../boarding/boarding_session_client.dart';
import '../boarding/boarding_creation_intent.dart';
import '../notification/notification_lifecycle.dart';
import 'alarm.dart';
import 'alarm_api_client.dart';
import 'alarm_detail_screen.dart';
import 'alarm_list_screen.dart';

enum _Page { list, search, stops, detail, boarding }

class AlarmHome extends StatefulWidget {
  const AlarmHome({
    super.key,
    required this.client,
    required this.authSession,
    required this.searchRoutes,
    required this.findStops,
    this.notifications,
    this.beforeActivate,
    this.findBoardingCandidates,
    this.boardingSessionClient,
    this.boardingIntentStorage,
  });

  final AlarmClient client;
  final NotificationLifecycle? notifications;
  final Future<bool> Function(BuildContext)? beforeActivate;
  final AuthSession authSession;
  final FindBoardingCandidates? findBoardingCandidates;
  final BoardingSessionClient? boardingSessionClient;
  final BoardingIntentStorage? boardingIntentStorage;
  final Future<List<BusRoute>> Function(String query) searchRoutes;
  final Future<List<BusRouteStopOccurrence>> Function(int routeId) findStops;

  @override
  State<AlarmHome> createState() => _AlarmHomeState();
}

class _AlarmHomeState extends State<AlarmHome> {
  _Page _page = _Page.list;
  BusRoute? _route;
  int? _alarmId;
  Alarm? _createdAlarm;
  late final int _sessionGeneration;

  @override
  void initState() {
    super.initState();
    _sessionGeneration = widget.authSession.generation;
    widget.notifications?.addListener(_notificationChanged);
    WidgetsBinding.instance.addPostFrameCallback((_) => _notificationChanged());
  }

  bool get _current =>
      mounted &&
      widget.authSession.isCurrentGeneration(_sessionGeneration) &&
      !widget.authSession.isLoggingOut;

  int _navigationEpoch = 0;

  @override
  void dispose() {
    widget.notifications?.removeListener(_notificationChanged);
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
          content: const Text('하차 알림이 도착했습니다.'),
          action: SnackBarAction(
            label: '알람 보기',
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

  Future<void> _openNotification(AlarmNotification tap) async {
    final epoch = ++_navigationEpoch;
    try {
      final alarm = await widget.client.findById(tap.alarmId);
      if (!mounted || !_current || epoch != _navigationEpoch) return;
      setState(() {
        _alarmId = alarm.id;
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
    });
  }

  @override
  Widget build(BuildContext context) => switch (_page) {
    _Page.list => Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
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
                _page = _Page.detail;
              });
            },
            onCreate: () {
              if (_current) {
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
      route: _route!,
      findStops: widget.findStops,
      alarmClient: widget.client,
      onBack: () {
        if (_current) {
          setState(() {
            _navigationEpoch++;
            _page = _Page.search;
          });
        }
      },
      onShowAlarms: _showList,
      onCreated: (alarm) {
        if (!_current) return;
        setState(() {
          _navigationEpoch++;
          _alarmId = alarm.id;
          _createdAlarm = alarm;
          _page = _Page.detail;
        });
      },
    ),
    _Page.detail => AlarmDetailScreen(
      key: ValueKey((_alarmId, _navigationEpoch)),
      beforeActivate: widget.beforeActivate,
      alarmId: _alarmId!,
      createdAlarm: _createdAlarm,
      client: widget.client,
      onBack: _showList,
    ),
  };
}
