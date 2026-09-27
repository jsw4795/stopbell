import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';

const pair = TokenPair(
  accessToken: 'fixture-access',
  refreshToken: 'fixture-refresh',
);
final baseUrl = Uri.parse('http://localhost:8080');

http.Response jsonResponse(String body, int status) => http.Response(
  body,
  status,
  headers: const {'content-type': 'application/json; charset=utf-8'},
);

class _Storage implements TokenPairStorage {
  @override
  Future<TokenPair?> read() async => pair;
  @override
  Future<void> save(TokenPair pair) async {}
  @override
  Future<void> delete() async {}
}

class _Authenticator implements Authenticator {
  @override
  Future<TokenPair> login() async => pair;
}

Future<AlarmApiClient> alarmClient(http.Client transport) async {
  final auth = AuthSession(
    authenticator: _Authenticator(),
    backend: BackendAuthClient(apiBaseUrl: baseUrl),
    tokenStorage: _Storage(),
  );
  await auth.initialize();
  return AlarmApiClient(
    AuthenticatedApiClient(
      apiBaseUrl: baseUrl,
      authSession: auth,
      client: transport,
    ),
  );
}

Map<String, Object> alarm([int id = 15, String status = 'INACTIVE']) => {
  'id': id,
  'transitType': 'BUS',
  'status': status,
  'routeNumber': '7000',
  'stopName': '사색의광장',
  'notifyOneStopBefore': true,
  'notifyOneStopAfter': false,
};

void main() {
  test('create sends only occurrence selection and options through protected client', () async {
    var calls = 0;
    final client = await alarmClient(
      MockClient((request) async {
        calls++;
        expect(request.method, 'POST');
        expect(request.url.path, '/api/v1/alarms');
        expect(request.headers['authorization'], 'Bearer fixture-access');
        expect(request.headers['content-type'], startsWith('application/json'));
        expect(jsonDecode(request.body), {
          'targetStopOccurrenceId': 12345,
          'notifyOneStopBefore': true,
          'notifyOneStopAfter': false,
        });
        return jsonResponse(jsonEncode(alarm()), 201);
      }),
    );
    final created = await client.create(
      targetStopOccurrenceId: 12345,
      notifyOneStopBefore: true,
      notifyOneStopAfter: false,
    );
    expect(calls, 1);
    expect(created.id, 15);
    expect(created.transitType, AlarmTransitType.bus);
    expect(created.status, AlarmStatus.inactive);
    expect(created.routeNumber, '7000');
    expect(created.stopName, '사색의광장');
    expect(created.notifyOneStopBefore, isTrue);
    expect(created.notifyOneStopAfter, isFalse);
  });

  test('list preserves server order and three distinct statuses', () async {
    final client = await alarmClient(
      MockClient(
        (_) async => jsonResponse(
          jsonEncode([alarm(3, 'FOLLOW_UP'), alarm(2, 'ACTIVE'), alarm(1)]),
          200,
        ),
      ),
    );
    final result = await client.findAll();
    expect(result.map((item) => item.id), [3, 2, 1]);
    expect(result.map((item) => item.status), [
      AlarmStatus.followUp,
      AlarmStatus.active,
      AlarmStatus.inactive,
    ]);
  });

  test('empty list succeeds and malformed items or roots fail', () async {
    final empty = await alarmClient(
      MockClient((_) async => jsonResponse('[]', 200)),
    );
    expect(await empty.findAll(), isEmpty);
    for (final body in ['{}', '[{}]', '[${jsonEncode(alarm())},{}]', '{']) {
      final client = await alarmClient(
        MockClient((_) async => jsonResponse(body, 200)),
      );
      await expectLater(client.findAll(), throwsA(isA<AlarmApiException>()));
    }
  });

  test('required response fields and enums reject malformed values', () async {
    final valid = alarm();
    final invalid = <Map<String, Object?>>[
      for (final field in valid.keys)
        <String, Object?>{...valid}..remove(field),
      ...[
        {'id': 0},
        {'id': '15'},
        {'transitType': 'TRAIN'},
        {'status': 'UNKNOWN'},
        {'routeNumber': ' '},
        {'routeNumber': 3},
        {'stopName': ''},
        {'stopName': 3},
        {'notifyOneStopBefore': 1},
        {'notifyOneStopAfter': 'false'},
      ].map((change) => <String, Object?>{...valid, ...change}),
    ];
    for (final value in invalid) {
      final client = await alarmClient(
        MockClient((_) async => jsonResponse(jsonEncode(value), 200)),
      );
      await expectLater(client.findById(15), throwsA(isA<AlarmApiException>()));
    }
    final malformed = await alarmClient(
      MockClient((_) async => jsonResponse('{', 200)),
    );
    await expectLater(
      malformed.findById(15),
      throwsA(isA<AlarmApiException>()),
    );
  });

  test(
    'detail, lifecycle, and delete use exact paths and status codes',
    () async {
      final paths = <String>[];
      final client = await alarmClient(
        MockClient((request) async {
          paths.add('${request.method} ${request.url.path}');
          if (request.method == 'DELETE') return jsonResponse('', 204);
          return jsonResponse(
            jsonEncode(
              alarm(
                15,
                  request.url.path.endsWith('/activate') ? 'ACTIVE' : 'INACTIVE',
              ),
            ),
            200,
          );
        }),
      );
      expect((await client.findById(15)).id, 15);
      expect((await client.activate(15)).status, AlarmStatus.active);
      expect((await client.deactivate(15)).status, AlarmStatus.inactive);
      await client.delete(15);
      expect(paths, [
        'GET /api/v1/alarms/15',
        'POST /api/v1/alarms/15/activate',
        'POST /api/v1/alarms/15/deactivate',
        'DELETE /api/v1/alarms/15',
      ]);
    },
  );

  test(
    'strict status and structured HTTP errors survive malformed error bodies',
    () async {
      for (final operation in <String>[
        'create',
        'list',
        'detail',
        'activate',
        'deactivate',
        'delete',
      ]) {
        final status = operation == 'create'
            ? 200
            : operation == 'delete'
            ? 200
            : 201;
        final client = await alarmClient(
          MockClient((_) async => jsonResponse('{}', status)),
        );
        Future<Object?> call() => switch (operation) {
          'create' => client.create(
            targetStopOccurrenceId: 1,
            notifyOneStopBefore: false,
            notifyOneStopAfter: false,
          ),
          'list' => client.findAll(),
          'detail' => client.findById(1),
          'activate' => client.activate(1),
          'deactivate' => client.deactivate(1),
          _ => client.delete(1),
        };
        await expectLater(
          call(),
          throwsA(
            isA<AlarmApiException>().having(
              (error) => error.statusCode,
              'statusCode',
              status,
            ),
          ),
        );
      }
      for (final code in [
        'TARGET_STOP_OCCURRENCE_NOT_FOUND',
        'ALARM_NOT_FOUND',
      ]) {
        final client = await alarmClient(
          MockClient(
            (_) async => jsonResponse(
              jsonEncode({'code': code, 'message': 'not found'}),
              404,
            ),
          ),
        );
        await expectLater(
          client.findById(1),
          throwsA(
            isA<AlarmApiException>().having(
              (error) => error.code,
              'code',
              code,
            ),
          ),
        );
      }
      final malformed = await alarmClient(
        MockClient((_) async => jsonResponse('{', 404)),
      );
      await expectLater(
        malformed.findById(1),
        throwsA(
          isA<AlarmApiException>().having(
            (error) => error.statusCode,
            'statusCode',
            404,
          ),
        ),
      );
    },
  );
}
