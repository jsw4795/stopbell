package com.stopbell.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.NotificationDeliveryStatus;
import com.stopbell.notification.entity.NotificationProviderResult;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.transit.client.TransitProviderClientFailureKind;
import com.stopbell.transit.domain.TransitProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class OperationalMetricsTest {
    @Test
    void gauges_and_bounded_outcomes_distinguish_unknown_from_empty() {
        var registry = new SimpleMeterRegistry();
        var deliveries = mock(NotificationDeliveryRepository.class);
        var alarms = mock(AlarmRepository.class);
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:02:00Z"), ZoneOffset.UTC);
        when(deliveries.countByStatus(NotificationDeliveryStatus.PENDING)).thenReturn(3L);
        when(deliveries.findOldestPendingCreatedAt()).thenReturn(LocalDateTime.parse("2026-01-01T00:00:00"));
        when(alarms.countByStatus(AlarmStatus.ACTIVE)).thenReturn(2L);
        var metrics = new OperationalMetrics(registry, deliveries, alarms, clock);
        metrics.provider(TransitProvider.TAGO, OperationalMetrics.Operation.TAGO_LOCATION,
                TransitProviderClientFailureKind.HTTP, 1000);
        metrics.evaluation(OperationalMetrics.Evaluation.EVENT);
        metrics.monitoringCycle(2000, OperationalMetrics.CycleOutcome.COMPLETED);
        metrics.notification(new NotificationSendResult(NotificationProviderResult.ACCEPTED, "secret-marker", null,
                clock.instant(), 10, true));
        metrics.delivery(OperationalMetrics.DeliveryTransition.RETRY_SCHEDULED);
        assertThat(registry.get("stopbell.notification.pending").gauge().value()).isEqualTo(3);
        assertThat(registry.get("stopbell.notification.pending.oldest.age").gauge().value()).isEqualTo(120);
        assertThat(registry.get("stopbell.alarm.count").tag("status", "ACTIVE").gauge().value()).isEqualTo(2);
        assertThat(registry.get("stopbell.transit.request").tag("outcome", "HTTP").timer().count()).isEqualTo(1);
        assertThat(registry.get("stopbell.monitoring.evaluation").counter().count()).isEqualTo(1);
        assertThat(registry.get("stopbell.monitoring.last.completion").gauge().value()).isEqualTo(clock.instant().getEpochSecond());
        assertThat(registry.get("stopbell.notification.provider").timer().count()).isEqualTo(1);
        assertThat(registry.get("stopbell.notification.delivery.transition").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .allSatisfy(tag -> {
                    assertThat(tag.getKey()).isIn("provider", "operation", "outcome", "status", "result", "request_started");
                    assertThat(tag.getValue()).doesNotContain("secret-marker");
                }));
        when(deliveries.countByStatus(NotificationDeliveryStatus.PENDING)).thenThrow(new IllegalStateException());
        assertThat(registry.get("stopbell.notification.pending").gauge().value()).isNaN();
        when(deliveries.findOldestPendingCreatedAt()).thenReturn(null);
        assertThat(registry.get("stopbell.notification.pending.oldest.age").gauge().value()).isZero();
    }
}
