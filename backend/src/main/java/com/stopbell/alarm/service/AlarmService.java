package com.stopbell.alarm.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.stopbell.alarm.dto.AlarmResponse;
import com.stopbell.alarm.dto.CreateAlarmRequest;
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
        Map<String, BusRouteEndpointResolver.Endpoints> endpoints = endpoints(targets);
        List<AlarmResponse> responses = new ArrayList<>(alarms.size());
        for (int index = 0; index < alarms.size(); index++) {
            Alarm alarm = alarms.get(index);
            BusAlarmTarget busTarget = targets.get(index);
            var routeEndpoints = endpoints.getOrDefault(routeKey(busTarget.getProvider(), busTarget.getExternalRouteId()),
                    BusRouteEndpointResolver.Endpoints.UNKNOWN);
            responses.add(new AlarmResponse(
                    alarm.getId(),
                    alarm.getTransitType(),
                    alarm.getStatus(),
                    busTarget.getRouteNumber(),
                    busTarget.getStopName(),
                    busTarget.isNotifyOneStopBefore(),
                    busTarget.isNotifyOneStopAfter(),
                    routeEndpoints.start(),
                    routeEndpoints.end()
            ));
        }
        return responses;
    }

    /**
     * 생성 당시 snapshot과 노선번호·지역이 같은 현재 노선의 공식 기·종점만 표시용으로 제공한다.
     */
    private Map<String, BusRouteEndpointResolver.Endpoints> endpoints(List<BusAlarmTarget> targets) {
        Map<TransitProvider, List<String>> idsByProvider = targets.stream().collect(Collectors.groupingBy(
                BusAlarmTarget::getProvider,
                Collectors.mapping(BusAlarmTarget::getExternalRouteId, Collectors.toList())));
        List<BusRoute> routes = new ArrayList<>();
        idsByProvider.forEach((provider, ids) -> routes.addAll(
                routeRepository.findAllByProviderAndExternalRouteIdIn(provider, distinct(ids))));
        Map<String, BusRoute> routesByKey = routes.stream().collect(Collectors.toMap(
                route -> routeKey(route.getProvider(), route.getExternalRouteId()), route -> route));
        List<BusRoute> matched = targets.stream()
                .filter(target -> matches(routesByKey.get(routeKey(target.getProvider(), target.getExternalRouteId())), target))
                .map(target -> routesByKey.get(routeKey(target.getProvider(), target.getExternalRouteId())))
                .distinct()
                .toList();
        if (matched.isEmpty()) return Map.of();
        Map<Long, BusRouteEndpointResolver.Endpoints> byRouteId = endpointResolver.resolveForDisplay(matched);
        return targets.stream()
                .filter(target -> matches(routesByKey.get(routeKey(target.getProvider(), target.getExternalRouteId())), target))
                .collect(Collectors.toMap(
                        target -> routeKey(target.getProvider(), target.getExternalRouteId()),
                        target -> byRouteId.getOrDefault(
                                routesByKey.get(routeKey(target.getProvider(), target.getExternalRouteId())).getId(),
                                BusRouteEndpointResolver.Endpoints.UNKNOWN),
                        (first, second) -> first));
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
