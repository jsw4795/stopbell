import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';

import 'boarding_test_fakes.dart';

void main() {
  test(
    'fresh identified candidate remains selectable with normal empty detail',
    () {
      final candidate = BoardingCandidate.fromJson({
        ...candidateJson(),
        'progress': null,
        'boardingOccurrenceGap': null,
        'reasonCode': 'DETAIL_EMPTY',
        'trackingReasonCode': 'DETAIL_EMPTY',
        'limitations': ['DETAIL_EMPTY'],
      });
      expect(candidate.canRequestConfirmation, isTrue);
      expect(candidate.progress, isNull);
    },
  );
  test(
    'preserves available, held, nulls, timestamps, reasons and server order',
    () {
      final result = BoardingCandidates.fromJson(
        candidatesJson(
          status: 'PARTIAL',
          candidates: [
            candidateJson(number: '서울70사9999'),
            {
              ...candidateJson(
                number: null,
                confirmation: 'HELD',
                tracking: 'HELD',
              ),
              'progress': null,
              'boardingOccurrenceGap': null,
              'reasonCode': 'IDENTITY_CONFLICT',
              'limitations': ['STALE_OBSERVATION', 'POSITION_UNAVAILABLE'],
            },
          ],
        ),
      );
      expect(result.candidateSetStatus, CandidateSetStatus.partial);
      expect(result.candidates.map((c) => c.vehicleNumber), [
        '서울70사9999',
        null,
      ]);
      final first = result.candidates.first;
      expect(first.confirmationStatus, ConfirmationStatus.available);
      expect(first.trackingSupport, TrackingSupport.conditional);
      expect(first.observedAt.toIso8601String(), '2026-10-20T01:02:03.123456Z');
      expect(first.boardingOccurrenceGap, 2);
      expect(first.progress!.occurrenceId, 11);
      expect(result.observedAt, DateTime.utc(2026, 10, 20, 1, 2, 3));
      expect(result.providerDataTime, isNull);
      final held = result.candidates.last;
      expect(held.confirmationStatus, ConfirmationStatus.held);
      expect(held.trackingSupport, TrackingSupport.held);
      expect(held.candidateRef, isNull);
      expect(held.progress, isNull);
      expect(held.boardingOccurrenceGap, isNull);
      expect(held.providerDataTime, isNull);
      expect(held.reasonCode, 'IDENTITY_CONFLICT');
      expect(held.limitations, ['STALE_OBSERVATION', 'POSITION_UNAVAILABLE']);
      expect(() => result.candidates.clear(), throwsUnsupportedError);
    },
  );

  test('all progress kinds and timezone offsets preserve instant meaning', () {
    for (final (raw, kind) in [
      ('ROUTE_PROGRESS', ProgressKind.routeProgress),
      ('ARRIVAL_OBSERVED', ProgressKind.arrivalObserved),
      ('MOVING_REFERENCE', ProgressKind.movingReference),
      ('NEW_KIND', ProgressKind.unknown),
    ]) {
      final candidate = BoardingCandidate.fromJson({
        ...candidateJson(),
        'progress': {
          'occurrenceId': 11,
          'stopName': '기준',
          'order': 3,
          'kind': raw,
        },
        'providerDataTime': '2026-10-20T10:02:03+09:00',
      });
      expect(candidate.progress!.kind, kind);
      expect(candidate.providerDataTime, DateTime.utc(2026, 10, 20, 1, 2, 3));
    }
  });

  test('unknown enums retain uncertainty, unknown reasons remain available for explanation', () {
    final result = BoardingCandidates.fromJson(
      candidatesJson(
        status: 'NEW',
        candidates: [
          {
            ...candidateJson(confirmation: 'NEW', tracking: 'NEW'),
            'reasonCode': 'NEW_REASON',
            'trackingReasonCode': 'NEW_TRACKING_REASON',
          },
        ],
      ),
    );
    expect(result.candidateSetStatus, CandidateSetStatus.unknown);
    expect(
      result.candidates.single.confirmationStatus,
      ConfirmationStatus.unknown,
    );
    expect(result.candidates.single.trackingSupport, TrackingSupport.unknown);
    expect(result.candidates.single.reasonCode, 'NEW_REASON');
  });

  test(
    'available empty partial are separate; inconsistent sets are rejected',
    () {
      expect(
        BoardingCandidates.fromJson(candidatesJson()).candidateSetStatus,
        CandidateSetStatus.available,
      );
      expect(
        BoardingCandidates.fromJson(candidatesJson(status: 'EMPTY')).candidates,
        isEmpty,
      );
      expect(
        BoardingCandidates.fromJson(
          candidatesJson(status: 'PARTIAL', candidates: []),
        ).candidateSetStatus,
        CandidateSetStatus.partial,
      );
      for (final body in [
        candidatesJson(candidates: []),
        candidatesJson(status: 'EMPTY', candidates: [candidateJson()]),
      ]) {
        expect(() => BoardingCandidates.fromJson(body), throwsFormatException);
      }
    },
  );

  test('rejects missing required fields and malformed values without success defaults', () {
    for (final field in ['observedAt', 'candidateSetStatus', 'candidates']) {
      expect(
        () => BoardingCandidates.fromJson(candidatesJson()..remove(field)),
        throwsFormatException,
      );
    }
    for (final field in [
      'confirmationStatus',
      'observedAt',
      'trackingSupport',
      'limitations',
    ]) {
      expect(
        () => BoardingCandidate.fromJson(candidateJson()..remove(field)),
        throwsFormatException,
      );
    }
    for (final invalid in <Map<String, Object?>>[
      {'vehicleNumber': 3},
      {'vehicleNumber': ''},
      {'candidateRef': 1},
      {'reasonCode': false},
      {'trackingReasonCode': []},
      {'limitations': null},
      {
        'limitations': [null],
      },
      {'confirmationStatus': null},
      {'trackingSupport': false},
      {'progress': []},
      {'boardingOccurrenceGap': 1.5},
      {'observedAt': '2026-02-30T00:00:00Z'},
      {'observedAt': '2026-10-20T01:02:03'},
      {'observedAt': '2026-10-20T25:02:03Z'},
      {'providerDataTime': 1},
    ]) {
      expect(
        () => BoardingCandidate.fromJson({...candidateJson(), ...invalid}),
        throwsFormatException,
        reason: invalid.keys.join(),
      );
    }
    for (final field in ['occurrenceId', 'stopName', 'order', 'kind']) {
      final progress = Map<String, dynamic>.from(
        candidateJson()['progress'] as Map,
      )..remove(field);
      expect(
        () => BoardingCandidate.fromJson({
          ...candidateJson(),
          'progress': progress,
        }),
        throwsFormatException,
      );
    }
  });
}
