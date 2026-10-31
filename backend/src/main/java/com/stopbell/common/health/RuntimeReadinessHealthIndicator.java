package com.stopbell.common.health;

import java.sql.Connection;
import javax.sql.DataSource;

import com.stopbell.alarm.service.BusAlarmMonitoringScheduler;
import com.stopbell.notification.service.NotificationDispatchWorker;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** Local dependencies only; never calls Transit or FCM. */
@Component("runtimeReadiness")
public class RuntimeReadinessHealthIndicator implements HealthIndicator {
    private final ObjectProvider<DataSource> dataSources;
    private final BusMetadataSyncStateRepository metadata;
    private final ThreadPoolTaskScheduler monitoringScheduler;
    private final ThreadPoolTaskScheduler dispatchScheduler;
    private final ObjectProvider<BusAlarmMonitoringScheduler> monitoring;
    private final ObjectProvider<NotificationDispatchWorker> dispatch;
    private final boolean monitoringEnabled;
    private final boolean dispatchEnabled;

    public RuntimeReadinessHealthIndicator(ObjectProvider<DataSource> dataSources,
            BusMetadataSyncStateRepository metadata,
            @Qualifier("transitMonitoringScheduler") ThreadPoolTaskScheduler monitoringScheduler,
            @Qualifier("notificationDispatchScheduler") ThreadPoolTaskScheduler dispatchScheduler,
            ObjectProvider<BusAlarmMonitoringScheduler> monitoring, ObjectProvider<NotificationDispatchWorker> dispatch,
            @Value("${transit.monitoring.enabled:false}") boolean monitoringEnabled,
            @Value("${notification.dispatch.enabled:false}") boolean dispatchEnabled) {
        this.dataSources = dataSources;
        this.metadata = metadata;
        this.monitoringScheduler = monitoringScheduler;
        this.dispatchScheduler = dispatchScheduler;
        this.monitoring = monitoring;
        this.dispatch = dispatch;
        this.monitoringEnabled = monitoringEnabled;
        this.dispatchEnabled = dispatchEnabled;
    }

    @Override
    public Health health() {
        try {
            DataSource dataSource = dataSources.getIfAvailable();
            if (dataSource == null) return Health.down().build();
            try (Connection connection = dataSource.getConnection()) {
                if (!connection.isValid(2)) return Health.down().build();
            }
            for (TransitProvider provider : TransitProvider.values()) {
                if (!metadata.existsById(provider)) return Health.down().build();
            }
            if (monitoringEnabled && (monitoring.getIfAvailable() == null || !running(monitoringScheduler))) {
                return Health.down().build();
            }
            if (dispatchEnabled && (dispatch.getIfAvailable() == null || !running(dispatchScheduler))) {
                return Health.down().build();
            }
            return Health.up().build();
        } catch (Exception ignored) {
            return Health.down().build();
        }
    }

    private boolean running(ThreadPoolTaskScheduler scheduler) {
        var executor = scheduler.getScheduledThreadPoolExecutor();
        return !executor.isShutdown() && !executor.isTerminated();
    }
}
