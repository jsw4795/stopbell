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
    }),
    traversal ?? stops,
    boardingId: 11,
    targetId: 22,
  );

  test('exact IDs and signed server gap use indices across sparse orders', () {
    expect(label(), (
      text: '목표까지 약 2개 지점',
      endpoint: BoardingGapEndpoint.target,
    ));
    expect(label(observedId: 10, observedOrder: 5, boardingGap: 1), (
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

  test('no progress or inconsistent evidence cannot produce a gap', () {
    expect(label(withProgress: false).text, '진행 위치 미확인');
    for (final value in [
      label(observedId: 99),
      label(observedOrder: 19),
      label(boardingGap: -2),
      label(boardingGap: null),
      label(kind: 'UNRECOGNIZED'),
      label(limitations: ['METADATA_MISMATCH']),
      label(limitations: ['TARGET_NOT_AHEAD']),
      label(limitations: ['STALE_OBSERVATION']),
      label(limitations: ['FUTURE_OBSERVATION']),
      label(
        traversal: [...stops.take(4), _stop(12, 45, '중복 ID'), ...stops.skip(4)],
      ),
      label(
        traversal: [...stops.take(4), _stop(14, 40, '중복 순서'), ...stops.skip(4)],
      ),
      label(
        traversal: [...stops.take(4), _stop(14, 25, '역순'), ...stops.skip(4)],
      ),
    ]) {
      expect(value, (text: '진행 위치 확인 필요', endpoint: null));
    }
    expect(label(limitations: ['PROVIDER_TIME_MISSING']), label());
  });

  test(
    'repeat on the same or unknown leg and crossing a turn stay uncertain',
    () {
      expect(
        label(traversal: [...stops.take(4), _stop(22, 90, '승차'), stops.last])
            .text,
        '진행 위치 확인 필요',
      );
      expect(
        label(
          traversal: [
            ...stops.take(4),
            _stop(22, 90, '목표', direction: '반대편'),
            _stop(23, 110, '다음', direction: '반대편'),
          ],
        ).text,
        '진행 위치 확인 필요',
      );
      expect(
        label(
          traversal: [
            for (final stop in stops.take(5))
              _stop(stop.id, stop.order, stop.name, direction: '목표'),
            _stop(23, 110, '승차', direction: '출발'),
          ],
        ).text,
        '목표까지 약 2개 지점',
      );
    },
  );
}
