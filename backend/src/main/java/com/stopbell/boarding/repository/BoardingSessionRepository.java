package com.stopbell.boarding.repository;

import java.util.Optional;

import com.stopbell.boarding.entity.BoardingSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BoardingSessionRepository extends JpaRepository<BoardingSession, Long> {
    boolean existsByUserIdAndClientRequestId(Long userId, String clientRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BoardingSession s where s.user.id = :userId and s.clientRequestId = :clientRequestId")
    Optional<BoardingSession> findRequestForUpdate(Long userId, String clientRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BoardingSession s where s.id = :id and s.user.id = :userId")
    Optional<BoardingSession> findOwnedForUpdate(Long id, Long userId);

    @Query(value = "select * from boarding_sessions where open_user_id = :userId for update", nativeQuery = true)
    Optional<BoardingSession> findCurrentForUpdate(Long userId);
}
