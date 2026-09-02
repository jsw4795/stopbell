package com.stopbell.user.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.TransitType;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.notification.entity.NotificationHistory;
import com.stopbell.notification.entity.NotificationStatus;
import com.stopbell.notification.repository.NotificationHistoryRepository;
import com.stopbell.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
class RepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4.11");

    @Autowired
    private UserRepository userRepository;

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

        assertThat(versions).contains("1", "2", "3");
    }

    @Test
    @DisplayName("User를 저장하면 ID와 생성 및 수정 시간이 생성되고 다시 조회할 수 있다")
    void save_user_and_find_by_id() {
        User savedUser = userRepository.saveAndFlush(new User());
        entityManager.clear();

        User foundUser = userRepository.findById(savedUser.getId()).orElseThrow();

        assertThat(foundUser.getId()).isNotNull();
        assertThat(foundUser.getCreatedAt()).isNotNull();
        assertThat(foundUser.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Alarm을 저장하면 User 관계와 TransitType 및 상태가 유지된다")
    void save_alarm_with_user_relation() {
        User user = userRepository.saveAndFlush(new User());
        Alarm savedAlarm = alarmRepository.saveAndFlush(new Alarm(user, TransitType.SUBWAY));
        entityManager.clear();

        Alarm foundAlarm = alarmRepository.findById(savedAlarm.getId()).orElseThrow();

        assertThat(foundAlarm.getUser().getId()).isEqualTo(user.getId());
        assertThat(foundAlarm.getTransitType()).isEqualTo(TransitType.SUBWAY);
        assertThat(foundAlarm.isActive()).isFalse();
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

        assertThat(updatedAlarm.isActive()).isTrue();
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

        assertThat(reloadedAlarm.isActive()).isTrue();
        assertThat(reloadedAlarm.getUpdatedAt()).isEqualTo(originalUpdatedAt);
    }

    private Alarm saveAlarm() {
        User user = userRepository.saveAndFlush(new User());
        return alarmRepository.saveAndFlush(new Alarm(user, TransitType.BUS));
    }
}
