package com.stopbell.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.stopbell.alarm.entity.AdjacentStopSnapshot;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.BusAlarmTarget;
import com.stopbell.alarm.service.AlarmService;
import com.stopbell.boarding.BoardingSessionFixtures;
import com.stopbell.boarding.entity.BoardingSession;
import com.stopbell.boarding.entity.BoardingStateReason;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.notification.repository.NotificationEventRepository;
import com.stopbell.transit.domain.TransitEvent;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
@Testcontainers
@Transactional
class NotificationPersistenceIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 0, 0);
    private static final UUID CYCLE = UUID.fromString("a1234567-1234-4123-8123-123456789abc");

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NotificationEventRepository events;
    @Autowired
    private NotificationDeliveryRepository deliveries;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @EnumSource(TransitEventType.class)
    @DisplayName("V16은 기존 네 Alarm type과 source별 NULL 필드 및 identity 조회를 보존한다")
    void preserve_all_alarm_types(TransitEventType type) {
        Alarm alarm = alarm();
        NotificationEvent saved = event(alarm, CYCLE, type);
        entityManager.clear();
        NotificationEvent found = events.findByAlarmIdAndActivationGenerationAndTrackingCycleIdAndEventType(
                alarm.getId(), 1, CYCLE.toString(), type).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getSourceType()).isEqualTo(NotificationSourceType.ALARM);
        assertThat(found.getBoardingSessionId()).isNull();
        assertThat(found.getBoardingEventType()).isNull();
        assertThat(found.getExpiresAt()).isNull();
        assertThat(events.findByBoardingSessionIdAndBoardingEventType(alarm.getId(),
                BoardingNotificationEventType.PREPARE_ALIGHT)).isEmpty();
        for (String assignment : List.of("boarding_session_id=1", "boarding_event_type='PREPARE_ALIGHT'",
                "expires_at=observed_at + interval 30 second", "source_type='BOARDING_SESSION'",
                "alarm_id=null, activation_generation=null, tracking_cycle_id=null, event_type=null")) {
            assertThatThrownBy(() -> jdbc.update("update notification_events set " + assignment + " where id=?", found.getId()))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_notification_events_source_identity");
        }
    }

    @Test
    @DisplayName("Session Event와 Delivery는 기존 모델로 저장되며 Session revision 변경 후에도 Event 중복을 차단한다")
    void persist_session_event_and_delivery_without_revision_identity() {
        User user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
        entityManager.persist(user);
        BoardingSession session = BoardingSessionFixtures.session(user);
        entityManager.persist(session);
        NotificationEvent saved = events.saveAndFlush(sessionEvent(session.getId(), user));
        Device device = device(user);
        User other = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
        entityManager.persist(other);
        device.transferOwnership(other, "other-fid", 1, BoardingSessionFixtures.UTC_NOW);
        device.transferOwnership(user, "returned-fid", 2, BoardingSessionFixtures.UTC_NOW);
        NotificationDelivery delivery = deliveries.saveAndFlush(new NotificationDelivery(saved, device,
                device.getOwnershipGeneration(), saved.getExpiresAt(), saved.getCreatedAt()));
        session.suspend(0, BoardingStateReason.VEHICLE_MISSING, BoardingSessionFixtures.UTC_NOW.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();
        NotificationEvent found = events.findByBoardingSessionIdAndBoardingEventType(session.getId(),
                BoardingNotificationEventType.PREPARE_ALIGHT).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getSourceType()).isEqualTo(NotificationSourceType.BOARDING_SESSION);
        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getAlarmId()).isNull();
        assertThat(found.getActivationGeneration()).isNull();
        assertThat(found.getTrackingCycleId()).isNull();
        assertThat(found.getEventType()).isNull();
        assertThat(found.getObservedAt()).isEqualTo(BoardingSessionFixtures.UTC_NOW);
        assertThat(found.getEventDetectedAt()).isEqualTo(saved.getEventDetectedAt());
        assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(found.getExpiresAt()).isEqualTo(saved.getExpiresAt());
        assertThat(found.getCurrentStopExternalId()).isEqualTo("s1");
        assertThat(found.getCurrentStopOrder()).isEqualTo(10);
        assertThat(found.getProviderDataTime()).isEqualTo(BoardingSessionFixtures.UTC_NOW.minusSeconds(1));
        assertThat(entityManager.find(BoardingSession.class, session.getId()).getRevision()).isOne();
        assertThatThrownBy(() -> jdbc.update("""
                insert into notification_events (source_type, boarding_session_id, boarding_event_type, user_id,
                    observed_at, event_detected_at, created_at, expires_at)
                select source_type, boarding_session_id, boarding_event_type, user_id,
                    observed_at, event_detected_at, created_at, expires_at from notification_events where id=?
                """, found.getId())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_notification_events_boarding_identity");
        NotificationDelivery loaded = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(loaded.getExpiresAt()).isEqualTo(found.getExpiresAt());
        assertThat(loaded.getRecipientOwnershipGeneration()).isEqualTo(2);
        assertThat(loaded.getAttemptCount()).isZero();
        assertThat(loaded.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThatThrownBy(() -> jdbc.update("""
                insert into notification_deliveries (notification_event_id, device_id, recipient_ownership_generation,
                    status, next_attempt_at, expires_at, created_at, updated_at)
                select notification_event_id, device_id, recipient_ownership_generation,
                    status, next_attempt_at, expires_at, created_at, updated_at from notification_deliveries where id=?
                """, delivery.getId())).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_notification_deliveries_event_device");
        assertThat(events.findByAlarmIdAndActivationGenerationAndTrackingCycleIdAndEventType(
                session.getId(), 0, CYCLE.toString(), TransitEventType.ARRIVED)).isEmpty();
        assertThat(deliveries.findPendingByAlarmIdForUpdate(session.getId())).isEmpty();
    }

    @Test
    @DisplayName("recipient 없는 서로 다른 Session Event도 보존하며 Session ID는 live FK 없이 불변 reference로 저장한다")
    void preserve_no_recipient_events_and_source_references() {
        User user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
        entityManager.persist(user);
        NotificationEvent first = events.saveAndFlush(sessionEvent(Long.MAX_VALUE, user));
        NotificationEvent second = events.saveAndFlush(sessionEvent(Long.MAX_VALUE - 1, user));
        entityManager.clear();
        assertThat(events.findByBoardingSessionIdAndBoardingEventType(Long.MAX_VALUE,
                BoardingNotificationEventType.PREPARE_ALIGHT).orElseThrow().getId()).isEqualTo(first.getId());
        assertThat(events.findById(second.getId())).isPresent();
        assertThat(deliveries.findAllByNotificationEventIdOrderByIdAsc(first.getId())).isEmpty();
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.KEY_COLUMN_USAGE
                where table_schema=database() and table_name='notification_events'
                  and column_name='boarding_session_id' and referenced_table_name is not null
                """, Integer.class)).isZero();
    }

    @Test
    @DisplayName("Session source CHECK는 NULL UNKNOWN 우회와 혼합 identity 및 잘못된 type과 기한을 거부한다")
    void reject_invalid_session_field_combinations() {
        User user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
        entityManager.persist(user);
        NotificationEvent event = events.saveAndFlush(sessionEvent(123L, user));
        for (String assignment : List.of(
                "alarm_id=1", "activation_generation=0", "tracking_cycle_id='" + CYCLE + "'", "event_type='ARRIVED'",
                "alarm_id=1, activation_generation=0, tracking_cycle_id='" + CYCLE + "', event_type='ARRIVED'",
                "boarding_session_id=null", "boarding_session_id=0", "boarding_session_id=-1",
                "boarding_event_type=null", "boarding_event_type='ARRIVED'", "boarding_event_type='prepare_alight'",
                "expires_at=null", "expires_at=observed_at", "expires_at=observed_at - interval 1 microsecond",
                "boarding_session_id=null, boarding_event_type=null, expires_at=null", "source_type='ALARM'")) {
            assertThatThrownBy(() -> jdbc.update("update notification_events set " + assignment + " where id=?", event.getId()))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_notification_events_source_identity");
        }
        for (String assignment : List.of("source_type=null", "source_type='OTHER'", "source_type='alarm'", "user_id=null")) {
            assertThatThrownBy(() -> jdbc.update("update notification_events set " + assignment + " where id=?", event.getId()))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> jdbc.update("update notification_events set user_id=? where id=?", Long.MAX_VALUE, event.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_notification_events_user_id");
        assertThatThrownBy(() -> jdbc.update("""
                insert into notification_events (user_id, alarm_id, activation_generation, tracking_cycle_id, event_type,
                    observed_at, event_detected_at, created_at) values (?, 1, 0, ?, 'ARRIVED', ?, ?, ?)
                """, user.getId(), CYCLE.toString(), NOW, NOW, NOW)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("source_type");
    }

    @Test
    @DisplayName("V14의 Event와 Delivery를 매핑하고 두 identity UNIQUE 및 FK를 강제한다")
    void persist_identity_and_enforce_uniqueness() {
        Alarm alarm = alarm();
        NotificationEvent event = event(alarm, CYCLE, TransitEventType.PASSED);
        Device device = device(alarm.getUser());
        NotificationDelivery delivery = deliveries.saveAndFlush(new NotificationDelivery(event, device, 0, NOW.plusMinutes(1), NOW));
        entityManager.clear();
        NotificationEvent found = events.findByAlarmIdAndActivationGenerationAndTrackingCycleIdAndEventType(
                alarm.getId(), 1, CYCLE.toString(), TransitEventType.PASSED).orElseThrow();
        assertThat(found.getUser().getId()).isEqualTo(alarm.getUser().getId());
        assertThat(found.getTrackingCycleId()).isEqualTo(CYCLE);
        assertThat(found.getObservedAt()).isEqualTo(NOW);
        assertThat(found.getEventDetectedAt()).isEqualTo(NOW);
        assertThat(found.getCurrentStopName()).isEqualTo("최근 정류장");
        assertThat(found.getLatitude()).isEqualByComparingTo("37.1234567");
        assertThat(found.getProviderDataTime()).isEqualTo(NOW.minusSeconds(1));
        assertThat(found.getStopsPastTarget()).isEqualTo(2);
        NotificationDelivery loaded = deliveries.findAllByNotificationEventIdOrderByIdAsc(event.getId()).getFirst();
        assertThat(loaded.getDevice().getId()).isEqualTo(device.getId());
        assertThat(loaded.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(loaded.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(loaded.getAttemptCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name='notification_history'", Integer.class)).isZero();

        assertThatThrownBy(() -> copyEvent(event.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_notification_events_logical_identity");
        for (String assignment : List.of("tracking_cycle_id = 'b1234567-1234-4123-8123-123456789abc'", "event_type = 'ARRIVED'", "activation_generation = 2")) {
            NotificationEvent distinct = event(alarm, UUID.randomUUID(), TransitEventType.ARRIVED);
            jdbc.update("update notification_events set tracking_cycle_id = ?, event_type = 'PASSED', activation_generation = 1 where id = ?", UUID.randomUUID().toString(), distinct.getId());
            jdbc.update("update notification_events set tracking_cycle_id = ?, " + assignment + " where id = ?", CYCLE.toString(), distinct.getId());
        }
        assertThatThrownBy(() -> jdbc.update("insert into notification_deliveries (notification_event_id, device_id, recipient_ownership_generation, status, next_attempt_at, expires_at, created_at, updated_at) select notification_event_id, device_id, 0, 'PENDING', next_attempt_at, expires_at, created_at, updated_at from notification_deliveries where id = ?", delivery.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_notification_deliveries_event_device");
        assertThatThrownBy(() -> jdbc.update("update notification_events set user_id = ? where id = ?", Long.MAX_VALUE, event.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_notification_events_user_id");
        assertThatThrownBy(() -> jdbc.update("update notification_deliveries set device_id = ? where id = ?", Long.MAX_VALUE, delivery.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_notification_deliveries_device_id");
        assertThatThrownBy(() -> jdbc.update("update notification_deliveries set notification_event_id = ? where id = ?", Long.MAX_VALUE, delivery.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_notification_deliveries_event_id");
        assertThatThrownBy(() -> jdbc.update("delete from notification_events where id = ?", event.getId()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from devices where id = ?", device.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("Delivery CHECK가 nullable 조합 우회를 막고 만료된 PENDING도 due 순서대로 제한 조회한다")
    void enforce_nullable_checks_and_query_due_rows() {
        Alarm alarm = alarm();
        NotificationEvent event = event(alarm, CYCLE, TransitEventType.ARRIVED);
        Device device = device(alarm.getUser());
        NotificationDelivery delivery = deliveries.saveAndFlush(new NotificationDelivery(event, device, 0, NOW.plusSeconds(1), NOW));
        String[][] invalid = {
                {"status = 'RETRYING', next_attempt_at = null", "status"}, {"attempt_count = -1", "attempt"},
                {"recipient_ownership_generation = -1", "counts"}, {"next_attempt_at = null", "schedule"},
                {"next_attempt_at = expires_at + interval 1 second", "schedule"},
                {"attempt_count = 1, last_attempt_at = created_at", "attempt"},
                {"attempt_count = 1, last_attempt_registration_revision = 0", "attempt"},
                {"last_attempt_at = created_at", "attempt"},
                {"attempt_count = 1, last_attempt_at = created_at, last_attempt_registration_revision = -1", "attempt"},
                {"attempt_count = 1, last_attempt_at = created_at, last_attempt_registration_revision = 0, last_provider_result = 'UNKNOWN'", "provider_result"},
                {"status = 'ACCEPTED', next_attempt_at = null, attempt_count = 1, last_attempt_at = created_at, last_attempt_registration_revision = 0", "acceptance"},
                {"status = 'ACCEPTED', next_attempt_at = null, attempt_count = 1, last_attempt_at = created_at, last_attempt_registration_revision = 0, provider_accepted_at = created_at", "acceptance"},
                {"attempt_count = 1, last_attempt_at = created_at, last_attempt_registration_revision = 0, last_provider_result = 'ACCEPTED'", "acceptance"},
                {"provider_accepted_at = created_at", "acceptance"},
                {"status = 'FAILED', last_failure_code = 'DISPATCH_NOT_ALLOWED'", "schedule"},
                {"status = 'FAILED', next_attempt_at = null", "failure_code"},
                {"status = 'EXPIRED', next_attempt_at = null, last_failure_code = ''", "failure_code"}
        };
        for (String[] entry : invalid) {
            assertThatThrownBy(() -> jdbc.update("update notification_deliveries set " + entry[0] + " where id = ?", delivery.getId()))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_notification_deliveries_" + entry[1]);
        }
        for (String field : List.of("alarm_id", "activation_generation", "tracking_cycle_id", "event_type", "user_id", "observed_at", "event_detected_at", "created_at")) {
            assertThatThrownBy(() -> jdbc.update("update notification_events set " + field + " = null where id = ?", event.getId())).isInstanceOf(DataAccessException.class);
        }
        for (String assignment : List.of("activation_generation = -1", "tracking_cycle_id = upper(tracking_cycle_id)", "event_type = 'UNKNOWN'")) {
            assertThatThrownBy(() -> jdbc.update("update notification_events set " + assignment + " where id = ?", event.getId())).isInstanceOf(DataAccessException.class);
        }
        jdbc.update("update notification_deliveries set attempt_count = 99, last_attempt_at = created_at, last_attempt_registration_revision = 0 where id = ?", delivery.getId());
        NotificationEvent second = event(alarm, UUID.randomUUID(), TransitEventType.ARRIVED);
        NotificationDelivery tied = deliveries.saveAndFlush(new NotificationDelivery(second, device, 0, NOW.plusSeconds(2), NOW));
        NotificationEvent third = event(alarm, UUID.randomUUID(), TransitEventType.ARRIVED);
        NotificationDelivery expired = deliveries.saveAndFlush(new NotificationDelivery(third, device, 0, NOW.plusSeconds(1), NOW.plusSeconds(1)));
        assertThat(expired.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        assertThat(expired.getLastFailureCode()).isEqualTo("FRESHNESS_EXPIRED");
        assertThat(deliveries.findDuePendingIds(NOW.plusMinutes(10), PageRequest.of(0, 1))).containsExactly(delivery.getId());
        assertThat(deliveries.findDuePendingIds(NOW.plusMinutes(10), PageRequest.of(0, 10))).containsExactly(delivery.getId(), tied.getId());
        String plan = jdbc.queryForObject("explain format=json select id from notification_deliveries where status='PENDING' and next_attempt_at <= ? order by next_attempt_at, id limit 10", String.class, NOW.plusMinutes(10));
        assertThat(plan).contains("idx_notification_deliveries_pending_due").doesNotContain("\"using_filesort\": true");
        assertThat(jdbc.queryForList("select distinct INDEX_NAME from information_schema.STATISTICS where TABLE_SCHEMA=database() and TABLE_NAME='notification_deliveries'", String.class))
                .containsExactlyInAnyOrder("PRIMARY", "uk_notification_deliveries_event_device", "idx_notification_deliveries_device_id", "idx_notification_deliveries_pending_due");
    }

    @Test
    @DisplayName("FOLLOW_UP은 원래 UUID를 저장하고 종료 시 제거하며 DB도 runtime과 UUID 형식을 강제한다")
    void persist_follow_up_uuid_and_clear_runtime() {
        Alarm alarm = alarm();
        alarm.startFollowUp("vehicle-1", CYCLE, NOW, NOW.plusMinutes(5));
        entityManager.flush();
        entityManager.clear();
        Alarm found = entityManager.find(Alarm.class, alarm.getId());
        assertThat(found.getFollowUpTrackingCycleId()).isEqualTo(CYCLE);
        assertThatThrownBy(() -> jdbc.update("update alarms set follow_up_tracking_cycle_id = null where id = ?", alarm.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_alarms_lifecycle");
        assertThatThrownBy(() -> jdbc.update("update alarms set follow_up_tracking_cycle_id = upper(follow_up_tracking_cycle_id) where id = ?", alarm.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_alarms_follow_up_tracking_cycle_id");
        found.completeFollowUp();
        entityManager.flush();
        assertThat(jdbc.queryForObject("select follow_up_tracking_cycle_id from alarms where id = ?", String.class, alarm.getId())).isNull();
        assertThatThrownBy(() -> jdbc.update("update alarms set follow_up_tracking_cycle_id = ? where id = ?", CYCLE.toString(), alarm.getId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_alarms_lifecycle");
    }

    @Test
    @DisplayName("Alarm 삭제는 기록을 보존하고 PENDING만 종료하며 기존 attempt 및 terminal 상태를 유지한다")
    void delete_alarm_preserves_records_and_terminates_pending() {
        Alarm alarm = alarm();
        NotificationEvent event = event(alarm, CYCLE, TransitEventType.ARRIVED);
        NotificationDelivery pending = deliveries.saveAndFlush(new NotificationDelivery(event, device(alarm.getUser()), 0, NOW.plusMinutes(1), NOW));
        jdbc.update("update notification_deliveries set attempt_count=1, last_attempt_at=created_at, last_attempt_registration_revision=4, last_provider_result='RETRYABLE', last_failure_code='UNAVAILABLE' where id=?", pending.getId());
        for (String status : List.of("ACCEPTED", "FAILED", "EXPIRED")) {
            NotificationDelivery row = deliveries.saveAndFlush(new NotificationDelivery(event, device(alarm.getUser()), 0, NOW.plusMinutes(1), NOW));
            if (status.equals("ACCEPTED")) {
                jdbc.update("update notification_deliveries set status='ACCEPTED', next_attempt_at=null, attempt_count=1, last_attempt_at=created_at, last_attempt_registration_revision=3, last_provider_result='ACCEPTED', provider_accepted_at=created_at where id=?", row.getId());
            } else {
                jdbc.update("update notification_deliveries set status=?, next_attempt_at=null, last_failure_code='ORIGINAL_CODE' where id=?", status, row.getId());
            }
        }
        var before = jdbc.queryForList("select * from notification_deliveries where id <> ? order by id", pending.getId());
        entityManager.clear();
        alarmService.delete(alarm.getUser().getId(), alarm.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(Alarm.class, alarm.getId())).isNull();
        assertThat(events.findById(event.getId()).orElseThrow().getAlarmId()).isEqualTo(alarm.getId());
        NotificationDelivery stopped = deliveries.findById(pending.getId()).orElseThrow();
        assertThat(stopped.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(stopped.getNextAttemptAt()).isNull();
        assertThat(stopped.getLastFailureCode()).isEqualTo("DISPATCH_NOT_ALLOWED");
        assertThat(stopped.getAttemptCount()).isOne();
        assertThat(stopped.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(stopped.getLastAttemptRegistrationRevision()).isEqualTo(4);
        assertThat(stopped.getLastProviderResult()).isEqualTo(NotificationProviderResult.RETRYABLE);
        assertThat(jdbc.queryForList("select * from notification_deliveries where id <> ? order by id", pending.getId())).isEqualTo(before);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("PENDING 종료 후 Alarm 삭제가 DB에서 실패하면 Delivery 상태와 Alarm이 모두 rollback된다")
    void failed_delete_rolls_back_pending_termination() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Long[] ids = tx.execute(state -> {
            Alarm alarm = alarm();
            NotificationEvent event = event(alarm, CYCLE, TransitEventType.ARRIVED);
            NotificationDelivery delivery = deliveries.saveAndFlush(new NotificationDelivery(event, device(alarm.getUser()), 0, NOW.plusMinutes(1), NOW));
            return new Long[] {alarm.getUser().getId(), alarm.getId(), delivery.getId()};
        });
        jdbc.execute("create table task707_delete_blocker (alarm_id bigint primary key, foreign key (alarm_id) references alarms(id))");
        jdbc.update("insert into task707_delete_blocker values (?)", ids[1]);
        try {
            assertThatThrownBy(() -> alarmService.delete(ids[0], ids[1])).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("select count(*) from alarms where id=?", Integer.class, ids[1])).isOne();
            assertThat(jdbc.queryForMap("select status, last_failure_code, next_attempt_at from notification_deliveries where id=?", ids[2]))
                    .containsEntry("status", "PENDING").containsEntry("last_failure_code", null).containsEntry("next_attempt_at", NOW);
        } finally {
            jdbc.execute("drop table task707_delete_blocker");
            jdbc.update("delete from notification_deliveries where id=?", ids[2]);
            jdbc.update("delete from notification_events where alarm_id=?", ids[1]);
            alarmService.delete(ids[0], ids[1]);
            jdbc.update("delete from devices where user_id=?", ids[0]);
            jdbc.update("delete from users where id=?", ids[0]);
        }
    }

    private Alarm alarm() {
        User user = new User(AuthProvider.GOOGLE, UUID.randomUUID().toString());
        entityManager.persist(user);
        Alarm alarm = new Alarm(user, new BusAlarmTarget(TransitProvider.SEOUL_BUS, "route", "target", 3, "143", "목적지", null, null, null, null, new AdjacentStopSnapshot("after", 4)));
        alarm.activate();
        entityManager.persist(alarm);
        entityManager.flush();
        return alarm;
    }

    private Device device(User user) {
        Device device = new Device(user, UUID.randomUUID().toString(), "a".repeat(64), DevicePlatform.IOS, UUID.randomUUID().toString(), 0, NOW);
        entityManager.persist(device);
        entityManager.flush();
        return device;
    }

    private NotificationEvent event(Alarm alarm, UUID cycle, TransitEventType type) {
        TransitEvent transitEvent = new TransitEvent(type, "vehicle-1", cycle, NOW.toInstant(ZoneOffset.UTC), "latest-stop", "최근 정류장", 5,
                new BigDecimal("37.1234567"), new BigDecimal("127.1234567"), NOW.minusSeconds(1).toInstant(ZoneOffset.UTC), 2);
        return events.saveAndFlush(new NotificationEvent(alarm.getId(), alarm.getUser(), 1, transitEvent, NOW, NOW));
    }

    private void copyEvent(Long id) {
        jdbc.update("insert into notification_events (source_type, alarm_id, user_id, activation_generation, tracking_cycle_id, event_type, observed_at, event_detected_at, created_at) select source_type, alarm_id, user_id, activation_generation, tracking_cycle_id, event_type, observed_at, event_detected_at, created_at from notification_events where id = ?", id);
    }

    private NotificationEvent sessionEvent(Long sessionId, User user) {
        LocalDateTime observed = BoardingSessionFixtures.UTC_NOW;
        return NotificationEvent.forBoardingSession(sessionId, user, BoardingNotificationEventType.PREPARE_ALIGHT,
                BoardingSessionFixtures.verified(BoardingSessionFixtures.metadata()).checkpoint(),
                observed, observed, observed.plusSeconds(30));
    }
}
