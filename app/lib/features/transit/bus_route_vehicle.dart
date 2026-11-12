enum BusRouteVehiclesStatus { available, empty, partial, stale }

/// atStop: occurrence 정류장에 있음, departed: 출발해 다음 정류장 사이에 있음.
enum BusVehiclePosition { atStop, departed }

enum BusVehicleSeatStatus { available, none, unavailable }

class BusRouteVehicle {
  const BusRouteVehicle({
    required this.vehicleNumber,
    required this.occurrenceId,
    required this.position,
    required this.seatStatus,
    required this.seatRemaining,
    required this.congestion,
  });

  final String vehicleNumber;

  /// 차량이 마지막으로 지나간 검증된 occurrence. 모호하면 null.
  final int? occurrenceId;
  final BusVehiclePosition position;
  final BusVehicleSeatStatus seatStatus;
  final int? seatRemaining;

  /// 1 여유, 2 보통, 3 혼잡, 4 매우혼잡. 알 수 없으면 null.
  final int? congestion;

  /// 화면에는 차량번호 끝 숫자 4자리만 짧게 보여 준다.
  String get shortNumber {
    final digits = RegExp(r'\d+$').firstMatch(vehicleNumber)?.group(0);
    if (digits == null) return vehicleNumber;
    return digits.length > 4 ? digits.substring(digits.length - 4) : digits;
  }

  factory BusRouteVehicle.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    final number = value['vehicleNumber'];
    final occurrence = value['occurrenceId'];
    final position = switch (value['positionState']) {
      'AT_STOP' => BusVehiclePosition.atStop,
      'DEPARTED' => BusVehiclePosition.departed,
      _ => throw const FormatException(),
    };
    final seat = value['seat'];
    final congestion = value['congestion'];
    if (number is! String ||
        number.trim().isEmpty ||
        (occurrence != null && (occurrence is! int || occurrence <= 0)) ||
        seat is! Map<String, dynamic> ||
        (congestion != null &&
            (congestion is! int || congestion < 1 || congestion > 4))) {
      throw const FormatException();
    }
    final status = switch (seat['status']) {
      'AVAILABLE' => BusVehicleSeatStatus.available,
      'NONE' => BusVehicleSeatStatus.none,
      'UNAVAILABLE' => BusVehicleSeatStatus.unavailable,
      _ => throw const FormatException(),
    };
    final remaining = seat['remaining'];
    if (remaining != null && (remaining is! int || remaining < 0)) {
      throw const FormatException();
    }
    if ((status == BusVehicleSeatStatus.available &&
            (remaining == null || remaining <= 0)) ||
        (status == BusVehicleSeatStatus.none && remaining != 0) ||
        (status == BusVehicleSeatStatus.unavailable && remaining != null)) {
      throw const FormatException();
    }
    return BusRouteVehicle(
      vehicleNumber: number,
      occurrenceId: occurrence as int?,
      position: position,
      seatStatus: status,
      seatRemaining: remaining as int?,
      congestion: congestion as int?,
    );
  }
}

class BusRouteVehicles {
  const BusRouteVehicles({
    required this.observedAt,
    required this.status,
    required this.vehicles,
  });

  final DateTime observedAt;
  final BusRouteVehiclesStatus status;
  final List<BusRouteVehicle> vehicles;

  factory BusRouteVehicles.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    final observedAt = value['observedAt'];
    final vehicles = value['vehicles'];
    if (observedAt is! String || vehicles is! List) {
      throw const FormatException();
    }
    final status = switch (value['status']) {
      'AVAILABLE' => BusRouteVehiclesStatus.available,
      'EMPTY' => BusRouteVehiclesStatus.empty,
      'PARTIAL' => BusRouteVehiclesStatus.partial,
      'STALE' => BusRouteVehiclesStatus.stale,
      _ => throw const FormatException(),
    };
    return BusRouteVehicles(
      observedAt: DateTime.parse(observedAt),
      status: status,
      vehicles: vehicles.map(BusRouteVehicle.fromJson).toList(growable: false),
    );
  }
}
