import '../transit/bus_route_stop_occurrence.dart';
import 'boarding_candidates.dart';

enum BoardingGapEndpoint { boarding, target }

typedef BoardingCandidateProgressLabel = ({
  String? stopName,
  String text,
  BoardingGapEndpoint? endpoint,
});

BoardingCandidateProgressLabel boardingCandidateProgressLabel(
  BoardingCandidate candidate,
  List<BusRouteStopOccurrence> stops, {
  required int boardingId,
  required int targetId,
}) {
  const unknown = (stopName: null, text: '진행 위치 미확인', endpoint: null);
  final progress = candidate.progress;
  if (progress == null || progress.kind == ProgressKind.unknown) return unknown;
  const unusable = {
    'POSITION_UNAVAILABLE',
    'METADATA_MISMATCH',
    'VEHICLE_ID_MISSING',
    'VEHICLE_NUMBER_MISSING',
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
  if (reasons.any(unusable.contains)) return unknown;

  final indices = <int, int>{};
  var observationMatches = 0;
  var ordered = true;
  for (final (index, stop) in stops.indexed) {
    if (stop.id == progress.occurrenceId || stop.order == progress.order) {
      observationMatches++;
    }
    if (indices.containsKey(stop.id) ||
        (index > 0 && stops[index - 1].order >= stop.order)) {
      ordered = false;
    }
    indices[stop.id] = index;
  }

  final observed = indices[progress.occurrenceId];
  if (observationMatches != 1 ||
      observed == null ||
      stops[observed].order != progress.order) {
    return unknown;
  }

  final stopName = progress.stopName.trim();
  final uncertain = (stopName: stopName, text: '남은 지점 확인 필요', endpoint: null);
  final gap = candidate.boardingOccurrenceGap;
  final boarding = indices[boardingId];
  final target = indices[targetId];
  if (!ordered ||
      gap == null ||
      boarding == null ||
      target == null ||
      target <= boarding ||
      boarding - observed != gap ||
      (observed < target && reasons.contains('TARGET_NOT_AHEAD'))) {
    return uncertain;
  }

  if (gap == 0) {
    return (stopName: stopName, text: '승차 지점 부근 관측', endpoint: null);
  }

  final endpoint = observed < boarding
      ? BoardingGapEndpoint.boarding
      : BoardingGapEndpoint.target;

  final text = switch (endpoint) {
    BoardingGapEndpoint.boarding => '승차까지 약 $gap개 지점',
    BoardingGapEndpoint.target when observed > target => '목표 이후 관측',
    BoardingGapEndpoint.target when observed == target => '목표 지점 부근 관측',
    BoardingGapEndpoint.target => '목표까지 약 ${target - observed}개 지점',
  };
  return (stopName: stopName, text: text, endpoint: endpoint);
}
