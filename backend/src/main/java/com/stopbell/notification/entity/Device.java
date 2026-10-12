package com.stopbell.notification.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Pattern;

import com.stopbell.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "devices")
public class Device {

    private static final Pattern INSTALLATION_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    );

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "installation_id", nullable = false, updatable = false, length = 36)
    private String installationId;

    @Column(name = "installation_credential_hash", nullable = false, updatable = false, length = 64)
    private String installationCredentialHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private DevicePlatform platform;

    @Column(name = "current_push_target_id", length = 255)
    private String currentPushTargetId;

    @Column(name = "registration_revision", nullable = false)
    private long registrationRevision;

    @Column(name = "ownership_generation", nullable = false)
    private long ownershipGeneration;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_registered_at", nullable = false)
    private LocalDateTime lastRegisteredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Device() {
    }

    public Device(
            User user,
            String installationId,
            String installationCredentialHash,
            DevicePlatform platform,
            String currentPushTargetId,
            long registrationRevision,
            LocalDateTime lastRegisteredAt
    ) {
        if (user == null || platform == null || lastRegisteredAt == null) {
            throw new IllegalArgumentException("Device owner, platform and registration time must not be null");
        }
        if (installationCredentialHash == null || !installationCredentialHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Installation credential hash must be lowercase SHA-256 hex");
        }
        validatePushTarget(currentPushTargetId);
        if (registrationRevision < 0) {
            throw new IllegalArgumentException("Registration revision must not be negative");
        }
        this.user = user;
        this.installationId = normalizeInstallationId(installationId);
        this.installationCredentialHash = installationCredentialHash;
        this.platform = platform;
        this.currentPushTargetId = currentPushTargetId;
        this.registrationRevision = registrationRevision;
        this.enabled = true;
        this.lastRegisteredAt = lastRegisteredAt;
    }

    public static String normalizeInstallationId(String installationId) {
        if (installationId == null || !INSTALLATION_ID.matcher(installationId).matches()) {
            throw new IllegalArgumentException("Installation ID must be a canonical UUID v4");
        }
        return installationId.toLowerCase(Locale.ROOT);
    }

    public static void validatePushTarget(String pushTarget) {
        if (pushTarget == null || pushTarget.isBlank() || pushTarget.length() > 255
                || pushTarget.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("Push target must contain 1 to 255 characters without whitespace");
        }
    }

    public void register(String pushTarget, long revision, LocalDateTime registeredAt) {
        validatePushTarget(pushTarget);
        validateNextRevision(revision);
        if (registeredAt == null) {
            throw new IllegalArgumentException("Registration time must not be null");
        }
        currentPushTargetId = pushTarget;
        registrationRevision = revision;
        enabled = true;
        lastRegisteredAt = registeredAt;
    }

    public void transferOwnership(User newOwner, String pushTarget, long revision, LocalDateTime registeredAt) {
        if (newOwner == null || newOwner.getId() == null || newOwner.getId().equals(user.getId())) {
            throw new IllegalArgumentException("Ownership transfer requires a different persisted owner");
        }
        long nextGeneration = Math.incrementExact(ownershipGeneration);
        register(pushTarget, revision, registeredAt);
        user = newOwner;
        ownershipGeneration = nextGeneration;
    }

    public void disable(long revision) {
        validateNextRevision(revision);
        currentPushTargetId = null;
        registrationRevision = revision;
        enabled = false;
    }

    /** Caller must hold the current Device write lock and match every attempt registration field. */
    public void disableUnregisteredTarget() {
        currentPushTargetId = null;
        enabled = false;
    }

    private void validateNextRevision(long revision) {
        if (revision < 0 || revision <= registrationRevision) {
            throw new IllegalArgumentException("Device mutation requires a higher registration revision");
        }
    }

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

    public User getUser() {
        return user;
    }

    public String getInstallationId() {
        return installationId;
    }

    public String getInstallationCredentialHash() {
        return installationCredentialHash;
    }

    public DevicePlatform getPlatform() {
        return platform;
    }

    public String getCurrentPushTargetId() {
        return currentPushTargetId;
    }

    public long getRegistrationRevision() {
        return registrationRevision;
    }

    public long getOwnershipGeneration() {
        return ownershipGeneration;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getLastRegisteredAt() {
        return lastRegisteredAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
