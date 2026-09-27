import 'dart:convert';

import '../../core/authenticated_api_client.dart';
import 'alarm.dart';

abstract class AlarmClient {
  Future<Alarm> create({
    required int targetStopOccurrenceId,
    required bool notifyOneStopBefore,
    required bool notifyOneStopAfter,
  });
  Future<List<Alarm>> findAll();
  Future<Alarm> findById(int alarmId);
  Future<Alarm> activate(int alarmId);
  Future<Alarm> deactivate(int alarmId);
  Future<void> delete(int alarmId);
}

class AlarmApiClient implements AlarmClient {
  const AlarmApiClient(this.apiClient);

  final AuthenticatedApiClient apiClient;

  @override
  Future<Alarm> create({
    required int targetStopOccurrenceId,
    required bool notifyOneStopBefore,
    required bool notifyOneStopAfter,
  }) async {
    if (targetStopOccurrenceId <= 0) {
      throw ArgumentError.value(
        targetStopOccurrenceId,
        'targetStopOccurrenceId',
      );
    }
    final response = await apiClient
        .request(
          'POST',
          '/api/v1/alarms',
          headers: const {'Content-Type': 'application/json'},
          body: jsonEncode({
            'targetStopOccurrenceId': targetStopOccurrenceId,
            'notifyOneStopBefore': notifyOneStopBefore,
            'notifyOneStopAfter': notifyOneStopAfter,
          }),
        )
        .timeout(const Duration(seconds: 15));
    return _alarm(response.statusCode, response.bodyBytes, 201);
  }

  @override
  Future<List<Alarm>> findAll() async {
    final response = await apiClient
        .request('GET', '/api/v1/alarms')
        .timeout(const Duration(seconds: 15));
    _checkStatus(response.statusCode, response.bodyBytes, 200);
    try {
      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      if (decoded is! List) throw const FormatException();
      return decoded.map((value) => Alarm.fromJson(value)).toList();
    } on FormatException {
      throw const AlarmApiException();
    }
  }

  @override
  Future<Alarm> findById(int alarmId) => _getAlarm('GET', _path(alarmId), 200);
  @override
  Future<Alarm> activate(int alarmId) =>
      _getAlarm('POST', '${_path(alarmId)}/activate', 200);
  @override
  Future<Alarm> deactivate(int alarmId) =>
      _getAlarm('POST', '${_path(alarmId)}/deactivate', 200);

  @override
  Future<void> delete(int alarmId) async {
    final response = await apiClient
        .request('DELETE', _path(alarmId))
        .timeout(const Duration(seconds: 15));
    _checkStatus(response.statusCode, response.bodyBytes, 204);
  }

  Future<Alarm> _getAlarm(String method, String path, int status) async {
    final response = await apiClient
        .request(method, path)
        .timeout(const Duration(seconds: 15));
    return _alarm(response.statusCode, response.bodyBytes, status);
  }

  String _path(int id) {
    if (id <= 0) throw ArgumentError.value(id, 'alarmId');
    return '/api/v1/alarms/$id';
  }

  Alarm _alarm(int statusCode, List<int> bodyBytes, int expectedStatus) {
    _checkStatus(statusCode, bodyBytes, expectedStatus);
    try {
      return Alarm.fromJson(jsonDecode(utf8.decode(bodyBytes)));
    } on FormatException {
      throw const AlarmApiException();
    }
  }

  void _checkStatus(int statusCode, List<int> bodyBytes, int expectedStatus) {
    if (statusCode == expectedStatus) return;
    String? code;
    String? message;
    try {
      final decoded = jsonDecode(utf8.decode(bodyBytes));
      if (decoded is Map<String, dynamic>) {
        if (decoded['code'] is String) code = decoded['code'] as String;
        if (decoded['message'] is String) {
          message = decoded['message'] as String;
        }
      }
    } catch (_) {
      // The HTTP failure remains actionable even with an invalid error body.
    }
    throw AlarmApiException(
      statusCode: statusCode,
      code: code,
      message: message,
    );
  }
}

class AlarmApiException implements Exception {
  const AlarmApiException({this.statusCode, this.code, this.message});

  final int? statusCode;
  final String? code;
  final String? message;

  bool get isAlarmNotFound => statusCode == 404 && code == 'ALARM_NOT_FOUND';
  bool get isStaleTarget =>
      statusCode == 404 && code == 'TARGET_STOP_OCCURRENCE_NOT_FOUND';
}
