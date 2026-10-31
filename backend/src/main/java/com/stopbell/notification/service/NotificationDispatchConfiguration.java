package com.stopbell.notification.service;

import java.time.Clock;

import com.stopbell.boarding.repository.BoardingSessionRepository;
import com.stopbell.common.observability.OperationalMetrics;
import com.stopbell.boarding.service.BoardingTrackingRuntime;
import com.stopbell.notification.client.FcmProviderClient;
import com.stopbell.notification.client.FcmClientProperties;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotificationDispatchProperties.class, NotificationRetryProperties.class,
        FcmClientProperties.class})
public class NotificationDispatchConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "notification.dispatch", name = "enabled", havingValue = "true")
    NotificationRetryPolicy notificationRetryPolicy(NotificationRetryProperties retry, FcmClientProperties fcm) {
        if (!fcm.configured()) {
            throw new IllegalStateException("notification.dispatch.enabled requires valid static FCM settings");
        }
        return new NotificationRetryPolicy(retry, fcm);
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.dispatch", name = "enabled", havingValue = "true")
    NotificationAttemptHandler notificationAttemptHandler(NotificationRetryPolicy retry, DeviceRepository devices,
            @Qualifier("transitMetadataClock") Clock clock, OperationalMetrics metrics) {
        DurableNotificationAttemptHandler handler = new DurableNotificationAttemptHandler(retry, devices, clock);
        handler.setOperationalMetrics(metrics);
        return handler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "notification.dispatch", name = "enabled", havingValue = "true")
    NotificationDispatchWorker notificationDispatchWorker(NotificationDispatchProperties properties,
            NotificationAttemptHandler handler, NotificationDeliveryRepository deliveries,
            DeviceRepository devices, PlatformTransactionManager manager, FcmProviderClient provider,
            BoardingSessionRepository sessions, BoardingTrackingRuntime runtime,
            @Qualifier("transitMetadataClock") Clock clock, OperationalMetrics metrics) {
        NotificationDispatchAttemptService attempts = new NotificationDispatchAttemptService(deliveries,
                new NotificationRecipientPreflight(devices), handler, manager, clock, sessions, runtime);
        attempts.setOperationalMetrics(metrics);
        NotificationDispatchWorker worker = new NotificationDispatchWorker(properties, deliveries, attempts, provider, clock, runtime);
        worker.setOperationalMetrics(metrics);
        return worker;
    }
}
