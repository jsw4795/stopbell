import 'dart:async';

import 'package:firebase_app_installations/firebase_app_installations.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/foundation.dart';
import 'package:firebase_messaging/firebase_messaging.dart';

import 'installation_storage.dart';

enum PushPermission { authorized, provisional, denied, notDetermined }

abstract interface class PushProvider {
  Future<PushPermission> permission({bool request = false});
  Future<String> registeredFid();
  Future<String> currentFid();
  Stream<String> get idChanges;
  Future<void> openSettings();
}

class FirebasePushProvider implements PushProvider {
  Future<void>? _initialization;
  Future<void> initialize() {
    final pending = _initialization;
    if (pending != null) return pending;
    final work = _initialize();
    _initialization = work;
    work.catchError((Object _) {
      if (identical(_initialization, work)) _initialization = null;
    });
    return work;
  }

  Future<void> _initialize() async {
    await Firebase.initializeApp();
  }

  @override
  Future<PushPermission> permission({bool request = false}) async {
    await initialize();
    final messaging = FirebaseMessaging.instance;
    final settings = request
        ? await messaging.requestPermission()
        : await messaging.getNotificationSettings();
    return switch (settings.authorizationStatus) {
      AuthorizationStatus.authorized => PushPermission.authorized,
      AuthorizationStatus.provisional => PushPermission.provisional,
      AuthorizationStatus.denied ||
      AuthorizationStatus.deniedPermanently => PushPermission.denied,
      AuthorizationStatus.notDetermined => PushPermission.notDetermined,
    };
  }

  @override
  Future<String> registeredFid() async {
    await initialize();
    await SecureInstallationPersistence.channel
        .invokeMethod<void>('registerAPNs')
        .timeout(const Duration(seconds: 2));
    String? apns;
    final apnsClock = Stopwatch()..start();
    const apnsLimit = Duration(seconds: 10);
    for (var attempt = 0; attempt < 20; attempt++) {
      final remaining = apnsLimit - apnsClock.elapsed;
      if (remaining <= Duration.zero) break;
      apns = await FirebaseMessaging.instance.getAPNSToken().timeout(
        remaining < const Duration(seconds: 2)
            ? remaining
            : const Duration(seconds: 2),
      );
      if (apns != null && apns.isNotEmpty) break;
      final delay = apnsLimit - apnsClock.elapsed;
      if (delay <= Duration.zero) break;
      await Future<void>.delayed(
        delay < const Duration(milliseconds: 500)
            ? delay
            : const Duration(milliseconds: 500),
      );
    }
    if (apns == null || apns.isEmpty) throw const PushReadinessException();
    if (kDebugMode) debugPrint('StopBell push stage: apns-ready');
    final before = await currentFid();
    final fid = await SecureInstallationPersistence.channel
        .invokeMethod<String>('register')
        .timeout(const Duration(seconds: 32));
    if (fid == null ||
        fid.isEmpty ||
        RegExp(r'\s').hasMatch(fid) ||
        fid.length > 255 ||
        before != fid ||
        await currentFid() != fid) {
      throw const PushReadinessException();
    }
    if (kDebugMode) debugPrint('StopBell push stage: registered-fid-ready');
    return fid;
  }

  @override
  Future<String> currentFid() async {
    await initialize();
    return FirebaseInstallations.instance.getId().timeout(
      const Duration(seconds: 8),
    );
  }

  @override
  Stream<String> get idChanges => FirebaseInstallations.instance.onIdChange;
  @override
  Future<void> openSettings() =>
      SecureInstallationPersistence.channel.invokeMethod<void>('openSettings');
}

class PushReadinessException implements Exception {
  const PushReadinessException();
}
