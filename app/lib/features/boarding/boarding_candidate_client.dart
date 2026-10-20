import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../../core/authenticated_api_client.dart';
import '../auth/auth_session.dart';
import 'boarding_candidates.dart';

typedef FindBoardingCandidates = Future<BoardingCandidates> Function({
  required int routeId,
  required int targetStopOccurrenceId,
  int? boardingStopOccurrenceId,
  int? expectedGeneration,
  bool Function()? isRequestCurrent,
});

class BoardingCandidateClient {
  const BoardingCandidateClient(this.apiClient);

  final AuthenticatedApiClient apiClient;

  Future<BoardingCandidates> find({
    required int routeId,
    required int targetStopOccurrenceId,
    int? boardingStopOccurrenceId,
    int? expectedGeneration,
    bool Function()? isRequestCurrent,
  }) async {
    for (final id in [
      routeId,
      targetStopOccurrenceId,
      boardingStopOccurrenceId,
    ]) {
      if (id != null && id <= 0) throw ArgumentError.value(id, 'id');
    }
    final generation = expectedGeneration ?? apiClient.authSession.generation;
    var requestActive = true;
    bool current() =>
        requestActive &&
        apiClient.authSession.isCurrentGeneration(generation) &&
        !apiClient.authSession.isLoggingOut &&
        isRequestCurrent?.call() != false;
    if (!current()) throw const StaleSessionException();
    final path = Uri(
      path: '/api/v1/bus-routes/$routeId/boarding-candidates',
      queryParameters: {
        'targetStopOccurrenceId': '$targetStopOccurrenceId',
        if (boardingStopOccurrenceId != null)
          'boardingStopOccurrenceId': '$boardingStopOccurrenceId',
      },
    ).toString();
    late final http.Response response;
    try {
      response = await apiClient
          .request(
            'GET',
            path,
            expectedGeneration: generation,
            isRequestCurrent: current,
          )
          .timeout(const Duration(seconds: 15));
    } on TimeoutException {
      // timeout does not cancel the transport. Block a late 401 from refreshing
      // credentials and retrying a request whose screen deadline has passed.
      requestActive = false;
      throw const BoardingCandidateException(CandidateFailure.timeout);
    } on http.ClientException {
      throw const BoardingCandidateException(CandidateFailure.network);
    } on IOException {
      throw const BoardingCandidateException(CandidateFailure.network);
    }
    if (!current()) throw const StaleSessionException();
    if (response.statusCode != 200) {
      String? code;
      try {
        final error = jsonDecode(utf8.decode(response.bodyBytes));
        if (error is Map<String, dynamic> && error['code'] is String) {
          code = error['code'] as String;
        }
      } on FormatException {
        // Preserve HTTP status even if the error body cannot be decoded.
      }
      throw BoardingCandidateException(
        CandidateFailure.http,
        statusCode: response.statusCode,
        code: code,
      );
    }
    try {
      return BoardingCandidates.fromJson(
        jsonDecode(utf8.decode(response.bodyBytes)),
      );
    } on FormatException {
      throw const BoardingCandidateException(CandidateFailure.parsing);
    }
  }
}

enum CandidateFailure { http, network, timeout, parsing }

class BoardingCandidateException implements Exception {
  const BoardingCandidateException(this.failure, {this.statusCode, this.code});
  final CandidateFailure failure;
  final int? statusCode;
  final String? code;
}
