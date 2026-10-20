import 'package:flutter/material.dart';

import '../auth/auth_session.dart';
import '../transit/bus_route.dart';
import '../transit/bus_route_stop_occurrence.dart';
import 'boarding_candidate_client.dart';
import 'boarding_candidates.dart';
import 'boarding_candidates_view.dart';

enum _StopsState { loading, success, empty, error }

enum _Picker { target, boarding }

class BoardingStopScreen extends StatefulWidget {
  const BoardingStopScreen({
    super.key,
    required this.route,
    required this.authSession,
    required this.findStops,
    required this.findCandidates,
    required this.onBack,
  });
  final BusRoute route;
  final AuthSession authSession;
  final Future<List<BusRouteStopOccurrence>> Function(int) findStops;
  final FindBoardingCandidates findCandidates;
  final VoidCallback onBack;

  @override
  State<BoardingStopScreen> createState() => _BoardingStopScreenState();
}

class _BoardingStopScreenState extends State<BoardingStopScreen> {
  late final int _generation;
  int _stopsEpoch = 0;
  int _candidateEpoch = 0;
  _StopsState _state = _StopsState.loading;
  List<BusRouteStopOccurrence> _stops = const [];
  BusRouteStopOccurrence? _target;
  BusRouteStopOccurrence? _boarding;
  _Picker? _picker = _Picker.target;
  BoardingCandidates? _result;
  DateTime? _receivedAt;
  bool _loadingCandidates = false;
  String? _error;
  bool _staleStops = false;

  bool get _current =>
      mounted &&
      widget.authSession.isCurrentGeneration(_generation) &&
      !widget.authSession.isLoggingOut;

  @override
  void initState() {
    super.initState();
    _generation = widget.authSession.generation;
    widget.authSession.addListener(_authChanged);
    _loadStops();
  }

  @override
  void didUpdateWidget(covariant BoardingStopScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.route.id != widget.route.id) _loadStops();
  }

  void _invalidateCandidates() {
    _candidateEpoch++;
    _result = null;
    _receivedAt = null;
    _loadingCandidates = false;
    _error = null;
    _staleStops = false;
  }

  void _authChanged() {
    if (_current || !mounted) return;
    setState(() {
      _stopsEpoch++;
      _stops = const [];
      _target = null;
      _boarding = null;
      _invalidateCandidates();
    });
  }

  @override
  void dispose() {
    _stopsEpoch++;
    _candidateEpoch++;
    widget.authSession.removeListener(_authChanged);
    super.dispose();
  }

  Future<void> _loadStops() async {
    if (!_current) return;
    final epoch = ++_stopsEpoch;
    final routeId = widget.route.id;
    setState(() {
      _invalidateCandidates();
      _target = null;
      _boarding = null;
      _picker = _Picker.target;
      _stops = const [];
      _state = _StopsState.loading;
    });
    try {
      final stops = await widget.findStops(routeId);
      if (!_current || epoch != _stopsEpoch) return;
      setState(() {
        _stops = stops;
        _state = stops.isEmpty ? _StopsState.empty : _StopsState.success;
      });
    } catch (_) {
      if (_current && epoch == _stopsEpoch) {
        setState(() => _state = _StopsState.error);
      }
    }
  }

  void _select(BusRouteStopOccurrence? stop) {
    if (!_current) return;
    setState(() {
      if (_picker == _Picker.target) {
        _target = stop;
      } else {
        _boarding = stop;
      }
      _picker = null;
      _invalidateCandidates();
    });
  }

  Future<void> _findCandidates() async {
    final target = _target;
    if (!_current || target == null || _loadingCandidates || _staleStops) {
      return;
    }
    _invalidateCandidates();
    final epoch = _candidateEpoch;
    final routeId = widget.route.id;
    final boardingId = _boarding?.id;
    bool current() =>
        _current &&
        epoch == _candidateEpoch &&
        routeId == widget.route.id &&
        target.id == _target?.id &&
        boardingId == _boarding?.id;
    setState(() => _loadingCandidates = true);
    try {
      final result = await widget.findCandidates(
        routeId: routeId,
        targetStopOccurrenceId: target.id,
        boardingStopOccurrenceId: boardingId,
        expectedGeneration: _generation,
        isRequestCurrent: current,
      );
      if (!current()) return;
      setState(() {
        _result = result;
        _receivedAt = DateTime.now().toUtc();
      });
    } on BoardingCandidateException catch (error) {
      if (!current()) return;
      setState(() {
        _staleStops = error.statusCode == 400 || error.statusCode == 404;
        _error = _staleStops
            ? '정류장 정보가 변경되었을 수 있어요. 목록을 다시 조회해 정류장을 선택해 주세요.'
            : switch (error.failure) {
                CandidateFailure.parsing =>
                  '차량 후보 응답 형식을 확인할 수 없어요. 다시 조회해 주세요.',
                CandidateFailure.timeout => '차량 후보 조회 시간이 초과됐어요. 다시 조회해 주세요.',
                CandidateFailure.network =>
                  '통신 오류로 차량 후보를 불러오지 못했어요. 연결을 확인하고 다시 조회해 주세요.',
                CandidateFailure.http =>
                  error.statusCode == 503
                      ? '버스 정보를 일시적으로 불러올 수 없어요. 잠시 후 다시 조회해 주세요.'
                      : '차량 후보를 불러오지 못했어요. 다시 조회해 주세요.',
              };
      });
    } catch (_) {
      if (current()) setState(() => _error = '차량 후보를 불러오지 못했어요. 다시 조회해 주세요.');
    } finally {
      if (current()) setState(() => _loadingCandidates = false);
    }
  }

  String _stopLabel(BusRouteStopOccurrence stop) =>
      '${stop.name} · 노선 순서 ${stop.order}\n'
      '${stop.destinationName == null ? '방면 정보 없음' : '${stop.destinationName} 방면'}\n'
      '이전: ${stop.previousStopName ?? '없음'} · 다음: ${stop.nextStopName ?? '없음'}';

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        TextButton(onPressed: widget.onBack, child: const Text('노선 다시 선택')),
        Text(
          '하차 알림 · ${widget.route.routeNumber}',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        Text(widget.route.regionName),
        const Text(
          '현재는 차량 후보 조회와 비교만 제공해요. 실제 하차 추적은 아직 지원되지 않아요. 차량번호 대조 가능 표시도 추적 시작을 뜻하지 않아요.',
        ),
        const SizedBox(height: 12),
        if (_state == _StopsState.loading) ...[
          const Center(child: CircularProgressIndicator()),
          const Text('정류장 조회 중...'),
        ],
        if (_state == _StopsState.error) const Text('정류장 정보를 불러오지 못했습니다.'),
        if (_state == _StopsState.empty) const Text('정류장 정보가 없습니다.'),
        if (_state == _StopsState.error || _state == _StopsState.empty)
          OutlinedButton(onPressed: _loadStops, child: const Text('정류장 다시 조회')),
        if (_state == _StopsState.success) ...[
          TextButton(
            key: const ValueKey('pick-target'),
            onPressed: _staleStops
                ? null
                : () => setState(() => _picker = _Picker.target),
            child: Text(
              '목적 정류장 (필수): ${_target == null ? '선택해 주세요' : _stopLabel(_target!)}',
            ),
          ),
          TextButton(
            key: const ValueKey('pick-boarding'),
            onPressed: _staleStops
                ? null
                : () => setState(() => _picker = _Picker.boarding),
            child: Text(
              '승차 정류장 (선택): ${_boarding == null ? '선택 안 함' : _stopLabel(_boarding!)}',
            ),
          ),
          if (_picker != null && !_staleStops) ...[
            Text(
              _picker == _Picker.target
                  ? '목적 정류장을 선택하세요'
                  : '승차 정류장을 선택하세요 (선택 사항)',
            ),
            if (_picker == _Picker.boarding)
              ListTile(
                key: const ValueKey('no-boarding'),
                title: const Text('선택 안 함'),
                onTap: () => _select(null),
              ),
            for (final stop in _stops)
              ListTile(
                key: ValueKey('occurrence-${stop.id}'),
                title: Text(stop.name),
                subtitle: Text(_stopLabel(stop)),
                selected:
                    stop.id ==
                    (_picker == _Picker.target ? _target?.id : _boarding?.id),
                trailing:
                    stop.id ==
                        (_picker == _Picker.target
                            ? _target?.id
                            : _boarding?.id)
                    ? const Icon(Icons.check)
                    : null,
                onTap: () => _select(stop),
              ),
          ],
          FilledButton(
            key: const ValueKey('find-candidates'),
            onPressed: _target == null || _loadingCandidates || _staleStops
                ? null
                : _findCandidates,
            child: Text(
              _result == null && _error == null ? '차량 후보 조회' : '차량 후보 다시 조회',
            ),
          ),
          if (_loadingCandidates) ...[
            const Center(child: CircularProgressIndicator()),
            const Text('차량 후보를 조회하고 있어요.'),
          ],
          if (_error != null) Text(_error!),
          if (_staleStops)
            OutlinedButton(
              onPressed: _loadStops,
              child: const Text('정류장 목록 다시 선택'),
            ),
          if (_result != null)
            BoardingCandidatesView(result: _result!, receivedAt: _receivedAt!),
        ],
      ],
    );
  }
}
