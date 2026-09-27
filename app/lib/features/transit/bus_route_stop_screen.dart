import 'package:flutter/material.dart';

import 'bus_route.dart';
import 'bus_route_stop_occurrence.dart';

enum _StopState { loading, success, empty, error }

class BusRouteStopScreen extends StatefulWidget {
  const BusRouteStopScreen({
    super.key,
    required this.route,
    required this.findStops,
    required this.onBack,
  });

  final BusRoute route;
  final Future<List<BusRouteStopOccurrence>> Function(int routeId) findStops;
  final VoidCallback onBack;

  @override
  State<BusRouteStopScreen> createState() => _BusRouteStopScreenState();
}

class _BusRouteStopScreenState extends State<BusRouteStopScreen> {
  _StopState _state = _StopState.loading;
  List<BusRouteStopOccurrence> _stops = const [];
  int? _selectedOccurrenceId;
  bool _notifyOneStopBefore = false;
  bool _notifyOneStopAfter = false;
  int _requestGeneration = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant BusRouteStopScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.route.id != widget.route.id) _load();
  }

  @override
  void dispose() {
    _requestGeneration++;
    super.dispose();
  }

  Future<void> _load() async {
    final request = ++_requestGeneration;
    final routeId = widget.route.id;
    setState(() {
      _state = _StopState.loading;
      _stops = const [];
      _selectedOccurrenceId = null;
      _notifyOneStopBefore = false;
      _notifyOneStopAfter = false;
    });
    try {
      final stops = await widget.findStops(routeId);
      if (!mounted || request != _requestGeneration) return;
      setState(() {
        _stops = stops;
        _state = stops.isEmpty ? _StopState.empty : _StopState.success;
      });
    } catch (_) {
      if (mounted && request == _requestGeneration) {
        setState(() => _state = _StopState.error);
      }
    }
  }

  void _select(BusRouteStopOccurrence occurrence) {
    setState(() {
      _selectedOccurrenceId = occurrence.id;
      _notifyOneStopBefore = false;
      _notifyOneStopAfter = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final selected = _stops.where((stop) => stop.id == _selectedOccurrenceId);
    final target = selected.isEmpty ? null : selected.first;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ListTile(
          leading: IconButton(
            tooltip: '노선 다시 선택',
            icon: const Icon(Icons.arrow_back),
            onPressed: widget.onBack,
          ),
          title: Text(widget.route.routeNumber),
          subtitle: Text(widget.route.regionName),
        ),
        Expanded(child: _buildStops()),
        if (target != null) ...[
          const Divider(),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Text('선택한 정류장: ${target.name} · 순서 ${target.order}'),
          ),
          SwitchListTile(
            title: const Text('한 정거장 전 알림'),
            value: _notifyOneStopBefore,
            onChanged: target.canNotifyOneStopBefore
                ? (value) => setState(() => _notifyOneStopBefore = value)
                : null,
          ),
          SwitchListTile(
            title: const Text('한 정거장 후 알림'),
            value: _notifyOneStopAfter,
            onChanged: target.canNotifyOneStopAfter
                ? (value) => setState(() => _notifyOneStopAfter = value)
                : null,
          ),
        ],
      ],
    );
  }

  Widget _buildStops() {
    switch (_state) {
      case _StopState.loading:
        return const Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 12),
              Text('정류장 조회 중...'),
            ],
          ),
        );
      case _StopState.empty:
        return const Center(child: Text('정류장 정보가 없습니다.'));
      case _StopState.error:
        return Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('정류장 정보를 불러오지 못했습니다.'),
              const SizedBox(height: 12),
              OutlinedButton(onPressed: _load, child: const Text('다시 시도')),
            ],
          ),
        );
      case _StopState.success:
        return ListView.builder(
          itemCount: _stops.length,
          itemBuilder: (context, index) {
            final stop = _stops[index];
            final isSelected = stop.id == _selectedOccurrenceId;
            return ListTile(
              key: ValueKey(stop.id),
              title: Text(stop.name),
              subtitle: Text('순서 ${stop.order}'),
              selected: isSelected,
              trailing: isSelected ? const Icon(Icons.check) : null,
              onTap: () => _select(stop),
            );
          },
        );
    }
  }
}
