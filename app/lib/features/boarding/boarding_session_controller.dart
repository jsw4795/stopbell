import 'package:flutter/foundation.dart';

import '../auth/auth_session.dart';
import 'boarding_candidates.dart';
import 'boarding_creation_intent.dart';
import 'boarding_session.dart';
import 'boarding_session_client.dart';

enum BoardingCreationState { notStarted, submitting, outcomeUnknown, resolved }

/// Screen-local coordination of durable creation and authoritative session state.
class BoardingSessionController extends ChangeNotifier {
  BoardingSessionController({
    required this.auth,
    required this.client,
    required this.storage,
    DateTime Function()? now,
  }) : generation = auth.generation,
       userIdentity = auth.userIdentity,
       sessionIdentity = auth.sessionIdentity,
       now = now ?? DateTime.now {
    auth.addListener(_authChanged);
  }
  final AuthSession auth;
  final BoardingSessionClient client;
  final BoardingIntentStorage storage;
  final DateTime Function() now;
  final int generation;
  final String? userIdentity, sessionIdentity;
  bool _disposed = false;
  int _readEpoch = 0;
  bool busy = false,
      initialized = false,
      lookupFailed = false,
      inaccessible = false,
      stateUncertain = false;
  String? message;
  BoardingSession? session;
  BoardingSelectionContext? selectionContext;
  BoardingCreationIntent? pending;
  BoardingCreationState creationState = BoardingCreationState.notStarted;
  bool get current =>
      !_disposed &&
      auth.isCurrentGeneration(generation) &&
      !auth.isLoggingOut &&
      auth.userIdentity == userIdentity &&
      auth.sessionIdentity == sessionIdentity;
  bool get needsRecovery =>
      pending != null && creationState != BoardingCreationState.resolved;
  bool get canCreate =>
      current &&
      initialized &&
      !busy &&
      !lookupFailed &&
      pending == null &&
      (session == null || session!.isTerminal);
  void _emit() {
    if (current) notifyListeners();
  }

  void _authChanged() {
    if (current || _disposed) return;
    _readEpoch++;
    session = null;
    pending = null;
    selectionContext = null;
    message = null;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _readEpoch++;
    auth.removeListener(_authChanged);
    super.dispose();
  }

  Future<void> initialize() async {
    if (!current || busy) return;
    busy = true;
    lookupFailed = false;
    message = null;
    _emit();
    try {
      if (sessionIdentity == null || userIdentity == null) {
        throw const FormatException();
      }
      final intent = await storage.read(sessionIdentity!);
      if (!current) return;
      if (intent != null &&
          intent.sessionIdentity == sessionIdentity &&
          intent.userIdentity == userIdentity) {
        pending = intent;
        creationState = BoardingCreationState.outcomeUnknown;
        selectionContext = intent.context;
        await _recover();
      } else {
        // Never send, recover or display another login's pending identity.
        final result = await client.findCurrent(
          generation: generation,
          current: () => current,
        );
        if (!current) return;
        session = result;
      }
      if (!current) return;
      initialized = true;
    } catch (_) {
      if (current) {
        lookupFailed = true;
        message = '현재 세션 또는 저장된 생성 요청을 확인하지 못했어요. 다시 확인해 주세요.';
      }
    } finally {
      if (current) {
        busy = false;
        _emit();
      }
    }
  }

  bool candidateFresh(BoardingCandidate candidate) {
    final time = now().toUtc();
    bool fresh(DateTime observed) =>
        !observed.isAfter(time) &&
        time.difference(observed) <= const Duration(seconds: 60);
    return fresh(candidate.observedAt) &&
        (candidate.providerDataTime == null ||
            fresh(candidate.providerDataTime!));
  }

  bool validCandidate(BoardingCandidate candidate, String number) =>
      candidate.canRequestConfirmation && candidateFresh(candidate);

  Future<void> create(
    BoardingCandidate candidate,
    String number,
    BoardingSelectionContext context, {
    Future<bool> Function()? preparePush,
    Future<BoardingCandidate?> Function(BoardingCandidate)? refreshSelected,
    int notificationOffsetStops = 2,
  }) async {
    if (!const [1, 2].contains(notificationOffsetStops)) {
      throw ArgumentError('notificationOffsetStops');
    }
    if (!canCreate) return;
    if (!validCandidate(candidate, number)) {
      message = '후보가 오래됐어요. 차량 정보를 다시 조회해 주세요.';
      _emit();
      return;
    }
    if (preparePush != null) {
      busy = true;
      message = null;
      _emit();
      bool allowed = false;
      try {
        allowed = await preparePush();
      } catch (_) {
        if (current) message = '알림 수신 준비를 확인하지 못했어요. 다시 시도해 주세요.';
      } finally {
        if (current) {
          busy = false;
          _emit();
        }
      }
      if (!current || !allowed) return;
      // Permission/registration may outlive candidate freshness. No UUID exists yet.
      if (!validCandidate(candidate, number)) {
        busy = true;
        _emit();
        BoardingCandidate? refreshed;
        try {
          refreshed = await refreshSelected?.call(candidate);
        } catch (_) {
          // A failed refresh cannot select a replacement or create a new request.
        }
        if (!current) return;
        busy = false;
        if (candidate.selectionKey == null ||
            refreshed?.selectionKey != candidate.selectionKey ||
            refreshed == null ||
            !validCandidate(refreshed, number)) {
          message = '후보 정보가 오래됐어요. 선택한 차량을 다시 조회해 주세요.';
          _emit();
          return;
        }
        candidate = refreshed;
      }
    }
    final intent = BoardingCreationIntent(
      clientRequestId: newBoardingRequestId(),
      candidateRef: candidate.candidateRef!,
      selectedVehicleNumber: candidate.vehicleNumber!,
      userIdentity: userIdentity!,
      sessionIdentity: sessionIdentity!,
      context: context,
      observedAt: candidate.observedAt,
      notificationOffsetStops: notificationOffsetStops,
    );
    busy = true;
    message = null;
    _emit();
    try {
      // Await durable write BEFORE any transport; failure leaves POST unstarted.
      await storage.save(intent);
    } catch (_) {
      if (current) {
        busy = false;
        message = '생성 요청을 안전하게 저장하지 못해 전송하지 않았어요. 다시 시도해 주세요.';
        _emit();
      }
      return;
    }
    if (!current) return;
    pending = intent;
    selectionContext = context;
    if (!validCandidate(candidate, number)) {
      // Storage can outlive the reference freshness window. No POST has been
      // sent yet, so this draft can safely be discarded after durable cleanup.
      try {
        await storage.delete(sessionIdentity!);
        if (!current) return;
        pending = null;
      } catch (_) {
        if (!current) return;
        creationState = BoardingCreationState.outcomeUnknown;
      }
      busy = false;
      message = '저장 중 후보 정보가 오래됐어요. 새 후보를 조회해 주세요.';
      _emit();
      return;
    }
    await _submit(retry: false);
  }

  Future<void> _submit({required bool retry}) async {
    final intent = pending!;
    creationState = BoardingCreationState.submitting;
    busy = true;
    _readEpoch++;
    _emit();
    try {
      final result = await client.create(
        intent.body,
        generation: generation,
        current: () => current,
      );
      if (!current) return;
      if (result.vehicleNumber != intent.selectedVehicleNumber ||
          result.notificationOffsetStops != intent.notificationOffsetStops ||
          (intent.notificationOffsetStops == null &&
              result.prepareNotificationEnabled !=
                  intent.prepareNotificationEnabled)) {
        throw const BoardingSessionException(SessionFailure.parsing);
      }
      session = result;
      creationState = BoardingCreationState.resolved;
      await _clearResolvedIntent();
    } on BoardingSessionException catch (error) {
      if (!current) return;
      message = boardingSessionError(error);
      // Once an earlier POST was uncertain, even a later 4xx cannot prove that
      // earlier request did not commit. Keep the original UUID/body recoverable.
      if (retry ||
          error.uncertain ||
          error.code == 'BOARDING_REQUEST_CONFLICT' ||
          !const {
            'INVALID_REQUEST',
            'BOARDING_CANDIDATE_CHANGED',
            'BOARDING_SESSION_CONFLICT',
            'BOARDING_TARGET_NOT_AHEAD',
            'BOARDING_TRACKING_UNSUPPORTED',
          }.contains(error.code)) {
        creationState = BoardingCreationState.outcomeUnknown;
      } else {
        try {
          await storage.delete(sessionIdentity!);
          if (!current) return;
          pending = null;
          creationState = BoardingCreationState.notStarted;
        } catch (_) {
          if (current) creationState = BoardingCreationState.outcomeUnknown;
        }
        if (current && error.code == 'BOARDING_SESSION_CONFLICT') {
          try {
            final result = await client.findCurrent(
              generation: generation,
              current: () => current,
            );
            if (!current) return;
            session = result;
          } catch (_) {
            if (current) lookupFailed = true;
          }
        }
      }
    } catch (_) {
      if (current) {
        creationState = BoardingCreationState.outcomeUnknown;
        message = '생성 결과를 확인할 수 없어요. 원래 요청의 결과를 조회해 주세요.';
      }
    } finally {
      if (current) {
        busy = false;
        _emit();
      }
    }
  }

  Future<void> _clearResolvedIntent() async {
    try {
      await storage.delete(sessionIdentity!);
      if (current) pending = null;
    } catch (_) {
      if (current) message = '세션은 확인됐지만 저장된 요청 정리를 마치지 못했어요. 다시 확인해 주세요.';
    }
  }

  Future<void> _recover() async {
    final result = await client.findRequest(
      pending!.clientRequestId,
      generation: generation,
      current: () => current,
    );
    if (!current) return;
    if (result == null) {
      creationState = BoardingCreationState.outcomeUnknown;
      message = '아직 생성 성공을 확인할 수 없어요. 서버 처리 중일 수 있어요. 새 요청을 만들지 않고 원래 결과를 다시 조회하거나 동일 요청만 재시도할 수 있어요.';
    } else {
      if (result.vehicleNumber != pending!.selectedVehicleNumber ||
          result.notificationOffsetStops != pending!.notificationOffsetStops ||
          (pending!.notificationOffsetStops == null &&
              result.prepareNotificationEnabled !=
                  pending!.prepareNotificationEnabled)) {
        throw const BoardingSessionException(SessionFailure.parsing);
      }
      session = result;
      creationState = BoardingCreationState.resolved;
      await _clearResolvedIntent();
    }
  }

  Future<void> recover() async {
    if (!current || busy || pending == null) return;
    busy = true;
    message = null;
    _emit();
    try {
      await _recover();
      if (current) {
        initialized = true;
        lookupFailed = false;
      }
    } catch (_) {
      if (current) message = '원래 생성 결과를 조회하지 못했어요. 요청을 보존했으니 다시 조회해 주세요.';
    } finally {
      if (current) {
        busy = false;
        _emit();
      }
    }
  }

  Future<void> retryCreation() async {
    if (!current ||
        busy ||
        creationState != BoardingCreationState.outcomeUnknown ||
        pending == null) {
      return;
    }
    await _submit(retry: true);
  }

  bool _accept(BoardingSession result) {
    final old = session;
    if (old != null &&
        (result.id != old.id || result.vehicleNumber != old.vehicleNumber)) {
      throw const BoardingSessionException(SessionFailure.parsing);
    }
    if (old == null ||
        result.revision < old.revision ||
        (old.isTerminal && result.status != old.status)) {
      return false;
    }
    session = result;
    return true;
  }

  Future<void> refresh() async {
    if (!current || session == null || busy) return;
    final id = session!.id;
    final epoch = ++_readEpoch;
    try {
      final result = await client.find(
        id,
        generation: generation,
        current: () => current && epoch == _readEpoch,
      );
      if (current && epoch == _readEpoch) {
        if (_accept(result)) {
          inaccessible = false;
          stateUncertain = false;
          message = null;
        }
        _emit();
      }
    } on BoardingSessionException catch (error) {
      if (current && epoch == _readEpoch) {
        inaccessible =
            error.code == 'BOARDING_SESSION_NOT_FOUND' ||
            error.statusCode == 404;
        message = boardingSessionError(error);
        _emit();
      }
    } catch (_) {
      if (current && epoch == _readEpoch) {
        message = '현재 상태를 확인하지 못했어요. 다시 조회해 주세요.';
        _emit();
      }
    }
  }

  Future<void> cancel() => _mutate(null, null);
  Future<void> reconfirm(
    BoardingCandidate candidate,
    String number,
    BoardingSelectionContext context,
  ) async {
    if (!current || session?.canReconfirm != true || busy || stateUncertain) {
      return;
    }
    if (!validCandidate(candidate, number) ||
        candidate.vehicleNumber != session!.vehicleNumber) {
      message = '기존 세션과 동일한 차량을 목록에서 다시 선택해 주세요.';
      _emit();
      return;
    }
    await _mutate(candidate, candidate.vehicleNumber);
    if (current && session?.status == BoardingSessionStatus.tracking) {
      selectionContext = context;
    }
  }

  Future<void> _mutate(BoardingCandidate? candidate, String? number) async {
    if (!current ||
        busy ||
        session?.isOpen != true ||
        inaccessible ||
        stateUncertain) {
      return;
    }
    final original = session!;
    busy = true;
    message = null;
    _readEpoch++;
    _emit();
    try {
      final result = candidate == null
          ? await client.cancel(
              original.id,
              original.revision,
              generation: generation,
              current: () => current,
            )
          : await client.reconfirm(
              original.id,
              original.revision,
              candidate.candidateRef!,
              number!,
              generation: generation,
              current: () => current,
            );
      if (current) _accept(result);
    } on BoardingSessionException catch (error) {
      if (!current) return;
      message = boardingSessionError(error);
      inaccessible = error.statusCode == 404;
      if (error.uncertain ||
          error.code == 'BOARDING_REVISION_CONFLICT' ||
          error.code == 'BOARDING_RECONFIRM_REQUIRED') {
        stateUncertain = true;
        await _refreshAfterMutation(original.id);
      }
    } catch (_) {
      if (current) {
        stateUncertain = true;
        message = '변경 결과를 확정할 수 없어 현재 상태를 다시 조회해요.';
        await _refreshAfterMutation(original.id);
      }
    } finally {
      if (current) {
        _readEpoch++;
        busy = false;
        _emit();
      }
    }
  }

  Future<void> _refreshAfterMutation(int id) async {
    try {
      final result = await client.find(
        id,
        generation: generation,
        current: () => current,
      );
      if (current) {
        if (_accept(result)) {
          stateUncertain = false;
        }
      }
    } on BoardingSessionException catch (error) {
      if (current) {
        inaccessible = error.statusCode == 404;
        message = boardingSessionError(error);
      }
    } catch (_) {
      if (current) {
        message = '${message ?? ''}\n현재 상태 재조회도 실패했어요. 상태를 다시 확인해 주세요.';
      }
    }
  }
}

String boardingSessionError(BoardingSessionException error) =>
    switch (error.code) {
      'BOARDING_CANDIDATE_CHANGED' =>
        '차량 후보가 변경되었거나 오래됐어요. 새 후보를 조회해 다시 선택해 주세요.',
      'BOARDING_SESSION_CONFLICT' => '이미 진행 중인 세션이 있어요. 현재 세션을 확인해 주세요.',
      'BOARDING_REQUEST_CONFLICT' =>
        '원래 생성 요청과 내용이 일치하지 않아요. 요청을 바꾸지 않고 원래 결과를 확인해 주세요.',
      'BOARDING_REVISION_CONFLICT' => '세션 상태가 변경되어 현재 상태를 다시 조회했어요.',
      'BOARDING_TARGET_NOT_AHEAD' => '목적지가 차량 진행 방향의 앞쪽인지 확인할 수 없어요.',
      'BOARDING_TRACKING_UNSUPPORTED' =>
        '현재 이 차량의 하차 알림 추적은 아직 지원되지 않아요. 차량번호를 확인했더라도 실제 추적을 시작할 수 없어요.',
      'BOARDING_RECONFIRM_REQUIRED' =>
        '현재 세션에서 재확인 조건을 충족하지 못했어요. 현재 상태와 차량 정보를 다시 확인해 주세요.',
      'BOARDING_SESSION_NOT_FOUND' => '세션이 없거나 접근할 수 없어요.',
      'TRANSIT_TEMPORARILY_UNAVAILABLE' =>
        '차량 정보를 일시적으로 확인할 수 없어요. 잠시 후 다시 확인해 주세요.',
      'INVALID_REQUEST' => '요청 형식 또는 선택한 정보가 유효하지 않아요. 다시 확인해 주세요.',
      _ => switch (error.failure) {
        SessionFailure.timeout => '응답 시간이 초과되어 결과를 확정할 수 없어요.',
        SessionFailure.network => '통신 오류로 결과를 확인하지 못했어요.',
        SessionFailure.parsing => '응답 형식을 확인할 수 없어 결과를 확정할 수 없어요.',
        SessionFailure.http => '서버 응답을 확인하지 못했어요. 현재 상태를 다시 확인해 주세요.',
      },
    };
