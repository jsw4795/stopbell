import 'package:flutter/material.dart';

import 'boarding_candidate_client.dart';
import 'boarding_candidates.dart';
import 'boarding_candidates_view.dart';
import 'boarding_creation_intent.dart';
import 'boarding_session_controller.dart';

/// Always obtains a new reference using retained exact IDs in this Auth Session.
class BoardingReconfirmationScreen extends StatefulWidget {
  const BoardingReconfirmationScreen({
    super.key,
    required this.controller,
    required this.selectionContext,
    required this.findCandidates,
    required this.onSelect,
    required this.onBack,
    required this.onReselect,
  });
  final BoardingSessionController controller;
  final BoardingSelectionContext selectionContext;
  final FindBoardingCandidates findCandidates;
  final ValueChanged<BoardingCandidate> onSelect;
  final VoidCallback onBack, onReselect;
  @override
  State<BoardingReconfirmationScreen> createState() =>
      _BoardingReconfirmationScreenState();
}

class _BoardingReconfirmationScreenState
    extends State<BoardingReconfirmationScreen> {
  BoardingCandidates? _result;
  DateTime? _receivedAt;
  String? _error;
  bool _loading = false;
  int _epoch = 0;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final epoch = ++_epoch;
    bool current() => mounted && widget.controller.current && epoch == _epoch;
    setState(() {
      _result = null;
      _loading = true;
      _error = null;
    });
    final selection = widget.selectionContext;
    try {
      final result = await widget.findCandidates(
        routeId: selection.routeId,
        targetStopOccurrenceId: selection.targetId,
        boardingStopOccurrenceId: selection.boardingId,
        expectedGeneration: widget.controller.generation,
        isRequestCurrent: current,
      );
      if (current()) {
        setState(() {
          _result = result;
          _receivedAt = DateTime.now().toUtc();
        });
      }
    } catch (_) {
      if (current()) {
        setState(
          () => _error = '새 차량 후보를 조회하지 못했어요. 다시 조회하거나 노선·정류장을 다시 선택해 주세요.',
        );
      }
    } finally {
      if (current()) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      TextButton(onPressed: widget.onBack, child: const Text('세션 상세로')),
      TextButton(
        onPressed: widget.onReselect,
        child: const Text('노선·정류장 다시 선택'),
      ),
      const Text('동일 차량 재확인 · 원래 선택 문맥으로 새 후보 조회'),
      const Text('기존 전체 차량번호와 같은 차량을 직접 선택해 다시 대조하세요. 서버가 같은 운행·경로인지 최종 검증해요.'),
      OutlinedButton(
        onPressed: _loading ? null : _load,
        child: const Text('새 후보 다시 조회'),
      ),
      if (_loading) const Text('새 차량 후보 조회 중...'),
      if (_error != null) Text(_error!),
      if (_result != null)
        BoardingCandidatesView(
          result: _result!,
          receivedAt: _receivedAt!,
          onSelect: widget.onSelect,
        ),
    ],
  );
}
