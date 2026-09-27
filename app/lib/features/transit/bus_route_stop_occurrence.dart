class BusRouteStopOccurrence {
  const BusRouteStopOccurrence({
    required this.id,
    required this.name,
    required this.order,
    required this.canNotifyOneStopBefore,
    required this.canNotifyOneStopAfter,
  });

  /// BusRouteStopOccurrence.id, used as the target selection reference.
  final int id;
  final String name;
  final int order;
  final bool canNotifyOneStopBefore;
  final bool canNotifyOneStopAfter;

  factory BusRouteStopOccurrence.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    final id = value['id'];
    final name = value['name'];
    final order = value['order'];
    final before = value['canNotifyOneStopBefore'];
    final after = value['canNotifyOneStopAfter'];
    if (id is! int ||
        id <= 0 ||
        name is! String ||
        name.trim().isEmpty ||
        order is! int ||
        order <= 0 ||
        before is! bool ||
        after is! bool) {
      throw const FormatException();
    }
    return BusRouteStopOccurrence(
      id: id,
      name: name,
      order: order,
      canNotifyOneStopBefore: before,
      canNotifyOneStopAfter: after,
    );
  }
}
