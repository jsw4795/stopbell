package com.stopbell.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.stopbell.alarm.service.*;
import com.stopbell.boarding.service.BoardingSessionPollingService;
import com.stopbell.notification.client.FcmProviderClient;
import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.NotificationProviderResult;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.notification.service.*;
import com.stopbell.transit.client.*;
import com.stopbell.transit.domain.TransitEventType;
import com.stopbell.transit.dto.tago.TagoVehicleLocationResponse;
import com.stopbell.transit.mapper.SeoulBusTransitObservationMapper;
import com.stopbell.transit.mapper.TagoTransitObservationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;

class SchedulingConfigurationTest {
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final Duration DELAY = Duration.ofMillis(60);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Provider나 FCM 한쪽이 대기해도 다른 Scheduler는 별도 스레드에서 여러 cycle을 실행한다")
    void slow_io_does_not_block_other_scheduler(boolean slowMonitoring) {
        var fixture = new Fixture();
        var blocked = slowMonitoring ? fixture.monitoring : fixture.dispatch;
        var independent = slowMonitoring ? fixture.dispatch : fixture.monitoring;
        blocked.block = true;
        fixture.context().run(app -> {
            await(blocked.entered);
            await(independent.threeCalls);
            assertThat(blocked.calls.get()).isOne();
            assertThat(blocked.maxActive.get()).isOne();
            assertThat(independent.maxActive.get()).isOne();
            assertThat(fixture.monitoring.thread.get()).startsWith("transit-monitoring-");
            assertThat(fixture.dispatch.thread.get()).startsWith("notification-dispatch-");
            assertThat(fixture.monitoring.thread.get()).isNotEqualTo(fixture.dispatch.thread.get());
            blocked.release.countDown();
        });
    }

    @Test
    @DisplayName("두 실제 Scheduled 작업은 대기 중 재진입하지 않고 완료 후 설정된 fixed-delay를 지킨다")
    void scheduled_tasks_remain_serial_with_fixed_delay() throws Exception {
        var fixture = new Fixture();
        fixture.monitoring.block = true;
        fixture.dispatch.block = true;
        fixture.context().run(app -> {
            await(fixture.monitoring.entered);
            await(fixture.dispatch.entered);
            assertThat(fixture.monitoring.secondCall.await(180, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(fixture.dispatch.secondCall.await(180, TimeUnit.MILLISECONDS)).isFalse();
            var monitoringScheduler = app.getBean("transitMonitoringScheduler", ThreadPoolTaskScheduler.class);
            var dispatchScheduler = app.getBean("notificationDispatchScheduler", ThreadPoolTaskScheduler.class);
            assertThat(monitoringScheduler.getScheduledThreadPoolExecutor())
                    .isNotSameAs(dispatchScheduler.getScheduledThreadPoolExecutor());
            for (var scheduler : List.of(monitoringScheduler, dispatchScheduler)) {
                assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isOne();
            }
            var tasks = app.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
            assertThat(tasks).hasSize(2).allSatisfy(task -> {
                assertThat(task.getTask()).isInstanceOf(FixedDelayTask.class);
                assertThat(((FixedDelayTask) task.getTask()).getIntervalDuration()).isEqualTo(DELAY);
            });
            fixture.monitoring.release.countDown();
            fixture.dispatch.release.countDown();
            await(fixture.monitoring.secondCall);
            await(fixture.dispatch.secondCall);
            for (var probe : List.of(fixture.monitoring, fixture.dispatch)) {
                assertThat(probe.maxActive.get()).isOne();
                assertThat(probe.secondStarted.get() - probe.firstFinished.get()).isGreaterThanOrEqualTo(DELAY.toNanos());
            }
        });
        Scheduled monitoring = BusAlarmMonitoringScheduler.class.getMethod("pollMonitoringAlarms").getAnnotation(Scheduled.class);
        Scheduled dispatch = NotificationDispatchWorker.class.getMethod("dispatchPending").getAnnotation(Scheduled.class);
        assertThat(monitoring.fixedDelayString()).isEqualTo("${transit.monitoring.fixed-delay:PT20S}");
        assertThat(dispatch.fixedDelayString()).isEqualTo("${notification.dispatch.fixed-delay:PT1S}");
    }

    @Test
    @DisplayName("기본 및 명시적 비활성 설정은 Monitoring과 Dispatch를 등록하거나 실행하지 않는다")
    void disabled_settings_register_no_tasks() {
        for (var properties : List.of(new String[0], new String[]{"transit.monitoring.enabled=false",
                "notification.dispatch.enabled=false"})) {
            new ApplicationContextRunner().withUserConfiguration(SchedulingConfiguration.class,
                    BusAlarmMonitoringScheduler.class, NotificationDispatchConfiguration.class)
                    .withPropertyValues(properties).run(app -> {
                        assertThat(app).hasNotFailed().doesNotHaveBean(BusAlarmMonitoringScheduler.class)
                                .doesNotHaveBean(NotificationDispatchWorker.class);
                        assertThat(app.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).isEmpty();
                        for (var scheduler : app.getBeansOfType(ThreadPoolTaskScheduler.class).values()) {
                            assertThat(scheduler.getScheduledThreadPoolExecutor().getPoolSize()).isZero();
                        }
                    });
        }
    }

    @Test
    @DisplayName("Context 종료는 새 cycle을 차단하고 실행 중 Provider와 FCM 결과 처리가 끝난 뒤 의존 Bean을 종료한다")
    void shutdown_drains_active_io_before_destroying_dependencies() {
        var fixture = new Fixture();
        fixture.monitoring.block = true;
        fixture.dispatch.block = true;
        var closed = new CountDownLatch(1);
        var destroyed = new CountDownLatch(1);
        fixture.context().withInitializer(context -> context.addApplicationListener(event -> {
            if (event instanceof ContextClosedEvent) {
                closed.countDown();
            }
        })).withBean("dependency", DisposableBean.class, () -> destroyed::countDown).run(app -> {
            await(fixture.monitoring.entered);
            await(fixture.dispatch.entered);
            var monitoringScheduler = app.getBean("transitMonitoringScheduler", ThreadPoolTaskScheduler.class);
            var dispatchScheduler = app.getBean("notificationDispatchScheduler", ThreadPoolTaskScheduler.class);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var closing = executor.submit(() -> app.getSourceApplicationContext().close());
                try {
                    await(closed);
                    assertThat(destroyed.await(180, TimeUnit.MILLISECONDS)).isFalse();
                    assertThat(closing.isDone()).isFalse();
                } finally {
                    fixture.monitoring.release.countDown();
                    fixture.dispatch.release.countDown();
                }
                closing.get(5, TimeUnit.SECONDS);
            }
            assertThat(destroyed.getCount()).isZero();
            verify(fixture.attempts).completeAttempt(any(), any());
            assertThat(fixture.monitoring.calls.get()).isOne();
            assertThat(fixture.dispatch.calls.get()).isOne();
            for (var scheduler : List.of(monitoringScheduler, dispatchScheduler)) {
                assertThat(scheduler.getScheduledThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
            fixture.monitor.pollMonitoringAlarms();
            fixture.worker.dispatchPending();
            assertThat(fixture.monitoring.calls.get()).isOne();
            assertThat(fixture.dispatch.calls.get()).isOne();
        });
    }

    private static class Fixture {
        final Probe monitoring = new Probe();
        final Probe dispatch = new Probe();
        final NotificationDispatchAttemptService attempts = mock(NotificationDispatchAttemptService.class);
        final BusAlarmMonitoringScheduler monitor;
        final NotificationDispatchWorker worker;

        Fixture() {
            var clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var polling = mock(BusAlarmPollingService.class);
            when(polling.findMonitoringGroups()).thenReturn(List.of());
            var boarding = mock(BoardingSessionPollingService.class);
            // Exercise Provider I/O through the same cycle passed to BoardingSession polling.
            when(boarding.poll(any())).thenAnswer(call -> {
                com.stopbell.transit.service.VehiclePollingCycle cycle = call.getArgument(0);
                cycle.tago("fixture-route", "31010");
                return List.of();
            });
            var tago = mock(TagoVehicleLocationClient.class);
            when(tago.fetchVehicleLocations(any())).thenAnswer(call -> {
                monitoring.call();
                return new TagoVehicleLocationResponse(new TagoVehicleLocationResponse.Response(
                        new TagoVehicleLocationResponse.Header("00", "OK"),
                        new TagoVehicleLocationResponse.Body(null, 0, 1, 1000)));
            });
            monitor = new BusAlarmMonitoringScheduler(polling, mock(BusAlarmEvaluator.class),
                    mock(BusAlarmLifecycleService.class), tago, mock(SeoulBusVehicleLocationClient.class),
                    mock(SeoulBusVehicleDetailClient.class), new TagoTransitObservationMapper(clock),
                    new SeoulBusTransitObservationMapper(clock), clock, Duration.ZERO, boarding);
            var deliveries = mock(NotificationDeliveryRepository.class);
            when(deliveries.findDuePendingIds(any(), any())).thenReturn(List.of(1L));
            var snapshot = new NotificationAttemptSnapshot(new NotificationDispatchTarget(1, 2, 3,
                    TransitEventType.ARRIVED, 4, 5, 0, 0, "fixture-fid",
                    LocalDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC)), 1,
                    LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            when(attempts.beginAttempt(1)).thenReturn(Optional.of(snapshot));
            var provider = mock(FcmProviderClient.class);
            when(provider.send(any())).thenAnswer(call -> {
                dispatch.call();
                return new NotificationSendResult(NotificationProviderResult.ACCEPTED, null, null, NOW, 1, true);
            });
            worker = new NotificationDispatchWorker(new NotificationDispatchProperties(true, 1, DELAY),
                    deliveries, attempts, provider, clock);
        }

        ApplicationContextRunner context() {
            return new ApplicationContextRunner().withUserConfiguration(SchedulingConfiguration.class)
                    .withPropertyValues("transit.monitoring.enabled=true", "transit.monitoring.fixed-delay=" + DELAY,
                            "notification.dispatch.fixed-delay=" + DELAY)
                    .withBean(BusAlarmMonitoringScheduler.class, () -> monitor)
                    .withBean(NotificationDispatchWorker.class, () -> worker);
        }
    }

    private static class Probe {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch secondCall = new CountDownLatch(1);
        final CountDownLatch threeCalls = new CountDownLatch(3);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final AtomicReference<String> thread = new AtomicReference<>();
        final AtomicLong firstFinished = new AtomicLong();
        final AtomicLong secondStarted = new AtomicLong();
        boolean block;

        void call() {
            int invocation = calls.incrementAndGet();
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            thread.set(Thread.currentThread().getName());
            entered.countDown();
            try {
                if (invocation == 1 && block) {
                    await(release);
                }
                if (invocation == 2) {
                    secondStarted.set(System.nanoTime());
                    secondCall.countDown();
                }
                threeCalls.countDown();
            } finally {
                if (invocation == 1) {
                    firstFinished.set(System.nanoTime());
                }
                active.decrementAndGet();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
