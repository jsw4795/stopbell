package com.stopbell.user.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import com.stopbell.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken refreshToken set refreshToken.rotatedAt = :rotatedAt "
            + "where refreshToken.tokenHash = :tokenHash and refreshToken.rotatedAt is null")
    int markRotated(@Param("tokenHash") String tokenHash, @Param("rotatedAt") LocalDateTime rotatedAt);

    @Modifying
    @Query("delete from RefreshToken refreshToken where refreshToken.tokenHash = :tokenHash")
    int deleteByTokenHash(@Param("tokenHash") String tokenHash);
}
