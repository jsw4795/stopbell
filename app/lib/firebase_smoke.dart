import 'dart:async';
import 'dart:io';

import 'package:firebase_app_installations/firebase_app_installations.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// TASK-701 only: `flutter run --debug -t lib/firebase_smoke.dart -d <iphone>`
void main() {
  WidgetsFlutterBinding.ensureInitialized();
  if (!kDebugMode || !Platform.isIOS) {
    throw UnsupportedError('Firebase smoke는 debug iPhone에서만 실행합니다.');
  }
  runApp(const MaterialApp(home: FirebaseSmokeScreen()));
}

class FirebaseSmokeScreen extends StatefulWidget {
  const FirebaseSmokeScreen({super.key});

  @override
  State<FirebaseSmokeScreen> createState() => _FirebaseSmokeScreenState();
}

class _FirebaseSmokeScreenState extends State<FirebaseSmokeScreen> {
  static const _channel = MethodChannel('com.stopbell.stopbell/firebase-smoke');
  StreamSubscription<String>? _idChanges;
  String _status = 'TASK-701: Firebase 구성 후 준비 버튼을 누르세요.';
  String? _projectId;
  String? _fid;
  String? _installationId;
  int _idChangeRevision = 0;
  bool _preparing = false;

  Future<void> _prepare() async {
    if (_preparing) return;
    setState(() {
      _preparing = true;
      _fid = null;
      _installationId = null;
      _projectId = null;
      _status = 'Firebase 초기화 중...';
    });
    try {
      final app = await Firebase.initializeApp();
      if (app.options.iosBundleId != 'com.stopbell.stopbell') {
        throw StateError('Firebase iOS App의 bundle ID를 확인하세요.');
      }
      final installations = FirebaseInstallations.instance;
      _idChanges ??= installations.onIdChange.listen(
        (id) {
          if (!mounted || _installationId == null || id == _installationId) {
            return;
          }
          _installationId = id;
          _idChangeRevision++;
          setState(() {
            _fid = null;
            _status = 'FID가 변경됐습니다. 준비 버튼으로 다시 등록하세요.';
          });
        },
        onError: (Object _) {
          if (!mounted) return;
          _idChangeRevision++;
          setState(() {
            _fid = null;
            _status = 'FID 변경 감지가 실패했습니다. 다시 준비하세요.';
          });
        },
      );
      final messaging = FirebaseMessaging.instance;
      final permission = await messaging.requestPermission();
      if (permission.authorizationStatus != AuthorizationStatus.authorized &&
          permission.authorizationStatus != AuthorizationStatus.provisional) {
        throw StateError('알림 권한이 없습니다. iPhone 설정에서 허용 후 다시 준비하세요.');
      }

      // Keep auto-init off: explicitly register APNs + FCM for this smoke only.
      // Enabling auto-init persists across normal product app launches.
      await _channel.invokeMethod<void>('registerAPNs');
      String? apns;
      for (var attempt = 0; attempt < 20; attempt++) {
        apns = await messaging.getAPNSToken();
        if (apns != null && apns.isNotEmpty) break;
        await Future<void>.delayed(const Duration(milliseconds: 500));
      }
      if (apns == null || apns.isEmpty) {
        throw StateError(
          'APNs 미준비: signing/profile, Push capability, 네트워크를 확인하세요.',
        );
      }
      final before = await installations.getId();
      // Raw FIS IDs are only for rotation checks, never the push target.
      _installationId = before;
      final revision = _idChangeRevision;
      final registeredFid = await _channel.invokeMethod<String>('register');
      if (registeredFid == null || registeredFid.isEmpty) {
        throw StateError('FCM registration callback FID가 없습니다. 다시 준비하세요.');
      }
      final current = await installations.getId();
      if (before != current || revision != _idChangeRevision) {
        throw StateError('등록 중 FID가 변경됐습니다. 다시 준비하세요.');
      }
      if (!mounted) return;
      setState(() {
        _projectId = app.options.projectId;
        _fid = registeredFid;
        _status =
            'Firebase 초기화 / APNs 준비 / FCM registration callback으로 FID 확보\n'
            'Installations FID ↔ FCM 등록 FID: ${registeredFid == current ? '일치' : '불일치'}\n'
            '앱을 background로 보내고 이 FID로 test notification을 전송하세요.\n'
            '이 상태는 실제 iPhone 수신 성공을 뜻하지 않습니다.';
      });
    } on FirebaseException catch (error) {
      if (mounted) setState(() => _status = 'Firebase 실패: ${error.code}');
    } on PlatformException catch (error) {
      if (mounted) setState(() => _status = 'iOS smoke 실패: ${error.code}');
    } on StateError catch (error) {
      if (mounted) setState(() => _status = error.message.toString());
    } catch (_) {
      if (mounted) {
        setState(
          () => _status = '준비 실패: GoogleService-Info.plist와 iOS 구성을 확인하세요.',
        );
      }
    } finally {
      if (mounted) setState(() => _preparing = false);
    }
  }

  @override
  void dispose() {
    _idChanges?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('TASK-701 Firebase iOS smoke')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(_status),
          const SizedBox(height: 24),
          ElevatedButton(
            onPressed: _preparing ? null : _prepare,
            child: Text(_preparing ? '준비 중...' : 'Firebase / APNs / FID 준비'),
          ),
          if (_fid != null) ...[
            const SizedBox(height: 24),
            Text('Firebase project: $_projectId'),
            const Text('Push target: FCM 등록 FID'),
            SelectableText(_fid!),
            const Text('FID는 이 debug 화면에서만 확인하며 로그에 기록하지 않습니다.'),
          ],
        ],
      ),
    );
  }
}
