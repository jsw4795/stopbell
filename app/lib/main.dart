import 'package:flutter/material.dart';

import 'core/app_config.dart';
import 'features/auth/backend_auth_client.dart';
import 'features/auth/auth_session.dart';
import 'features/auth/google_auth_service.dart';
import 'features/auth/token_pair_storage.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final config = AppConfig.fromEnvironment();
  final backend = BackendAuthClient(
    apiBaseUrl: Uri.tryParse(config.apiBaseUrl) ?? Uri(),
  );
  runApp(
    StopBellApplication(
      authSession: AuthSession(
        authenticator: GoogleAuthService(config: config, backend: backend),
        backend: backend,
        tokenStorage: SecureTokenPairStorage(),
      ),
    ),
  );
}

class StopBellApplication extends StatefulWidget {
  const StopBellApplication({super.key, required this.authSession});

  final AuthSession authSession;

  @override
  State<StopBellApplication> createState() => _StopBellApplicationState();
}

class _StopBellApplicationState extends State<StopBellApplication> {
  @override
  void initState() {
    super.initState();
    widget.authSession.initialize().catchError((Object _) {});
  }

  @override
  void dispose() {
    widget.authSession.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'StopBell',
      home: LoginScreen(authSession: widget.authSession),
    );
  }
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.authSession});

  final AuthSession authSession;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  bool _isLoading = false;
  String? _message;

  Future<void> _login() async {
    if (_isLoading || widget.authSession.state != AuthState.unauthenticated) {
      return;
    }
    setState(() {
      _isLoading = true;
      _message = null;
    });

    try {
      await widget.authSession.login();
    } on AppConfigException catch (error) {
      if (mounted) setState(() => _message = error.message);
    } on LoginException catch (error) {
      if (mounted) setState(() => _message = error.message);
    } on BackendAuthException catch (error) {
      if (mounted) setState(() => _message = error.message);
    } catch (_) {
      if (mounted) setState(() => _message = '로그인이 실패했습니다. 다시 시도해 주세요.');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.authSession,
      builder: (context, _) => Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('StopBell', style: TextStyle(fontSize: 32)),
              const SizedBox(height: 24),
              if (widget.authSession.state == AuthState.initializing)
                const Text('인증 상태 확인 중...')
              else if (widget.authSession.state == AuthState.authenticated)
                const Text('로그인 성공')
              else ...[
                ElevatedButton(
                  onPressed: _isLoading ? null : _login,
                  child: const Text('Google로 계속하기'),
                ),
                if (_isLoading) ...[
                  const SizedBox(height: 16),
                  const CircularProgressIndicator(),
                  const Text('로그인 중...'),
                ],
                if (_message != null) ...[
                  const SizedBox(height: 16),
                  Text(_message!, textAlign: TextAlign.center),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}
