import 'dart:convert';

import '../../core/authenticated_api_client.dart';
import 'bus_route_vehicle.dart';

typedef FindBusRouteVehicles = Future<BusRouteVehicles> Function(int routeId);

class BusRouteVehicleClient {
  const BusRouteVehicleClient(this.apiClient);

  final AuthenticatedApiClient apiClient;

  Future<BusRouteVehicles> findVehicles(int routeId) async {
    if (routeId <= 0) throw ArgumentError.value(routeId, 'routeId');
    final response = await apiClient
        .request('GET', '/api/v1/bus-routes/$routeId/vehicles')
        .timeout(const Duration(seconds: 15));
    if (response.statusCode != 200) {
      throw BusRouteVehicleException(statusCode: response.statusCode);
    }
    try {
      return BusRouteVehicles.fromJson(
        jsonDecode(utf8.decode(response.bodyBytes)),
      );
    } on FormatException {
      throw const BusRouteVehicleException();
    }
  }
}

class BusRouteVehicleException implements Exception {
  const BusRouteVehicleException({this.statusCode});

  final int? statusCode;
}
