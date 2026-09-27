enum AlarmStatus { inactive, active, followUp }

enum AlarmTransitType { bus, subway }

class Alarm {
  const Alarm({
    required this.id,
    required this.transitType,
    required this.status,
    required this.routeNumber,
    required this.stopName,
    required this.notifyOneStopBefore,
    required this.notifyOneStopAfter,
  });

  final int id;
  final AlarmTransitType transitType;
  final AlarmStatus status;
  final String routeNumber;
  final String stopName;
  final bool notifyOneStopBefore;
  final bool notifyOneStopAfter;

  factory Alarm.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    final id = value['id'];
    final routeNumber = value['routeNumber'];
    final stopName = value['stopName'];
    final before = value['notifyOneStopBefore'];
    final after = value['notifyOneStopAfter'];
    final transitType = switch (value['transitType']) {
      'BUS' => AlarmTransitType.bus,
      'SUBWAY' => AlarmTransitType.subway,
      _ => throw const FormatException(),
    };
    final status = switch (value['status']) {
      'INACTIVE' => AlarmStatus.inactive,
      'ACTIVE' => AlarmStatus.active,
      'FOLLOW_UP' => AlarmStatus.followUp,
      _ => throw const FormatException(),
    };
    if (id is! int ||
        id <= 0 ||
        routeNumber is! String ||
        routeNumber.trim().isEmpty ||
        stopName is! String ||
        stopName.trim().isEmpty ||
        before is! bool ||
        after is! bool) {
      throw const FormatException();
    }
    return Alarm(
      id: id,
      transitType: transitType,
      status: status,
      routeNumber: routeNumber,
      stopName: stopName,
      notifyOneStopBefore: before,
      notifyOneStopAfter: after,
    );
  }
}

extension AlarmStatusLabel on AlarmStatus {
  String get label => switch (this) {
    AlarmStatus.inactive => 'INACTIVE · 비활성',
    AlarmStatus.active => 'ACTIVE · 감시 중',
    AlarmStatus.followUp => 'FOLLOW_UP · 한 정거장 후 추적 중',
  };
}

extension AlarmTransitTypeLabel on AlarmTransitType {
  String get code => switch (this) {
    AlarmTransitType.bus => 'BUS',
    AlarmTransitType.subway => 'SUBWAY',
  };
}
