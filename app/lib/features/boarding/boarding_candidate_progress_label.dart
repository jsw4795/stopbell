import '../transit/bus_route_stop_occurrence.dart';
import '../transit/stop_display_name.dart';
import 'boarding_candidates.dart';

enum BoardingGapEndpoint { boarding, target }

typedef BoardingCandidateProgressLabel = ({
  String text,
  BoardingGapEndpoint? endpoint,
});

BoardingCandidateProgressLabel boardingCandidateProgressLabel(
  BoardingCandidate candidate,
  List<BusRouteStopOccurrence> stops, {
  required int boardingId,
  required int targetId,
}) {
  const unknown = (text: '진행 위치 미확인', endpoint: null);
  const uncertain = (text: '진행 위치 확인 필요', endpoint: null);
  final progress = candidate.progress;
  if (progress == null) return unknown;
  final gap = candidate.boardingOccurrenceGap;
  const unusable = {
    'POSITION_UNAVAILABLE',
    'METADATA_MISMATCH',
    'IDENTITY_CONFLICT',
    'DETAIL_ID_MISMATCH',
    'DETAIL_NUMBER_MISMATCH',
    'STALE_OBSERVATION',
    'FUTURE_OBSERVATION',
    'PROVIDER_TIME_INVALID',
  };
  final reasons = {
    candidate.reasonCode,
    candidate.trackingReasonCode,
    ...candidate.limitations,
  };
  if (gap == null ||
      progress.kind == ProgressKind.unknown ||
      reasons.any(unusable.contains)) {
    return uncertain;
  }

  final indices = <int, int>{};
  final repeated = <(String, String?)>{};
  final names = <(String, String?)>{};
  (String, String?) nameInDirection(BusRouteStopOccurrence stop) =>
      (stopDisplayName(stop.name).name, stop.destinationName);
  for (final (index, stop) in stops.indexed) {
    if (indices.containsKey(stop.id) ||
        (index > 0 && stops[index - 1].order >= stop.order)) {
      return uncertain;
    }
    indices[stop.id] = index;
    final name = nameInDirection(stop);
    if (!names.add(name)) repeated.add(name);
  }

  final observed = indices[progress.occurrenceId];
  final boarding = indices[boardingId];
  final target = indices[targetId];
  if (observed == null ||
      boarding == null ||
      target == null ||
      target <= boarding ||
      (observed < target && reasons.contains('TARGET_NOT_AHEAD')) ||
      stops[observed].order != progress.order ||
      boarding - observed != gap) {
    return uncertain;
  }

  if (gap == 0) return (text: '승차 지점 부근 관측', endpoint: null);

  // A change of direction or a repeated stop on the same leg can make the
  // traversal ambiguous, even when all three occurrence IDs are known.
  final start = gap > 0 ? observed : boarding;
  final end = gap > 0 ? boarding : (observed > target ? observed : target);
  final direction = stops[start].destinationName;
  for (var index = start; index <= end; index++) {
    final stop = stops[index];
    if (stop.destinationName != direction ||
        repeated.contains(nameInDirection(stop))) {
      return uncertain;
    }
  }

  if (gap > 0) {
    return (text: '승차까지 약 $gap개 지점', endpoint: BoardingGapEndpoint.boarding);
  }
  if (observed >= target) {
    return (
      text: observed > target ? '목표 이후 관측' : '목표 지점 부근 관측',
      endpoint: BoardingGapEndpoint.target,
    );
  }
  return (
    text: '목표까지 약 ${target - observed}개 지점',
    endpoint: BoardingGapEndpoint.target,
  );
}
