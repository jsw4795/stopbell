package com.stopbell.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class DevicePersistenceIntegrationTest {

    private static final String INSTALLATION_ID = "a1234567-1234-4123-8123-123456789abc";
    private static final String OTHER_INSTALLATION_ID = "b1234567-1234-4123-8123-123456789abc";
    private static final LocalDateTime REGISTERED_AT = LocalDateTime.of(2026, 10, 9, 0, 0);

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("V13 적용 후 Device의 식별자와 User 관계 및 등록 상태를 JPA로 저장하고 조회한다")
    void persist_and_load_device_mapping() {
        User user = persistUser("device-mapping-user");
        String pushTarget = "CaseSensitiveFID".repeat(3);
        Device device = new Device(
                user, INSTALLATION_ID.toUpperCase(java.util.Locale.ROOT), "a".repeat(64),
                DevicePlatform.IOS, pushTarget, 1, REGISTERED_AT
        );
        entityManager.persist(device);
        entityManager.persist(new Device(
                user, OTHER_INSTALLATION_ID, "b".repeat(64), DevicePlatform.ANDROID,
                "another-fid", 0, REGISTERED_AT
        ));
        entityManager.flush();
        Long deviceId = device.getId();
        entityManager.clear();

        Device found = entityManager.find(Device.class, deviceId);

        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getInstallationId()).isEqualTo(INSTALLATION_ID);
        assertThat(found.getInstallationCredentialHash()).isEqualTo("a".repeat(64));
        assertThat(found.getPlatform()).isEqualTo(DevicePlatform.IOS);
        assertThat(found.getCurrentPushTargetId()).isEqualTo(pushTarget);
        assertThat(found.getRegistrationRevision()).isEqualTo(1);
        assertThat(found.getOwnershipGeneration()).isZero();
        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getLastRegisteredAt()).isEqualTo(REGISTERED_AT);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isEqualTo(found.getCreatedAt());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from devices where user_id = ? and enabled = true", Integer.class, user.getId()
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select version from flyway_schema_history where success = true order by installed_rank desc limit 1",
                String.class
        )).isEqualTo("13");
    }

    @Test
    @DisplayName("Device의 UNIQUE와 FK 및 필수 제약을 강제하고 비활성화하면 FID 점유가 해제된다")
    void enforce_schema_constraints_and_release_target() {
        User user = persistUser("device-constraint-user");
        insertDevice(user.getId(), INSTALLATION_ID, "FID-AAA");

        assertThatThrownBy(() -> insertDevice(user.getId(), INSTALLATION_ID, "FID-BBB"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_devices_installation_id");
        assertThatThrownBy(() -> insertDevice(user.getId(), OTHER_INSTALLATION_ID, "FID-AAA"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_devices_current_push_target_id");
        assertThatThrownBy(() -> insertDevice(Long.MAX_VALUE, OTHER_INSTALLATION_ID, "FID-BBB"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_devices_user_id");

        assertConstraintViolation("registration_revision = -1", "ck_devices_registration_revision");
        assertConstraintViolation("ownership_generation = -1", "ck_devices_ownership_generation");
        assertConstraintViolation("platform = 'WEB'", "ck_devices_platform");
        assertConstraintViolation("current_push_target_id = null", "ck_devices_enabled_target");
        assertConstraintViolation("current_push_target_id = ''", "ck_devices_enabled_target");
        assertConstraintViolation("enabled = false", "ck_devices_enabled_target");
        for (String column : new String[] {
                "user_id", "installation_id", "installation_credential_hash", "platform",
                "registration_revision", "ownership_generation", "enabled",
                "last_registered_at", "created_at", "updated_at"
        }) {
            assertConstraintViolation(column + " = null", column);
        }

        insertDevice(user.getId(), OTHER_INSTALLATION_ID, "fid-AAA");
        assertThat(jdbcTemplate.queryForObject(
                "select PAD_ATTRIBUTE from information_schema.COLLATIONS where COLLATION_NAME = 'utf8mb4_0900_bin'",
                String.class
        )).isEqualTo("NO PAD");
        jdbcTemplate.update("update devices set enabled = false, current_push_target_id = null");
        insertDevice(user.getId(), "c1234567-1234-4123-8123-123456789abc", "FID-AAA");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from devices where enabled = false and current_push_target_id is null", Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from devices where enabled = true and current_push_target_id = 'FID-AAA'", Integer.class
        )).isEqualTo(1);
    }

    private User persistUser(String providerUserId) {
        User user = new User(AuthProvider.GOOGLE, providerUserId);
        entityManager.persist(user);
        entityManager.flush();
        return user;
    }

    private void insertDevice(Long userId, String installationId, String pushTarget) {
        jdbcTemplate.update("""
                insert into devices (
                    user_id, installation_id, installation_credential_hash, platform, current_push_target_id,
                    registration_revision, ownership_generation, enabled, last_registered_at, created_at, updated_at
                ) values (?, ?, ?, 'IOS', ?, 1, 0, true, ?, ?, ?)
                """, userId, installationId, "a".repeat(64), pushTarget, REGISTERED_AT, REGISTERED_AT, REGISTERED_AT);
    }

    private void assertConstraintViolation(String assignment, String constraint) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update devices set " + assignment + " where installation_id = ?", INSTALLATION_ID
        )).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }
}
