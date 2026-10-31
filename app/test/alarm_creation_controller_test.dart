import 'dart:async';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:stopbell/features/alarm/alarm.dart';
import 'package:stopbell/features/alarm/alarm_api_client.dart';
import 'package:stopbell/features/alarm/alarm_creation_controller.dart';
import 'package:stopbell/features/alarm/alarm_creation_intent.dart';

import 'alarm_screen_test.dart' show StubAlarms, sample, withStatus;
import 'notification_test_fakes.dart';

class RecoveryClient extends StubAlarms {
  String? id;
  Future<Alarm> Function(String)? lookup;
  @override
  Future<Alarm> create({
    String? clientRequestId,
    required int targetStopOccurrenceId,
    required bool notifyOneStopBefore,
    required bool notifyOneStopAfter,
  }) {
    id = clientRequestId;
    return super.create(
      targetStopOccurrenceId: targetStopOccurrenceId,
      notifyOneStopBefore: notifyOneStopBefore,
      notifyOneStopAfter: notifyOneStopAfter,
    );
  }

  @override
  Future<Alarm> findCreation(String clientRequestId) =>
      lookup!(clientRequestId);
}

class FailingStorage implements AlarmIntentStorage {
  @override
  Future<AlarmCreationIntent?> read(String sessionIdentity) async => null;
  @override
  Future<bool> saveIfAbsent(AlarmCreationIntent intent) async =>
      throw StateError('storage');
  @override
  Future<bool> deleteIfMatching(
    String sessionIdentity,
    String requestId,
  ) async => false;
}

class DeleteFailingStorage implements AlarmIntentStorage {
  DeleteFailingStorage(this.delegate);
  final AlarmIntentStorage delegate;
  @override
  Future<AlarmCreationIntent?> read(String sessionIdentity) =>
      delegate.read(sessionIdentity);
  @override
  Future<bool> saveIfAbsent(AlarmCreationIntent intent) =>
      delegate.saveIfAbsent(intent);
  @override
  Future<bool> deleteIfMatching(
    String sessionIdentity,
    String requestId,
  ) async => throw StateError('delete');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late PushFixture f;
  late RecoveryClient client;
  const storage = SecureAlarmIntentStorage();
  setUp(() async {
    FlutterSecureStorage.setMockInitialValues({});
    f = PushFixture();
    await f.initialize();
    client = RecoveryClient()..createAlarm = (_, _, _) async => sample;
  });
  tearDown(() => f.auth.dispose());

  test(
    'UUID v4 and conditional serialized cleanup preserve newer intent',
    () async {
      final id = newAlarmRequestId();
      expect(
        id,
        matches(
          RegExp(
            r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
          ),
        ),
      );
      AlarmCreationIntent intent(String request) => AlarmCreationIntent(
        clientRequestId: request,
        targetStopOccurrenceId: 7,
        notifyOneStopBefore: true,
        notifyOneStopAfter: false,
        userIdentity: f.auth.userIdentity!,
        sessionIdentity: f.auth.sessionIdentity!,
        createdAt: DateTime.now().toUtc(),
      );
      final first = intent(id);
      final second = intent(newAlarmRequestId());
      expect(
        await Future.wait([
          storage.saveIfAbsent(first),
          storage.saveIfAbsent(second),
        ]),
        [true, false],
      );
      expect(
        await storage.deleteIfMatching(
          first.sessionIdentity,
          second.clientRequestId,
        ),
        isFalse,
      );
      expect((await storage.read(first.sessionIdentity))!.body, first.body);
      expect(
        await storage.deleteIfMatching(
          first.sessionIdentity,
          first.clientRequestId,
        ),
        isTrue,
      );
      expect(await storage.saveIfAbsent(second), isTrue);
      expect(
        await storage.deleteIfMatching(
          first.sessionIdentity,
          first.clientRequestId,
        ),
        isFalse,
      );
      expect((await storage.read(first.sessionIdentity))!.body, second.body);
      expect(await storage.read('other-session'), isNull);
    },
  );

  test('storage failure prevents POST', () async {
    final controller = AlarmCreationController(
      f.auth,
      client,
      FailingStorage(),
    );
    await controller.initialize();
    expect(await controller.create(7, true, false, () => true), isNull);
    expect(client.createCalls, 0);
    expect(controller.message, contains('전송하지 않았습니다'));
    controller.dispose();
  });

  test('failed cleanup retains result and blocks a new request', () async {
    final controller = AlarmCreationController(
      f.auth,
      client,
      DeleteFailingStorage(storage),
    );
    await controller.initialize();
    expect(await controller.create(7, true, false, () => true), sample);
    expect(controller.pending, isNotNull);
    expect(controller.result, sample);
    expect(controller.blocked, isTrue);
    await controller.create(8, false, false, () => true);
    expect(client.createCalls, 1);
    controller.dispose();
  });

  test(
    'old intent cannot be retried and conflict never starts a new request',
    () async {
      final old = AlarmCreationIntent(
        clientRequestId: newAlarmRequestId(),
        targetStopOccurrenceId: 7,
        notifyOneStopBefore: false,
        notifyOneStopAfter: true,
        userIdentity: f.auth.userIdentity!,
        sessionIdentity: f.auth.sessionIdentity!,
        createdAt: DateTime.now().toUtc().subtract(const Duration(days: 31)),
      );
      await storage.saveIfAbsent(old);
      final controller = AlarmCreationController(f.auth, client, storage);
      await controller.initialize();
      expect(controller.canRetry, isFalse);
      await controller.recover(retry: true);
      expect(client.createCalls, 0);
      client.lookup = (_) async => throw const AlarmApiException(
        statusCode: 409,
        code: 'ALARM_CREATION_REQUEST_CONFLICT',
      );
      await controller.recover();
      expect(controller.pending!.body, old.body);
      expect(controller.message, contains('일치하지'));
      controller.dispose();
    },
  );

  test(
    'uncertain response restores original body and 404 keeps pending',
    () async {
      client.createAlarm = (_, _, _) async => throw TimeoutException('lost');
      var controller = AlarmCreationController(f.auth, client, storage);
      await controller.initialize();
      await controller.create(7, true, false, () => true);
      final original = controller.pending!;
      expect(original.clientRequestId, client.id);
      expect(controller.canRetry, isTrue);
      controller.dispose();
      controller = AlarmCreationController(f.auth, client, storage);
      await controller.initialize();
      expect(controller.pending!.body, original.body);
      expect(controller.canRetry, isFalse);
      client.lookup = (_) async => throw const AlarmApiException(
        statusCode: 404,
        code: 'ALARM_CREATION_REQUEST_NOT_FOUND',
      );
      await controller.recover();
      expect(controller.pending!.body, original.body);
      expect(client.createCalls, 1);
      client.lookup = (_) async => withStatus(AlarmStatus.followUp);
      await controller.recover();
      expect(controller.result!.status, AlarmStatus.followUp);
      expect(controller.pending, isNull);
      expect(client.activateCalls, 0);
      controller.dispose();
    },
  );

  test('explicit retry uses original ID and body without activation', () async {
    client.createAlarm = (_, _, _) async => throw TimeoutException('lost');
    final controller = AlarmCreationController(f.auth, client, storage);
    await controller.initialize();
    await controller.create(7, true, false, () => true);
    final id = client.id;
    client.createAlarm = (target, before, after) async {
      expect([target, before, after], [7, true, false]);
      return withStatus(AlarmStatus.active);
    };
    await controller.recover(retry: true);
    expect(client.id, id);
    expect(client.createCalls, 2);
    expect(client.activateCalls, 0);
    expect(controller.result!.status, AlarmStatus.active);
    controller.dispose();
  });

  test('another user record is not exposed or sent', () async {
    final foreign = AlarmCreationIntent(
      clientRequestId: newAlarmRequestId(),
      targetStopOccurrenceId: 7,
      notifyOneStopBefore: false,
      notifyOneStopAfter: false,
      userIdentity: 'another-user',
      sessionIdentity: f.auth.sessionIdentity!,
      createdAt: DateTime.now().toUtc(),
    );
    await storage.saveIfAbsent(foreign);
    final controller = AlarmCreationController(f.auth, client, storage);
    await controller.initialize();
    expect(controller.initialized, isFalse);
    expect(controller.pending, isNull);
    await controller.create(7, false, false, () => true);
    expect(client.createCalls, 0);
    controller.dispose();
  });

  test('navigation change during lookup keeps original intent', () async {
    client.createAlarm = (_, _, _) async => throw TimeoutException('lost');
    final controller = AlarmCreationController(f.auth, client, storage);
    await controller.initialize();
    await controller.create(7, false, false, () => true);
    final response = Completer<Alarm>();
    client.lookup = (_) => response.future;
    var current = true;
    final operation = controller.recover(navigationCurrent: () => current);
    current = false;
    response.complete(sample);
    await operation;
    expect(controller.pending, isNotNull);
    expect(controller.result, isNull);
    controller.dispose();
  });

  test('gone is cleared and account change ignores late response', () async {
    client.createAlarm = (_, _, _) async => throw TimeoutException('lost');
    final controller = AlarmCreationController(f.auth, client, storage);
    await controller.initialize();
    await controller.create(7, false, false, () => true);
    client.lookup = (_) async => throw const AlarmApiException(
      statusCode: 410,
      code: 'ALARM_CREATION_RESULT_GONE',
    );
    await controller.recover();
    expect(controller.pending, isNull);
    expect(controller.message, contains('삭제'));
    final pending = Completer<Alarm>();
    client.createAlarm = (_, _, _) => pending.future;
    final request = controller.create(7, false, false, () => true);
    await Future<void>.delayed(Duration.zero);
    await f.switchUser('B');
    pending.complete(sample);
    expect(await request, isNull);
    expect(client.activateCalls, 0);
    controller.dispose();
  });
}
