package com.stopbell.alarm.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.repository.AlarmRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusAlarmPollingService {

    private static final List<AlarmStatus> MONITORING_STATUSES = List.of(
            AlarmStatus.ACTIVE,
            AlarmStatus.FOLLOW_UP
    );

    private final AlarmRepository alarmRepository;

    public BusAlarmPollingService(AlarmRepository alarmRepository) {
        this.alarmRepository = alarmRepository;
    }

    @Transactional(readOnly = true)
    public List<BusAlarmPollingGroup> findMonitoringGroups() {
        List<Alarm> monitoringAlarms = alarmRepository.findAllByTransitTypeAndStatusInOrderByIdAsc(
                TransitType.BUS,
                MONITORING_STATUSES
        );
        Map<BusPollingKey, List<Alarm>> alarmsByPollingKey = new LinkedHashMap<>();

        for (Alarm alarm : monitoringAlarms) {
            BusPollingKey pollingKey = pollingKeyOf(alarm);
            alarmsByPollingKey.computeIfAbsent(pollingKey, ignored -> new ArrayList<>()).add(alarm);
        }

        return alarmsByPollingKey.entrySet().stream()
                .map(entry -> new BusAlarmPollingGroup(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static BusPollingKey pollingKeyOf(Alarm alarm) {
        BusAlarmTarget target = alarm.getBusAlarmTarget();
        if (target == null) {
            throw new IllegalStateException("Bus monitoring alarm must have a BusAlarmTarget: " + alarm.getId());
        }
        return new BusPollingKey(
                target.getProvider(),
                target.getExternalRouteId(),
                target.getCityCode()
        );
    }
}
