package com.stopbell.user.entity;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = false, length = 20)
    private AuthProvider authProvider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "apple_generation_issued_at", updatable = false)
    private LocalDateTime appleGenerationIssuedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected User() {
    }

    public User(AuthProvider authProvider, String providerUserId) {
        this.authProvider = authProvider;
        this.providerUserId = providerUserId;
    }

    public User(AuthProvider authProvider, String providerUserId, Instant appleIssuedAt) {
        this(authProvider, providerUserId);
        if (authProvider != AuthProvider.APPLE || appleIssuedAt == null) throw new IllegalArgumentException("Apple identity required");
        this.appleGenerationIssuedAt = LocalDateTime.ofInstant(appleIssuedAt, ZoneOffset.UTC);
    }

    public LocalDateTime getAppleGenerationIssuedAt() { return appleGenerationIssuedAt; }

    @PrePersist
    private void onPrePersist() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void onPreUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public Long getId() {
        return id;
    }

    public AuthProvider getAuthProvider() {
        return authProvider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
