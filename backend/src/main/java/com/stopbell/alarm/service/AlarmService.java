package com.stopbell.alarm.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.stopbell.alarm.dto.AlarmResponse;
import com.stopbell.alarm.dto.CreateAlarmRequest;
import com.stopbell.alarm.dto.UpdateAlarmOptionsRequest;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.domain.BusRouteType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.repository.BusRouteRepository;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.transit.service.BusRouteEndpointResolver;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final BusRouteStopOccurrenceRepository occurrenceRepository;
    private final BusRouteRepository routeRepository;
    private final BusRouteEndpointResolver endpointResolver;

    public AlarmService(
            AlarmRepository alarmRepository,
            UserRepository userRepository,
            BusRouteStopOccurrenceRepository occurrenceRepository,
            NotificationDeliveryRepository deliveryRepository,
            BusRouteRepository routeRepository,
            BusRouteEndpointResolver endpointResolver
    ) {
        this.alarmRepository = alarmRepository;
        this.deliveryRepository = deliveryRepository;
        this.userRepository = userRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.routeRepository = routeRepository;
        this.endpointResolver = endpointResolver;
    }

    @Transactional
    public AlarmResponse create(Long userId, CreateAlarmRequest request) {
        if (request == null || request.targetStopOccurrenceId() == null
                || request.targetStopOccurrenceId() <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        userRepository.requireActive(userId);
        BusRouteStopOccurrence targetOccurrence = occurrenceRepository.findById(request.targetStopOccurrenceId())
                .orElseThrow(() -> new ApiException(ErrorCode.TARGET_STOP_OCCURRENCE_NOT_FOUND));

        BusRoute route = targetOccurrence.getRoute();
        List<BusRouteStopOccurrence> traversal = occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route);
        int targetIndex = -1;
        for (int index = 0; index < traversal.size(); index++) {
            if (Objects.equals(traversal.get(index).getId(), targetOccurrence.getId())) {
                targetIndex = index;
                break;
            }
        }
        if (targetIndex < 0) {
            throw new ApiException(ErrorCode.TARGET_STOP_OCCURRENCE_NOT_FOUND);
        }

        if (request.notifyOneStopBefore() && targetIndex == 0) {
            throw new ApiException(ErrorCode.INVALID_ALARM_REQUEST, "Target stop has no predecessor stop.");
        }
        if (request.notifyOneStopAfter() && targetIndex == traversal.size() - 1) {
            throw new ApiException(ErrorCode.INVALID_ALARM_REQUEST, "Target stop has no successor stop.");
        }

        AdjacentStopSnapshot predecessor = request.notifyOneStopBefore()
                ? adjacentSnapshot(traversal.get(targetIndex - 1)) : null;
        AdjacentStopSnapshot successor = request.notifyOneStopAfter()
                ? adjacentSnapshot(traversal.get(targetIndex + 1)) : null;
        BusStop stop = targetOccurrence.getStop();
        BusAlarmTarget busTarget = new BusAlarmTarget(
                route.getProvider(),
                route.getExternalRouteId(),
                stop.getExternalStopId(),
                targetOccurrence.getStopOrder(),
                route.getRouteNumber(),
                stop.getStopName(),
                stop.getLatitude(),
                stop.getLongitude(),
                route.getCityCode(),
                predecessor,
                successor
        );
        Alarm alarm = alarmRepository.save(new Alarm(user, busTarget));
        return toResponse(alarm);
    }

    @Transactional(readOnly = true)
    public List<AlarmResponse> findAll(Long userId) {
        return toResponses(alarmRepository.findAllByUserIdOrderByCreatedAtDesc(userId));
    }

    @Transactional(readOnly = true)
    public AlarmResponse findById(Long userId, Long alarmId) {
        Alarm alarm = alarmRepository.findByIdAndUserId(alarmId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.ALARM_NOT_FOUND));
        return toResponse(alarm);
    }

    @Transactional
    public AlarmResponse activate(Long userId, Long alarmId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        userRepository.requireActive(userId);
        Alarm alarm = alarmRepository.findByIdAndUserIdForUpdate(alarmId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.ALARM_NOT_FOUND));
        alarm.activate();
        return toResponse(alarm);
    }

    @Transactional
    public AlarmResponse deactivate(Long userId, Long alarmId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        userRepository.requireActive(userId);
        Alarm alarm = alarmRepository.findByIdAndUserIdForUpdate(alarmId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.ALARM_NOT_FOUND));
        alarm.deactivate();
        return toResponse(alarm);
    }

    /** 활성 알람의 한 정류장 전/후 옵션만 바꾼다. 이후 평가부터 적용되며 이미 발생한 이벤트는 건드리지 않는다. */
    @Transactional
    public AlarmResponse updateOptions(Long userId, Long alarmId, UpdateAlarmOptionsRequest request) {
        if (request == null || request.notifyOneStopBefore() == null || request.notifyOneStopAfter() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        userRepository.requireActive(userId);
        Alarm alarm = alarmRepository.findByIdAndUserIdForUpdate(alarmId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.ALARM_NOT_FOUND));
        if (alarm.getStatus() != AlarmStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ALARM_NOT_ACTIVE);
        }
        BusAlarmTarget target = busTarget(alarm);
        BusRoute route = routeRepository
                .findByProviderAndExternalRouteId(target.getProvider(), target.getExternalRouteId())
                .filter(found -> matches(found, target))
                .orElseThrow(() -> new ApiException(ErrorCode.BUS_ROUTE_NOT_FOUND));
        List<BusRouteStopOccurrence> traversal = occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route);
        int targetIndex = -1;
        for (int index = 0; index < traversal.size(); index++) {
            BusRouteStopOccurrence occurrence = traversal.get(index);
            if (occurrence.getStopOrder() == target.getTargetStopOrder()
                    && Objects.equals(occurrence.getStop().getExternalStopId(), target.getExternalStopId())) {
                targetIndex = index;
                break;
            }
        }
        if (targetIndex < 0) {
            throw new ApiException(ErrorCode.TARGET_STOP_OCCURRENCE_NOT_FOUND);
        }
        boolean before = request.notifyOneStopBefore();
        boolean after = request.notifyOneStopAfter();
        if (before && targetIndex == 0) {
            throw new ApiException(ErrorCode.INVALID_ALARM_REQUEST, "Target stop has no predecessor stop.");
        }
        if (after && targetIndex == traversal.size() - 1) {
            throw new ApiException(ErrorCode.INVALID_ALARM_REQUEST, "Target stop has no successor stop.");
        }
        target.updateAdjacentStops(
                before ? adjacentSnapshot(traversal.get(targetIndex - 1)) : null,
                after ? adjacentSnapshot(traversal.get(targetIndex + 1)) : null);
        return toResponse(alarm);
    }

    @Transactional
    public void delete(Long userId, Long alarmId) {
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
        userRepository.requireActive(userId);
        Alarm alarm = alarmRepository.findByIdAndUserIdForUpdate(alarmId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.ALARM_NOT_FOUND));
        var pendingDeliveries = deliveryRepository.findPendingByAlarmIdForUpdate(alarmId);
        LocalDateTime terminatedAt = LocalDateTime.now(ZoneOffset.UTC);
        pendingDeliveries.forEach(delivery -> delivery.terminateDispatch(terminatedAt));
        alarmRepository.delete(alarm);
    }

    private AlarmResponse toResponse(Alarm alarm) {
        return toResponses(List.of(alarm)).getFirst();
    }

    private List<AlarmResponse> toResponses(List<Alarm> alarms) {
        List<BusAlarmTarget> targets = alarms.stream().map(AlarmService::busTarget).toList();
        Map<String, RouteDisplay> displays = routeDisplays(targets);
        List<AlarmResponse> responses = new ArrayList<>(alarms.size());
        Map<Long, List<BusRouteStopOccurrence>> traversals = new HashMap<>();
        for (int index = 0; index < alarms.size(); index++) {
            Alarm alarm = alarms.get(index);
            BusAlarmTarget busTarget = targets.get(index);
            RouteDisplay display = displays.getOrDefault(
                    routeKey(busTarget.getProvider(), busTarget.getExternalRouteId()), RouteDisplay.UNKNOWN);
            Long targetOccurrenceId = targetOccurrenceId(busTarget, display.route(), traversals);
            responses.add(new AlarmResponse(
                    alarm.getId(),
                    alarm.getTransitType(),
                    alarm.getStatus(),
                    busTarget.getRouteNumber(),
                    busTarget.getStopName(),
                    busTarget.isNotifyOneStopBefore(),
                    busTarget.isNotifyOneStopAfter(),
                    display.endpoints().start(),
                    display.endpoints().end(),
                    display.provider(),
                    display.routeType(),
                    canNotify(busTarget, display.route(), true, traversals),
                    canNotify(busTarget, display.route(), false, traversals),
                    targetOccurrenceId == null ? null : display.route().getId(),
                    targetOccurrenceId
            ));
        }
        return responses;
    }

    private Long targetOccurrenceId(BusAlarmTarget target, BusRoute route,
                                    Map<Long, List<BusRouteStopOccurrence>> traversals) {
        if (!matches(route, target)) return null;
        return traversals.computeIfAbsent(route.getId(),
                        id -> occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route)).stream()
                .filter(occurrence -> occurrence.getStopOrder() == target.getTargetStopOrder()
                        && occurrence.getStop().getProvider() == target.getProvider()
                        && Objects.equals(occurrence.getStop().getExternalStopId(), target.getExternalStopId()))
                .map(BusRouteStopOccurrence::getId)
                .findFirst().orElse(null);
    }

    /** 생성·옵션 수정 검증과 같은 기준: 대상 정류장이 노선 첫(전)/마지막(후) occurrence가 아니면 가능. 노선을 못 찾으면 저장된 옵션 상태로 대체한다. */
    private boolean canNotify(BusAlarmTarget target, BusRoute route, boolean before,
                              Map<Long, List<BusRouteStopOccurrence>> traversals) {
        if (route == null) {
            return before ? target.getPredecessorStopOrder() != null : target.getSuccessorStopOrder() != null;
        }
        List<BusRouteStopOccurrence> traversal = traversals.computeIfAbsent(
                route.getId(), id -> occurrenceRepository.findAllByRouteOrderByStopOrderAsc(route));
        for (int index = 0; index < traversal.size(); index++) {
            BusRouteStopOccurrence occurrence = traversal.get(index);
            if (occurrence.getStopOrder() == target.getTargetStopOrder()
                    && Objects.equals(occurrence.getStop().getExternalStopId(), target.getExternalStopId())) {
                return before ? index > 0 : index < traversal.size() - 1;
            }
        }
        return before ? target.getPredecessorStopOrder() != null : target.getSuccessorStopOrder() != null;
    }

    /**
     * 생성 당시 snapshot과 노선번호·지역이 같은 현재 노선의 공식 기·종점과 노선 유형만 표시용으로 제공한다.
     */
    private Map<String, RouteDisplay> routeDisplays(List<BusAlarmTarget> targets) {
        Map<TransitProvider, List<String>> idsByProvider = targets.stream().collect(Collectors.groupingBy(
                BusAlarmTarget::getProvider,
                Collectors.mapping(BusAlarmTarget::getExternalRouteId, Collectors.toList())));
        List<BusRoute> routes = new ArrayList<>();
        idsByProvider.forEach((provider, ids) -> routes.addAll(
                routeRepository.findAllByProviderAndExternalRouteIdIn(provider, distinct(ids))));
        Map<String, BusRoute> routesByKey = routes.stream().collect(Collectors.toMap(
                route -> routeKey(route.getProvider(), route.getExternalRouteId()), route -> route));
        Map<String, BusRoute> matched = new HashMap<>();
        for (BusAlarmTarget target : targets) {
            String key = routeKey(target.getProvider(), target.getExternalRouteId());
            BusRoute route = routesByKey.get(key);
            if (matches(route, target)) matched.putIfAbsent(key, route);
        }
        if (matched.isEmpty()) return Map.of();
        Map<Long, BusRouteEndpointResolver.Endpoints> endpoints =
                endpointResolver.resolveForDisplay(List.copyOf(new java.util.LinkedHashSet<>(matched.values())));
        Map<String, RouteDisplay> result = new HashMap<>();
        matched.forEach((key, route) -> result.put(key, new RouteDisplay(
                endpoints.getOrDefault(route.getId(), BusRouteEndpointResolver.Endpoints.UNKNOWN),
                route.getProvider(), route.getRouteType(), route)));
        return result;
    }

    private record RouteDisplay(BusRouteEndpointResolver.Endpoints endpoints, TransitProvider provider,
            BusRouteType routeType, BusRoute route) {
        private static final RouteDisplay UNKNOWN =
                new RouteDisplay(BusRouteEndpointResolver.Endpoints.UNKNOWN, null, null, null);
    }

    private static boolean matches(BusRoute route, BusAlarmTarget target) {
        return route != null
                && Objects.equals(route.getRouteNumber(), target.getRouteNumber())
                && Objects.equals(route.getCityCode(), target.getCityCode());
    }

    private static List<String> distinct(Collection<String> values) {
        return values.stream().distinct().toList();
    }

    private static String routeKey(TransitProvider provider, String externalRouteId) {
        return provider.name() + ":" + externalRouteId;
    }

    private static BusAlarmTarget busTarget(Alarm alarm) {
        BusAlarmTarget busTarget = alarm.getBusAlarmTarget();
        if (alarm.getTransitType() == TransitType.BUS && busTarget == null) {
            throw new IllegalStateException("Bus alarm requires a BusAlarmTarget");
        }
        if (alarm.getTransitType() != TransitType.BUS) {
            throw new IllegalStateException("Unsupported alarm transit type: " + alarm.getTransitType());
        }
        return busTarget;
    }

    private static AdjacentStopSnapshot adjacentSnapshot(BusRouteStopOccurrence occurrence) {
        return new AdjacentStopSnapshot(occurrence.getStop().getExternalStopId(), occurrence.getStopOrder());
    }
}
