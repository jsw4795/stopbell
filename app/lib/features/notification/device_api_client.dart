import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../core/authenticated_api_client.dart';

class DeviceState {
  const DeviceState({
    required this.enabled,
    required this.revision,
    required this.generation,
    this.ownedByCurrentUser = true,
  });
  final bool enabled;
  final int revision;
  final int generation;
  final bool ownedByCurrentUser;
}

abstract interface class DeviceClient {
  Future<DeviceState?> find(
    String id,
    String credential,
    int sessionGeneration,
  );
  Future<DeviceState> mutate(
    Map<String, dynamic> payload,
    String kind,
    String credential,
    int sessionGeneration,
  );
}

class DeviceApiClient implements DeviceClient {
  DeviceApiClient(this.api, {this.timeout = const Duration(seconds: 8)});
  final AuthenticatedApiClient api;
  final Duration timeout;

  @override
  Future<DeviceState?> find(
    String id,
    String credential,
    int sessionGeneration,
  ) async {
    try {
      return await _request(
        'GET',
        '/api/v1/devices/$id',
        credential,
        sessionGeneration,
        id: id,
        query: true,
      );
    } on DeviceApiException catch (error) {
      if (error.status == 404 && error.code == 'DEVICE_NOT_FOUND') return null;
      rethrow;
    }
  }

  @override
  Future<DeviceState> mutate(
    Map<String, dynamic> payload,
    String kind,
    String credential,
    int sessionGeneration,
  ) => _request(
    'POST',
    kind == 'disable' ? '/api/v1/devices/disable' : '/api/v1/devices',
    credential,
    sessionGeneration,
    id: payload['installationId'] as String,
    payload: payload,
  );

  Future<DeviceState> _request(
    String method,
    String path,
    String credential,
    int generation, {
    required String id,
    Map<String, dynamic>? payload,
    bool query = false,
  }) async {
    var active = true;
    final responseFuture = api.request(
      method,
      path,
      isRequestCurrent: () => active,
      expectedGeneration: generation,
      headers: {
        'X-Installation-Credential': credential,
        'Content-Type': 'application/json',
      },
      body: payload == null ? null : jsonEncode(payload),
    );
    late final http.Response response;
    try {
      response = await responseFuture.timeout(timeout);
    } finally {
      active = false;
    }
    if (response.statusCode != 200 &&
        !(method == 'POST' &&
            response.statusCode == 201 &&
            !path.endsWith('/disable'))) {
      String? code;
      try {
        final value = jsonDecode(utf8.decode(response.bodyBytes));
        if (value is Map && DeviceApiException.codes.contains(value['code'])) {
          code = value['code'] as String;
        }
      } catch (_) {
        /* Do not expose backend response bodies. */
      }
      throw DeviceApiException(response.statusCode, code);
    }
    try {
      final value = jsonDecode(utf8.decode(response.bodyBytes));
      if (value is! Map ||
          value['installationId'] != id ||
          value['enabled'] is! bool ||
          value['registrationRevision'] is! int ||
          value['registrationRevision'] < 0 ||
          value['ownershipGeneration'] is! int ||
          value['ownershipGeneration'] < 0 ||
          (query && value['ownedByCurrentUser'] is! bool)) {
        throw const FormatException();
      }
      return DeviceState(
        enabled: value['enabled'] as bool,
        revision: value['registrationRevision'] as int,
        generation: value['ownershipGeneration'] as int,
        ownedByCurrentUser: query ? value['ownedByCurrentUser'] as bool : true,
      );
    } catch (_) {
      // A malformed success could have committed: retain the pending intent.
      throw const DeviceResponseException();
    }
  }
}

class DeviceApiException implements Exception {
  const DeviceApiException(this.status, this.code);
  final int status;
  final String? code;
  bool get definitive => status >= 400 && status < 500;
  static const codes = {
    'INVALID_REQUEST',
    'DEVICE_CREDENTIAL_INVALID',
    'DEVICE_NOT_FOUND',
    'DEVICE_OWNERSHIP_CONFLICT',
    'DEVICE_PLATFORM_CONFLICT',
    'DEVICE_REGISTRATION_STALE',
    'DEVICE_REVISION_CONFLICT',
    'DEVICE_PUSH_TARGET_CONFLICT',
  };
}

class DeviceResponseException implements Exception {
  const DeviceResponseException();
}
