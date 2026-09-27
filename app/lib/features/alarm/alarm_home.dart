import 'package:flutter/material.dart';

import '../transit/bus_route.dart';
import '../transit/bus_route_search_screen.dart';
import '../transit/bus_route_stop_occurrence.dart';
import '../transit/bus_route_stop_screen.dart';
import '../auth/auth_session.dart';
import 'alarm.dart';
import 'alarm_api_client.dart';
import 'alarm_detail_screen.dart';
import 'alarm_list_screen.dart';

enum _Page { list, search, stops, detail }

class AlarmHome extends StatefulWidget {
  const AlarmHome({
    super.key,
    required this.client,
    required this.authSession,
    required this.searchRoutes,
    required this.findStops,
  });

  final AlarmClient client;
  final AuthSession authSession;
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
  }

  bool get _current =>
      mounted && widget.authSession.isCurrentGeneration(_sessionGeneration);

  void _showList() {
    if (!_current) return;
    setState(() {
      _page = _Page.list;
      _route = null;
      _alarmId = null;
      _createdAlarm = null;
    });
  }

  @override
  Widget build(BuildContext context) => switch (_page) {
    _Page.list => AlarmListScreen(
      findAll: widget.client.findAll,
      onSelect: (id) {
        if (!_current) return;
        setState(() {
          _alarmId = id;
          _createdAlarm = null;
          _page = _Page.detail;
        });
      },
      onCreate: () {
        if (_current) setState(() => _page = _Page.search);
      },
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
        if (_current) setState(() => _page = _Page.search);
      },
      onShowAlarms: _showList,
      onCreated: (alarm) {
        if (!_current) return;
        setState(() {
          _alarmId = alarm.id;
          _createdAlarm = alarm;
          _page = _Page.detail;
        });
      },
    ),
    _Page.detail => AlarmDetailScreen(
      alarmId: _alarmId!,
      createdAlarm: _createdAlarm,
      client: widget.client,
      onBack: _showList,
    ),
  };
}
