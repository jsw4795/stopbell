import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:stopbell/core/authenticated_api_client.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/notification/device_api_client.dart';

import 'notification_test_fakes.dart';

const installation = 'a1234567-1234-4123-8123-123456789abc';
Map<String, dynamic> payload() => {
  'installationId': installation,
  'platform': 'IOS',
  'pushRegistrationId': 'fixture-FID',
  'registrationRevision': 1,
  'expectedOwnershipGeneration': 0,
  'transferOwnership': false,
};
String responseBody({bool query = false, bool enabled = true}) => jsonEncode({
  'id': 1,
  'installationId': installation,
  'enabled': enabled,
  'registrationRevision': 1,
  'ownershipGeneration': 0,
  if (query) 'ownedByCurrentUser': false,
});
DeviceApiClient client(PushFixture f, MockClient mock, {Duration? timeout}) =>
    DeviceApiClient(
      AuthenticatedApiClient(
        apiBaseUrl: Uri.parse('https://stopbell.test'),
        authSession: f.auth,
        client: mock,
      ),
      timeout: timeout ?? const Duration(seconds: 1),
    );

void main() {
  test(
    '201/200과 조회 ownership, disable을 기존 authenticated client로 처리한다',
    () async {
      final f = PushFixture();
      await f.initialize();
      final captured = <http.Request>[];
      final api = client(
        f,
        MockClient((request) async {
          captured.add(request);
          expect(
            request.headers['Authorization'],
            'Bearer ${f.auth.accessToken}',
          );
          expect(
            request.headers['X-Installation-Credential'],
            'fixture-credential',
          );
          if (request.method == 'GET') {
            return http.Response(responseBody(query: true), 200);
          }
          return http.Response(
            responseBody(enabled: !request.url.path.endsWith('/disable')),
            captured.length == 1 ? 201 : 200,
          );
        }),
      );
      expect(
        (await api.mutate(
          payload(),
          'register',
          'fixture-credential',
          f.auth.generation,
        )).enabled,
        true,
      );
      expect(
        (await api.mutate(
          payload(),
          'register',
          'fixture-credential',
          f.auth.generation,
        )).revision,
        1,
      );
      expect(
        (await api.find(
          installation,
          'fixture-credential',
          f.auth.generation,
        ))!.ownedByCurrentUser,
        false,
      );
      expect(
        (await api.mutate(
          {
            'installationId': installation,
            'registrationRevision': 1,
            'expectedOwnershipGeneration': 0,
          },
          'disable',
          'fixture-credential',
          f.auth.generation,
        )).enabled,
        false,
      );
      expect(captured.last.url.path, '/api/v1/devices/disable');
      expect(jsonDecode(captured.first.body), payload());
    },
  );
  test('모든 Device 오류 code를 구분하고 민감한 오류 원문은 보존하지 않는다', () async {
    final f = PushFixture();
    await f.initialize();
    for (final code in DeviceApiException.codes) {
      final api = client(
        f,
        MockClient(
          (_) async => http.Response(
            jsonEncode({'code': code, 'message': 'secret-raw-FID'}),
            409,
          ),
        ),
      );
      try {
        await api.mutate(
          payload(),
          'register',
          'fixture-credential',
          f.auth.generation,
        );
        fail('must reject');
      } on DeviceApiException catch (error) {
        expect(error.code, code);
        expect(error.definitive, true);
        expect(error.toString(), isNot(contains('secret-raw-FID')));
      }
    }
    final missing = client(
      f,
      MockClient(
        (_) async => http.Response('{"code":"DEVICE_NOT_FOUND"}', 404),
      ),
    );
    expect(
      await missing.find(installation, 'fixture-credential', f.auth.generation),
      isNull,
    );
  });
  test('같은 login generation의 401은 rotation 후 동일 body를 재전송한다', () async {
    final f = PushFixture();
    await f.initialize();
    final requests = <http.Request>[];
    final api = client(
      f,
      MockClient((request) async {
        requests.add(request);
        return requests.length == 1
            ? http.Response('', 401)
            : http.Response(responseBody(), 200);
      }),
    );
    final identity = f.auth.sessionIdentity;
    await api.mutate(
      payload(),
      'register',
      'fixture-credential',
      f.auth.generation,
    );
    expect(f.backend.refreshCalls, 1);
    expect(requests[0].body, requests[1].body);
    expect(
      requests[0].headers['Authorization'],
      isNot(requests[1].headers['Authorization']),
    );
    expect(f.auth.sessionIdentity, identity);
    expect(f.tokens.pair!.sessionIdentity, identity);
  });
  test('A 요청의 401이 B login 이후 도착하면 B JWT로 retry하지 않는다', () async {
    final f = PushFixture();
    await f.initialize();
    final delayed = Completer<http.Response>();
    final sent = Completer<void>();
    var calls = 0;
    final api = client(
      f,
      MockClient((_) {
        calls++;
        sent.complete();
        return delayed.future;
      }),
    );
    final request = api.mutate(
      payload(),
      'register',
      'fixture-credential',
      f.auth.generation,
    );
    final check = expectLater(request, throwsA(isA<StaleSessionException>()));
    await sent.future;
    await f.switchUser('B');
    delayed.complete(http.Response('', 401));
    await check;
    expect(calls, 1);
    expect(f.backend.refreshCalls, 0);
  });
  test('timeout 이후 도착한 401은 뒤늦은 refresh와 재전송을 막는다', () async {
    final f = PushFixture();
    await f.initialize();
    final delayed = Completer<http.Response>();
    var calls = 0;
    final api = client(
      f,
      MockClient((_) {
        calls++;
        return delayed.future;
      }),
      timeout: const Duration(milliseconds: 10),
    );
    await expectLater(
      api.mutate(
        payload(),
        'register',
        'fixture-credential',
        f.auth.generation,
      ),
      throwsA(isA<TimeoutException>()),
    );
    delayed.complete(http.Response('', 401));
    await Future<void>.delayed(Duration.zero);
    expect(calls, 1);
    expect(f.backend.refreshCalls, 0);
  });
  test('expired 요청의 refresh 결과를 받더라도 mutation 재전송은 막는다', () async {
    final f = PushFixture();
    await f.initialize();
    final rotated = Completer<TokenPair>();
    f.backend.rotation = (_) => rotated.future;
    var calls = 0;
    final api = client(
      f,
      MockClient((_) async {
        calls++;
        return http.Response('', 401);
      }),
      timeout: const Duration(milliseconds: 10),
    );
    await expectLater(
      api.mutate(
        payload(),
        'register',
        'fixture-credential',
        f.auth.generation,
      ),
      throwsA(isA<TimeoutException>()),
    );
    rotated.complete(userPair('A', rotation: '2'));
    await Future<void>.delayed(Duration.zero);
    expect(calls, 1);
    expect(f.backend.refreshCalls, 1);
  });
}
