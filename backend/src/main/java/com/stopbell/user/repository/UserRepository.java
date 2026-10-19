package com.stopbell.user.repository;

import java.util.Optional;

import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(Long id);

    Optional<User> findByAuthProviderAndProviderUserId(AuthProvider authProvider, String providerUserId);
}
