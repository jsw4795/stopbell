import 'package:flutter/material.dart';

import '../auth/auth_session.dart';
import '../transit/bus_route.dart';
import '../transit/bus_route_search_screen.dart';
import '../transit/bus_route_stop_occurrence.dart';
import 'boarding_candidate_client.dart';
import 'boarding_stop_screen.dart';

class BoardingHome extends StatefulWidget {
  const BoardingHome({
    super.key,
    required this.authSession,
    required this.searchRoutes,
    required this.findStops,
    required this.findCandidates,
    required this.onBack,
  });
  final AuthSession authSession;
  final Future<List<BusRoute>> Function(String) searchRoutes;
  final Future<List<BusRouteStopOccurrence>> Function(int) findStops;
  final FindBoardingCandidates findCandidates;
  final VoidCallback onBack;

  @override
  State<BoardingHome> createState() => _BoardingHomeState();
}

class _BoardingHomeState extends State<BoardingHome> {
  BusRoute? _route;
  late final int _generation;

  @override
  void initState() {
    super.initState();
    _generation = widget.authSession.generation;
    widget.authSession.addListener(_authChanged);
  }

  bool get _current =>
      mounted &&
      widget.authSession.isCurrentGeneration(_generation) &&
      !widget.authSession.isLoggingOut;

  void _authChanged() {
    if (!_current && mounted) setState(() => _route = null);
  }

  @override
  void dispose() {
    widget.authSession.removeListener(_authChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    final route = _route;
    if (route != null) {
      return BoardingStopScreen(
        key: ValueKey(route.id),
        route: route,
        authSession: widget.authSession,
        findStops: widget.findStops,
        findCandidates: widget.findCandidates,
        onBack: () {
          if (_current) setState(() => _route = null);
        },
      );
    }
    return Column(
      children: [
        TextButton(onPressed: widget.onBack, child: const Text('버스 도착 알림으로')),
        const Text('하차 알림 · 노선 선택'),
        Expanded(
          child: BusRouteSearchScreen(
            search: widget.searchRoutes,
            onSelect: (route) {
              if (_current) setState(() => _route = route);
            },
          ),
        ),
      ],
    );
  }
}
