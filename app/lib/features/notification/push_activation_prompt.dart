import 'package:flutter/material.dart';

import '../auth/auth_session.dart';
import 'push_permission_service.dart';
import 'push_registration_service.dart';

Future<bool> prepareAlarmPush(
  BuildContext context,
  PushRegistrationService service,
) => _preparePush(context, service);

Future<bool> prepareBoardingPush(
  BuildContext context,
  PushRegistrationService? service,
) async {
  if (service != null) return _preparePush(context, service, boarding: true);
  final proceed = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: const Text('이 기기의 알림 수신 준비가 필요합니다'),
      content: const Text(
        '이 환경에서는 알림 수신 준비를 확인할 수 없어요. 이 기기에서 하차 알림을 받지 못할 수 있어요. 세션 생성은 서버의 추적 지원 검증을 거쳐요.',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext, false),
          child: const Text('취소'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(dialogContext, true),
          child: const Text('알림 없이 계속'),
        ),
      ],
    ),
  );
  return context.mounted && proceed == true;
}

Future<bool> _preparePush(
  BuildContext context,
  PushRegistrationService service, {
  bool boarding = false,
}) async {
  final generation = service.auth.generation;
  final user = service.auth.userIdentity;
  final session = service.auth.sessionIdentity;
  bool current() =>
      context.mounted &&
      service.auth.isCurrentGeneration(generation) &&
      !service.auth.isLoggingOut &&
      service.auth.userIdentity == user &&
      service.auth.sessionIdentity == session;
  PushReadiness result;
  try {
    final permission = await service.provider.permission();
    if (!context.mounted || !current()) return false;
    if (permission == PushPermission.notDetermined) {
      final request = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(boarding ? '하차 알림 받기' : '버스 도착 알림 받기'),
          content: Text(
            boarding
                ? '하차 준비 알림을 받으려면 알림 권한과 기기 등록이 필요해요. 실제 수신 시점은 보장되지 않아요.'
                : '버스 도착을 알려드리기 위해 알림 권한과 기기 등록이 필요합니다.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('나중에'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('계속'),
            ),
          ],
        ),
      );
      if (!context.mounted || !current()) return false;
      if (!boarding && request != true) return false;
      if (request == true) await service.provider.permission(request: true);
    }
    if (!context.mounted || !current()) return false;
    result = await service.synchronize(explicit: true);
  } on StaleSessionException {
    return false;
  } catch (_) {
    result = PushReadiness.providerFailed;
  }
  if (!context.mounted || !current() || result == PushReadiness.stale) {
    return false;
  }
  if (result == PushReadiness.ready) {
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('이 기기의 알림 수신 준비를 완료했습니다.')));
    return true;
  }
  final continuation = boarding
      ? '이 기기에서 하차 알림을 받지 못할 수 있어요. 나중에 기기를 등록해도 이미 발생한 알림은 추가 전송되지 않아요. 세션 생성은 서버 검증을 거쳐요.'
      : '알람은 활성화할 수 있습니다.';
  final denied =
      result == PushReadiness.denied ||
      result == PushReadiness.permissionRequired;
  final notification = boarding ? '하차 알림' : '버스 도착 알림';
  final proceed = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: const Text('이 기기의 알림 수신 준비가 필요합니다'),
      content: Text(
        denied
            ? '알림 권한이 없어 이 기기에서 $notification을 받기 어렵습니다. iPhone 설정에서 알림을 허용할 수 있습니다. $continuation'
            : result == PushReadiness.backendFailed
            ? '서버에 기기 등록을 완료하지 못했습니다. 네트워크를 확인하고 다시 시도해 주세요. $continuation'
            : '알림 수신 준비를 완료하지 못했습니다. 네트워크와 알림 권한을 확인하고 다시 시도해 주세요. $continuation',
      ),
      actions: [
        if (denied)
          TextButton(
            onPressed: () async {
              try {
                await service.provider.openSettings();
              } catch (_) {
                /* Fixed explanatory text remains available. */
              }
            },
            child: const Text('설정 열기'),
          ),
        TextButton(
          onPressed: () => Navigator.pop(dialogContext, false),
          child: const Text('취소'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(dialogContext, true),
          child: Text(boarding ? '알림 없이 계속' : '알람 활성화'),
        ),
      ],
    ),
  );
  return current() && proceed == true;
}
