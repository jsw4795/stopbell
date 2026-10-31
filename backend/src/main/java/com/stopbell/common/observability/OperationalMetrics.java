package com.stopbell.common.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.NotificationDeliveryStatus;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.transit.client.TransitProviderClientFailureKind;
import com.stopbell.transit.domain.TransitProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded operational measurements; never accepts request identifiers as tags. */
@Component
public class OperationalMetrics {
    private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);
    public enum Operation { TAGO_LOCATION, SEOUL_ROSTER, SEOUL_DETAIL }
    public enum Evaluation { EVENT, NO_EVENT, FOLLOW_UP_EXPIRED }
    public enum CycleOutcome { COMPLETED, INTERRUPTED, FAILED }
    public enum DeliveryTransition { ACCEPTED, FAILED, EXPIRED, RETRY_SCHEDULED }
    private final MeterRegistry registry;
    private final Clock clock;
    private final AtomicLong lastMonitoringCompletion = new AtomicLong();

    public OperationalMetrics(MeterRegistry registry, NotificationDeliveryRepository deliveries,
            AlarmRepository alarms, @Qualifier("transitMetadataClock") Clock clock) {
        this.registry = registry;
        this.clock = clock;
        Gauge.builder("stopbell.notification.pending", deliveries,
                repository -> safe(() -> repository.countByStatus(NotificationDeliveryStatus.PENDING)))
                .register(registry);
        Gauge.builder("stopbell.notification.pending.oldest.age", deliveries, repository -> safe(() -> {
            LocalDateTime oldest = repository.findOldestPendingCreatedAt();
            return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(ZoneOffset.UTC), clock.instant()).toMillis() / 1000.0);
        })).baseUnit("seconds").register(registry);
        for (AlarmStatus status : new AlarmStatus[] {AlarmStatus.ACTIVE, AlarmStatus.FOLLOW_UP}) {
            Gauge.builder("stopbell.alarm.count", alarms, repository -> safe(() -> repository.countByStatus(status)))
                    .tag("status", status.name()).register(registry);
        }
        Gauge.builder("stopbell.monitoring.last.completion", lastMonitoringCompletion, AtomicLong::get)
                .baseUnit("seconds").register(registry);
    }

    private static double safe(java.util.function.DoubleSupplier query) {
        try {
            return query.getAsDouble();
        } catch (RuntimeException ignored) {
            // An unavailable database is unknown, never a fabricated empty queue.
            return Double.NaN;
        }
    }

    public void provider(TransitProvider provider, Operation operation, TransitProviderClientFailureKind failure,
            long elapsedNanos) {
        providerOutcome(provider, operation, failure == null ? "SUCCESS" : failure.name(), elapsedNanos);
    }

    public void unexpectedProviderFailure(TransitProvider provider, Operation operation, long elapsedNanos) {
        providerOutcome(provider, operation, "UNEXPECTED", elapsedNanos);
    }

    private void providerOutcome(TransitProvider provider, Operation operation, String outcome, long elapsedNanos) {
        log.debug("event=transit_request provider={} operation={} outcome={} elapsedMs={}", provider, operation,
                outcome, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        registry.timer("stopbell.transit.request", "provider", provider.name(), "operation", operation.name(),
                "outcome", outcome).record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void evaluation(Evaluation result) {
        log.debug("event=alarm_evaluation outcome={}", result);
        registry.counter("stopbell.monitoring.evaluation", "outcome", result.name()).increment();
    }

    public void monitoringCycle(long elapsedNanos, CycleOutcome outcome) {
        log.debug("event=monitoring_cycle outcome={} elapsedMs={}", outcome,
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        registry.timer("stopbell.monitoring.cycle", "outcome", outcome.name())
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
        if (outcome == CycleOutcome.COMPLETED) lastMonitoringCompletion.set(clock.instant().getEpochSecond());
    }

    public void notification(NotificationSendResult result) {
        log.info("event=notification_provider result={} requestStarted={} elapsedMs={}", result.result(),
                result.fcmRequestStarted(), result.elapsedMs());
        registry.timer("stopbell.notification.provider", "result", result.result().name(),
                "request_started", Boolean.toString(result.fcmRequestStarted()))
                .record(Math.max(0, result.elapsedMs()), TimeUnit.MILLISECONDS);
    }

    public void delivery(DeliveryTransition transition) {
        registry.counter("stopbell.notification.delivery.transition", "outcome", transition.name()).increment();
    }
}
