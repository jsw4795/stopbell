import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';

import 'core/app_config.dart';
import 'core/authenticated_api_client.dart';
import 'core/theme/stopbell_theme.dart';
import 'features/notification/installation_storage.dart';
import 'features/notification/device_api_client.dart';
import 'features/notification/push_permission_service.dart';
import 'features/notification/push_registration_service.dart';
import 'features/notification/push_activation_prompt.dart';
import 'features/notification/notification_lifecycle.dart';
import 'features/alarm/alarm_api_client.dart';
import 'features/alarm/alarm_home.dart';
import 'features/boarding/boarding_candidate_client.dart';
import 'features/boarding/boarding_session_client.dart';
import 'features/boarding/boarding_creation_intent.dart';
import 'features/auth/backend_auth_client.dart';
import 'features/auth/apple_auth_service.dart';
import 'features/auth/auth_session.dart';
import 'features/auth/google_auth_service.dart';
import 'features/auth/token_pair_storage.dart';
import 'features/auth/account_deletion_client.dart';
import 'features/auth/account_deletion_controller.dart';
import 'features/auth/login_view.dart';
import 'features/settings/settings_screen.dart';
import 'features/alarm/alarm_creation_intent.dart';
import 'features/transit/bus_route.dart';
import 'features/transit/bus_route_search_client.dart';
import 'features/transit/bus_route_stop_client.dart';
import 'features/transit/bus_route_stop_occurrence.dart';
import 'features/transit/bus_route_vehicle_bar.dart';
import 'features/transit/bus_route_vehicle_client.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final config = AppConfig.fromEnvironment();
  try {
    config.validate();
  } on AppConfigException catch (error) {
    runApp(
      MaterialApp(
        theme: StopBellTheme.light,
        darkTheme: StopBellTheme.dark,
        themeMode: ThemeMode.system,
        builder: StopBellTheme.withSystemOverlay,
        home: Scaffold(body: Center(child: Text(error.message))),
      ),
    );
    return;
  }
  final backend = BackendAuthClient(
    apiBaseUrl: Uri.tryParse(config.apiBaseUrl) ?? Uri(),
  );
  final tokens = SecureTokenPairStorage();
  final authSession = AuthSession(
    authenticator: GoogleAuthService(config: config, backend: backend),
    backend: backend,
    tokenStorage: tokens,
  );
  final apiClient = AuthenticatedApiClient(
    apiBaseUrl: Uri.tryParse(config.apiBaseUrl) ?? Uri(),
    authSession: authSession,
  );
  final routeSearch = BusRouteSearchClient(apiClient);
  final routeStops = BusRouteStopClient(apiClient);
  final alarms = AlarmApiClient(apiClient);
  final push = Platform.isIOS
      ? PushRegistrationService(
          auth: authSession,
          storage: InstallationStorage(SecureInstallationPersistence(), tokens),
          devices: DeviceApiClient(apiClient),
          provider: FirebasePushProvider(),
        )
      : null;
  final notifications = Platform.isIOS
      ? NotificationLifecycle(authSession)
      : null;
  runApp(
    StopBellApplication(
      authSession: authSession,
      appleAuthenticator: AppleAuthService(backend: backend),
      appleLoginEnabled: config.appleLoginEnabled,
      push: push,
      notifications: notifications,
      searchRoutes: routeSearch.search,
      findStops: routeStops.findStops,
      findVehicles: BusRouteVehicleClient(apiClient).findVehicles,
      alarmClient: alarms,
      findBoardingCandidates: BoardingCandidateClient(apiClient).find,
      boardingSessionClient: BoardingSessionApiClient(apiClient),
      boardingIntentStorage: const SecureBoardingIntentStorage(),
      deletion: AccountDeletionController(
        auth: authSession,
        client: AccountDeletionClient(
          apiBaseUrl: Uri.tryParse(config.apiBaseUrl) ?? Uri(),
        ),
        storage: SecureDeletionStorage(
          origin: Uri.parse(config.apiBaseUrl).origin,
        ),
        alarms: const SecureAlarmIntentStorage(),
        boarding: const SecureBoardingIntentStorage(),
      ),
    ),
  );
}

class StopBellApplication extends StatefulWidget {
  const StopBellApplication({
    super.key,
    required this.authSession,
    required this.searchRoutes,
    required this.findStops,
    required this.alarmClient,
    this.appleAuthenticator,
    this.appleLoginEnabled = true,
    this.push,
    this.notifications,
    this.findBoardingCandidates,
    this.boardingSessionClient,
    this.boardingIntentStorage,
    this.deletion,
    this.findVehicles,
  });

  final FindBusRouteVehicles? findVehicles;
  final AuthSession authSession;
  final Authenticator? appleAuthenticator;
  final bool appleLoginEnabled;
  final PushRegistrationService? push;
  final NotificationLifecycle? notifications;
  final Future<List<BusRoute>> Function(String query) searchRoutes;
  final Future<List<BusRouteStopOccurrence>> Function(int routeId) findStops;
  final AlarmClient alarmClient;
  final FindBoardingCandidates? findBoardingCandidates;
  final BoardingSessionClient? boardingSessionClient;
  final BoardingIntentStorage? boardingIntentStorage;
  final AccountDeletionController? deletion;

  @override
  State<StopBellApplication> createState() => _StopBellApplicationState();
}

class _StopBellApplicationState extends State<StopBellApplication>
    with WidgetsBindingObserver {
  StreamSubscription<String>? _idChanges;
  bool _storageFailed = false;
  bool _initialized = false;
  int? _syncedGeneration;
  bool _hadAuthenticatedSession = false;
  (int, String?, String?)? _visibleIdentity;

  Future<void> _initialize() async {
    try {
      await widget.push?.storage.initialize();
    } catch (_) {
      // A deletion receipt may need the existing pair for a safe retry.
      // Fail closed without erasing it before the receipt has been restored.
      if (widget.deletion != null) {
        if (mounted) setState(() => _storageFailed = true);
        return;
      }
      // Registration remains fail-closed. Avoid restoring any possibly surviving
      // Keychain login when container/installation bootstrap cannot be trusted.
      try {
        await widget.push?.storage.tokens.delete();
      } catch (_) {
        if (mounted) setState(() => _storageFailed = true);
        return;
      }
    }
    try {
      await widget.authSession.initialize();
      await widget.deletion?.initialize();
    } catch (_) {
      if (widget.deletion != null) {
        if (mounted) setState(() => _storageFailed = true);
        return;
      }
    }
    if (!mounted) return;
    setState(() => _initialized = true);
    _authChanged();
    _prepareFirebase().catchError((Object _) {});
  }

  Future<void>? _firebaseSetup;
  Future<void> _prepareFirebase() {
    final pending = _firebaseSetup;
    if (pending != null) return pending;
    final provider = widget.push?.provider;
    if (provider is! FirebasePushProvider) return Future<void>.value();
    final work = () async {
      await provider.initialize();
      if (!mounted) return;
      _idChanges ??= provider.idChanges.listen(
        (_) {
          widget.push?.fidChanged();
          _synchronize();
        },
        onError: (Object _) {
          widget.push?.fidChanged();
        },
      );
      await widget.notifications?.start();
    }();
    _firebaseSetup = work;
    work.then<void>(
      (_) {},
      onError: (Object _, StackTrace _) {
        if (identical(_firebaseSetup, work)) _firebaseSetup = null;
      },
    );
    return work;
  }

  void _synchronize() {
    if (_initialized &&
        !widget.authSession.isLoggingOut &&
        !widget.authSession.isDeletingAccount &&
        !(widget.deletion?.loading ?? false)) {
      _prepareFirebase()
          .then((_) {
            if (mounted) widget.push?.synchronize();
          })
          .catchError((Object _) {});
    }
  }

  void _authChanged() {
    final auth = widget.authSession;
    if (!mounted) return;
    final identity = (auth.generation, auth.userIdentity, auth.sessionIdentity);
    if (_hadAuthenticatedSession &&
        (auth.isLoggingOut ||
            auth.isDeletingAccount ||
            auth.state != AuthState.authenticated ||
            identity != _visibleIdentity)) {
      _messengerKey.currentState?.clearSnackBars();
      _navigatorKey.currentState?.popUntil((route) => route.isFirst);
    }
    _visibleIdentity = identity;
    _hadAuthenticatedSession =
        auth.state == AuthState.authenticated &&
        !auth.isLoggingOut &&
        !auth.isDeletingAccount;
    if (_initialized &&
        auth.state == AuthState.authenticated &&
        !auth.isLoggingOut &&
        !auth.isDeletingAccount &&
        !(widget.deletion?.loading ?? false) &&
        _syncedGeneration != auth.generation) {
      _syncedGeneration = auth.generation;
      _synchronize();
    }
  }

  final _messengerKey = GlobalKey<ScaffoldMessengerState>();
  final _navigatorKey = GlobalKey<NavigatorState>();

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _synchronize();
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    widget.authSession.addListener(_authChanged);
    widget.deletion?.addListener(_authChanged);
    _initialize();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    widget.authSession.removeListener(_authChanged);
    widget.deletion?.removeListener(_authChanged);
    _idChanges?.cancel();
    widget.notifications?.dispose();
    widget.deletion?.dispose();
    widget.authSession.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'StopBell',
      theme: StopBellTheme.light,
      darkTheme: StopBellTheme.dark,
      themeMode: ThemeMode.system,
      builder: (context, child) => BusRouteVehiclesScope(
        find: widget.findVehicles,
        child: StopBellTheme.withSystemOverlay(context, child),
      ),
      scaffoldMessengerKey: _messengerKey,
      navigatorKey: _navigatorKey,
      home: _storageFailed
          ? const Scaffold(
              body: Center(
                child: Text('저장된 정보를 안전하게 복구하지 못했어요. 앱 저장소를 확인해 주세요.'),
              ),
            )
          : !_initialized && widget.deletion != null
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : LoginScreen(
              authSession: widget.authSession,
              appleAuthenticator: widget.appleAuthenticator,
              appleLoginEnabled: widget.appleLoginEnabled,
              push: widget.push,
              notifications: widget.notifications,
              searchRoutes: widget.searchRoutes,
              findStops: widget.findStops,
              alarmClient: widget.alarmClient,
              findBoardingCandidates: widget.findBoardingCandidates,
              boardingSessionClient: widget.boardingSessionClient,
              boardingIntentStorage: widget.boardingIntentStorage,
              deletion: widget.deletion,
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
    required this.alarmClient,
    this.appleAuthenticator,
    this.appleLoginEnabled = true,
    this.push,
    this.notifications,
    this.findBoardingCandidates,
    this.boardingSessionClient,
    this.boardingIntentStorage,
    this.deletion,
  });

  final AuthSession authSession;
  final Authenticator? appleAuthenticator;
  final bool appleLoginEnabled;
  final PushRegistrationService? push;
  final NotificationLifecycle? notifications;
  final Future<List<BusRoute>> Function(String query) searchRoutes;
  final Future<List<BusRouteStopOccurrence>> Function(int routeId) findStops;
  final AlarmClient alarmClient;
  final FindBoardingCandidates? findBoardingCandidates;
  final BoardingSessionClient? boardingSessionClient;
  final BoardingIntentStorage? boardingIntentStorage;
  final AccountDeletionController? deletion;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _homeRequests = ValueNotifier<int>(0);
  bool _isLoading = false;
  String? _message;

  @override
  void dispose() {
    _homeRequests.dispose();
    super.dispose();
  }

  Future<void> _confirmDeletion() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('회원 탈퇴'),
        content: const Text(
          '계정과 관련 데이터가 삭제되고 기존 알람과 하차 알람을 이용할 수 없어요. 삭제된 데이터는 앱에서 복구할 수 없어요. Apple 계정 연결을 직접 해제해야 할 수 있으며, 탈퇴 처리에 시간이 걸릴 수 있어요.',
        ),
        actions: [
          TextButton(
            autofocus: true,
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          TextButton(
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('탈퇴 확인'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    Navigator.of(context).popUntil((route) => route.isFirst);
    await widget.deletion?.start();
  }

  Future<void> _login({Authenticator? authenticator}) async {
    if (_isLoading || widget.authSession.state != AuthState.unauthenticated) {
      return;
    }
    setState(() {
      _isLoading = true;
      _message = null;
    });

    try {
      await widget.authSession.login(authenticator: authenticator);
    } on AppleLoginCanceledException {
      // Cancellation does not indicate a failed login.
    } on AppConfigException catch (error) {
      if (mounted) setState(() => _message = error.message);
    } on LoginException catch (error) {
      if (mounted) setState(() => _message = error.message);
    } on BackendAuthException catch (error) {
      if (mounted) setState(() => _message = error.message);
    } catch (_) {
      if (mounted) setState(() => _message = '로그인이 실패했어요. 다시 시도해 주세요.');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  void _openSettings() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => SettingsScreen(
          onLogout: _logout,
          onDeleteAccount: widget.deletion == null ? null : _confirmDeletion,
          pushProvider: widget.push?.provider,
        ),
      ),
    );
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
      await widget.authSession.logout(beforeLogout: widget.push?.beforeLogout);
    } on AuthSessionPersistenceException {
      if (mounted) {
        setState(() => _message = '저장된 인증 정보를 삭제하지 못했어요. 다시 시도해 주세요.');
      }
    } on BackendLogoutCleanupException {
      if (mounted) {
        setState(() => _message = '이 기기에서는 로그아웃했어요. 서버 세션은 정리하지 못했어요.');
      }
    } catch (_) {
      if (mounted) setState(() => _message = '로그아웃 중 오류가 발생했어요. 다시 시도해 주세요.');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([
        widget.authSession,
        if (widget.deletion != null) widget.deletion!,
      ]),
      builder: (context, _) {
        final deletion = widget.deletion;
        if (deletion != null &&
            deletion.loading &&
            widget.authSession.state == AuthState.authenticated) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }
        if (widget.authSession.isDeletingAccount &&
            (deletion == null || !deletion.active)) {
          return const Scaffold(body: Center(child: Text('회원 탈퇴를 준비하고 있어요.')));
        }
        if (deletion != null && deletion.active) {
          if (deletion.manualNotice) {
            return Scaffold(
              body: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('StopBell 계정의 삭제가 완료되었어요.'),
                    Text(
                      deletion.appleUncertain
                          ? 'Apple 계정과 StopBell의 연결 해제 결과를 확인할 수 없어요. Apple 계정 설정에서 앱 연결을 확인하고 필요하면 직접 해제해 주세요.'
                          : 'Apple 계정과 StopBell의 연결은 자동으로 해제하지 못했어요. Apple 계정 설정에서 앱 연결을 확인하고 필요하면 직접 해제해 주세요.',
                      textAlign: TextAlign.center,
                    ),
                    if (deletion.message != null) Text(deletion.message!),
                    TextButton(
                      onPressed: deletion.acknowledgeManual,
                      child: const Text('확인'),
                    ),
                  ],
                ),
              ),
            );
          }
          return Scaffold(
            appBar: AppBar(title: const Text('회원 탈퇴')),
            body: Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    deletion.message ?? '탈퇴 처리 상태를 확인하고 있어요.',
                    textAlign: TextAlign.center,
                  ),
                  if (deletion.busy) const CircularProgressIndicator(),
                  if (deletion.confirmed)
                    TextButton(
                      onPressed: deletion.busy ? null : deletion.finishCleanup,
                      child: const Text('기기 정보 정리 재시도'),
                    )
                  else ...[
                    TextButton(
                      onPressed: deletion.busy ? null : deletion.check,
                      child: const Text('처리 상태 확인'),
                    ),
                    TextButton(
                      onPressed: deletion.canRetry ? deletion.retry : null,
                      child: const Text('탈퇴 요청 재시도'),
                    ),
                  ],
                ],
              ),
            ),
          );
        }
        if (widget.authSession.state == AuthState.authenticated &&
            !widget.authSession.isLoggingOut) {
          return Scaffold(
            body: Column(
              children: [
                Expanded(
                  child: SafeArea(
                    child: AlarmHome(
                      onOpenSettings: _isLoading ? null : _openSettings,
                      key: ValueKey(widget.authSession.generation),
                      homeRequests: _homeRequests,
                      client: widget.alarmClient,
                      notifications: widget.notifications,
                      beforeBoardingCreate: (context) =>
                          prepareBoardingPush(context, widget.push),
                      beforeActivate: widget.push == null
                          ? null
                          : (context) =>
                                prepareAlarmPush(context, widget.push!),
                      authSession: widget.authSession,
                      searchRoutes: widget.searchRoutes,
                      findStops: widget.findStops,
                      findBoardingCandidates: widget.findBoardingCandidates,
                      boardingSessionClient: widget.boardingSessionClient,
                      boardingIntentStorage: widget.boardingIntentStorage,
                    ),
                  ),
                ),
                if (_isLoading) const Text('로그아웃 중...'),
                if (_message != null)
                  Text(_message!, textAlign: TextAlign.center),
                if (deletion?.message != null)
                  Text(deletion!.message!, textAlign: TextAlign.center),
              ],
            ),
          );
        }
        final appleAvailable =
            widget.appleLoginEnabled &&
            widget.appleAuthenticator != null &&
            !kIsWeb &&
            defaultTargetPlatform == TargetPlatform.iOS;
        return LoginView(
          initializing: widget.authSession.state == AuthState.initializing,
          loading: _isLoading,
          loadingMessage: widget.authSession.isLoggingOut
              ? '로그아웃 중...'
              : '로그인 중...',
          onGoogle: () => _login(),
          onApple: appleAvailable
              ? () => _login(authenticator: widget.appleAuthenticator)
              : null,
          messages: [?_message, ?deletion?.message],
        );
      },
    );
  }
}
