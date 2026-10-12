package com.stopbell.notification.service;

import java.time.Clock;

import com.stopbell.notification.client.FcmProviderClient;
import com.stopbell.notification.repository.DeviceRepository;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationDispatchProperties.class)
public class NotificationDispatchConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "notification.dispatch", name = "enabled", havingValue = "true")
    NotificationDispatchWorker notificationDispatchWorker(NotificationDispatchProperties properties,
            ObjectProvider<NotificationAttemptHandler> handlers, NotificationDeliveryRepository deliveries,
            DeviceRepository devices, PlatformTransactionManager manager, FcmProviderClient provider,
            @Qualifier("transitMetadataClock") Clock clock) {
        NotificationAttemptHandler handler = handlers.getIfAvailable();
        if (handler == null) {
            throw new IllegalStateException("notification.dispatch.enabled requires the TASK-709 durable attempt/result handler");
        }
        NotificationDispatchAttemptService attempts = new NotificationDispatchAttemptService(deliveries,
                new NotificationRecipientPreflight(devices), handler, manager, clock);
        return new NotificationDispatchWorker(properties, deliveries, attempts, provider, clock);
    }
}
