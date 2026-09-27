import 'dart:convert';

import '../../core/authenticated_api_client.dart';
import 'bus_route_stop_occurrence.dart';

class BusRouteStopClient {
  const BusRouteStopClient(this.apiClient);

  final AuthenticatedApiClient apiClient;

  Future<List<BusRouteStopOccurrence>> findStops(int routeId) async {
    if (routeId <= 0) throw ArgumentError.value(routeId, 'routeId');
    final response = await apiClient
        .request('GET', '/api/v1/bus-routes/$routeId/stops')
        .timeout(const Duration(seconds: 15));
    if (response.statusCode != 200) {
      throw BusRouteStopException(statusCode: response.statusCode);
    }

    try {
      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      if (decoded is! List) throw const FormatException();
      return decoded
          .map((value) => BusRouteStopOccurrence.fromJson(value))
          .toList();
    } on FormatException {
      throw const BusRouteStopException();
    }
  }
}

class BusRouteStopException implements Exception {
  const BusRouteStopException({this.statusCode});

  final int? statusCode;
}
