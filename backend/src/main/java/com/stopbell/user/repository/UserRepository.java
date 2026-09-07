package com.stopbell.user.repository;

import java.util.Optional;

import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByAuthProviderAndProviderUserId(AuthProvider authProvider, String providerUserId);
}
