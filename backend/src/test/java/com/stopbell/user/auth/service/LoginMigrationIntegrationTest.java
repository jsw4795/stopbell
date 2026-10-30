package com.stopbell.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.stopbell.user.auth.exception.InvalidRefreshTokenException;
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class LoginMigrationIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired private LoginService loginService;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;
    @MockitoBean private GoogleIdentityVerifier googleIdentityVerifier;

    @Test
    @DisplayName("Fresh 로그인과 최신 DB 재적용 뒤 재로그인은 User와 Alarm 소유권 및 독립 Refresh Session을 보존한다")
    void preserve_login_and_ownership_after_no_op_migration() throws Exception {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("20");
        assertThat(users.count()).isZero();
        // Supply the verified-identity boundary fixture without contacting Google.
        ExternalIdentity identity = new ExternalIdentity(AuthProvider.GOOGLE, "migration-login-sub");
        var first = loginService.login(identity);
        Long ownerId = jwtTokenService.extractUserId(first.accessToken());
        jdbc.update("insert into alarms (user_id, transit_type, status, created_at, updated_at) values (?, 'SUBWAY', 'INACTIVE', '2026-10-01 12:34:56.123456', '2026-10-02 12:34:56.654321')", ownerId);
        var originalUsers = jdbc.queryForList("select * from users order by id");
        var originalAlarms = jdbc.queryForList("select * from alarms order by id");
        var originalTokens = jdbc.queryForList("select * from refresh_tokens order by id");
        var originalHistory = jdbc.queryForList("select * from flyway_schema_history order by installed_rank");

        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForList("select * from users order by id")).isEqualTo(originalUsers);
        assertThat(jdbc.queryForList("select * from refresh_tokens order by id")).isEqualTo(originalTokens);
        assertThat(jdbc.queryForList("select * from flyway_schema_history order by installed_rank")).isEqualTo(originalHistory);

        var second = loginService.login(identity);
        assertThat(jwtTokenService.extractUserId(second.accessToken())).isEqualTo(ownerId);
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        var another = loginService.login(new ExternalIdentity(AuthProvider.GOOGLE, "another-migration-sub"));
        assertThat(jwtTokenService.extractUserId(another.accessToken())).isNotEqualTo(ownerId);
        assertThat(users.count()).isEqualTo(2);
        assertThat(jdbc.queryForList("select * from users where id=?", ownerId)).isEqualTo(originalUsers);
        assertThat(jdbc.queryForList("select * from alarms order by id")).isEqualTo(originalAlarms);
        assertThat(jdbc.queryForList("select * from refresh_tokens where token_hash=?", hash(first.refreshToken()))).isEqualTo(originalTokens);

        var rotated = refreshTokenService.refresh(first.refreshToken());
        assertThat(jwtTokenService.extractUserId(rotated.accessToken())).isEqualTo(ownerId);
        assertThatThrownBy(() -> refreshTokenService.refresh(first.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        refreshTokenService.invalidate(second.refreshToken());
        assertThatThrownBy(() -> refreshTokenService.refresh(second.refreshToken())).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(jwtTokenService.extractUserId(refreshTokenService.refresh(rotated.refreshToken()).accessToken())).isEqualTo(ownerId);
        assertThat(jwtTokenService.extractUserId(refreshTokenService.refresh(another.refreshToken()).accessToken())).isNotEqualTo(ownerId);
        assertThat(jdbc.queryForList("select * from alarms order by id")).isEqualTo(originalAlarms);
    }

    private String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
