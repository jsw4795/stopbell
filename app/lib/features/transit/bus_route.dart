class BusRoute {
  const BusRoute({
    required this.id,
    required this.routeNumber,
    required this.regionName,
    this.provider,
    this.routeType,
  });

  final int id;
  final String routeNumber;
  final String regionName;
  final String? provider;
  final String? routeType;

  factory BusRoute.fromJson(Object? value) {
    if (value is! Map<String, dynamic>) throw const FormatException();
    final id = value['id'];
    final routeNumber = value['routeNumber'];
    final regionName = value['regionName'];
    if (id is! int ||
        id <= 0 ||
        routeNumber is! String ||
        routeNumber.trim().isEmpty ||
        regionName is! String ||
        regionName.trim().isEmpty) {
      throw const FormatException();
    }
    return BusRoute(
      id: id,
      routeNumber: routeNumber,
      regionName: regionName,
      provider: value['provider'] is String
          ? value['provider'] as String
          : null,
      routeType: value['routeType'] is String
          ? value['routeType'] as String
          : null,
    );
  }
}
