import 'dart:async';
import 'dart:convert';

import 'package:stopbell/features/auth/auth_session.dart';
import 'package:stopbell/features/auth/backend_auth_client.dart';
import 'package:stopbell/features/auth/google_auth_service.dart';
import 'package:stopbell/features/auth/token_pair.dart';
import 'package:stopbell/features/auth/token_pair_storage.dart';
import 'package:stopbell/features/notification/device_api_client.dart';
import 'package:stopbell/features/notification/installation_storage.dart';
import 'package:stopbell/features/notification/push_permission_service.dart';
import 'package:stopbell/features/notification/push_registration_service.dart';

TokenPair userPair(String user, {String rotation = '1'}) => TokenPair(
  accessToken:
      'header.${base64UrlEncode(utf8.encode(jsonEncode({'sub': user})))}.$rotation',
  refreshToken: 'refresh-$user-$rotation',
);

class MemoryTokens implements TokenPairStorage {
  TokenPair? pair;
  int deletes = 0;
  @override
  Future<TokenPair?> read() async => pair;
  @override
  Future<void> save(TokenPair value) async => pair = value;
  @override
  Future<void> delete() async {
    deletes++;
    pair = null;
  }
}

class MemoryInstallation implements InstallationPersistence {
  String? value;
  String markerId = 'container-1';
  bool initialized = false;
  bool failWrite = false;
  bool failCommit = false;
  @override
  Future<String?> read() async => value;
  @override
  Future<void> write(String value) async {
    if (failWrite) throw StateError('fixture storage failure');
    this.value = value;
  }

  @override
  Future<({String id, bool initialized})> marker() async =>
      (id: markerId, initialized: initialized);
  @override
  Future<void> commitMarker() async {
    if (failCommit) throw StateError('fixture marker failure');
    initialized = true;
  }
}

class UserAuthenticator implements Authenticator {
  String user = 'A';
  @override
  Future<TokenPair> login() async => userPair(user);
}

class TestAuthBackend extends BackendAuthClient {
  TestAuthBackend() : super(apiBaseUrl: Uri.parse('https://stopbell.test'));
  int logoutCalls = 0;
  int refreshCalls = 0;
  bool offline = false;
  Future<TokenPair> Function(String)? rotation;
  @override
  Future<void> logout(String token) async {
    logoutCalls++;
    if (offline) throw StateError('offline');
  }

  @override
  Future<TokenPair> refresh(String token) async {
    refreshCalls++;
    return rotation == null
        ? userPair('A', rotation: '2')
        : await rotation!(token);
  }
}

class FakePushProvider implements PushProvider {
  PushPermission status = PushPermission.authorized;
  String fid = 'FID-A';
  bool fail = false;
  int registrations = 0;
  int requests = 0;
  int settings = 0;
  Future<String> Function()? registration;
  @override
  Future<PushPermission> permission({bool request = false}) async {
    if (request) {
      requests++;
      status = PushPermission.authorized;
    }
    return status;
  }

  @override
  Future<String> registeredFid() async {
    registrations++;
    if (fail) throw StateError('provider failure');
    return registration == null ? fid : await registration!();
  }

  @override
  Future<String> currentFid() async => fid;
  @override
  Stream<String> get idChanges => const Stream.empty();
  @override
  Future<void> openSettings() async {
    settings++;
  }
}

class FakeDevices implements DeviceClient {
  FakeDevices(this.auth, this.persistence);
  final AuthSession auth;
  final MemoryInstallation persistence;
  String? owner;
  String? fid;
  int revision = 0;
  int generation = 0;
  bool enabled = false;
  final List<Map<String, dynamic>> requests = [];
  final List<String?> users = [];
  final List<String?> sessions = [];
  Future<DeviceState> Function(Map<String, dynamic>, String)? mutation;
  Future<DeviceState?> Function()? query;
  @override
  Future<DeviceState?> find(
    String id,
    String credential,
    int sessionGeneration,
  ) async {
    if (query != null) return query!();
    if (owner == null) return null;
    return state(owned: owner == auth.userIdentity);
  }

  DeviceState state({bool owned = true}) => DeviceState(
    enabled: enabled,
    revision: revision,
    generation: generation,
    ownedByCurrentUser: owned,
  );
  DeviceState apply(Map<String, dynamic> payload, String kind) {
    if (owner != null && owner != auth.userIdentity) generation++;
    owner = auth.userIdentity;
    enabled = kind == 'register';
    revision = payload['registrationRevision'] as int;
    fid = enabled ? payload['pushRegistrationId'] as String : null;
    return state();
  }

  @override
  Future<DeviceState> mutate(
    Map<String, dynamic> payload,
    String kind,
    String credential,
    int sessionGeneration,
  ) async {
    final persisted = jsonDecode(persistence.value!) as Map;
    if (persisted['pending']['payload']['registrationRevision'] !=
        payload['registrationRevision']) {
      throw StateError('mutation before durable intent');
    }
    requests.add(Map<String, dynamic>.from(payload));
    users.add(auth.userIdentity);
    sessions.add(auth.sessionIdentity);
    return mutation == null
        ? apply(payload, kind)
        : await mutation!(payload, kind);
  }
}

class PushFixture {
  final tokens = MemoryTokens();
  final persistence = MemoryInstallation();
  final authenticator = UserAuthenticator();
  final backend = TestAuthBackend();
  final provider = FakePushProvider();
  late final auth = AuthSession(
    authenticator: authenticator,
    backend: backend,
    tokenStorage: tokens,
  );
  late final storage = InstallationStorage(persistence, tokens);
  late final devices = FakeDevices(auth, persistence);
  late final service = PushRegistrationService(
    auth: auth,
    storage: storage,
    devices: devices,
    provider: provider,
  );

  Future<void> initialize({bool login = true}) async {
    await storage.initialize();
    await auth.initialize();
    if (login) await auth.login();
  }

  Future<void> switchUser(String user) async {
    await auth.logout();
    authenticator.user = user;
    await auth.login();
  }
}
