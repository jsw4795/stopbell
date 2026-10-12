package com.stopbell.notification.client;

import java.time.Duration;
import java.time.Instant;

import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.notification.entity.Device;
import com.stopbell.notification.entity.DevicePlatform;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static com.stopbell.notification.entity.NotificationProviderResult.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FcmSmokeRunnerTest {
    private final FcmProviderClient provider = mock(FcmProviderClient.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final AlarmRepository alarms = mock(AlarmRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final Environment environment = mock(Environment.class);
    private final ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    private final FcmSmokeRunner runner = new FcmSmokeRunner(provider, devices, alarms, transactions, environment, context);

    @Test
    @DisplayName("Firebase 설정과 credential 없이 Provider context가 정상 초기화된다")
    void starts_context_without_firebase_configuration() {
        new ApplicationContextRunner().withUserConfiguration(FcmClientConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FcmProviderClient.class);
                });
    }

    @Test
    @DisplayName("smoke는 명시적 CLI 없이 실행되거나 servlet 환경이면 전송하지 않는다")
    void requires_explicit_non_web_cli() {
        runner.run(new DefaultApplicationArguments());
        runner.run(new DefaultApplicationArguments("--notification.fcm.smoke=true",
                "--spring.main.web-application-type=servlet"));
        verifyNoInteractions(provider, devices, alarms, transactions);
    }

    @Test
    @DisplayName("smoke는 기존 Device와 동일 owner의 Alarm만 조회하고 transaction 종료 뒤 한 번 전송한다")
    void smoke_sends_once_after_read_transaction() {
        var device = mock(Device.class);
        var alarm = mock(Alarm.class);
        var user = mock(User.class);
        when(user.getId()).thenReturn(7L);
        when(device.getUser()).thenReturn(user);
        when(alarm.getUser()).thenReturn(user);
        when(device.getPlatform()).thenReturn(DevicePlatform.IOS);
        when(device.isEnabled()).thenReturn(true);
        when(device.getCurrentPushTargetId()).thenReturn("synthetic-target");
        when(devices.findById(10L)).thenReturn(java.util.Optional.of(device));
        when(alarms.findById(20L)).thenReturn(java.util.Optional.of(alarm));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(environment.getProperty("transit.monitoring.enabled", Boolean.class, false)).thenReturn(false);
        when(provider.send(any())).thenReturn(new NotificationSendResult(ACCEPTED, null, Duration.ZERO, Instant.now(), 1, true));
        runner.run(arguments());
        var order = inOrder(transactions, devices, alarms, provider);
        order.verify(transactions).getTransaction(any());
        order.verify(devices).findById(10L);
        order.verify(alarms).findById(20L);
        order.verify(transactions).commit(any());
        var request = ArgumentCaptor.forClass(NotificationSendRequest.class);
        order.verify(provider).send(request.capture());
        assertThat(request.getValue().fid()).isEqualTo("synthetic-target");
        assertThat(request.getValue().alarmId()).isEqualTo(20);
        assertThat(request.getValue().notificationEventId()).isEqualTo(1);
        verifyNoMoreInteractions(provider, devices, alarms);
        verify(context).close();
    }

    @Test
    @DisplayName("owner가 다른 Alarm이나 비활성 Device를 선택하면 smoke 전송을 거부한다")
    void rejects_ineligible_device() {
        when(environment.getProperty("transit.monitoring.enabled", Boolean.class, false)).thenReturn(false);
        var device = mock(Device.class);
        var alarm = mock(Alarm.class);
        when(devices.findById(10L)).thenReturn(java.util.Optional.of(device));
        when(alarms.findById(20L)).thenReturn(java.util.Optional.of(alarm));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        runner.run(arguments());
        verifyNoInteractions(provider);
        verify(transactions).rollback(any());
    }

    private DefaultApplicationArguments arguments() {
        return new DefaultApplicationArguments("--notification.fcm.smoke=true", "--spring.main.web-application-type=none",
                "--notification.fcm.smoke-device-id=10", "--notification.fcm.smoke-alarm-id=20");
    }
}
