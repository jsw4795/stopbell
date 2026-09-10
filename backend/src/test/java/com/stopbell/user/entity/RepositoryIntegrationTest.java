package com.stopbell.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HexFormat;
import javax.sql.DataSource;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.notification.entity.NotificationHistory;
import com.stopbell.notification.entity.NotificationStatus;
import com.stopbell.notification.repository.NotificationHistoryRepository;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.user.auth.identity.ExternalIdentity;
import com.stopbell.user.auth.dto.TokenResponse;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.auth.service.LoginService;
import com.stopbell.user.repository.RefreshTokenRepository;
import com.stopbell.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class RepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LoginService loginService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private NotificationHistoryRepository notificationHistoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("테스트 데이터베이스는 Testcontainer의 랜덤 포트를 사용한다")
    void test_data_source_uses_testcontainer_random_port() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String jdbcUrl = connection.getMetaData().getURL();

            assertThat(jdbcUrl).contains(":" + mysql.getMappedPort(3306));
            assertThat(jdbcUrl).doesNotContain(":3306/");
        }
    }

    @Test
    @DisplayName("Flyway Migration이 Testcontainer 데이터베이스에 모두 적용된다")
    void flyway_applies_all_schema_migrations() {
        List<String> versions = jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success = true order by installed_rank",
                String.class
        );

        assertThat(versions).contains("1", "2", "3", "4", "5", "6");
    }

    @Test
    @DisplayName("User Identity를 저장하면 ID와 Identity 및 생성 및 수정 시간이 생성되고 다시 조회할 수 있다")
    void save_user_identity_and_find_by_id() {
        User savedUser = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-user-123"));
        entityManager.clear();

        User foundUser = userRepository.findById(savedUser.getId()).orElseThrow();

        assertThat(foundUser.getId()).isNotNull();
        assertThat(foundUser.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(foundUser.getProviderUserId()).isEqualTo("google-user-123");
        assertThat(foundUser.getCreatedAt()).isNotNull();
        assertThat(foundUser.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("동일한 Provider와 Provider 사용자 식별자는 중복 저장할 수 없다")
    void save_same_user_identity_fails() {
        userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "same-user"));

        assertThatThrownBy(() -> userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "same-user")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Provider가 다르면 같은 Provider 사용자 식별자를 저장할 수 있다")
    void save_same_provider_user_id_with_different_provider() {
        User googleUser = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "same-user"));
        User kakaoUser = userRepository.saveAndFlush(new User(AuthProvider.KAKAO, "same-user"));

        assertThat(googleUser.getId()).isNotNull();
        assertThat(kakaoUser.getId()).isNotNull();
        assertThat(kakaoUser.getId()).isNotEqualTo(googleUser.getId());
    }

    @Test
    @DisplayName("신규 Google Identity 로그인은 User를 생성하고 Access Token과 해시 저장 Refresh Token을 발급한다")
    void login_new_google_identity_creates_user_and_token_pair() throws Exception {
        TokenResponse tokenResponse = loginService.login(new ExternalIdentity(AuthProvider.GOOGLE, "google-login-user"));

        User user = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, "google-login-user")
                .orElseThrow();

        assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(user.getProviderUserId()).isEqualTo("google-login-user");
        assertThat(jwtTokenService.extractUserId(tokenResponse.accessToken())).isEqualTo(user.getId());
        assertThat(tokenResponse.refreshToken()).isNotBlank();
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(sha256(tokenResponse.refreshToken()))
                .orElseThrow();
        assertThat(refreshToken.getUser().getId()).isEqualTo(user.getId());
        assertThat(refreshToken.getTokenHash()).isNotEqualTo(tokenResponse.refreshToken());
    }

    @Test
    @DisplayName("기존 Google Identity 로그인은 기존 User를 재사용하고 새 Refresh Token Session을 발급한다")
    void login_existing_google_identity_reuses_user() {
        User existingUser = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-existing-login-user"));

        TokenResponse tokenResponse = loginService.login(new ExternalIdentity(AuthProvider.GOOGLE, "google-existing-login-user"));

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(jwtTokenService.extractUserId(tokenResponse.accessToken())).isEqualTo(existingUser.getId());
        assertThat(refreshTokenRepository.findByTokenHash(sha256Unchecked(tokenResponse.refreshToken()))).isPresent();
    }

    @Test
    @DisplayName("RefreshToken을 저장하면 User 관계와 해시 및 만료 시각이 유지되고 생성 시간이 생성된다")
    void save_refresh_token_with_user_relation() {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-refresh-token-user"));
        LocalDateTime expiresAt = LocalDateTime.of(2026, 10, 4, 12, 0);
        RefreshToken savedRefreshToken = refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, "a".repeat(64), expiresAt)
        );
        entityManager.clear();

        RefreshToken foundRefreshToken = refreshTokenRepository.findById(savedRefreshToken.getId()).orElseThrow();

        assertThat(foundRefreshToken.getId()).isNotNull();
        assertThat(foundRefreshToken.getUser().getId()).isEqualTo(user.getId());
        assertThat(foundRefreshToken.getTokenHash()).isEqualTo("a".repeat(64));
        assertThat(foundRefreshToken.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(foundRefreshToken.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("한 User에 서로 다른 해시를 가진 여러 RefreshToken을 저장할 수 있다")
    void save_multiple_refresh_tokens_for_same_user() {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-multiple-refresh-token-user"));

        RefreshToken firstRefreshToken = refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, "b".repeat(64), LocalDateTime.of(2026, 10, 5, 12, 0))
        );
        RefreshToken secondRefreshToken = refreshTokenRepository.saveAndFlush(
                new RefreshToken(user, "c".repeat(64), LocalDateTime.of(2026, 10, 6, 12, 0))
        );

        assertThat(firstRefreshToken.getId()).isNotNull();
        assertThat(secondRefreshToken.getId()).isNotNull();
        assertThat(secondRefreshToken.getId()).isNotEqualTo(firstRefreshToken.getId());
    }

    @Test
    @DisplayName("동일한 RefreshToken 해시는 데이터베이스 Unique Constraint로 중복 저장할 수 없다")
    void save_same_refresh_token_hash_fails() {
        User firstUser = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-first-refresh-token-user"));
        User secondUser = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-second-refresh-token-user"));
        String tokenHash = "d".repeat(64);
        refreshTokenRepository.saveAndFlush(
                new RefreshToken(firstUser, tokenHash, LocalDateTime.of(2026, 10, 5, 12, 0))
        );

        assertThatThrownBy(() -> refreshTokenRepository.saveAndFlush(
                new RefreshToken(secondUser, tokenHash, LocalDateTime.of(2026, 10, 6, 12, 0))
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Alarm을 저장하면 User 관계와 TransitType 및 상태가 유지된다")
    void save_alarm_with_user_relation() {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-alarm-user"));
        Alarm savedAlarm = alarmRepository.saveAndFlush(new Alarm(user, TransitType.SUBWAY));
        entityManager.clear();

        Alarm foundAlarm = alarmRepository.findById(savedAlarm.getId()).orElseThrow();

        assertThat(foundAlarm.getUser().getId()).isEqualTo(user.getId());
        assertThat(foundAlarm.getTransitType()).isEqualTo(TransitType.SUBWAY);
        assertThat(foundAlarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(foundAlarm.getCreatedAt()).isNotNull();
        assertThat(foundAlarm.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("SUCCESS NotificationHistory는 실패 사유 없이 저장하고 조회할 수 있다")
    void save_success_notification_history() {
        Alarm alarm = saveAlarm();
        NotificationHistory savedHistory = notificationHistoryRepository.saveAndFlush(
                new NotificationHistory(alarm, NotificationStatus.SUCCESS, null)
        );
        entityManager.clear();

        NotificationHistory foundHistory = notificationHistoryRepository.findById(savedHistory.getId()).orElseThrow();

        assertThat(foundHistory.getAlarm().getId()).isEqualTo(alarm.getId());
        assertThat(foundHistory.getStatus()).isEqualTo(NotificationStatus.SUCCESS);
        assertThat(foundHistory.getFailureReason()).isNull();
        assertThat(foundHistory.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("FAILURE NotificationHistory는 실패 사유와 함께 저장하고 조회할 수 있다")
    void save_failure_notification_history() {
        Alarm alarm = saveAlarm();
        NotificationHistory savedHistory = notificationHistoryRepository.saveAndFlush(
                new NotificationHistory(alarm, NotificationStatus.FAILURE, "push provider rejected the request")
        );
        entityManager.clear();

        NotificationHistory foundHistory = notificationHistoryRepository.findById(savedHistory.getId()).orElseThrow();

        assertThat(foundHistory.getAlarm().getId()).isEqualTo(alarm.getId());
        assertThat(foundHistory.getStatus()).isEqualTo(NotificationStatus.FAILURE);
        assertThat(foundHistory.getFailureReason()).isEqualTo("push provider rejected the request");
        assertThat(foundHistory.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("관리 중인 Alarm을 변경하면 save 없이 Dirty Checking으로 반영되고 수정 시간이 갱신된다")
    void update_alarm_by_dirty_checking_without_save() {
        Alarm savedAlarm = saveAlarm();
        entityManager.clear();

        Alarm managedAlarm = alarmRepository.findById(savedAlarm.getId()).orElseThrow();
        LocalDateTime previousUpdatedAt = managedAlarm.getUpdatedAt();

        managedAlarm.activate();
        entityManager.flush();
        entityManager.clear();

        Alarm updatedAlarm = alarmRepository.findById(savedAlarm.getId()).orElseThrow();

        assertThat(updatedAlarm.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(updatedAlarm.getUpdatedAt()).isAfter(previousUpdatedAt);
    }

    @Test
    @DisplayName("이미 활성화된 Alarm을 다시 활성화해도 상태와 수정 시간이 변경되지 않는다")
    void activate_when_already_active() {
        Alarm alarm = saveAlarm();
        alarm.activate();
        entityManager.flush();
        entityManager.clear();

        Alarm managedAlarm = alarmRepository.findById(alarm.getId()).orElseThrow();
        LocalDateTime originalUpdatedAt = managedAlarm.getUpdatedAt();

        managedAlarm.activate();
        entityManager.flush();
        entityManager.clear();

        Alarm reloadedAlarm = alarmRepository.findById(alarm.getId()).orElseThrow();

        assertThat(reloadedAlarm.getStatus()).isEqualTo(AlarmStatus.ACTIVE);
        assertThat(reloadedAlarm.getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    @Test
    @DisplayName("Bus Alarm Target의 식별자와 metadata snapshot 및 옵션을 저장하고 조회할 수 있다")
    void save_bus_alarm_target_snapshots() {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-bus-target-user"));
        BusAlarmTarget target = new BusAlarmTarget(
                TransitProvider.TAGO,
                "route-external-123",
                "stop-external-456",
                12,
                "7007-1",
                "판교역",
                new BigDecimal("37.3947000"),
                new BigDecimal("127.1112000"),
                "31020",
                new AdjacentStopSnapshot("predecessor-stop", 10),
                new AdjacentStopSnapshot("successor-stop", 15)
        );
        Alarm savedAlarm = alarmRepository.saveAndFlush(new Alarm(user, target));
        entityManager.clear();

        Alarm foundAlarm = alarmRepository.findById(savedAlarm.getId()).orElseThrow();
        BusAlarmTarget foundTarget = foundAlarm.getBusAlarmTarget();

        assertThat(foundAlarm.getTransitType()).isEqualTo(TransitType.BUS);
        assertThat(foundAlarm.getStatus()).isEqualTo(AlarmStatus.INACTIVE);
        assertThat(foundTarget.getAlarmId()).isEqualTo(foundAlarm.getId());
        assertThat(foundTarget.getProvider()).isEqualTo(TransitProvider.TAGO);
        assertThat(foundTarget.getExternalRouteId()).isEqualTo("route-external-123");
        assertThat(foundTarget.getExternalStopId()).isEqualTo("stop-external-456");
        assertThat(foundTarget.getTargetStopOrder()).isEqualTo(12);
        assertThat(foundTarget.getRouteNumber()).isEqualTo("7007-1");
        assertThat(foundTarget.getStopName()).isEqualTo("판교역");
        assertThat(foundTarget.getTargetStopLatitude()).isEqualByComparingTo("37.3947000");
        assertThat(foundTarget.getTargetStopLongitude()).isEqualByComparingTo("127.1112000");
        assertThat(foundTarget.getCityCode()).isEqualTo("31020");
        assertThat(foundTarget.isNotifyOneStopBefore()).isTrue();
        assertThat(foundTarget.getPredecessorExternalStopId()).isEqualTo("predecessor-stop");
        assertThat(foundTarget.getPredecessorStopOrder()).isEqualTo(10);
        assertThat(foundTarget.isNotifyOneStopAfter()).isTrue();
        assertThat(foundTarget.getSuccessorExternalStopId()).isEqualTo("successor-stop");
        assertThat(foundTarget.getSuccessorStopOrder()).isEqualTo(15);
    }

    @Test
    @DisplayName("GPS와 인접 정류장 옵션이 없는 Bus Alarm Target도 저장할 수 있다")
    void save_bus_alarm_target_without_optional_snapshots() {
        Alarm savedAlarm = saveAlarm();
        entityManager.clear();

        BusAlarmTarget foundTarget = alarmRepository.findById(savedAlarm.getId()).orElseThrow().getBusAlarmTarget();

        assertThat(foundTarget.getTargetStopLatitude()).isNull();
        assertThat(foundTarget.getTargetStopLongitude()).isNull();
        assertThat(foundTarget.getCityCode()).isNull();
        assertThat(foundTarget.isNotifyOneStopBefore()).isFalse();
        assertThat(foundTarget.getPredecessorExternalStopId()).isNull();
        assertThat(foundTarget.getPredecessorStopOrder()).isNull();
        assertThat(foundTarget.isNotifyOneStopAfter()).isFalse();
        assertThat(foundTarget.getSuccessorExternalStopId()).isNull();
        assertThat(foundTarget.getSuccessorStopOrder()).isNull();
    }

    @Test
    @DisplayName("같은 Route와 Stop ID의 서로 다른 Target occurrence를 저장할 수 있다")
    void save_revisited_stop_occurrences() {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-revisited-stop-user"));
        Alarm firstOccurrence = alarmRepository.saveAndFlush(new Alarm(user, createSeoulTarget(10)));
        Alarm secondOccurrence = alarmRepository.saveAndFlush(new Alarm(user, createSeoulTarget(30)));

        assertThat(firstOccurrence.getId()).isNotEqualTo(secondOccurrence.getId());
        assertThat(firstOccurrence.getBusAlarmTarget().getExternalRouteId())
                .isEqualTo(secondOccurrence.getBusAlarmTarget().getExternalRouteId());
        assertThat(firstOccurrence.getBusAlarmTarget().getExternalStopId())
                .isEqualTo(secondOccurrence.getBusAlarmTarget().getExternalStopId());
        assertThat(firstOccurrence.getBusAlarmTarget().getTargetStopOrder()).isEqualTo(10);
        assertThat(secondOccurrence.getBusAlarmTarget().getTargetStopOrder()).isEqualTo(30);
    }

    @Test
    @DisplayName("FOLLOW_UP 상태와 차량 추적 문맥은 문자열 상태와 timestamp로 저장된다")
    void save_follow_up_runtime() {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-follow-up-user"));
        Alarm alarm = new Alarm(user, new BusAlarmTarget(
                TransitProvider.SEOUL_BUS,
                "route-1",
                "stop-1",
                12,
                "143",
                "서울역",
                null,
                null,
                null,
                null,
                new AdjacentStopSnapshot("successor-stop", 13)
        ));
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 10, 12, 0);
        alarm.activate();
        alarm.startFollowUp("vehicle-123", startedAt, startedAt.plusMinutes(10));
        Alarm savedAlarm = alarmRepository.saveAndFlush(alarm);
        entityManager.clear();

        Alarm foundAlarm = alarmRepository.findById(savedAlarm.getId()).orElseThrow();
        String rawStatus = jdbcTemplate.queryForObject(
                "select status from alarms where id = ?",
                String.class,
                savedAlarm.getId()
        );

        assertThat(foundAlarm.getStatus()).isEqualTo(AlarmStatus.FOLLOW_UP);
        assertThat(foundAlarm.getFollowUpVehicleTrackingId()).isEqualTo("vehicle-123");
        assertThat(foundAlarm.getFollowUpStartedAt()).isEqualTo(startedAt);
        assertThat(foundAlarm.getFollowUpExpiresAt()).isEqualTo(startedAt.plusMinutes(10));
        assertThat(rawStatus).isEqualTo("FOLLOW_UP");
    }

    @Test
    @DisplayName("Alarm을 삭제하면 공유 PK로 연결된 Bus Alarm Target도 삭제된다")
    void delete_alarm_removes_bus_alarm_target() {
        Alarm alarm = saveAlarm();
        Long alarmId = alarm.getId();

        alarmRepository.delete(alarm);
        alarmRepository.flush();
        entityManager.clear();

        Integer targetCount = jdbcTemplate.queryForObject(
                "select count(*) from bus_alarm_targets where alarm_id = ?",
                Integer.class,
                alarmId
        );
        assertThat(targetCount).isZero();
    }

    @Test
    @DisplayName("FOLLOW_UP 상태는 완전한 runtime field 없이 데이터베이스에 저장할 수 없다")
    void save_follow_up_without_runtime_fails_by_check_constraint() {
        Alarm alarm = saveAlarm();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update alarms set status = 'FOLLOW_UP' where id = ?",
                alarm.getId()
        )).isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_alarms_lifecycle");
    }

    @Test
    @DisplayName("한 정거장 후 옵션은 successor snapshot 없이 데이터베이스에 저장할 수 없다")
    void save_after_option_without_successor_fails_by_check_constraint() {
        Alarm alarm = saveAlarm();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update bus_alarm_targets set notify_one_stop_after = true where alarm_id = ?",
                alarm.getId()
        )).isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining("ck_bus_alarm_targets_successor");
    }

    private Alarm saveAlarm() {
        User user = userRepository.saveAndFlush(new User(AuthProvider.GOOGLE, "google-notification-user"));
        return alarmRepository.saveAndFlush(new Alarm(user, createSeoulTarget(1)));
    }

    private BusAlarmTarget createSeoulTarget(int targetStopOrder) {
        return new BusAlarmTarget(
                TransitProvider.SEOUL_BUS,
                "route-1",
                "stop-1",
                targetStopOrder,
                "143",
                "서울역",
                null,
                null,
                null,
                null,
                null
        );
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String sha256Unchecked(String value) {
        try {
            return sha256(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
