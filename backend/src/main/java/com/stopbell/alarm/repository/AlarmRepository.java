package com.stopbell.alarm.repository;

import java.util.List;

import com.stopbell.alarm.entity.Alarm;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    @EntityGraph(attributePaths = "busAlarmTarget")
    List<Alarm> findAllByUserIdOrderByCreatedAtDesc(Long userId);
}
