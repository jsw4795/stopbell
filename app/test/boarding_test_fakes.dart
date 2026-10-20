import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';
import 'package:stopbell/features/transit/bus_route.dart';
import 'package:stopbell/features/transit/bus_route_stop_occurrence.dart';

final boardingBaseUrl = Uri.parse('http://localhost:8080');
const boardingPair = TokenPair(
  accessToken: 'access-a',
  refreshToken: 'refresh-a',
);

class BoardingStorage implements TokenPairStorage {
  TokenPair? pair = boardingPair;
  @override
  Future<TokenPair?> read() async => pair;
  @override
  Future<void> save(TokenPair pair) async => this.pair = pair;
  @override
  Future<void> delete() async => pair = null;
}

class BoardingAuthenticator implements Authenticator {
  @override
  Future<TokenPair> login() async =>
      const TokenPair(accessToken: 'access-b', refreshToken: 'refresh-b');
}

Future<AuthSession> boardingAuth(http.Client transport) async {
  final auth = AuthSession(
    authenticator: BoardingAuthenticator(),
    backend: BackendAuthClient(apiBaseUrl: boardingBaseUrl, client: transport),
    tokenStorage: BoardingStorage(),
  );
  await auth.initialize();
  return auth;
}

http.Response boardingJson(Object body, [int status = 200]) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

Map<String, dynamic> candidateJson({
  String? number = '서울70사1234',
  String confirmation = 'AVAILABLE',
  String tracking = 'CONDITIONAL',
}) => {
  'candidateRef': confirmation == 'AVAILABLE' ? 'private-reference' : null,
  'vehicleNumber': number,
  'confirmationStatus': confirmation,
  'reasonCode': null,
  'progress': {
    'occurrenceId': 11,
    'stopName': '진행 기준',
    'order': 3,
    'kind': 'ROUTE_PROGRESS',
  },
  'boardingOccurrenceGap': 2,
  'observedAt': '2026-10-20T01:02:03.123456Z',
  'providerDataTime': null,
  'trackingSupport': tracking,
  'trackingReasonCode': 'REVALIDATION_REQUIRED',
  'limitations': <String>[],
};

Map<String, dynamic> candidatesJson({
  String status = 'AVAILABLE',
  List<Map<String, dynamic>>? candidates,
}) => {
  'observedAt': '2026-10-20T01:02:03Z',
  'providerDataTime': null,
  'candidateSetStatus': status,
  'candidates': candidates ?? (status == 'EMPTY' ? [] : [candidateJson()]),
};

const boardingRoute = BusRoute(id: 754, routeNumber: '7000', regionName: '서울');
const boardingStops = [
  BusRouteStopOccurrence(
    id: 11,
    name: '반복 정류장',
    order: 3,
    destinationName: '종점',
    previousStopName: '이전',
    nextStopName: '다음',
    canNotifyOneStopBefore: true,
    canNotifyOneStopAfter: true,
  ),
  BusRouteStopOccurrence(
    id: 22,
    name: '반복 정류장',
    order: 9,
    destinationName: '종점',
    previousStopName: '이전',
    nextStopName: '다음',
    canNotifyOneStopBefore: true,
    canNotifyOneStopAfter: true,
  ),
];
