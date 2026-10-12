package com.stopbell.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
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
    @DisplayName("dispatch만 켜고 TASK-709 handler가 없으면 명시적인 구성 오류로 startup을 차단한다")
    void missing_handler_fails_closed() {
        context.withPropertyValues("notification.dispatch.enabled=true").run(app -> {
            assertThat(app).hasFailed();
            assertThat(app.getStartupFailure()).hasRootCauseMessage(
                    "notification.dispatch.enabled requires the TASK-709 durable attempt/result handler");
        });
    }

    @Test
    @DisplayName("명시적 활성화와 handler를 함께 제공해야 Worker가 생성되고 fixed-delay 설정을 사용한다")
    void enabled_requires_handler_and_binds_settings() throws Exception {
        context.withBean(NotificationAttemptHandler.class, () -> mock(NotificationAttemptHandler.class))
                .withUserConfiguration(SchedulingConfiguration.class)
                .withPropertyValues("notification.dispatch.enabled=true", "notification.dispatch.batch-size=3",
                        "notification.dispatch.fixed-delay=PT2S").run(app -> {
                    assertThat(app).hasNotFailed().hasSingleBean(NotificationDispatchWorker.class);
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
        for (String property : List.of("batch-size=0", "batch-size=-1", "fixed-delay=PT0S",
                "fixed-delay=-PT1S", "fixed-delay=PT0.000001S", "fixed-delay=PT9223372036854775807S")) {
            context.withPropertyValues("notification.dispatch." + property).run(app -> assertThat(app).hasFailed());
        }
    }
}
