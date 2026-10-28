enum CandidateSetStatus { available, empty, partial, unknown }

enum ConfirmationStatus { available, held, unknown }

enum TrackingSupport { conditional, held, unknown }

enum ProgressKind { routeProgress, arrivalObserved, movingReference, unknown }

class BoardingCandidates {
  BoardingCandidates.fromJson(Object? value) {
    final json = _object(value);
    observedAt = boardingInstant(json['observedAt']);
    providerDataTime = _optionalInstant(json['providerDataTime']);
    candidateSetStatus = switch (_text(json['candidateSetStatus'])) {
      'AVAILABLE' => CandidateSetStatus.available,
      'EMPTY' => CandidateSetStatus.empty,
      'PARTIAL' => CandidateSetStatus.partial,
      _ => CandidateSetStatus.unknown,
    };
    final items = json['candidates'];
    if (items is! List) throw const FormatException();
    candidates = List.unmodifiable(items.map(BoardingCandidate.fromJson));
    if ((candidateSetStatus == CandidateSetStatus.empty &&
            candidates.isNotEmpty) ||
        (candidateSetStatus == CandidateSetStatus.available &&
            candidates.isEmpty)) {
      throw const FormatException();
    }
  }

  late final DateTime observedAt;
  late final DateTime? providerDataTime;
  late final CandidateSetStatus candidateSetStatus;
  late final List<BoardingCandidate> candidates;
}

class BoardingCandidate {
  BoardingCandidate.fromJson(Object? value) {
    final json = _object(value);
    selectionKey = _optionalText(json['selectionKey']);
    candidateRef = _optionalText(json['candidateRef']);
    vehicleNumber = _optionalText(json['vehicleNumber']);
    confirmationStatus = switch (_text(json['confirmationStatus'])) {
      'AVAILABLE' => ConfirmationStatus.available,
      'HELD' => ConfirmationStatus.held,
      _ => ConfirmationStatus.unknown,
    };
    reasonCode = _optionalText(json['reasonCode']);
    progress = json['progress'] == null
        ? null
        : BoardingProgress.fromJson(json['progress']);
    final gap = json['boardingOccurrenceGap'];
    if (gap != null && (gap is! int)) throw const FormatException();
    boardingOccurrenceGap = gap as int?;
    observedAt = boardingInstant(json['observedAt']);
    providerDataTime = _optionalInstant(json['providerDataTime']);
    trackingSupport = switch (_text(json['trackingSupport'])) {
      'CONDITIONAL' => TrackingSupport.conditional,
      'HELD' => TrackingSupport.held,
      _ => TrackingSupport.unknown,
    };
    trackingReasonCode = _optionalText(json['trackingReasonCode']);
    final reasons = json['limitations'];
    if (reasons is! List) throw const FormatException();
    limitations = List.unmodifiable(reasons.map(_text));
  }

  late final String? candidateRef, selectionKey;
  late final String? vehicleNumber;
  late final ConfirmationStatus confirmationStatus;
  late final String? reasonCode;
  late final BoardingProgress? progress;
  late final int? boardingOccurrenceGap;
  late final DateTime observedAt;
  late final DateTime? providerDataTime;
  late final TrackingSupport trackingSupport;
  late final String? trackingReasonCode;
  late final List<String> limitations;

  /// CONDITIONAL permits server revalidation, never tracking success.
  bool get canRequestConfirmation =>
      confirmationStatus == ConfirmationStatus.available &&
      trackingSupport == TrackingSupport.conditional &&
      candidateRef?.trim().isNotEmpty == true &&
      vehicleNumber?.trim().isNotEmpty == true &&
      ![reasonCode, ...limitations].any(
        (reason) => const {
          'IDENTITY_CONFLICT',
          'DETAIL_ID_MISMATCH',
          'DETAIL_NUMBER_MISMATCH',
          'STALE_OBSERVATION',
          'FUTURE_OBSERVATION',
        }.contains(reason),
      );
}

class BoardingProgress {
  BoardingProgress.fromJson(Object? value) {
    final json = _object(value);
    occurrenceId = _positiveInt(json['occurrenceId']);
    stopName = _text(json['stopName']);
    order = _positiveInt(json['order']);
    kind = switch (_text(json['kind'])) {
      'ROUTE_PROGRESS' => ProgressKind.routeProgress,
      'ARRIVAL_OBSERVED' => ProgressKind.arrivalObserved,
      'MOVING_REFERENCE' => ProgressKind.movingReference,
      _ => ProgressKind.unknown,
    };
  }

  late final int occurrenceId;
  late final String stopName;
  late final int order;
  late final ProgressKind kind;
}

Map<String, dynamic> _object(Object? value) {
  if (value is! Map<String, dynamic>) throw const FormatException();
  return value;
}

String _text(Object? value) {
  if (value is! String || value.trim().isEmpty) throw const FormatException();
  return value;
}

String? _optionalText(Object? value) => value == null ? null : _text(value);

int _positiveInt(Object? value) {
  if (value is! int || value <= 0) throw const FormatException();
  return value;
}

DateTime? _optionalInstant(Object? value) =>
    value == null ? null : boardingInstant(value);

DateTime boardingInstant(Object? value) {
  final text = _text(value);
  final match = RegExp(
    r'^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$',
  ).firstMatch(text);
  if (match == null) throw const FormatException();
  final parts = [for (var i = 1; i <= 6; i++) int.parse(match.group(i)!)];
  final [year, month, day, hour, minute, second] = parts;
  final zone = match.group(7)!;
  if (month < 1 ||
      month > 12 ||
      day < 1 ||
      day > DateTime.utc(year, month + 1, 0).day ||
      hour > 23 ||
      minute > 59 ||
      second > 59 ||
      (zone != 'Z' &&
          (int.parse(zone.substring(1, 3)) > 23 ||
              int.parse(zone.substring(4)) > 59))) {
    throw const FormatException();
  }
  return DateTime.parse(text).toUtc();
}
