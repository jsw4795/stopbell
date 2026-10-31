package com.stopbell.alarm.repository;

import java.util.Optional;

import com.stopbell.alarm.entity.AlarmCreationRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlarmCreationRequestRepository extends JpaRepository<AlarmCreationRequest, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AlarmCreationRequest r where r.user.id = :userId and r.clientRequestId = :requestId")
    Optional<AlarmCreationRequest> findForUpdate(@Param("userId") Long userId, @Param("requestId") String requestId);
}
