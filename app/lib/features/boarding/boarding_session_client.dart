import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../../core/authenticated_api_client.dart';
import '../auth/auth_session.dart';
import 'boarding_session.dart';

abstract interface class BoardingSessionClient {
  Future<BoardingSession> create(
    Map<String, Object> body, {
    required int generation,
    required bool Function() current,
  });
  Future<BoardingSession> find(
    int id, {
    required int generation,
    required bool Function() current,
  });
  Future<BoardingSession?> findCurrent({
    required int generation,
    required bool Function() current,
  });
  Future<BoardingSession?> findRequest(
    String uuid, {
    required int generation,
    required bool Function() current,
  });
  Future<BoardingSession> cancel(
    int id,
    int revision, {
    required int generation,
    required bool Function() current,
  });
  Future<BoardingSession> reconfirm(
    int id,
    int revision,
    String reference,
    String number, {
    required int generation,
    required bool Function() current,
  });
}

bool isBoardingUuid(String value) => RegExp(
  r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$',
).hasMatch(value);

enum SessionFailure { http, timeout, network, parsing }

class BoardingSessionException implements Exception {
  const BoardingSessionException(this.failure, {this.statusCode, this.code});
  final SessionFailure failure;
  final int? statusCode;
  final String? code;
  bool get uncertain =>
      failure != SessionFailure.http ||
      statusCode == null ||
      statusCode! >= 500;
}

class BoardingSessionApiClient implements BoardingSessionClient {
  const BoardingSessionApiClient(
    this.api, {
    this.timeout = const Duration(seconds: 15),
  });
  final AuthenticatedApiClient api;
  final Duration timeout;
  static const _path = '/api/v1/boarding-sessions';

  @override
  Future<BoardingSession> create(
    Map<String, Object> body, {
    required int generation,
    required bool Function() current,
  }) async {
    if ((body.length < 2 || body.length > 4) ||
        (body.containsKey('notificationOffsetStops') &&
            (body['notificationOffsetStops'] is! int ||
                !const [1, 2].contains(body['notificationOffsetStops']))) ||
        (body.containsKey('notificationOffsetStops') &&
            body.containsKey('prepareNotificationEnabled')) ||
        (body.containsKey('prepareNotificationEnabled') &&
            body['prepareNotificationEnabled'] is! bool) ||
        body.keys.any(
          (key) => !const {
            'clientRequestId',
            'candidateRef',
            'confirmedVehicleNumber',
            'prepareNotificationEnabled',
            'notificationOffsetStops',
          }.contains(key),
        ) ||
        body['clientRequestId'] is! String ||
        !isBoardingUuid(body['clientRequestId'] as String)) {
      throw ArgumentError('clientRequestId');
    }
    _reference(body['candidateRef'], body['confirmedVehicleNumber']);
    try {
      return BoardingSession.fromJson(
        await _request(
          'POST',
          _path,
          generation,
          current,
          body: body,
          created: true,
        ),
      );
    } on FormatException {
      throw const BoardingSessionException(SessionFailure.parsing);
    }
  }

  @override
  Future<BoardingSession> find(
    int id, {
    required int generation,
    required bool Function() current,
  }) async {
    _id(id);
    return _sessionRequest('GET', '$_path/$id', generation, current, id: id);
  }

  @override
  Future<BoardingSession?> findCurrent({
    required int generation,
    required bool Function() current,
  }) => _list('scope', 'current', generation, current);
  @override
  Future<BoardingSession?> findRequest(
    String uuid, {
    required int generation,
    required bool Function() current,
  }) {
    if (!isBoardingUuid(uuid)) throw ArgumentError('clientRequestId');
    return _list('clientRequestId', uuid, generation, current);
  }

  @override
  Future<BoardingSession> cancel(
    int id,
    int revision, {
    required int generation,
    required bool Function() current,
  }) {
    _id(id);
    _revision(revision);
    return _sessionRequest(
      'POST',
      '$_path/$id/cancel',
      generation,
      current,
      id: id,
      body: {'expectedRevision': revision},
    );
  }

  @override
  Future<BoardingSession> reconfirm(
    int id,
    int revision,
    String reference,
    String number, {
    required int generation,
    required bool Function() current,
  }) {
    _id(id);
    _revision(revision);
    _reference(reference, number);
    return _sessionRequest(
      'POST',
      '$_path/$id/reconfirm',
      generation,
      current,
      id: id,
      body: {'expectedRevision': revision, 'candidateRef': reference},
    );
  }

  Future<BoardingSession> _sessionRequest(
    String method,
    String path,
    int generation,
    bool Function() current, {
    required int id,
    Map<String, Object>? body,
  }) async {
    final response = await _request(
      method,
      path,
      generation,
      current,
      body: body,
    );
    try {
      final session = BoardingSession.fromJson(response);
      if (session.id != id) throw const FormatException();
      return session;
    } on FormatException {
      throw const BoardingSessionException(SessionFailure.parsing);
    }
  }

  Future<BoardingSession?> _list(
    String key,
    String value,
    int generation,
    bool Function() current,
  ) async {
    final json = await _request(
      'GET',
      Uri(path: _path, queryParameters: {key: value}).toString(),
      generation,
      current,
    );
    try {
      if (json is! List || json.length > 1) throw const FormatException();
      final session = json.isEmpty
          ? null
          : BoardingSession.fromJson(json.single);
      if (key == 'scope' && session != null && !session.isOpen) {
        throw const FormatException();
      }
      return session;
    } on FormatException {
      throw const BoardingSessionException(SessionFailure.parsing);
    }
  }

  Future<Object?> _request(
    String method,
    String path,
    int generation,
    bool Function() current, {
    Map<String, Object>? body,
    bool created = false,
  }) async {
    var active = true;
    bool valid() =>
        active &&
        api.authSession.isCurrentGeneration(generation) &&
        !api.authSession.isLoggingOut &&
        current();
    if (!valid()) throw const StaleSessionException();
    late final http.Response response;
    try {
      response = await api
          .request(
            method,
            path,
            headers: body == null
                ? const {}
                : {'Content-Type': 'application/json'},
            body: body == null ? null : jsonEncode(body),
            expectedGeneration: generation,
            isRequestCurrent: valid,
          )
          .timeout(timeout);
    } on TimeoutException {
      active =
          false; // Also prevents a late 401 from refreshing/replaying a POST.
      throw const BoardingSessionException(SessionFailure.timeout);
    } on http.ClientException {
      throw const BoardingSessionException(SessionFailure.network);
    } on IOException {
      throw const BoardingSessionException(SessionFailure.network);
    }
    if (!valid()) throw const StaleSessionException();
    if (response.statusCode != 200 &&
        !(created && response.statusCode == 201)) {
      String? code;
      try {
        final json = jsonDecode(utf8.decode(response.bodyBytes));
        if (json is Map<String, dynamic> && json['code'] is String) {
          code = json['code'] as String;
        }
      } on FormatException {
        /* Preserve status for invalid error JSON. */
      }
      throw BoardingSessionException(
        SessionFailure.http,
        statusCode: response.statusCode,
        code: code,
      );
    }
    try {
      return jsonDecode(utf8.decode(response.bodyBytes));
    } on FormatException {
      throw const BoardingSessionException(SessionFailure.parsing);
    }
  }

  void _id(int id) {
    if (id <= 0) throw ArgumentError('sessionId');
  }

  void _revision(int revision) {
    if (revision < 0) throw ArgumentError('revision');
  }

  void _reference(Object? reference, Object? number) {
    for (final value in [reference, ?number]) {
      if (value is! String || value.trim().isEmpty || value.length > 255) {
        throw ArgumentError('vehicle confirmation');
      }
    }
  }
}
