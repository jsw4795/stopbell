import 'package:flutter/material.dart';

import 'core/app_config.dart';
import 'features/auth/backend_auth_client.dart';
import 'features/auth/google_auth_service.dart';

void main() {
  final config = AppConfig.fromEnvironment();
  runApp(
    StopBellApplication(
      authenticator: GoogleAuthService(
        config: config,
        backend: BackendAuthClient(
          apiBaseUrl: Uri.tryParse(config.apiBaseUrl) ?? Uri(),
        ),
      ),
    ),
  );
}

class StopBellApplication extends StatelessWidget {
  const StopBellApplication({super.key, required this.authenticator});

  final Authenticator authenticator;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'StopBell',
      home: LoginScreen(authenticator: authenticator),
    );
  }
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.authenticator});

  final Authenticator authenticator;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  bool _isLoading = false;
  bool _isLoggedIn = false;
  String? _message;

  Future<void> _login() async {
    if (_isLoading || _isLoggedIn) return;
    setState(() {
      _isLoading = true;
      _message = null;
    });

    try {
      await widget.authenticator.login();
      if (!mounted) return;
      setState(() => _isLoggedIn = true);
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
    return Scaffold(
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('StopBell', style: TextStyle(fontSize: 32)),
            const SizedBox(height: 24),
            if (_isLoggedIn)
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
    );
  }
}
