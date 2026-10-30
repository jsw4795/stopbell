package com.stopbell.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * TASK-814 UTC regression contract, replacing the AUD-12 host-local characterization expectations.
 * Run in separate test JVMs with -Duser.timezone=UTC, Asia/Seoul and America/Los_Angeles.
 * Never changes TimeZone.setDefault(). Only the callback thread's Clock factories are fixed;
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
    @DisplayName("테스트 Worker의 기본 시간대는 JVM 시작 시 전달한 검증 시간대와 일치한다")
    void verifies_worker_timezone() {
        ZoneId actual = ZoneId.systemDefault();
        String expected = System.getProperty("task814.expectedTimezone", System.getProperty("user.timezone"));
        assertThat(actual).isEqualTo(ZoneId.of(expected));
        assertThat(System.getProperty("user.timezone")).isEqualTo(expected);
        System.out.println("TASK-814 test worker timezone=" + actual + ", expected=" + expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-30T00:00:00Z", "2026-10-30T00:00:00.123456Z"})
    @DisplayName("User·RefreshToken·Alarm 콜백은 JVM 시간대와 무관하게 UTC를 생성하고 저장 및 복원한다")
    void persists_utc_callbacks(String value) {
        Instant instant = Instant.parse(value);
        LocalDateTime utcNow = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        AuditSample sample = persistAuditSample("callback", instant);
        assertAuditSample(sample, utcNow, utcNow);
        assertThat(sample.token().getExpiresAt()).isEqualTo(utcNow.plusDays(30));

        atFixedTime(instant.plusSeconds(60), () -> {
            // User has no public update operation; force dirty state for real @PreUpdate.
            User user = em.find(User.class, sample.user().getId());
            Alarm alarm = em.find(Alarm.class, sample.alarm().getId());
            ReflectionTestUtils.setField(user, "providerUserId", "audit-updated-user");
            alarm.activate();
            em.flush();
            assertThat(user.getUpdatedAt()).isEqualTo(utcNow.plusMinutes(1));
            assertThat(alarm.getUpdatedAt()).isEqualTo(utcNow.plusMinutes(1));
        });
        assertAuditSample(sample, utcNow, utcNow.plusMinutes(1));
        atFixedTime(instant.plusSeconds(120), () -> {
            em.find(Alarm.class, sample.alarm().getId()).activate();
            em.flush();
        });
        assertAuditSample(sample, utcNow, utcNow.plusMinutes(1));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-03-08T09:59:59Z, 2026-03-08T10:00:00Z, 2026-03-08T01:59:59, 2026-03-08T03:00:00",
            "2026-11-01T08:59:59Z, 2026-11-01T09:00:00Z, 2026-11-01T01:59:59, 2026-11-01T01:00:00",
            "2026-11-01T08:30:00Z, 2026-11-01T09:30:00Z, 2026-11-01T01:30:00, 2026-11-01T01:30:00"
    })
    @DisplayName("LA의 실제 DST 누락과 중첩 구간에서도 서로 다른 순간을 UTC로 구분해 저장한다")
    void preserves_distinct_instants_across_dst(String firstValue, String secondValue,
            String firstLocal, String secondLocal) {
        Instant first = Instant.parse(firstValue);
        Instant second = Instant.parse(secondValue);
        ZoneId losAngeles = ZoneId.of("America/Los_Angeles");
        // Check the fixture against the runtime ZoneRules rather than guessing a DST date.
        assertThat(first.atZone(losAngeles).toLocalDateTime()).isEqualTo(LocalDateTime.parse(firstLocal));
        assertThat(second.atZone(losAngeles).toLocalDateTime()).isEqualTo(LocalDateTime.parse(secondLocal));
        assertThat(first.atZone(losAngeles).getOffset()).isNotEqualTo(second.atZone(losAngeles).getOffset());
        LocalDateTime firstUtc = LocalDateTime.ofInstant(first, ZoneOffset.UTC);
        LocalDateTime secondUtc = LocalDateTime.ofInstant(second, ZoneOffset.UTC);
        AuditSample before = persistAuditSample("dst-before", first);
        assertAuditSample(before, firstUtc, firstUtc);
        AuditSample after = persistAuditSample("dst-after", second);
        assertAuditSample(after, secondUtc, secondUtc);
        assertThat(Duration.between(
                rawTime("alarms", "created_at", "id", before.alarm().getId()),
                rawTime("alarms", "created_at", "id", after.alarm().getId())))
                .isEqualTo(Duration.between(first, second)).isPositive();
        atFixedTime(second, () -> {
            User user = em.find(User.class, before.user().getId());
            ReflectionTestUtils.setField(user, "providerUserId", "dst-updated-user");
            em.find(Alarm.class, before.alarm().getId()).activate();
            em.flush();
            assertThat(user.getUpdatedAt()).isEqualTo(secondUtc);
        });
        assertAuditSample(before, firstUtc, secondUtc);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-30T00:00:00.123456789Z, 2026-10-30T00:00:00.123457",
            "2026-10-30T23:59:59.999999999Z, 2026-10-31T00:00:00"
    })
    @DisplayName("나노초 감사 시각은 기존 DATETIME 마이크로초 반올림과 날짜 경계 처리를 유지한다")
    void preserves_datetime_microsecond_binding(String instantValue, String storedValue) {
        Instant instant = Instant.parse(instantValue);
        AuditSample sample = persistAuditSample("precision", instant);
        LocalDateTime stored = LocalDateTime.parse(storedValue);
        assertAuditSample(sample, stored, stored);
    }

    @Test
    @DisplayName("기존 Host 감사 값과 UTC 만료는 읽기 시 보정하지 않고 새 수정 시각만 UTC로 갱신한다")
    void preserves_legacy_values_without_conversion() {
        AuditSample sample = persistAuditSample("legacy", NOW);
        LocalDateTime legacy = UTC.plusHours(9);
        em.createNativeQuery("update users set created_at=:legacy, updated_at=:legacy where id=:id")
                .setParameter("legacy", legacy).setParameter("id", sample.user().getId()).executeUpdate();
        em.createNativeQuery("update alarms set created_at=:legacy, updated_at=:legacy where id=:id")
                .setParameter("legacy", legacy).setParameter("id", sample.alarm().getId()).executeUpdate();
        em.createNativeQuery("update refresh_tokens set created_at=:legacy where id=:id")
                .setParameter("legacy", legacy).setParameter("id", sample.token().getId()).executeUpdate();
        em.clear();
        User user = em.find(User.class, sample.user().getId());
        Alarm alarm = em.find(Alarm.class, sample.alarm().getId());
        RefreshToken token = em.find(RefreshToken.class, sample.token().getId());
        assertThat(user.getCreatedAt()).isEqualTo(legacy);
        assertThat(user.getUpdatedAt()).isEqualTo(legacy);
        assertThat(alarm.getCreatedAt()).isEqualTo(legacy);
        assertThat(alarm.getUpdatedAt()).isEqualTo(legacy);
        assertThat(token.getCreatedAt()).isEqualTo(legacy);
        assertThat(token.getExpiresAt()).isEqualTo(UTC.plusDays(30));
        em.flush();
        assertThat(rawTime("users", "updated_at", "id", user.getId())).isEqualTo(legacy);
        assertThat(rawTime("alarms", "updated_at", "id", alarm.getId())).isEqualTo(legacy);
        atFixedTime(NOW.plusSeconds(60), () -> {
            ReflectionTestUtils.setField(user, "providerUserId", "legacy-updated-user");
            alarm.activate();
            em.flush();
        });
        em.clear();
        assertThat(em.find(User.class, user.getId()).getCreatedAt()).isEqualTo(legacy);
        assertThat(em.find(User.class, user.getId()).getUpdatedAt()).isEqualTo(UTC.plusMinutes(1));
        assertThat(em.find(Alarm.class, alarm.getId()).getCreatedAt()).isEqualTo(legacy);
        assertThat(em.find(Alarm.class, alarm.getId()).getUpdatedAt()).isEqualTo(UTC.plusMinutes(1));
        assertThat(rawTime("users", "created_at", "id", user.getId())).isEqualTo(legacy);
        assertThat(rawTime("users", "updated_at", "id", user.getId())).isEqualTo(UTC.plusMinutes(1));
        assertThat(rawTime("alarms", "created_at", "id", alarm.getId())).isEqualTo(legacy);
        assertThat(rawTime("alarms", "updated_at", "id", alarm.getId())).isEqualTo(UTC.plusMinutes(1));
        assertThat(rawTime("refresh_tokens", "created_at", "id", token.getId())).isEqualTo(legacy);
        assertThat(rawTime("refresh_tokens", "expires_at", "id", token.getId())).isEqualTo(UTC.plusDays(30));
    }

    @Test
    @DisplayName("UTC 감사 시각 수정 후에도 Refresh 만료 경계와 Rotation 및 Logout을 보존한다")
    void preserves_refresh_expiry_and_rotation() {
        User user = new User(AuthProvider.GOOGLE, "audit-refresh-user");
        atFixedTime(NOW, () -> em.persist(user));
        RefreshTokenService issuing = refreshService(NOW);
        String[] issued = new String[2];
        atFixedTime(NOW, () -> {
            issued[0] = issuing.issue(user);
            issued[1] = issuing.issue(user);
            em.flush();
        });
        assertThat(tokens.findAll()).allSatisfy(token -> {
            assertThat(token.getCreatedAt()).isEqualTo(UTC);
            assertThat(token.getExpiresAt()).isEqualTo(UTC.plusDays(30));
        });

        Instant beforeExpiry = NOW.plusSeconds(30L * 86400).minusNanos(1_000);
        atFixedTime(beforeExpiry, () -> {
            var rotated = refreshService(beforeExpiry).refresh(issued[0]);
            assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo(issued[0]);
            assertThatThrownBy(() -> issuing.refresh(issued[0])).isInstanceOf(InvalidRefreshTokenException.class);
            em.flush();
        });
        assertThat(tokens.findAll()).anySatisfy(token ->
                assertThat(token.getExpiresAt()).isEqualTo(UTC.plusDays(60).minusNanos(1_000)));
        assertThatThrownBy(() -> refreshService(NOW.plusSeconds(30L * 86400)).refresh(issued[1]))
                .isInstanceOf(InvalidRefreshTokenException.class);
        // Logout of a token already expired by the service clock still deletes by hash only.
        String expiredForLogout = issuing.issue(user);
        refreshService(NOW.plusSeconds(31L * 86400)).invalidate(expiredForLogout);
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("FOLLOW_UP은 관측 UTC 기준 시작과 5분 만료를 저장하고 재조회 뒤 같은 경계로 만료한다")
    void preserves_follow_up_utc_and_reload() {
        User user = new User(AuthProvider.GOOGLE, "audit-follow-up-user");
        BusAlarmTarget target = new BusAlarmTarget(TransitProvider.SEOUL_BUS, "route", "target", 2,
                "100", "목적지", null, null, null, null, new AdjacentStopSnapshot("after", 3));
        Alarm alarm = new Alarm(user, target);
        atFixedTime(NOW, () -> {
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
        assertThat(reloaded.getCreatedAt()).isEqualTo(UTC);
        assertThat(reloaded.getFollowUpStartedAt()).isEqualTo(UTC);
        assertThat(reloaded.getFollowUpExpiresAt()).isEqualTo(UTC.plusMinutes(5));
        assertThat(rawTime("alarms", "follow_up_started_at", "id", alarm.getId())).isEqualTo(UTC);
        assertThat(rawTime("alarms", "follow_up_expires_at", "id", alarm.getId())).isEqualTo(UTC.plusMinutes(5));
        assertThat(evaluator.evaluate(reloaded, BusAlarmEvaluationState.initial(), List.of(), NOW.plusSeconds(300).minusNanos(1_000))
                .followUpExpired()).isFalse();
        assertThat(evaluator.evaluate(reloaded, BusAlarmEvaluationState.initial(), List.of(), NOW.plusSeconds(300))
                .followUpExpired()).isTrue();
        assertThat(evaluator.evaluate(reloaded, BusAlarmEvaluationState.initial(), List.of(), NOW.plusSeconds(300).plusNanos(1_000))
                .followUpExpired()).isTrue();
    }

    @Test
    @DisplayName("실제 MySQL에서 DATETIME·TIMESTAMP·JDBC 해석을 구분하고 UTC Instant metadata를 왕복한다")
    void preserves_mysql_datetime_and_instant_boundary() {
        // The same UTC SQL literal must retain its fields in every JVM.
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

    private AuditSample persistAuditSample(String identity, Instant instant) {
        LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        User user = new User(AuthProvider.GOOGLE, "audit-" + identity);
        Alarm alarm = new Alarm(user, TransitType.SUBWAY);
        // Distinct token hashes for multiple samples in the same DST transaction.
        String tokenHash = (identity.equals("dst-after") ? "b" : "a").repeat(64);
        RefreshToken token = new RefreshToken(user, tokenHash, utc.plusDays(30));
        atFixedTime(instant, () -> {
            em.persist(user);
            em.persist(alarm);
            em.persist(token);
            em.flush();
            assertThat(user.getCreatedAt()).isEqualTo(utc);
            assertThat(user.getUpdatedAt()).isEqualTo(utc);
            assertThat(alarm.getCreatedAt()).isEqualTo(utc);
            assertThat(alarm.getUpdatedAt()).isEqualTo(utc);
            assertThat(token.getCreatedAt()).isEqualTo(utc);
        });
        return new AuditSample(user, alarm, token);
    }

    private void assertAuditSample(AuditSample sample, LocalDateTime createdAt, LocalDateTime updatedAt) {
        em.clear();
        User user = em.find(User.class, sample.user().getId());
        Alarm alarm = em.find(Alarm.class, sample.alarm().getId());
        RefreshToken token = em.find(RefreshToken.class, sample.token().getId());
        assertThat(user.getCreatedAt()).isEqualTo(createdAt);
        assertThat(user.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(alarm.getCreatedAt()).isEqualTo(createdAt);
        assertThat(alarm.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(token.getCreatedAt()).isEqualTo(createdAt);
        assertThat(rawTime("users", "created_at", "id", user.getId())).isEqualTo(createdAt);
        assertThat(rawTime("users", "updated_at", "id", user.getId())).isEqualTo(updatedAt);
        assertThat(rawTime("alarms", "created_at", "id", alarm.getId())).isEqualTo(createdAt);
        assertThat(rawTime("alarms", "updated_at", "id", alarm.getId())).isEqualTo(updatedAt);
        assertThat(rawTime("refresh_tokens", "created_at", "id", token.getId())).isEqualTo(createdAt);
    }

    private record AuditSample(User user, Alarm alarm, RefreshToken token) { }

    private RefreshTokenService refreshService(Instant instant) {
        return new RefreshTokenService(tokens, mock(JwtTokenService.class), Clock.fixed(instant, ZoneId.systemDefault()));
    }

    private void atFixedTime(Instant instant, Runnable work) {
        Clock fixedHostClock = Clock.fixed(instant, ZoneId.systemDefault());
        Clock fixedUtcClock = Clock.fixed(instant, ZoneOffset.UTC);
        try (var clocks = mockStatic(Clock.class, CALLS_REAL_METHODS)) {
            clocks.when(Clock::systemDefaultZone).thenReturn(fixedHostClock);
            // LocalDateTime.now(ZoneOffset.UTC) calls this factory in Java 21.
            clocks.when(() -> Clock.system(ZoneOffset.UTC)).thenReturn(fixedUtcClock);
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
