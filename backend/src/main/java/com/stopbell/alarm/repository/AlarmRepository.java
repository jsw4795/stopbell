package com.stopbell.alarm.repository;

import java.util.List;
import java.util.Optional;

import com.stopbell.alarm.entity.Alarm;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    @EntityGraph(attributePaths = "busAlarmTarget")
    List<Alarm> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    @EntityGraph(attributePaths = "busAlarmTarget")
    Optional<Alarm> findByIdAndUserId(Long alarmId, Long userId);
}
