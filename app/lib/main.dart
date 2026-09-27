import 'package:flutter/material.dart';

import 'core/app_config.dart';
import 'core/authenticated_api_client.dart';
import 'features/auth/backend_auth_client.dart';
import 'features/auth/auth_session.dart';
import 'features/auth/google_auth_service.dart';
import 'features/auth/token_pair_storage.dart';
import 'features/transit/bus_route.dart';
import 'features/transit/bus_route_search_client.dart';
import 'features/transit/bus_route_search_screen.dart';
import 'features/transit/bus_route_stop_client.dart';
import 'features/transit/bus_route_stop_occurrence.dart';
import 'features/transit/bus_route_stop_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final config = AppConfig.fromEnvironment();
  final backend = BackendAuthClient(
    apiBaseUrl: Uri.tryParse(config.apiBaseUrl) ?? Uri(),
  );
  final authSession = AuthSession(
    authenticator: GoogleAuthService(config: config, backend: backend),
    backend: backend,
    tokenStorage: SecureTokenPairStorage(),
  );
  final apiClient = AuthenticatedApiClient(
    apiBaseUrl: Uri.tryParse(config.apiBaseUrl) ?? Uri(),
    authSession: authSession,
  );
  final routeSearch = BusRouteSearchClient(apiClient);
  final routeStops = BusRouteStopClient(apiClient);
  runApp(
    StopBellApplication(
      authSession: authSession,
      searchRoutes: routeSearch.search,
      findStops: routeStops.findStops,
    ),
  );
}

class StopBellApplication extends StatefulWidget {
  const StopBellApplication({
    super.key,
    required this.authSession,
    required this.searchRoutes,
    required this.findStops,
  });

  final AuthSession authSession;
  final Future<List<BusRoute>> Function(String query) searchRoutes;
  final Future<List<BusRouteStopOccurrence>> Function(int routeId) findStops;

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
      home: LoginScreen(
        authSession: widget.authSession,
        searchRoutes: widget.searchRoutes,
        findStops: widget.findStops,
      ),
    );
  }
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({
    super.key,
    required this.authSession,
    required this.searchRoutes,
    required this.findStops,
  });

  final AuthSession authSession;
  final Future<List<BusRoute>> Function(String query) searchRoutes;
  final Future<List<BusRouteStopOccurrence>> Function(int routeId) findStops;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  bool _isLoading = false;
  String? _message;
  BusRoute? _selectedRoute;

  @override
  void initState() {
    super.initState();
    widget.authSession.addListener(_clearRouteWhenUnauthenticated);
  }

  void _clearRouteWhenUnauthenticated() {
    if (widget.authSession.state != AuthState.authenticated) {
      _selectedRoute = null;
    }
  }

  @override
  void dispose() {
    widget.authSession.removeListener(_clearRouteWhenUnauthenticated);
    super.dispose();
  }

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

  Future<void> _logout() async {
    if (_isLoading || widget.authSession.state != AuthState.authenticated) {
      return;
    }
    setState(() {
      _isLoading = true;
      _message = null;
    });
    try {
      await widget.authSession.logout();
    } on AuthSessionPersistenceException {
      if (mounted) {
        setState(() => _message = '저장된 인증 정보를 삭제하지 못했습니다. 다시 시도해 주세요.');
      }
    } on BackendLogoutCleanupException {
      if (mounted) {
        setState(() => _message = '이 기기에서는 로그아웃했습니다. 서버 세션은 정리하지 못했습니다.');
      }
    } catch (_) {
      if (mounted) setState(() => _message = '로그아웃 중 오류가 발생했습니다. 다시 시도해 주세요.');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.authSession,
      builder: (context, _) {
        if (widget.authSession.state == AuthState.authenticated) {
          return Scaffold(
            appBar: AppBar(
              title: const Text('StopBell'),
              actions: [
                ElevatedButton(
                  onPressed: _isLoading ? null : _logout,
                  child: const Text('로그아웃'),
                ),
              ],
            ),
            body: Column(
              children: [
                Expanded(
                  child: _selectedRoute == null
                      ? BusRouteSearchScreen(
                          search: widget.searchRoutes,
                          onSelect: (route) =>
                              setState(() => _selectedRoute = route),
                        )
                      : BusRouteStopScreen(
                          route: _selectedRoute!,
                          findStops: widget.findStops,
                          onBack: () => setState(() => _selectedRoute = null),
                        ),
                ),
                if (_isLoading) const Text('로그아웃 중...'),
                if (_message != null)
                  Text(_message!, textAlign: TextAlign.center),
              ],
            ),
          );
        }
        return Scaffold(
          body: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('StopBell', style: TextStyle(fontSize: 32)),
                const SizedBox(height: 24),
                if (widget.authSession.state == AuthState.initializing)
                  const Text('인증 상태 확인 중...')
                else ...[
                  ElevatedButton(
                    onPressed: _isLoading ? null : _login,
                    child: const Text('Google로 계속하기'),
                  ),
                  if (_isLoading) ...[
                    const SizedBox(height: 16),
                    const CircularProgressIndicator(),
                    Text(
                      widget.authSession.isLoggingOut
                          ? '로그아웃 중...'
                          : '로그인 중...',
                    ),
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
      },
    );
  }
}
