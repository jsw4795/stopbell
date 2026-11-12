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
    this.startStopName,
    this.endStopName,
    this.provider,
    this.routeType,
    this.canNotifyOneStopBefore,
    this.canNotifyOneStopAfter,
  });

  final int id;
  final AlarmTransitType transitType;
  final AlarmStatus status;
  final String routeNumber;
  final String stopName;
  final bool notifyOneStopBefore;
  final bool notifyOneStopAfter;

  /// 공식 원본에서 검증된 노선 기·종점. 확인할 수 없으면 둘 다 null이다.
  final String? startStopName;
  final String? endStopName;

  /// 현재 노선과 snapshot이 일치할 때의 공식 Provider·노선 유형. 표시 색에만 사용한다.
  final String? provider;
  final String? routeType;

  /// 대상 정류장이 노선 첫/마지막이면 false. 구버전 응답은 null이며 표시한다.
  final bool? canNotifyOneStopBefore;
  final bool? canNotifyOneStopAfter;

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
    final start = _optionalText(value['startStopName']);
    final end = _optionalText(value['endStopName']);
    final hasEndpoints = start != null && end != null;
    return Alarm(
      id: id,
      transitType: transitType,
      status: status,
      routeNumber: routeNumber,
      stopName: stopName,
      notifyOneStopBefore: before,
      notifyOneStopAfter: after,
      startStopName: hasEndpoints ? start : null,
      endStopName: hasEndpoints ? end : null,
      provider: _optionalText(value['provider']),
      routeType: _optionalText(value['routeType']),
      canNotifyOneStopBefore: _optionalBool(value['canNotifyOneStopBefore']),
      canNotifyOneStopAfter: _optionalBool(value['canNotifyOneStopAfter']),
    );
  }

  static bool? _optionalBool(Object? value) => value is bool ? value : null;

  static String? _optionalText(Object? value) {
    if (value == null) return null;
    if (value is! String) throw const FormatException();
    final text = value.trim();
    return text.isEmpty ? null : text;
  }
}

extension AlarmStatusLabel on AlarmStatus {
  String get label => switch (this) {
    AlarmStatus.inactive => 'INACTIVE · 비활성',
    AlarmStatus.active => 'ACTIVE · 감시 중',
    AlarmStatus.followUp => 'FOLLOW_UP · 한 정류장 후 추적 중',
  };
}

extension AlarmStatusShortLabel on AlarmStatus {
  /// 홈 목록 칩용 짧은 표기. 상세 화면은 기존 [AlarmStatusLabel.label]을 유지한다.
  String get shortLabel => switch (this) {
    AlarmStatus.inactive => '비활성',
    AlarmStatus.active => '감시 중',
    AlarmStatus.followUp => '한 정류장 후 추적 중',
  };
}

extension AlarmTransitTypeLabel on AlarmTransitType {
  String get code => switch (this) {
    AlarmTransitType.bus => 'BUS',
    AlarmTransitType.subway => 'SUBWAY',
  };
}
