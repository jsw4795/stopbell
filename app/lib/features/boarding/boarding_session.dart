import 'boarding_candidates.dart' show boardingInstant;

enum BoardingSessionStatus {
  tracking,
  suspended,
  completed,
  cancelled,
  expired,
  unknown,
}

enum BoardingEvaluation {
  verificationPending,
  ready,
  recoveryPending,
  held,
  ended,
  unknown,
}

enum BoardingReason {
  baselineUnverified,
  targetNotAhead,
  temporaryProviderFailure,
  staleProgress,
  vehicleMissing,
  identityConflict,
  progressRegression,
  traversalAmbiguous,
  continuityGapExceeded,
  metadataChanged,
  arrivalObserved,
  passedObserved,
  userCancelled,
  sessionLifetimeExpired,
  unknown;

  bool get needsReconfirmation => const {
    BoardingReason.targetNotAhead,
    BoardingReason.vehicleMissing,
    BoardingReason.identityConflict,
    BoardingReason.progressRegression,
    BoardingReason.traversalAmbiguous,
    BoardingReason.continuityGapExceeded,
  }.contains(this);
}

class BoardingSession {
  BoardingSession.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    id = boardingInteger(value['id'], positive: true);
    revision = boardingInteger(value['revision']);
    routeNumber = boardingText(value['routeNumber']);
    vehicleNumber = boardingText(value['vehicleNumber']);
    targetName = boardingText(value['targetName']);
    if (value.containsKey('prepareNotificationEnabled') &&
        value['prepareNotificationEnabled'] is! bool) {
      throw const FormatException();
    }
    prepareNotificationEnabled =
        value['prepareNotificationEnabled'] as bool? ?? true;
    final offset = value['notificationOffsetStops'];
    if (offset != null &&
        (offset is! int ||
            !const [1, 2].contains(offset) ||
            !prepareNotificationEnabled)) {
      throw const FormatException();
    }
    notificationOffsetStops = offset as int?;
    preparationNotificationStatus =
        value['preparationNotificationStatus'] == null
        ? 'UNKNOWN'
        : boardingText(value['preparationNotificationStatus']);
    targetOrder = boardingInteger(value['targetOrder'], positive: true);
    status = switch (boardingText(value['status'])) {
      'VERIFYING' =>
        BoardingSessionStatus.tracking, // Legacy response compatibility.
      'TRACKING' => BoardingSessionStatus.tracking,
      'SUSPENDED' => BoardingSessionStatus.suspended,
      'COMPLETED' => BoardingSessionStatus.completed,
      'CANCELLED' => BoardingSessionStatus.cancelled,
      'EXPIRED' => BoardingSessionStatus.expired,
      _ => BoardingSessionStatus.unknown,
    };
    evaluationStatus = switch (boardingText(value['evaluationStatus'])) {
      'VERIFICATION_PENDING' => BoardingEvaluation.verificationPending,
      'READY' => BoardingEvaluation.ready,
      'RECOVERY_PENDING' => BoardingEvaluation.recoveryPending,
      'HELD' => BoardingEvaluation.held,
      'ENDED' => BoardingEvaluation.ended,
      _ => BoardingEvaluation.unknown,
    };
    // The server may retain a historical gap in future versions. Only READY
    // permits displaying it; keep the nullable wire value without inventing one.
    final gap = value['remainingOccurrenceGap'];
    if (gap != null && gap is! int) throw const FormatException();
    remainingOccurrenceGap = gap as int?;
    checkpointObservedAt = value['checkpointObservedAt'] == null
        ? null
        : boardingInstant(value['checkpointObservedAt']);
    providerDataTime = value['providerDataTime'] == null
        ? null
        : boardingInstant(value['providerDataTime']);
    expiresAt = boardingInstant(value['expiresAt']);
    endedAt = value['endedAt'] == null
        ? null
        : boardingInstant(value['endedAt']);
    final reason = value['reasonCode'];
    reasonCode = reason == null
        ? null
        : switch (boardingText(reason)) {
            'BASELINE_UNVERIFIED' => BoardingReason.baselineUnverified,
            'TARGET_NOT_AHEAD' => BoardingReason.targetNotAhead,
            'TEMPORARY_PROVIDER_FAILURE' =>
              BoardingReason.temporaryProviderFailure,
            'STALE_PROGRESS' => BoardingReason.staleProgress,
            'VEHICLE_MISSING' => BoardingReason.vehicleMissing,
            'IDENTITY_CONFLICT' => BoardingReason.identityConflict,
            'PROGRESS_REGRESSION' => BoardingReason.progressRegression,
            'TRAVERSAL_AMBIGUOUS' => BoardingReason.traversalAmbiguous,
            'CONTINUITY_GAP_EXCEEDED' => BoardingReason.continuityGapExceeded,
            'METADATA_CHANGED' => BoardingReason.metadataChanged,
            'ARRIVAL_OBSERVED' => BoardingReason.arrivalObserved,
            'PASSED_OBSERVED' => BoardingReason.passedObserved,
            'USER_CANCELLED' => BoardingReason.userCancelled,
            'SESSION_LIFETIME_EXPIRED' => BoardingReason.sessionLifetimeExpired,
            _ => BoardingReason.unknown,
          };
    if (status != BoardingSessionStatus.unknown &&
        evaluationStatus != BoardingEvaluation.unknown) {
      if ((isTerminal &&
              (evaluationStatus != BoardingEvaluation.ended ||
                  endedAt == null)) ||
          (isOpen && endedAt != null) ||
          (status == BoardingSessionStatus.tracking &&
              evaluationStatus != BoardingEvaluation.ready &&
              evaluationStatus != BoardingEvaluation.recoveryPending &&
              evaluationStatus != BoardingEvaluation.verificationPending) ||
          (evaluationStatus != BoardingEvaluation.verificationPending &&
              checkpointObservedAt == null &&
              status != BoardingSessionStatus.suspended &&
              !isTerminal) ||
          (status == BoardingSessionStatus.suspended &&
              evaluationStatus != BoardingEvaluation.held)) {
        throw const FormatException();
      }
    }
  }

  late final bool prepareNotificationEnabled;
  late final int? notificationOffsetStops;
  late final String preparationNotificationStatus;
  late final int id, revision, targetOrder;
  late final String routeNumber, vehicleNumber, targetName;
  late final BoardingSessionStatus status;
  late final BoardingEvaluation evaluationStatus;
  late final BoardingReason? reasonCode;
  late final int? remainingOccurrenceGap;
  late final DateTime expiresAt;
  late final DateTime? checkpointObservedAt, providerDataTime, endedAt;

  bool get isOpen =>
      status == BoardingSessionStatus.tracking ||
      status == BoardingSessionStatus.suspended;
  bool get isTerminal => const {
    BoardingSessionStatus.completed,
    BoardingSessionStatus.cancelled,
    BoardingSessionStatus.expired,
  }.contains(status);
  bool get canReconfirm =>
      status == BoardingSessionStatus.suspended &&
      reasonCode?.needsReconfirmation == true;
}

int boardingInteger(Object? value, {bool positive = false}) {
  if (value is! int || value < (positive ? 1 : 0)) {
    throw const FormatException();
  }
  return value;
}

String boardingText(Object? value) {
  if (value is! String || value.trim().isEmpty) throw const FormatException();
  return value;
}
