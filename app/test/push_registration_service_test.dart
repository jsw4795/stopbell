import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/notification/device_api_client.dart';
import 'package:stopbell/features/notification/installation_storage.dart';
import 'package:stopbell/features/notification/push_permission_service.dart';
import 'package:stopbell/features/notification/push_registration_service.dart';

import 'notification_test_fakes.dart';

void main() {
  test('최초 등록은 durable r1이며 같은 FID resume는 mutation 없이 완료한다', () async {
    final f = PushFixture();
    await f.initialize();
    expect(await f.service.synchronize(), PushReadiness.ready);
    expect(await f.service.synchronize(), PushReadiness.ready);
    expect(f.devices.requests.length, 1);
    expect(f.devices.requests.single['registrationRevision'], 1);
    expect(f.devices.requests.single['transferOwnership'], false);
    final restored = await InstallationStorage(
      f.persistence,
      f.tokens,
    ).initialize();
    expect(restored.revision, 1);
    expect(restored.pending, isNull);
    expect(restored.lastRegistration?['fid'], 'FID-A');
  });
  test('FID rotation은 높은 revision으로 같은 ownership을 갱신한다', () async {
    final f = PushFixture();
    await f.initialize();
    await f.service.synchronize();
    f.provider.fid = 'FID-B';
    f.service.fidChanged();
    expect(await f.service.synchronize(), PushReadiness.ready);
    expect(f.devices.requests.last['registrationRevision'], 2);
    expect(f.devices.requests.last['transferOwnership'], false);
    expect(f.devices.generation, 0);
  });
  test('응답 유실은 같은 payload와 revision으로 재전송 후 restart에서 복구한다', () async {
    final f = PushFixture();
    await f.initialize();
    var first = true;
    f.devices.mutation = (payload, kind) async {
      final state = f.devices.apply(payload, kind);
      if (first) {
        first = false;
        throw TimeoutException('fixture');
      }
      return state;
    };
    expect(await f.service.synchronize(), PushReadiness.backendFailed);
    final pending = jsonDecode(f.persistence.value!)['pending'];
    final restoredAuth = AuthSession(
      authenticator: f.authenticator,
      backend: f.backend,
      tokenStorage: f.tokens,
    );
    await restoredAuth.initialize();
    expect(restoredAuth.sessionIdentity, f.auth.sessionIdentity);
    final next = PushRegistrationService(
      auth: restoredAuth,
      storage: InstallationStorage(f.persistence, f.tokens),
      devices: f.devices,
      provider: f.provider,
    );
    expect(await next.synchronize(), PushReadiness.ready);
    expect(f.devices.requests[1], f.devices.requests[0]);
    expect(f.devices.requests[1], pending['payload']);
    expect(jsonDecode(f.persistence.value!)['pending'], isNull);
  });
  test('일시 실패 후 FID 변경은 기존 intent 복구 뒤 새 revision을 저장한다', () async {
    final f = PushFixture();
    await f.initialize();
    f.devices.mutation = (_, _) async => throw TimeoutException('fixture');
    await f.service.synchronize();
    f.devices.mutation = null;
    f.provider.fid = 'FID-B';
    expect(await f.service.synchronize(), PushReadiness.ready);
    expect(f.devices.requests.map((p) => p['registrationRevision']), [1, 1, 2]);
    expect(f.devices.fid, 'FID-B');
  });
  test('409는 자동 이전 loop를 막고 새 명시적 의도에서만 상태를 조회한다', () async {
    final f = PushFixture();
    await f.initialize();
    f.devices.mutation = (_, _) async =>
        throw const DeviceApiException(409, 'DEVICE_OWNERSHIP_CONFLICT');
    expect(await f.service.synchronize(), PushReadiness.backendFailed);
    expect(await f.service.synchronize(), PushReadiness.backendFailed);
    expect(f.devices.requests.length, 1);
    f.devices.mutation = null;
    expect(await f.service.synchronize(explicit: true), PushReadiness.ready);
    expect(f.devices.requests.last['registrationRevision'], 2);
  });
  test('A→B→A는 설치본을 보존하고 서버 generation만 사용한다', () async {
    final f = PushFixture();
    await f.initialize();
    await f.service.synchronize();
    final original = (await f.storage.initialize()).id;
    final sessionA = f.auth.sessionIdentity;
    await f.switchUser('B');
    await f.service.synchronize();
    expect(f.devices.requests.last['expectedOwnershipGeneration'], 0);
    expect(f.devices.requests.last['transferOwnership'], true);
    expect(f.devices.generation, 1);
    await f.switchUser('A');
    await f.service.synchronize();
    expect(f.auth.sessionIdentity, isNot(sessionA));
    expect(f.devices.requests.last['expectedOwnershipGeneration'], 1);
    expect(f.devices.generation, 2);
    expect((await f.storage.initialize()).id, original);
    expect(f.devices.users, ['A', 'B', 'A']);
  });
  test('A pending을 B JWT로 재전송하지 않고 B의 새 이전 intent를 생성한다', () async {
    final f = PushFixture();
    await f.initialize();
    f.devices.mutation = (payload, kind) async {
      f.devices.apply(payload, kind);
      throw TimeoutException('fixture');
    };
    await f.service.synchronize();
    await f.switchUser('B');
    f.devices.mutation = null;
    await f.service.synchronize();
    expect(f.devices.users, ['A', 'B']);
    expect(f.devices.requests[1]['registrationRevision'], 2);
    expect(f.devices.requests[1]['transferOwnership'], true);
  });
  test('지연된 A 결과는 폐기하고 queue에 대기한 B 동기화를 실행한다', () async {
    final f = PushFixture();
    await f.initialize();
    final sent = Completer<void>();
    final delayed = Completer<DeviceState>();
    f.devices.mutation = (payload, kind) {
      f.devices.apply(payload, kind);
      sent.complete();
      return delayed.future;
    };
    final old = f.service.synchronize();
    await sent.future;
    await f.switchUser('B');
    final next = f.service.synchronize();
    f.devices.mutation = null;
    delayed.complete(
      const DeviceState(enabled: true, revision: 1, generation: 0),
    );
    expect(await old, PushReadiness.stale);
    expect(await next, PushReadiness.ready);
    expect((await f.storage.initialize()).lastRegistration?['user'], 'B');
  });
  test('Logout hook은 높은 revision으로 현재 기기만 disable하고 identity를 유지한다', () async {
    final f = PushFixture();
    await f.initialize();
    await f.service.synchronize();
    final record = await f.storage.initialize();
    final id = record.id;
    final credential = record.credential;
    await f.auth.logout(beforeLogout: f.service.beforeLogout);
    expect(f.devices.enabled, false);
    expect(f.devices.fid, isNull);
    expect(f.devices.requests.last['registrationRevision'], 2);
    expect(f.auth.state, AuthState.unauthenticated);
    expect(record.id, id);
    expect(record.credential, credential);
    expect(record.revision, 2);
    expect(f.backend.logoutCalls, 1);
  });
  test(
    'Offline disable 실패에도 logout 진행 후 원래 session의 secure pending만 남긴다',
    () async {
      final f = PushFixture();
      await f.initialize();
      await f.service.synchronize();
      f.devices.mutation = (_, _) async => throw TimeoutException('offline');
      f.backend.offline = true;
      await expectLater(
        f.auth.logout(beforeLogout: f.service.beforeLogout),
        throwsA(isA<BackendLogoutCleanupException>()),
      );
      expect(f.auth.state, AuthState.unauthenticated);
      final pending = (await f.storage.initialize()).pending!;
      expect(pending['kind'], 'disable');
      expect(pending['user'], 'A');
      expect(pending['accessToken'], userPair('A').accessToken);
    },
  );
  test('권한 거부와 provider 실패는 서버 성공으로 표시하지 않는다', () async {
    final f = PushFixture();
    await f.initialize();
    f.provider.status = PushPermission.denied;
    expect(await f.service.synchronize(), PushReadiness.denied);
    expect(f.devices.requests, isEmpty);
    f.provider.status = PushPermission.authorized;
    f.provider.fail = true;
    expect(await f.service.synchronize(), PushReadiness.providerFailed);
    expect(f.devices.requests, isEmpty);
  });
  test('등록 도중 FID 변경은 old readiness를 폐기한다', () async {
    final f = PushFixture();
    await f.initialize();
    f.provider.registration = () async {
      f.provider.fid = 'FID-B';
      return 'FID-A';
    };
    expect(await f.service.synchronize(), PushReadiness.providerFailed);
    expect(f.devices.requests, isEmpty);
  });
  test('durable intent 저장 실패는 network mutation을 보내지 않는다', () async {
    final f = PushFixture();
    await f.initialize();
    f.persistence.failWrite = true;
    expect(await f.service.synchronize(), PushReadiness.backendFailed);
    expect(f.devices.requests, isEmpty);
    expect(await f.service.synchronize(), PushReadiness.backendFailed);
    expect(f.devices.requests, isEmpty);
    f.persistence.failWrite = false;
    expect(await f.service.synchronize(), PushReadiness.ready);
    expect(f.devices.requests.single['registrationRevision'], 1);
  });
  test(
    'revision/generation metadata 유실은 credential 상태 조회 후 높은 counter로 복구한다',
    () async {
      final f = PushFixture();
      await f.initialize();
      await f.service.synchronize();
      final value = jsonDecode(f.persistence.value!) as Map<String, dynamic>;
      value.remove('revision');
      value.remove('generation');
      f.persistence.value = jsonEncode(value);
      final storage = InstallationStorage(f.persistence, f.tokens);
      final service = PushRegistrationService(
        auth: f.auth,
        storage: storage,
        devices: f.devices,
        provider: f.provider,
      );
      expect(await service.synchronize(), PushReadiness.ready);
      expect(f.devices.requests.last['registrationRevision'], 2);
      expect(f.devices.requests.last['expectedOwnershipGeneration'], 0);
    },
  );
  test('같은 User 새 login도 원래 pending session의 재전송 권한을 갖지 않는다', () async {
    final f = PushFixture();
    await f.initialize();
    f.devices.mutation = (payload, kind) async {
      f.devices.apply(payload, kind);
      throw TimeoutException('fixture');
    };
    await f.service.synchronize();
    final originalSession = f.auth.sessionIdentity;
    await f.switchUser('A');
    f.devices.mutation = null;
    expect(await f.service.synchronize(), PushReadiness.ready);
    expect(f.auth.sessionIdentity, isNot(originalSession));
    expect(f.devices.requests.map((p) => p['registrationRevision']), [1, 2]);
    expect(f.devices.requests.last['transferOwnership'], false);
  });
  test('resume가 겹쳐도 같은 session의 등록은 한 작업으로 직렬화한다', () async {
    final f = PushFixture();
    await f.initialize();
    final gate = Completer<String>();
    f.provider.registration = () => gate.future;
    final first = f.service.synchronize();
    final second = f.service.synchronize();
    expect(identical(first, second), true);
    gate.complete('FID-A');
    expect(await first, PushReadiness.ready);
    expect(await second, PushReadiness.ready);
    expect(f.devices.requests.length, 1);
  });
}
