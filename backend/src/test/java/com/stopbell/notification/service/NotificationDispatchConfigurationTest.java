package com.stopbell.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.boarding.service.BoardingTrackingRuntime;
import java.time.Duration;
import java.util.List;

import com.stopbell.notification.client.FcmProviderClient;
import com.stopbell.common.config.SchedulingConfiguration;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.transaction.PlatformTransactionManager;

class NotificationDispatchConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(NotificationDispatchConfiguration.class)
            .withBean(NotificationDeliveryRepository.class, () -> mock(NotificationDeliveryRepository.class))
            .withBean(BoardingSessionRepository.class, () -> mock(BoardingSessionRepository.class))
            .withBean(BoardingTrackingRuntime.class, BoardingTrackingRuntime::new)
            .withBean(DeviceRepository.class, () -> mock(DeviceRepository.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(FcmProviderClient.class, () -> mock(FcmProviderClient.class))
            .withBean("transitMetadataClock", Clock.class, Clock::systemUTC);

    @Test
    @DisplayName("설정 누락이나 false이면 FCM enabled와 무관하게 Worker가 생성되지 않는다")
    void disabled_by_default_independent_of_fcm() {
        for (String enabled : List.of("", "notification.dispatch.enabled=false")) {
            ApplicationContextRunner runner = context.withPropertyValues("notification.fcm.enabled=true");
            if (!enabled.isEmpty()) {
                runner = runner.withPropertyValues(enabled);
            }
            runner.run(app -> {
                assertThat(app).hasNotFailed().doesNotHaveBean(NotificationDispatchWorker.class);
                assertThat(app.getBean(NotificationDispatchProperties.class).enabled()).isFalse();
                verifyNoInteractions(app.getBean(FcmProviderClient.class));
            });
        }
    }

    @Test
    @DisplayName("dispatch를 켜도 FCM 정적 설정이 없으면 startup을 차단한다")
    void missing_fcm_settings_fail_closed() {
        context.withPropertyValues("notification.dispatch.enabled=true").run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).hasRootCauseMessage(
                    "notification.dispatch.enabled requires valid static FCM settings");
        });
    }

    @Test
    @DisplayName("안전한 정적 FCM 설정이면 production handler와 fixed-delay Worker를 연결한다")
    void enabled_requires_handler_and_binds_settings() throws Exception {
        context.withPropertyValues("notification.fcm.enabled=true", "notification.fcm.project-id=test-project",
                        "notification.fcm.credential-path=/synthetic/never-read.json",
                        "notification.fcm.connect-timeout=PT2S", "notification.fcm.read-timeout=PT5S",
                        "notification.fcm.request-budget=PT15S")
                .withUserConfiguration(SchedulingConfiguration.class)
                .withPropertyValues("notification.dispatch.enabled=true", "notification.dispatch.batch-size=3",
                        "notification.dispatch.fixed-delay=PT2S").run(app -> {
                    assertThat(app).hasNotFailed().hasSingleBean(NotificationDispatchWorker.class);
                    assertThat(app.getBean(NotificationAttemptHandler.class))
                            .isInstanceOf(DurableNotificationAttemptHandler.class);
                    assertThat(app.getBean(NotificationDispatchProperties.class).batchSize()).isEqualTo(3);
                    assertThat(app.getBean(NotificationDispatchProperties.class).fixedDelay()).isEqualTo(Duration.ofSeconds(2));
                    var tasks = app.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    assertThat(tasks.iterator().next().getTask()).isInstanceOf(FixedDelayTask.class);
                    assertThat(((FixedDelayTask) tasks.iterator().next().getTask()).getIntervalDuration())
                            .isEqualTo(Duration.ofSeconds(2));
                    verifyNoInteractions(app.getBean(FcmProviderClient.class));
                });
        Scheduled scheduled = NotificationDispatchWorker.class.getMethod("dispatchPending").getAnnotation(Scheduled.class);
        assertThat(scheduled.fixedDelayString()).isEqualTo("${notification.dispatch.fixed-delay:PT1S}");
        assertThat(scheduled.fixedRateString()).isEmpty();
    }

    @Test
    @DisplayName("0과 음수 batch 및 millisecond 미만이나 overflow delay를 구성 단계에서 거부한다")
    void invalid_bounds_fail_configuration() {
        for (String property : List.of("batch-size=0", "batch-size=-1", "batch-size=1001", "fixed-delay=PT61S", "fixed-delay=PT0S",
                "fixed-delay=-PT1S", "fixed-delay=PT0.000001S", "fixed-delay=PT9223372036854775807S")) {
            context.withPropertyValues("notification.dispatch." + property).run(app -> assertThat(app).hasFailed());
        }
    }

    @Test
    @DisplayName("누락 또는 잘못된 FCM project와 credential 및 timeout은 실전송 전에 fail-closed 차단한다")
    void unsafe_fcm_settings_fail_closed() {
        for (String invalid : List.of("enabled=false", "project-id=", "project-id=INVALID PROJECT",
                "credential-path=", "connect-timeout=PT0S", "read-timeout=-PT1S", "request-budget=PT6M")) {
            context.withPropertyValues("notification.dispatch.enabled=true", "notification.fcm.enabled=true",
                    "notification.fcm.project-id=test-project", "notification.fcm.credential-path=/synthetic/never-read.json",
                    "notification.fcm.connect-timeout=PT2S", "notification.fcm.read-timeout=PT5S",
                    "notification.fcm.request-budget=PT15S", "notification.fcm." + invalid)
                    .run(app -> assertThat(app).hasFailed());
        }
    }

    @Test
    @DisplayName("retry budget과 delay 및 jitter의 잘못된 설정은 기본 비활성 상태에서도 거부한다")
    void invalid_retry_settings_fail_configuration() {
        for (String invalid : List.of("max-attempts=0", "max-attempts=11", "base-retry-delay=PT9S",
                "max-retry-delay=PT9S", "bounded-jitter=-PT1S", "bounded-jitter=PT61S",
                "recovery-delay=PT9S", "recovery-delay=PT6M")) {
            context.withPropertyValues("notification.dispatch.retry." + invalid)
                    .run(app -> assertThat(app).hasFailed());
        }
    }
}
