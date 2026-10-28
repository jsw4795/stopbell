package com.stopbell.boarding.entity;

import static com.stopbell.boarding.BoardingSessionFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers
@Transactional
class BoardingSessionPersistenceIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private BoardingSession persist() {
        var user = new User(AuthProvider.GOOGLE, "session-owner");
        em.persist(user);
        var session = session(user);
        em.persist(session);
        em.flush();
        return session;
    }

    @Test
    @DisplayName("V15는 불변 snapshot과 최초 checkpoint 및 UTC microsecond 시각을 저장하고 복원한다")
    void persist_and_restore() {
        var saved = persist();
        em.clear();
        var loaded = em.find(BoardingSession.class, saved.getId());
        assertThat(loaded.getTraversal()).isEqualTo(saved.getTraversal());
        assertThat(loaded.getCheckpoint().getStopExternalId()).isEqualTo("s1");
        assertThat(loaded.getCheckpoint().getEvidence()).isEqualTo(BoardingCheckpoint.Evidence.ID_ORDER);
        assertThat(loaded.getCheckpoint().getProviderDataTime()).isEqualTo(UTC_NOW.minusSeconds(1));
        assertThat(loaded.getCreatedAt()).isEqualTo(UTC_NOW);
        assertThat(loaded.getUpdatedAt()).isEqualTo(UTC_NOW);
        assertThat(loaded.getExpiresAt()).isEqualTo(UTC_NOW.plusHours(4));
        assertThat(jdbc.queryForObject("select version from flyway_schema_history order by installed_rank desc limit 1", String.class)).isEqualTo("19");
        assertThat(jdbc.queryForObject("select open_user_id from boarding_sessions where id=?", Long.class, saved.getId()))
                .isEqualTo(saved.getUser().getId());
        assertThat(jdbc.queryForObject("select json_type(traversal_snapshot) from boarding_sessions where id=?", String.class, saved.getId())).isEqualTo("OBJECT");
    }

    @Test
    @DisplayName("한 User의 TRACKING 또는 SUSPENDED 세션은 DB UNIQUE로 하나만 허용한다")
    void unique_open_user() {
        var original = persist();
        original.suspend(0, BoardingStateReason.VEHICLE_MISSING, UTC_NOW.plusSeconds(1));
        em.flush();
        var second = copyFor(original.getUser(), "bbbbbbbb-1111-4111-8111-111111111111");
        assertThatThrownBy(() -> { em.persist(second); em.flush(); }).hasStackTraceContaining("uk_boarding_sessions_open_user");
    }

    @Test
    @DisplayName("종료 뒤 새 세션은 허용하지만 동일 요청 UUID는 terminal 상태에서도 재사용할 수 없다")
    void terminal_releases_open_key_and_preserves_request_key() {
        var original = persist();
        original.cancel(0, UTC_NOW.plusSeconds(1));
        em.flush();
        em.persist(copyFor(original.getUser(), "bbbbbbbb-1111-4111-8111-111111111111"));
        em.flush();
        assertThat(jdbc.queryForObject("select open_user_id from boarding_sessions where id=?", Long.class, original.getId())).isNull();
        assertThatThrownBy(() -> { em.persist(copyFor(original.getUser(), REQUEST_ID)); em.flush(); })
                .hasStackTraceContaining("uk_boarding_sessions_user_request");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "status='CONFIRMED'", "revision=-1", "provider='OTHER'", "vehicle_number=''",
            "external_route_id=''", "city_code='31010'", "provider='TAGO', city_code=null",
            "checkpoint_stop_order=0", "checkpoint_index=-1", "checkpoint_evidence='OTHER'",
            "checkpoint_evidence='ID_ORDER', checkpoint_stop_external_id=null",
            "checkpoint_evidence='VERIFIED_ORDER_ONLY'", "state_reason='VEHICLE_MISSING'",
            "status='SUSPENDED', state_reason=null", "status='SUSPENDED', state_reason='USER_CANCELLED'",
            "status='CANCELLED', state_reason='USER_CANCELLED', ended_at=null",
            "ended_at=created_at", "expires_at=created_at", "request_fingerprint='invalid'",
            "client_request_id='not-a-uuid'", "traversal_fingerprint='invalid'"
    })
    @DisplayName("DB CHECK는 상태와 사유 및 nullable 근거와 수명 및 opaque reference의 잘못된 조합을 거부한다")
    void database_checks(String assignment) {
        var saved = persist();
        assertThatThrownBy(() -> jdbc.update("update boarding_sessions set " + assignment + " where id=?", saved.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("opaque 차량 식별자는 대소문자와 끝 공백을 합치지 않는다")
    void preserve_opaque_comparison() {
        var saved = persist();
        assertThat(jdbc.queryForObject("select count(*) from boarding_sessions where vehicle_tracking_id=?", Long.class, "VEHICLE-ID")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from boarding_sessions where vehicle_tracking_id=?", Long.class, "vehicle-id ")).isZero();
        assertThat(jdbc.queryForObject("select vehicle_number from boarding_sessions where id=?", String.class, saved.getId())).isEqualTo(NUMBER);
    }

    @Test
    @DisplayName("복원된 checkpoint의 시각 모순은 Domain 검증에서 거부한다")
    void reject_inconsistent_restored_checkpoint() {
        var saved = persist();
        jdbc.update("update boarding_sessions set checkpoint_provider_data_time=date_add(checkpoint_observed_at, interval 1 second) where id=?", saved.getId());
        em.clear();
        assertThatThrownBy(() -> em.find(BoardingSession.class, saved.getId()).getTraversal())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("세션이 소유한 User는 FK로 삭제가 제한된다")
    void preserve_owner_foreign_key() {
        var saved = persist();
        assertThatThrownBy(() -> jdbc.update("delete from users where id=?", saved.getUser().getId()))
                .isInstanceOf(DataAccessException.class);
    }

    private BoardingSession copyFor(User user, String key) {
        var start = verified(metadata());
        return new BoardingSession(user, key, "a".repeat(64), start.vehicleTrackingId(), NUMBER, "7000", "목적지",
                start.traversal(), start.checkpoint(), UTC_NOW, UTC_NOW.plusHours(4));
    }
}
