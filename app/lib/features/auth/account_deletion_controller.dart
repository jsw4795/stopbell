import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../alarm/alarm_creation_intent.dart';
import '../boarding/boarding_creation_intent.dart';
import 'account_deletion_client.dart';
import 'auth_session.dart';

class DeletionRecord {
  const DeletionRecord({
    required this.receipt,
    required this.session,
    this.confirmed = false,
    this.manual = false,
    this.appleUncertain = false,
    this.retryAt,
  });
  final String receipt, session;
  final bool confirmed, manual, appleUncertain;
  final DateTime? retryAt;
  Map<String, Object?> toJson() => {
    'receipt': receipt,
    'session': session,
    'confirmed': confirmed,
    'manual': manual,
    'appleUncertain': appleUncertain,
    'retryAt': retryAt?.toUtc().toIso8601String(),
  };
  factory DeletionRecord.fromJson(Object? value) {
    if (value is! Map ||
        value['receipt'] is! String ||
        !RegExp(r'^[A-Za-z0-9_-]{43}$').hasMatch(value['receipt']) ||
        value['session'] is! String ||
        (value['session'] as String).isEmpty ||
        value['confirmed'] is! bool ||
        value['manual'] is! bool ||
        (value['appleUncertain'] != null && value['appleUncertain'] is! bool) ||
        (value['manual'] == true && value['confirmed'] != true)) {
      throw const FormatException();
    }
    final raw = value['retryAt'];
    final retryAt = raw is String ? DateTime.tryParse(raw) : null;
    if (raw != null && retryAt == null) throw const FormatException();
    return DeletionRecord(
      receipt: value['receipt'],
      session: value['session'],
      confirmed: value['confirmed'],
      manual: value['manual'],
      appleUncertain: value['appleUncertain'] == true,
      retryAt: retryAt,
    );
  }
}

abstract interface class DeletionStorage {
  Future<DeletionRecord?> read();
  Future<void> save(DeletionRecord value);
  Future<void> delete();
}

class SecureDeletionStorage implements DeletionStorage {
  const SecureDeletionStorage({this.storage = const FlutterSecureStorage()});
  final FlutterSecureStorage storage;
  static const _key = 'account_deletion_v1';
  @override
  Future<DeletionRecord?> read() async {
    final raw = await storage.read(key: _key);
    return raw == null ? null : DeletionRecord.fromJson(jsonDecode(raw));
  }

  @override
  Future<void> save(DeletionRecord value) =>
      storage.write(key: _key, value: jsonEncode(value.toJson()));
  @override
  Future<void> delete() => storage.delete(key: _key);
}

class AccountDeletionController extends ChangeNotifier {
  AccountDeletionController({
    required this.auth,
    required this.client,
    required this.storage,
    required this.alarms,
    required this.boarding,
  });
  final AuthSession auth;
  final AccountDeletionClient client;
  final DeletionStorage storage;
  final AlarmIntentStorage alarms;
  final BoardingIntentStorage boarding;
  DeletionRecord? _record;
  bool busy = false;
  String? message;
  AccountDeletionStatus? status;
  Timer? _retryTimer;
  bool _cleanupComplete = false;
  bool _disposed = false;

  void _emit() {
    if (!_disposed) notifyListeners();
  }

  void _scheduleRetry() {
    _retryTimer?.cancel();
    final deadline = _record?.retryAt;
    if (deadline == null) return;
    final delay = deadline.difference(DateTime.now());
    if (delay > Duration.zero) {
      _retryTimer = Timer(delay, _emit);
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _retryTimer?.cancel();
    super.dispose();
  }

  bool get active =>
      _record != null &&
      (auth.sessionIdentity == _record!.session ||
          (auth.state == AuthState.unauthenticated && _record!.confirmed));
  bool get manualNotice =>
      active &&
      _cleanupComplete &&
      _record!.confirmed &&
      _record!.manual &&
      auth.state == AuthState.unauthenticated;
  bool get confirmed => active && _record!.confirmed;
  bool get appleUncertain => active && _record!.appleUncertain;
  bool get canRetry =>
      active &&
      !busy &&
      !_record!.confirmed &&
      (_record!.retryAt == null || !DateTime.now().isBefore(_record!.retryAt!));

  Future<void> initialize() async {
    _record = await storage.read();
    _scheduleRetry();
    if (!active) return;
    if (auth.state == AuthState.authenticated) {
      await auth.beginAccountDeletion();
    }
    if (_record!.confirmed) {
      await _finish();
    } else {
      status = AccountDeletionStatus.uncertain;
      message = '탈퇴 처리 상태를 확인해 주세요. 아직 완료 여부를 알 수 없습니다.';
    }
    _emit();
  }

  Future<void> start() async {
    if (busy || auth.sessionIdentity == null) return;
    if (_record != null) {
      message = '저장된 정보를 안전하게 준비하지 못했습니다. 앱 저장소를 확인해 주세요.';
      _emit();
      return;
    }
    busy = true;
    message = null;
    _emit();
    try {
      await auth.beginAccountDeletion();
      final random = Random.secure();
      final receipt = base64UrlEncode(
        List<int>.generate(32, (_) => random.nextInt(256)),
      ).replaceAll('=', '');
      final record = DeletionRecord(
        receipt: receipt,
        session: auth.sessionIdentity!,
      );
      try {
        await storage.save(record);
      } catch (_) {
        auth.cancelAccountDeletionPreparation();
        rethrow;
      }
      _record = record;
      await _send();
    } catch (_) {
      message = '탈퇴 요청을 준비하거나 저장하지 못했습니다. 저장 공간을 확인해 주세요.';
    } finally {
      busy = false;
      _emit();
    }
  }

  Future<void> check() async {
    if (!active || busy || _record!.confirmed) return;
    busy = true;
    _emit();
    try {
      await _handle(await client.status(_record!.receipt));
    } catch (_) {
      message = '상태를 확인하지 못했습니다. 나중에 다시 확인해 주세요.';
    } finally {
      busy = false;
      _emit();
    }
  }

  Future<void> retry() async {
    if (!canRetry) return;
    busy = true;
    _emit();
    try {
      await _send();
    } catch (_) {
      message = '요청 결과를 확인하지 못했습니다. 상태를 다시 확인해 주세요.';
    } finally {
      busy = false;
      _emit();
    }
  }

  Future<void> _send() async {
    final token = auth.deletionAccessToken;
    if (token == null) {
      message = '인증이 만료되었을 수 있습니다. receipt로 처리 상태를 확인해 주세요.';
      return;
    }
    final result = await client.delete(token, _record!.receipt);
    if (result.status == AccountDeletionStatus.deleted) {
      // Concurrent authenticated requests may return 204 after another request
      // deleted the user; ask the durable record about the Apple outcome.
      final durable = await client.status(_record!.receipt);
      if (durable.status ==
          AccountDeletionStatus.manualAppleRevocationRequired) {
        await _handle(durable);
        return;
      }
      if (durable.status != AccountDeletionStatus.deleted) {
        // A concurrent request can produce 204 without proving Apple's outcome.
        await _handle(
          const AccountDeletionResult(
            AccountDeletionStatus.manualAppleRevocationRequired,
          ),
          appleUncertain: true,
        );
        return;
      }
    }
    await _handle(result);
  }

  Future<void> _handle(
    AccountDeletionResult result, {
    bool appleUncertain = false,
  }) async {
    status = result.status;
    if (result.status == AccountDeletionStatus.deleted ||
        result.status == AccountDeletionStatus.manualAppleRevocationRequired) {
      final old = _record!;
      final confirmed = DeletionRecord(
        receipt: old.receipt,
        session: old.session,
        confirmed: true,
        manual:
            result.status ==
            AccountDeletionStatus.manualAppleRevocationRequired,
        appleUncertain: appleUncertain,
      );
      await storage.save(confirmed);
      _record = confirmed;
      await _finish();
      return;
    }
    if (result.retryAfter != null) {
      final old = _record!;
      final updated = DeletionRecord(
        receipt: old.receipt,
        session: old.session,
        retryAt: DateTime.now().add(result.retryAfter!),
      );
      await storage.save(updated);
      _record = updated;
      _scheduleRetry();
    }
    message = switch (result.status) {
      AccountDeletionStatus.pending => '탈퇴가 진행 중입니다. 완료까지 시간이 걸릴 수 있습니다.',
      AccountDeletionStatus.rejected ||
      AccountDeletionStatus.configurationError =>
        '서버에서 탈퇴를 완료하지 못했습니다. 나중에 상태를 확인하거나 다시 시도해 주세요.',
      AccountDeletionStatus.unauthenticated =>
        '인증이 만료되었거나 계정이 삭제되었을 수 있습니다. 상태를 확인해 주세요.',
      _ => '요청 결과를 확인하지 못했습니다. 탈퇴 완료 여부를 확인해 주세요.',
    };
  }

  Future<void> _finish() async {
    final record = _record!;
    try {
      final intent = await alarms.read(record.session);
      if (intent != null &&
          !await alarms.deleteIfMatching(
            record.session,
            intent.clientRequestId,
          )) {
        throw StateError('alarm cleanup');
      }
      await boarding.delete(record.session);
      if (auth.state == AuthState.authenticated &&
          auth.sessionIdentity == record.session) {
        await auth.completeAccountDeletion();
      }
      _cleanupComplete = true;
      if (!record.manual) {
        await storage.delete();
        _record = null;
      }
      message = null;
    } catch (_) {
      message = '삭제는 확인되었지만 이 기기의 사용자 정보를 정리하지 못했습니다. 다시 시도해 주세요.';
    }
  }

  Future<void> finishCleanup() async {
    if (!active || busy || !_record!.confirmed) return;
    busy = true;
    _emit();
    await _finish();
    busy = false;
    _emit();
  }

  Future<void> acknowledgeManual() async {
    if (!manualNotice || busy) return;
    try {
      await storage.delete();
      _record = null;
      message = null;
    } catch (_) {
      message = '안내 상태를 저장하지 못했습니다. 다시 시도해 주세요.';
    }
    _emit();
  }
}
