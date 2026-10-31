import 'package:flutter/foundation.dart';

import '../auth/auth_session.dart';
import 'alarm.dart';
import 'alarm_api_client.dart';
import 'alarm_creation_intent.dart';

class AlarmCreationController extends ChangeNotifier {
  AlarmCreationController(this.auth, this.client, this.storage)
    : generation = auth.generation,
      user = auth.userIdentity,
      session = auth.sessionIdentity;

  final AuthSession auth;
  final AlarmClient client;
  final AlarmIntentStorage storage;
  final int generation;
  final String? user, session;
  bool initialized = false, busy = false, disposed = false;
  AlarmCreationIntent? pending;
  Alarm? result;
  String? message;
  bool gone = false;
  Stopwatch? _age;

  bool get current =>
      !disposed &&
      user != null &&
      session != null &&
      auth.isCurrentGeneration(generation) &&
      !auth.isLoggingOut &&
      auth.userIdentity == user &&
      auth.sessionIdentity == session;
  bool get blocked => !initialized || busy || pending != null;
  bool get canRetry {
    if (pending == null || _age == null) return false;
    final wallAge = DateTime.now().toUtc().difference(pending!.createdAt);
    final elapsed = _age!.elapsed;
    return !wallAge.isNegative &&
        wallAge < const Duration(days: 30) &&
        elapsed < const Duration(days: 30) &&
        (wallAge - elapsed).abs() < const Duration(minutes: 1);
  }

  void _emit() {
    if (current) notifyListeners();
  }

  Future<void> initialize() async {
    if (!current) return;
    try {
      final value = await storage.read(session!);
      if (!current) return;
      if (value != null &&
          (value.userIdentity != user || value.sessionIdentity != session)) {
        message = '저장된 요청의 계정을 확인할 수 없습니다.';
        return;
      }
      pending = value;
      initialized = true;
      if (pending != null) message = '이전 알람 생성 결과가 불확실합니다. 원래 요청을 확인해 주세요.';
    } catch (_) {
      if (current) message = '저장된 생성 요청을 읽지 못했습니다. 다시 확인해 주세요.';
    } finally {
      _emit();
    }
  }

  Future<Alarm?> create(
    int target,
    bool before,
    bool after,
    bool Function() navigationCurrent,
  ) async {
    if (!current || blocked || !navigationCurrent()) return null;
    busy = true;
    message = null;
    result = null;
    _emit();
    final intent = AlarmCreationIntent(
      clientRequestId: newAlarmRequestId(),
      targetStopOccurrenceId: target,
      notifyOneStopBefore: before,
      notifyOneStopAfter: after,
      userIdentity: user!,
      sessionIdentity: session!,
      createdAt: DateTime.now().toUtc(),
    );
    try {
      bool saved;
      try {
        saved = await storage.saveIfAbsent(intent);
      } catch (_) {
        if (current) message = '요청을 저장하지 못해 전송하지 않았습니다.';
        return null;
      }
      if (!current) return null;
      if (!saved) {
        pending = await storage.read(session!);
        if (current) message = '이전 생성 요청을 먼저 확인해 주세요.';
        return null;
      }
      pending = intent;
      _age = Stopwatch()..start();
      if (!navigationCurrent() || !current) return null;
      try {
        final alarm = await _post(intent);
        if (!current || !navigationCurrent()) return null;
        result = alarm;
        await _clear(intent);
        if (!current || !navigationCurrent()) return null;
        return alarm;
      } on AlarmApiException catch (error) {
        if (!current || !navigationCurrent()) return null;
        if (error.isCreationRejected) {
          await _clear(intent);
          if (current && navigationCurrent()) rethrow;
        } else if (error.statusCode == 410 &&
            error.code == 'ALARM_CREATION_RESULT_GONE') {
          gone = true;
          await _clear(intent);
          if (current && pending == null) {
            message = '원래 생성된 알람이 삭제되었습니다. 새 알람은 별도로 생성해 주세요.';
          }
        } else if (error.statusCode == 409 &&
            error.code == 'ALARM_CREATION_REQUEST_CONFLICT') {
          message = '원래 요청의 내용이 일치하지 않습니다. 원래 결과를 확인해 주세요.';
        } else {
          message = '알람 생성 결과가 불확실합니다. 원래 요청을 조회하거나 재시도해 주세요.';
        }
      } catch (_) {
        if (current && navigationCurrent()) {
          message = '알람 생성 결과가 불확실합니다. 원래 요청을 조회하거나 재시도해 주세요.';
        }
      }
      return null;
    } catch (_) {
      if (current && !initialized) message = '요청을 저장하지 못해 전송하지 않았습니다.';
      rethrow;
    } finally {
      if (current) {
        busy = false;
        _emit();
      }
    }
  }

  Future<void> discardForNewCreation() async {
    final intent = pending;
    if (!current || busy || intent == null || canRetry) return;
    busy = true;
    _emit();
    await _clear(intent);
    if (current) {
      if (pending == null) message = '새 알람을 만들 수 있습니다. 이전 요청의 결과는 불확실합니다.';
      busy = false;
      _emit();
    }
  }

  Future<Alarm> _post(AlarmCreationIntent intent) => client.create(
    clientRequestId: intent.clientRequestId,
    targetStopOccurrenceId: intent.targetStopOccurrenceId,
    notifyOneStopBefore: intent.notifyOneStopBefore,
    notifyOneStopAfter: intent.notifyOneStopAfter,
  );

  Future<void> recover({
    bool retry = false,
    bool Function()? navigationCurrent,
  }) async {
    final intent = pending;
    bool valid() => current && (navigationCurrent?.call() ?? true);
    if (!valid() || busy || intent == null || (retry && !canRetry)) return;
    busy = true;
    message = null;
    _emit();
    try {
      final alarm = retry
          ? await _post(intent)
          : await client.findCreation(intent.clientRequestId);
      if (!valid()) return;
      result = alarm;
      await _clear(intent);
      if (valid() && pending == null) {
        message = '원래 생성된 알람을 확인했습니다. 현재 상태를 확인해 주세요.';
      }
    } on AlarmApiException catch (error) {
      if (!valid()) return;
      if (error.statusCode == 410 &&
          error.code == 'ALARM_CREATION_RESULT_GONE') {
        gone = true;
        await _clear(intent);
        if (valid() && pending == null) {
          message = '원래 생성된 알람이 삭제되었습니다. 새 알람은 별도로 생성해 주세요.';
        }
      } else if (error.statusCode == 404 &&
          error.code == 'ALARM_CREATION_REQUEST_NOT_FOUND') {
        message = '원래 요청을 찾지 못했습니다. 처리 중이거나 기록이 만료됐을 수 있습니다.';
      } else if (error.statusCode == 409 &&
          error.code == 'ALARM_CREATION_REQUEST_CONFLICT') {
        message = '원래 요청의 내용이 일치하지 않습니다. 원래 결과를 확인해 주세요.';
      } else {
        message = '결과를 확정할 수 없습니다. 원래 요청을 보존합니다.';
      }
    } catch (_) {
      if (valid()) message = '결과를 확정할 수 없습니다. 원래 요청을 보존합니다.';
    } finally {
      if (current) {
        busy = false;
        _emit();
      }
    }
  }

  Future<void> _clear(AlarmCreationIntent intent) async {
    try {
      final deleted = await storage.deleteIfMatching(
        intent.sessionIdentity,
        intent.clientRequestId,
      );
      if (current &&
          deleted &&
          pending?.clientRequestId == intent.clientRequestId) {
        pending = null;
      }
      if (current && !deleted) message = '저장된 요청을 정리하지 못했습니다. 다시 확인해 주세요.';
    } catch (_) {
      if (current) message = '저장된 요청을 정리하지 못했습니다. 다시 확인해 주세요.';
    }
  }

  @override
  void dispose() {
    disposed = true;
    super.dispose();
  }
}
