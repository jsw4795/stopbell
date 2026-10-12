import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/notification/installation_storage.dart';

import 'notification_test_fakes.dart';

void main() {
  test(
    'identity와 32-byte canonical credential을 한 레코드로 생성하고 재시작에도 유지한다',
    () async {
      final persistence = MemoryInstallation();
      final tokens = MemoryTokens();
      final storage = InstallationStorage(persistence, tokens);
      final first = await storage.initialize();
      expect(
        first.id,
        matches(
          r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
        ),
      );
      expect(first.credential.length, 43);
      expect(base64Url.decode('${first.credential}=').length, 32);
      first.revision = 18;
      first.generation = 3;
      await storage.save(first);
      final restored = await InstallationStorage(
        persistence,
        tokens,
      ).initialize();
      expect(restored.id, first.id);
      expect(restored.credential, first.credential);
      expect(restored.revision, 18);
      expect(restored.generation, 3);
      expect(tokens.deletes, 1);
    },
  );
  test('재설치 마커 변경은 남은 Keychain과 Auth Token을 새 설치본으로 교체한다', () async {
    final f = PushFixture();
    await f.initialize();
    final first = await f.storage.initialize();
    f.persistence.markerId = 'container-2';
    f.persistence.initialized = false;
    final next = await InstallationStorage(
      f.persistence,
      f.tokens,
    ).initialize();
    expect(next.id, isNot(first.id));
    expect(next.credential, isNot(first.credential));
    expect(next.revision, 0);
    expect(next.generation, 0);
    expect(f.tokens.pair, isNull);
  });
  test('기존 설치본의 credential 유실과 레코드 유실은 fail-closed다', () async {
    final persistence = MemoryInstallation();
    final tokens = MemoryTokens();
    final first = await InstallationStorage(persistence, tokens).initialize();
    final value = first.toJson()..remove('credential');
    persistence.value = jsonEncode(value);
    await expectLater(
      InstallationStorage(persistence, tokens).initialize(),
      throwsA(isA<InstallationStorageException>()),
    );
    expect(jsonDecode(persistence.value!)['id'], first.id);
    persistence.value = null;
    await expectLater(
      InstallationStorage(persistence, tokens).initialize(),
      throwsA(isA<InstallationStorageException>()),
    );
  });
  test('원자 레코드 저장 후 마커 commit 실패는 같은 identity로 복구한다', () async {
    final persistence = MemoryInstallation()..failCommit = true;
    final tokens = MemoryTokens();
    await expectLater(
      InstallationStorage(persistence, tokens).initialize(),
      throwsA(isA<InstallationStorageException>()),
    );
    final id = jsonDecode(persistence.value!)['id'];
    persistence.failCommit = false;
    final restored = await InstallationStorage(
      persistence,
      tokens,
    ).initialize();
    expect(restored.id, id);
    expect(persistence.initialized, isTrue);
  });
  test('원자 레코드 저장 전 실패는 미완료 마커로 안전하게 다시 생성한다', () async {
    final persistence = MemoryInstallation()..failWrite = true;
    final tokens = MemoryTokens();
    await expectLater(
      InstallationStorage(persistence, tokens).initialize(),
      throwsA(isA<InstallationStorageException>()),
    );
    expect(persistence.value, isNull);
    expect(persistence.initialized, isFalse);
    persistence.failWrite = false;
    await InstallationStorage(persistence, tokens).initialize();
    expect(persistence.initialized, isTrue);
  });
}
