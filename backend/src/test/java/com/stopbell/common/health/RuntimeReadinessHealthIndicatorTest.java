package com.stopbell.common.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import javax.sql.DataSource;

import com.stopbell.alarm.service.BusAlarmMonitoringScheduler;
import com.stopbell.notification.service.NotificationDispatchWorker;
import com.stopbell.transit.domain.TransitProvider;
import com.stopbell.transit.repository.BusMetadataSyncStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class RuntimeReadinessHealthIndicatorTest {
    @Test
    void readiness_requires_database_metadata_and_enabled_workers_without_remote_calls() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<DataSource> sources = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<BusAlarmMonitoringScheduler> monitoring = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<NotificationDispatchWorker> dispatch = mock(ObjectProvider.class);
        var metadata = mock(BusMetadataSyncStateRepository.class);
        var monitorScheduler = mock(ThreadPoolTaskScheduler.class);
        var dispatchScheduler = mock(ThreadPoolTaskScheduler.class);
        var monitorExecutor = mock(ScheduledThreadPoolExecutor.class);
        var dispatchExecutor = mock(ScheduledThreadPoolExecutor.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(sources.getIfAvailable()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(metadata.existsById(any())).thenReturn(true);
        when(monitoring.getIfAvailable()).thenReturn(mock(BusAlarmMonitoringScheduler.class));
        when(dispatch.getIfAvailable()).thenReturn(mock(NotificationDispatchWorker.class));
        when(monitorScheduler.getScheduledThreadPoolExecutor()).thenReturn(monitorExecutor);
        when(dispatchScheduler.getScheduledThreadPoolExecutor()).thenReturn(dispatchExecutor);
        var indicator = new RuntimeReadinessHealthIndicator(sources, metadata, monitorScheduler, dispatchScheduler,
                monitoring, dispatch, true, true);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        verify(metadata).existsById(TransitProvider.TAGO);
        verify(metadata).existsById(TransitProvider.SEOUL_BUS);
        when(metadata.existsById(TransitProvider.TAGO)).thenReturn(false);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        when(metadata.existsById(TransitProvider.TAGO)).thenReturn(true);
        when(dispatchExecutor.isShutdown()).thenReturn(true);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        when(dispatchExecutor.isShutdown()).thenReturn(false);
        when(connection.isValid(2)).thenReturn(false);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("secret-marker"));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).isEmpty();
    }

    @Test
    void disabled_workers_do_not_make_an_initialized_backend_unready() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<DataSource> sources = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<BusAlarmMonitoringScheduler> monitoring = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<NotificationDispatchWorker> dispatch = mock(ObjectProvider.class);
        var metadata = mock(BusMetadataSyncStateRepository.class);
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(sources.getIfAvailable()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        when(metadata.existsById(any())).thenReturn(true);
        var indicator = new RuntimeReadinessHealthIndicator(sources, metadata, mock(ThreadPoolTaskScheduler.class),
                mock(ThreadPoolTaskScheduler.class), monitoring, dispatch, false, false);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        verifyNoInteractions(monitoring, dispatch);
    }
}
