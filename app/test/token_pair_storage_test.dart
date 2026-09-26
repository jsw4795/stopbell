import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';

const storageKey = 'auth_token_pair_v1';
const pair = TokenPair(
  accessToken: 'test-access-token',
  refreshToken: 'test-refresh-token',
);

void main() {
  late FlutterSecureStorage secureStorage;
  late SecureTokenPairStorage storage;

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    secureStorage = const FlutterSecureStorage();
    storage = SecureTokenPairStorage(secureStorage: secureStorage);
  });

  test('Token Pair를 하나의 JSON entry로 저장하고 동일한 Pair를 읽는다', () async {
    await storage.save(pair);

    final values = await secureStorage.readAll();
    expect(values.keys, [storageKey]);
    expect(jsonDecode(values[storageKey]!), {
      'accessToken': pair.accessToken,
      'refreshToken': pair.refreshToken,
    });

    final restored = await storage.read();
    expect(restored?.accessToken, pair.accessToken);
    expect(restored?.refreshToken, pair.refreshToken);
  });

  test('새 Token Pair를 저장하면 기존 Pair를 교체한다', () async {
    await storage.save(pair);
    const replacement = TokenPair(
      accessToken: 'replacement-access-token',
      refreshToken: 'replacement-refresh-token',
    );

    await storage.save(replacement);

    final restored = await storage.read();
    expect(restored?.accessToken, replacement.accessToken);
    expect(restored?.refreshToken, replacement.refreshToken);
  });

  test('저장값이 없으면 null을 반환한다', () async {
    expect(await storage.read(), isNull);
  });

  test('Token Pair key만 삭제한다', () async {
    await storage.save(pair);
    await secureStorage.write(key: 'unrelated_secure_value', value: 'keep');

    await storage.delete();

    expect(await storage.read(), isNull);
    expect(await secureStorage.read(key: 'unrelated_secure_value'), 'keep');
  });

  test('JSON이 아닌 저장값은 삭제하고 null을 반환한다', () async {
    await secureStorage.write(key: storageKey, value: 'not-json');

    expect(await storage.read(), isNull);
    expect(await secureStorage.read(key: storageKey), isNull);
  });

  test('object가 아니거나 필드가 누락된 저장값은 삭제한다', () async {
    for (final invalidValue in [
      jsonEncode(['not-an-object']),
      jsonEncode({'accessToken': pair.accessToken}),
      jsonEncode({'accessToken': pair.accessToken, 'refreshToken': 123}),
    ]) {
      await secureStorage.write(key: storageKey, value: invalidValue);

      expect(await storage.read(), isNull);
      expect(await secureStorage.read(key: storageKey), isNull);
    }
  });

  test('blank Token이 포함된 저장값은 삭제한다', () async {
    for (final invalidPair in [
      {'accessToken': ' ', 'refreshToken': pair.refreshToken},
      {'accessToken': pair.accessToken, 'refreshToken': '\n'},
    ]) {
      await secureStorage.write(
        key: storageKey,
        value: jsonEncode(invalidPair),
      );

      expect(await storage.read(), isNull);
      expect(await secureStorage.read(key: storageKey), isNull);
    }
  });

  test('blank Token Pair 저장은 기존 값을 변경하지 않는다', () async {
    await storage.save(pair);

    await expectLater(
      storage.save(
        const TokenPair(accessToken: '', refreshToken: 'invalid-refresh'),
      ),
      throwsArgumentError,
    );

    final restored = await storage.read();
    expect(restored?.accessToken, pair.accessToken);
    expect(restored?.refreshToken, pair.refreshToken);
  });
}
