import 'package:flutter/material.dart';

import '../auth/auth_session.dart';
import '../transit/bus_route.dart';
import '../transit/bus_route_stop_occurrence.dart';
import '../transit/bus_route_timeline.dart';
import '../transit/bus_stop_selection_layout.dart';
import 'boarding_candidate_client.dart';
import 'boarding_candidates.dart';
import 'boarding_candidates_view.dart';
import 'boarding_creation_intent.dart';

enum _StopsState { loading, success, empty, error }

enum _Picker { target, boarding, review }

class BoardingStopScreen extends StatefulWidget {
  const BoardingStopScreen({
    super.key,
    required this.route,
    required this.authSession,
    required this.findStops,
    required this.findCandidates,
    required this.onBack,
    this.onSelectCandidate,
  });
  final BusRoute route;
  final AuthSession authSession;
  final Future<List<BusRouteStopOccurrence>> Function(int) findStops;
  final FindBoardingCandidates findCandidates;
  final VoidCallback onBack;
  final void Function(BoardingCandidate, BoardingSelectionContext, String)?
  onSelectCandidate;

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
  _Picker _picker = _Picker.boarding;
  _Picker? _returnTo;
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
      _picker = _Picker.boarding;
      _returnTo = null;
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

  bool _validTarget(BusRouteStopOccurrence stop) =>
      _boarding != null && stop.order > _boarding!.order;

  void _select(BusRouteStopOccurrence stop) {
    if (!_current || _staleStops || _result != null) return;
    if (_picker == _Picker.target && !_validTarget(stop)) return;
    setState(() {
      if (_picker != _Picker.target) {
        _boarding = stop;
        if (_target != null && !_validTarget(_target!)) _target = null;
        _picker = _target == null ? _Picker.target : _Picker.review;
      } else {
        _target = stop;
        _picker = _Picker.review;
      }
      _returnTo = null;
      _invalidateCandidates();
    });
  }

  Future<void> _findCandidates() async {
    final target = _target;
    if (!_current ||
        target == null ||
        _boarding == null ||
        !_validTarget(target) ||
        _loadingCandidates ||
        _staleStops) {
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
    setState(() {
      _picker = _Picker.review;
      _returnTo = null;
      _loadingCandidates = true;
    });
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

  void _pick(_Picker picker) {
    if (!_current || _staleStops || picker == _picker) return;
    setState(() {
      _returnTo ??= _picker;
      _picker = picker;
      _invalidateCandidates();
    });
  }

  bool get _showingCandidates => _result != null || _loadingCandidates;

  String get _backLabel {
    if (_showingCandidates) return '정류장 선택으로';
    if (_returnTo != null) return '정류장 변경 취소';
    if (_picker == _Picker.target) return '승차 정류장 선택으로';
    return '노선 다시 선택';
  }

  void _back() {
    if (!_current) return;
    if (_showingCandidates) {
      setState(_invalidateCandidates);
    } else if (_returnTo != null) {
      setState(() {
        _picker = _returnTo!;
        _returnTo = null;
      });
    } else if (_picker == _Picker.target) {
      setState(() => _picker = _Picker.boarding);
    } else {
      widget.onBack();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!_current) return const SizedBox.shrink();
    return PopScope<Object?>(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _back();
      },
      child: BusStopSelectionLayout(
        route: widget.route,
        title: '하차 알림',
        onBack: _back,
        backLabel: _backLabel,
        body: _state == _StopsState.success ? _routeAndCandidates() : _status(),
        summary: _state == _StopsState.success ? _summary() : null,
      ),
    );
  }

  Widget _status() => Center(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_state == _StopsState.loading) ...[
            const CircularProgressIndicator(),
            const SizedBox(height: 12),
            const Text('정류장 조회 중...'),
          ] else ...[
            Text(
              _state == _StopsState.empty
                  ? '정류장 정보가 없습니다.'
                  : '정류장 정보를 불러오지 못했습니다.',
            ),
            OutlinedButton(
              onPressed: _loadStops,
              child: const Text('정류장 다시 조회'),
            ),
          ],
        ],
      ),
    ),
  );

  Widget _routeAndCandidates() => IndexedStack(
    index: _result == null ? 0 : 1,
    sizing: StackFit.expand,
    children: [
      BusRouteTimeline(
        stops: _stops,
        targetId: _target?.id,
        boardingId: _boarding?.id,
        isSelectable: _picker == _Picker.target ? _validTarget : null,
        rowKey: (id) => ValueKey('occurrence-$id'),
        onSelect: _staleStops || _result != null ? null : _select,
      ),
      if (_result == null)
        const SizedBox.shrink()
      else
        SingleChildScrollView(
          key: const ValueKey('boarding-candidate-results'),
          primary: false,
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              BoardingCandidatesView(
                result: _result!,
                receivedAt: _receivedAt!,
                onSelect: widget.onSelectCandidate == null
                    ? null
                    : (candidate) {
                        if (!_current ||
                            _target == null ||
                            !(_result?.candidates.contains(candidate) ??
                                false)) {
                          return;
                        }
                        widget.onSelectCandidate!(
                          candidate,
                          BoardingSelectionContext(
                            routeId: widget.route.id,
                            targetId: _target!.id,
                            boardingId: _boarding?.id,
                          ),
                          _stopLabel(_target!),
                        );
                      },
              ),
              const SizedBox(height: 12),
              const Text(
                '목적지 2정거장 전(기본) 또는 1정거장 전(선택)에 하차 준비 알림을 받을 수 있어요. 목록에서 차량을 직접 선택해 등록하세요. '
                "등록 후 바로 추적을 시작하며, 위치를 확인하는 동안에는 '차량 위치 확인 중'으로 표시해요.",
              ),
            ],
          ),
        ),
    ],
  );

  Widget _summary() => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      if (!_staleStops && _result == null && !_loadingCandidates)
        Text(switch (_picker) {
          _Picker.target => '승차 정류장 이후의 정류장을 선택해 주세요',
          _Picker.boarding => '노선도에서 승차 정류장을 선택하세요.',
          _Picker.review => '정류장을 변경하거나 차량 후보를 조회하세요.',
        }, style: Theme.of(context).textTheme.titleSmall),
      _modeButton(_Picker.boarding, _boarding),
      _modeButton(_Picker.target, _target),
      FilledButton(
        key: const ValueKey('find-candidates'),
        onPressed:
            _target == null ||
                _boarding == null ||
                !_validTarget(_target!) ||
                _loadingCandidates ||
                _staleStops
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
    ],
  );

  Widget _modeButton(_Picker picker, BusRouteStopOccurrence? stop) {
    final target = picker == _Picker.target;
    final active = picker == _picker;
    final role = target ? '목표 정류장' : '승차 정류장';
    final details = stop == null ? null : selectedStopContext(stop, _stops);
    final name = stop?.name ?? '선택해 주세요';
    final colors = Theme.of(context).colorScheme;
    return Semantics(
      selected: active,
      child: TextButton(
        key: ValueKey(target ? 'pick-target' : 'pick-boarding'),
        onPressed: _staleStops || (target && _boarding == null)
            ? null
            : () => _pick(picker),
        style: TextButton.styleFrom(
          alignment: Alignment.centerLeft,
          backgroundColor: active ? colors.primaryContainer : null,
          foregroundColor: active
              ? colors.onPrimaryContainer
              : colors.onSurface,
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        ),
        child: Row(
          children: [
            Icon(
              target ? Icons.flag_outlined : Icons.directions_bus_outlined,
              size: 18,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '$role · $name${details == null ? '' : '\n$details'}',
              ),
            ),
            const SizedBox(width: 8),
            Text(active ? '선택 중' : '변경'),
          ],
        ),
      ),
    );
  }
}
