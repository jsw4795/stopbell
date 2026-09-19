package com.stopbell.alarm.service;

import java.util.List;
import java.util.Objects;

import com.stopbell.alarm.dto.AlarmResponse;
import com.stopbell.alarm.dto.CreateAlarmRequest;
import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.transit.entity.BusRoute;
import com.stopbell.transit.entity.BusRouteStopOccurrence;
import com.stopbell.transit.entity.BusStop;
import com.stopbell.transit.repository.BusRouteStopOccurrenceRepository;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlarmService {

    private final AlarmRepository alarmRepository;
    private final UserRepository userRepository;
    private final BusRouteStopOccurrenceRepository occurrenceRepository;

    public AlarmService(
            AlarmRepository alarmRepository,
            UserRepository userRepository,
            BusRouteStopOccurrenceRepository occurrenceRepository
    ) {
        this.alarmRepository = alarmRepository;
        this.userRepository = userRepository;
        this.occurrenceRepository = occurrenceRepository;
    }

    @Transactional
    public AlarmResponse create(Long userId, CreateAlarmRequest request) {
        if (request == null || request.targetStopOccurrenceId() == null
                || request.targetStopOccurrenceId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target stop occurrence ID is required");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        BusRouteStopOccurrence targetOccurrence = occurrenceRepository.findById(request.targetStopOccurrenceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Target stop occurrence not found"));

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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Target stop occurrence not found");
        }

        if (request.notifyOneStopBefore() && targetIndex == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target has no predecessor stop");
        }
        if (request.notifyOneStopAfter() && targetIndex == traversal.size() - 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target has no successor stop");
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
        return alarmRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(AlarmService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AlarmResponse findById(Long userId, Long alarmId) {
        Alarm alarm = alarmRepository.findByIdAndUserId(alarmId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alarm not found"));
        return toResponse(alarm);
    }

    @Transactional
    public void delete(Long userId, Long alarmId) {
        Alarm alarm = alarmRepository.findByIdAndUserId(alarmId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alarm not found"));
        alarmRepository.delete(alarm);
    }

    private static AlarmResponse toResponse(Alarm alarm) {
        BusAlarmTarget busTarget = alarm.getBusAlarmTarget();
        if (alarm.getTransitType() == TransitType.BUS && busTarget == null) {
            throw new IllegalStateException("Bus alarm requires a BusAlarmTarget");
        }
        if (alarm.getTransitType() != TransitType.BUS) {
            throw new IllegalStateException("Unsupported alarm transit type: " + alarm.getTransitType());
        }
        return new AlarmResponse(
                alarm.getId(),
                alarm.getTransitType(),
                alarm.getStatus(),
                busTarget.getRouteNumber(),
                busTarget.getStopName(),
                busTarget.isNotifyOneStopBefore(),
                busTarget.isNotifyOneStopAfter()
        );
    }

    private static AdjacentStopSnapshot adjacentSnapshot(BusRouteStopOccurrence occurrence) {
        return new AdjacentStopSnapshot(occurrence.getStop().getExternalStopId(), occurrence.getStopOrder());
    }
}
