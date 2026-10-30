package com.stopbell.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.service.BusAlarmEvaluationState;
import com.stopbell.alarm.service.BusAlarmEvaluator;
import com.stopbell.alarm.service.BusAlarmLifecycleService;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.entity.BusMetadataSyncState;
import com.stopbell.user.auth.exception.InvalidRefreshTokenException;
import com.stopbell.user.auth.service.JwtTokenService;
import com.stopbell.user.auth.service.RefreshTokenService;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.RefreshToken;
import com.stopbell.user.entity.User;
import com.stopbell.user.repository.RefreshTokenRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * AUD-12 characterization, not the desired UTC contract. TASK-814 must replace the host-local expectations.
 * Run in separate test JVMs with -Duser.timezone=UTC, Asia/Seoul and America/Los_Angeles.
 * Never changes TimeZone.setDefault(). Only the callback thread's Clock.systemDefaultZone() is fixed;
 * LocalDateTime.now(), JPA callbacks, Hibernate, Connector/J and MySQL remain real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers
@Transactional
class UtcTimestampCharacterizationIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    private static final LocalDateTime UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired private EntityManager em;
    @Autowired private RefreshTokenRepository tokens;
    @Autowired private BusAlarmEvaluator evaluator;

    @Test
    @DisplayName("기존 User·RefreshToken·Alarm 콜백은 같은 Instant를 JVM 기본 시간대의 DATETIME으로 저장한다")
    void characterize_host_local_callbacks() {
        User user = new User(AuthProvider.GOOGLE, "audit-callback-user");
        Alarm alarm = new Alarm(user, TransitType.SUBWAY);
        RefreshToken token = new RefreshToken(user, "a".repeat(64), UTC.plusDays(30));
        atHostTime(NOW, () -> {
            em.persist(user);
            em.persist(alarm);
            em.persist(token);
            em.flush();
        });
        em.clear();

        User storedUser = em.find(User.class, user.getId());
        Alarm storedAlarm = em.find(Alarm.class, alarm.getId());
        RefreshToken storedToken = em.find(RefreshToken.class, token.getId());
        LocalDateTime hostNow = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        assertThat(storedUser.getCreatedAt()).isEqualTo(hostNow);
        assertThat(storedUser.getUpdatedAt()).isEqualTo(hostNow);
        assertThat(storedAlarm.getCreatedAt()).isEqualTo(hostNow);
        assertThat(storedAlarm.getUpdatedAt()).isEqualTo(hostNow);
        assertThat(storedToken.getCreatedAt()).isEqualTo(hostNow);
        assertThat(storedToken.getExpiresAt()).isEqualTo(UTC.plusDays(30));
        assertThat(rawTime("users", "created_at", "id", user.getId())).isEqualTo(hostNow);
        assertThat(rawTime("alarms", "created_at", "id", alarm.getId())).isEqualTo(hostNow);
        assertThat(rawTime("refresh_tokens", "created_at", "id", token.getId())).isEqualTo(hostNow);

        atHostTime(NOW.plusSeconds(60), () -> {
            // User currently has no public update operation; force a dirty field to exercise real @PreUpdate.
            ReflectionTestUtils.setField(storedUser, "providerUserId", "audit-updated-user");
            storedAlarm.activate();
            em.flush();
        });
        em.clear();
        assertThat(em.find(User.class, user.getId()).getUpdatedAt()).isEqualTo(hostNow.plusMinutes(1));
        assertThat(em.find(Alarm.class, alarm.getId()).getUpdatedAt()).isEqualTo(hostNow.plusMinutes(1));
        assertThat(rawTime("users", "updated_at", "id", user.getId())).isEqualTo(hostNow.plusMinutes(1));
        assertThat(rawTime("alarms", "updated_at", "id", alarm.getId())).isEqualTo(hostNow.plusMinutes(1));
    }

    @Test
    @DisplayName("Refresh의 UTC 만료 경계·Rotation·Logout은 Host 시각의 createdAt을 판단에 사용하지 않는다")
    void characterize_refresh_expiry_and_rotation() {
        User user = new User(AuthProvider.GOOGLE, "audit-refresh-user");
        atHostTime(NOW, () -> em.persist(user));
        RefreshTokenService issuing = refreshService(NOW);
        String[] issued = new String[2];
        atHostTime(NOW, () -> {
            issued[0] = issuing.issue(user);
            issued[1] = issuing.issue(user);
            em.flush();
        });
        assertThat(tokens.findAll()).allSatisfy(token -> {
            assertThat(token.getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneId.systemDefault()));
            assertThat(token.getExpiresAt()).isEqualTo(UTC.plusDays(30));
        });

        Instant beforeExpiry = NOW.plusSeconds(30L * 86400 - 1);
        atHostTime(beforeExpiry, () -> {
            var rotated = refreshService(beforeExpiry).refresh(issued[0]);
            assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo(issued[0]);
            assertThatThrownBy(() -> issuing.refresh(issued[0])).isInstanceOf(InvalidRefreshTokenException.class);
            em.flush();
        });
        assertThat(tokens.findAll()).anySatisfy(token ->
                assertThat(token.getExpiresAt()).isEqualTo(UTC.plusDays(60).minusSeconds(1)));
        assertThatThrownBy(() -> refreshService(NOW.plusSeconds(30L * 86400)).refresh(issued[1]))
                .isInstanceOf(InvalidRefreshTokenException.class);
        // Logout of a token already expired by the service clock still deletes by hash only.
        String expiredForLogout = issuing.issue(user);
        refreshService(NOW.plusSeconds(31L * 86400)).invalidate(expiredForLogout);
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("FOLLOW_UP은 Host 감사 시각과 별도로 UTC 시작·5분 만료를 저장하고 재조회 뒤 같은 경계로 만료한다")
    void characterize_follow_up_utc_and_reload() {
        User user = new User(AuthProvider.GOOGLE, "audit-follow-up-user");
        BusAlarmTarget target = new BusAlarmTarget(TransitProvider.SEOUL_BUS, "route", "target", 2,
                "100", "목적지", null, null, null, null, new AdjacentStopSnapshot("after", 3));
        Alarm alarm = new Alarm(user, target);
        atHostTime(NOW, () -> {
            em.persist(user);
            em.persist(alarm);
            alarm.activate();
            TransitEvent arrived = new TransitEvent(TransitEventType.ARRIVED, "vehicle", UUID.randomUUID(),
                    NOW, "target", "목적지", 2, null, null, null, null);
            // Exercise the existing conversion, not a duplicate formula in this test.
            ReflectionTestUtils.invokeMethod(BusAlarmLifecycleService.class, "applyArrival", alarm, arrived);
            em.flush();
        });
        em.clear();
        Alarm reloaded = em.find(Alarm.class, alarm.getId());
        assertThat(reloaded.getCreatedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneId.systemDefault()));
        assertThat(reloaded.getFollowUpStartedAt()).isEqualTo(UTC);
        assertThat(reloaded.getFollowUpExpiresAt()).isEqualTo(UTC.plusMinutes(5));
        assertThat(rawTime("alarms", "follow_up_started_at", "id", alarm.getId())).isEqualTo(UTC);
        assertThat(rawTime("alarms", "follow_up_expires_at", "id", alarm.getId())).isEqualTo(UTC.plusMinutes(5));
        assertThat(evaluator.evaluate(reloaded, BusAlarmEvaluationState.initial(), List.of(), NOW.plusSeconds(299))
                .followUpExpired()).isFalse();
        assertThat(evaluator.evaluate(reloaded, BusAlarmEvaluationState.initial(), List.of(), NOW.plusSeconds(300))
                .followUpExpired()).isTrue();
    }

    @Test
    @DisplayName("실제 MySQL에서 DATETIME·TIMESTAMP·JDBC 해석을 구분하고 UTC Instant metadata를 왕복한다")
    void characterize_mysql_datetime_and_instant_boundary() {
        // This SQL literal is identical in all three JVMs, unlike the host-generated callback values.
        em.createNativeQuery("insert into users (auth_provider, provider_user_id, created_at, updated_at) "
                + "values ('GOOGLE', 'audit-literal-user', '2026-10-30 00:00:00', '2026-10-30 00:00:00')")
                .executeUpdate();
        User literal = em.createQuery("select u from User u where u.providerUserId = :id", User.class)
                .setParameter("id", "audit-literal-user").getSingleResult();
        assertThat(literal.getCreatedAt()).isEqualTo(UTC);

        em.persist(new BusMetadataSyncState(TransitProvider.SEOUL_BUS, NOW));
        em.flush();
        em.clear();
        assertThat(em.find(BusMetadataSyncState.class, TransitProvider.SEOUL_BUS).getLastCompleteSyncAt())
                .isEqualTo(NOW);
        assertThat(rawTime("bus_metadata_sync_states", "last_complete_sync_at", "provider", "SEOUL_BUS"))
                .isEqualTo(UTC);

        em.unwrap(Session.class).doWork(connection -> {
            String originalZone;
            try (var statement = connection.createStatement(); var result = statement.executeQuery("select @@session.time_zone")) {
                result.next();
                originalZone = result.getString(1);
            }
            try (var statement = connection.createStatement()) {
                statement.execute("create temporary table aud12_time (dt datetime(6), ts timestamp(6))");
                statement.execute("set session time_zone = '+00:00'");
                try (var insert = connection.prepareStatement("insert into aud12_time values (?, ?)")) {
                    insert.setObject(1, UTC);
                    insert.setObject(2, UTC);
                    insert.executeUpdate();
                }
                for (String offset : List.of("+00:00", "+09:00", "-07:00")) {
                    statement.execute("set session time_zone = '" + offset + "'");
                    try (ResultSet row = statement.executeQuery("select dt, ts, from_unixtime(" + NOW.getEpochSecond() + ") as server_time from aud12_time")) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getObject("dt", LocalDateTime.class)).isEqualTo(UTC);
                        assertThat(row.getString("dt")).startsWith("2026-10-30 00:00:00");
                        assertThat(row.getTimestamp("dt").toInstant()).isEqualTo(UTC.atZone(ZoneId.systemDefault()).toInstant());
                        LocalDateTime sessionTime = LocalDateTime.ofInstant(NOW, ZoneOffset.of(offset));
                        assertThat(row.getObject("ts", LocalDateTime.class)).isEqualTo(sessionTime);
                        assertThat(row.getObject("server_time", LocalDateTime.class)).isEqualTo(sessionTime);
                    }
                    em.clear();
                    assertThat(em.find(User.class, literal.getId()).getCreatedAt()).isEqualTo(UTC);
                    assertThat(em.find(BusMetadataSyncState.class, TransitProvider.SEOUL_BUS).getLastCompleteSyncAt())
                            .isEqualTo(NOW);
                }
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("drop temporary table if exists aud12_time");
                }
                try (var restore = connection.prepareStatement("set session time_zone = ?")) {
                    restore.setString(1, originalZone);
                    restore.execute();
                }
            }
        });
    }

    private RefreshTokenService refreshService(Instant instant) {
        return new RefreshTokenService(tokens, mock(JwtTokenService.class), Clock.fixed(instant, ZoneId.systemDefault()));
    }

    private void atHostTime(Instant instant, Runnable work) {
        Clock fixedHostClock = Clock.fixed(instant, ZoneId.systemDefault());
        try (var clocks = mockStatic(Clock.class, CALLS_REAL_METHODS)) {
            clocks.when(Clock::systemDefaultZone).thenReturn(fixedHostClock);
            work.run();
        }
    }

    private LocalDateTime rawTime(String table, String column, String key, Object id) {
        return em.unwrap(Session.class).doReturningWork(connection -> {
            // All identifiers are constants owned by this test.
            try (var query = connection.prepareStatement("select " + column + " from " + table + " where " + key + " = ?")) {
                query.setObject(1, id);
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    return row.getObject(1, LocalDateTime.class);
                }
            }
        });
    }
}
