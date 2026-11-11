import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/boarding/boarding_candidate_progress_label.dart';
import 'package:stopbell/features/boarding/boarding_candidates.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';

import 'boarding_test_fakes.dart';

BusRouteStopOccurrence _stop(
  int id,
  int order,
  String name, {
  String? direction,
}) => BusRouteStopOccurrence(
  id: id,
  name: name,
  order: order,
  destinationName: direction,
  canNotifyOneStopBefore: true,
  canNotifyOneStopAfter: true,
);

void main() {
  final stops = [
    _stop(10, 5, '출발'),
    _stop(11, 19, '승차'),
    _stop(12, 24, '경유(미정차)'),
    _stop(13, 40, '중간'),
    _stop(22, 90, '목표'),
    _stop(23, 110, '다음'),
  ];

  BoardingCandidateProgressLabel label({
    int observedId = 12,
    int observedOrder = 24,
    int? boardingGap = -1,
    String kind = 'ROUTE_PROGRESS',
    List<String> limitations = const [],
    String? reason,
    String? trackingReason,
    int targetId = 22,
    bool withProgress = true,
    List<BusRouteStopOccurrence>? traversal,
  }) => boardingCandidateProgressLabel(
    BoardingCandidate.fromJson({
      ...candidateJson(),
      'boardingOccurrenceGap': boardingGap,
      'progress': withProgress
          ? {
              'occurrenceId': observedId,
              'order': observedOrder,
              'stopName': '표시 이름으로 매칭하지 않음',
              'kind': kind,
            }
          : null,
      'limitations': limitations,
      'reasonCode': reason,
      'trackingReasonCode': trackingReason,
    }),
    traversal ?? stops,
    boardingId: 11,
    targetId: targetId,
  );

  test('exact IDs and signed server gap use indices across sparse orders', () {
    expect(label(), (
      stopName: '표시 이름으로 매칭하지 않음',
      text: '목표까지 약 2개 지점',
      endpoint: BoardingGapEndpoint.target,
    ));
    expect(label(observedId: 10, observedOrder: 5, boardingGap: 1), (
      stopName: '표시 이름으로 매칭하지 않음',
      text: '승차까지 약 1개 지점',
      endpoint: BoardingGapEndpoint.boarding,
    ));
    expect(
      label(observedId: 11, observedOrder: 19, boardingGap: 0).text,
      '승차 지점 부근 관측',
    );
    expect(
      label(observedId: 22, observedOrder: 90, boardingGap: -3).text,
      '목표 지점 부근 관측',
    );
    expect(
      label(observedId: 23, observedOrder: 110, boardingGap: -4).text,
      '목표 이후 관측',
    );
  });

  test('different occurrence IDs disambiguate identical names', () {
    expect(
      label(traversal: [...stops.take(5), _stop(23, 110, '경유(미정차)')]).text,
      '목표까지 약 2개 지점',
    );
  });

  test('missing destination labels do not invalidate an exact gap', () {
    expect(
      label(
        traversal: [
          ...stops.take(4),
          _stop(22, 90, '목표', direction: '목표'),
          stops.last,
        ],
      ).text,
      '목표까지 약 2개 지점',
    );
  });

  test('unreliable or mismatched observations hide both location and gap', () {
    for (final value in [
      label(withProgress: false),
      label(observedId: 99),
      label(observedOrder: 19),
      label(kind: 'UNRECOGNIZED'),
      label(reason: 'METADATA_MISMATCH'),
      label(reason: 'IDENTITY_CONFLICT'),
      label(trackingReason: 'FUTURE_OBSERVATION'),
      label(limitations: ['STALE_OBSERVATION']),
      label(limitations: ['DETAIL_NUMBER_MISMATCH']),
      label(limitations: ['PROVIDER_TIME_INVALID']),
      label(
        traversal: [...stops.take(4), _stop(12, 45, '중복 ID'), ...stops.skip(4)],
      ),
      label(
        traversal: [
          ...stops.take(4),
          _stop(14, 24, '관측 순서 중복'),
          ...stops.skip(4),
        ],
      ),
    ]) {
      expect(value, (stopName: null, text: '진행 위치 미확인', endpoint: null));
    }
  });

  test('a verified location survives a missing or contradictory gap', () {
    for (final value in [
      label(boardingGap: -2),
      label(boardingGap: null),
      label(targetId: 99),
      label(limitations: ['TARGET_NOT_AHEAD']),
      label(
        traversal: [
          ...stops.take(4),
          _stop(14, 40, '다른 순서 중복'),
          ...stops.skip(4),
        ],
      ),
      label(
        traversal: [...stops.take(4), _stop(14, 20, '역순'), ...stops.skip(4)],
      ),
    ]) {
      expect(value, (
        stopName: '표시 이름으로 매칭하지 않음',
        text: '남은 지점 확인 필요',
        endpoint: null,
      ));
    }
    expect(label(limitations: ['PROVIDER_TIME_MISSING']), label());
    expect(
      label(
        observedId: 23,
        observedOrder: 110,
        boardingGap: -4,
        limitations: ['TARGET_NOT_AHEAD'],
      ).text,
      '목표 이후 관측',
    );
  });

  test('direction changes do not hide an exact occurrence gap', () {
    final traversal = [
      for (final stop in stops)
        _stop(
          stop.id,
          stop.order,
          stop.name,
          direction: switch (stop.id) {
            12 => null,
            22 || 23 => '반대편',
            _ => '목표',
          },
        ),
    ];
    final acrossTurn = label(traversal: traversal);
    expect(acrossTurn.text, '목표까지 약 2개 지점');
    expect(acrossTurn.endpoint, BoardingGapEndpoint.target);
    expect(
      label(
        traversal: traversal,
        observedId: 22,
        observedOrder: 90,
        boardingGap: -3,
        targetId: 23,
      ).text,
      '목표까지 약 1개 지점',
    );
  });
}
