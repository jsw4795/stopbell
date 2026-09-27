class BusRouteStopOccurrence {
  const BusRouteStopOccurrence({
    required this.id,
    required this.name,
    required this.order,
    this.destinationName,
    this.previousStopName,
    this.nextStopName,
    required this.canNotifyOneStopBefore,
    required this.canNotifyOneStopAfter,
  });

  /// BusRouteStopOccurrence.id, used as the target selection reference.
  final int id;
  final String name;
  final int order;
  final String? destinationName;
  final String? previousStopName;
  final String? nextStopName;
  final bool canNotifyOneStopBefore;
  final bool canNotifyOneStopAfter;

  factory BusRouteStopOccurrence.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    final id = value['id'];
    final name = value['name'];
    final order = value['order'];
    final destination = value['destinationName'];
    final previous = value['previousStopName'];
    final next = value['nextStopName'];
    final before = value['canNotifyOneStopBefore'];
    final after = value['canNotifyOneStopAfter'];
    if (id is! int ||
        id <= 0 ||
        name is! String ||
        name.trim().isEmpty ||
        order is! int ||
        order <= 0 ||
        !value.containsKey('destinationName') ||
        !value.containsKey('previousStopName') ||
        !value.containsKey('nextStopName') ||
        !validName(destination) ||
        !validName(previous) ||
        !validName(next) ||
        before is! bool ||
        after is! bool) {
      throw const FormatException();
    }
    return BusRouteStopOccurrence(
      id: id,
      name: name,
      order: order,
      destinationName: destination as String?,
      previousStopName: previous as String?,
      nextStopName: next as String?,
      canNotifyOneStopBefore: before,
      canNotifyOneStopAfter: after,
    );
  }

  static bool validName(Object? value) =>
      value == null || (value is String && value.trim().isNotEmpty);
}
