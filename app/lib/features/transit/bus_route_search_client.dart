import 'dart:convert';

import '../../core/authenticated_api_client.dart';
import 'bus_route.dart';

class BusRouteSearchClient {
  const BusRouteSearchClient(this.apiClient);

  final AuthenticatedApiClient apiClient;

  Future<List<BusRoute>> search(String query) async {
    final path = Uri(
      path: '/api/v1/bus-routes',
      queryParameters: {'query': query.trim()},
    ).toString();
    final response = await apiClient.request('GET', path).timeout(
      const Duration(seconds: 15),
    );
    if (response.statusCode != 200) {
      throw BusRouteSearchException(statusCode: response.statusCode);
    }

    try {
      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      if (decoded is! List) throw const FormatException();
      return decoded.map((value) => BusRoute.fromJson(value)).toList();
    } on FormatException {
      throw const BusRouteSearchException();
    }
  }
}

class BusRouteSearchException implements Exception {
  const BusRouteSearchException({this.statusCode});

  final int? statusCode;
}
