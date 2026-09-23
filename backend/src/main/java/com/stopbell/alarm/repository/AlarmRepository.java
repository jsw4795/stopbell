package com.stopbell.alarm.repository;

import java.util.List;
import java.util.Optional;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.TransitType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    @EntityGraph(attributePaths = "busAlarmTarget")
    List<Alarm> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    @EntityGraph(attributePaths = "busAlarmTarget")
    Optional<Alarm> findByIdAndUserId(Long alarmId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "busAlarmTarget")
    @Query("select alarm from Alarm alarm where alarm.id = :alarmId and alarm.user.id = :userId")
    Optional<Alarm> findByIdAndUserIdForUpdate(@Param("alarmId") Long alarmId, @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "busAlarmTarget")
    @Query("select alarm from Alarm alarm where alarm.id = :alarmId")
    Optional<Alarm> findByIdForUpdate(@Param("alarmId") Long alarmId);

    @EntityGraph(attributePaths = "busAlarmTarget")
    List<Alarm> findAllByTransitTypeAndStatusInOrderByIdAsc(
            TransitType transitType,
            List<AlarmStatus> statuses
    );
}
