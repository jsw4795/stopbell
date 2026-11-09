package com.stopbell.alarm.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.stopbell.StopBellApplication;
import com.stopbell.alarm.entity.Alarm;
import com.stopbell.alarm.entity.AlarmStatus;
import com.stopbell.alarm.repository.AlarmRepository;
import com.stopbell.transit.domain.ArrivalEvidence;
import com.stopbell.transit.domain.TransitObservation;
import com.stopbell.transit.domain.TransitProvider;
import org.springframework.boot.SpringApplication;

/** Child JVM using the real application and an isolated Testcontainers database. */
public class RestartProcessProbe {
    public static void main(String[] args) throws Exception {
        var app = SpringApplication.run(StopBellApplication.class,
                "--spring.profiles.active=test", "--server.port=0", "--server.shutdown=graceful",
                "--spring.datasource.url=" + args[0], "--spring.datasource.username=" + args[1],
                "--spring.datasource.password=" + args[2], "--transit.monitoring.enabled=false",
                "--notification.dispatch.enabled=false", "--notification.decision.ttl.one-stop-before=PT5M");
        if (args.length > 5 && args[5].equals("dispatch")) {
            var clock = java.time.Clock.systemUTC();
            var devices = app.getBean(com.stopbell.notification.repository.DeviceRepository.class);
            var deliveries = app.getBean(com.stopbell.notification.repository.NotificationDeliveryRepository.class);
            var retry = new com.stopbell.notification.service.NotificationRetryPolicy(
                    app.getBean(com.stopbell.notification.service.NotificationRetryProperties.class),
                    app.getBean(com.stopbell.notification.client.FcmClientProperties.class));
            var handler = new com.stopbell.notification.service.DurableNotificationAttemptHandler(retry, devices, clock);
            var attempts = new com.stopbell.notification.service.NotificationDispatchAttemptService(deliveries,
                    new com.stopbell.notification.service.NotificationRecipientPreflight(devices), handler,
                    app.getBean(org.springframework.transaction.PlatformTransactionManager.class), clock);
            var provider = org.mockito.Mockito.mock(com.stopbell.notification.client.FcmProviderClient.class);
            org.mockito.Mockito.when(provider.send(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                    new com.stopbell.notification.client.NotificationSendResult(
                            com.stopbell.notification.entity.NotificationProviderResult.ACCEPTED, null, null,
                            Instant.now(), 0, true));
            var worker = new com.stopbell.notification.service.NotificationDispatchWorker(
                    new com.stopbell.notification.service.NotificationDispatchProperties(true, 50, java.time.Duration.ofSeconds(1)),
                    deliveries, attempts, provider, clock);
            worker.dispatchPending();
            org.mockito.Mockito.verify(provider).send(org.mockito.ArgumentMatchers.any());
            publishMarker(Path.of(args[4]), "DISPATCHED");
            Thread.sleep(Long.MAX_VALUE);
        }
        long alarmId = Long.parseLong(args[3]);
        if (args.length > 5 && args[5].equals("inflight")) {
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            tx.executeWithoutResult(transaction -> {
                app.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(
                        "update alarms set initial_baseline_pending = false where id = ?", alarmId);
                try {
                    publishMarker(Path.of(args[4]), "IN_FLIGHT");
                    Thread.sleep(Long.MAX_VALUE);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            return;
        }
        boolean followUp = args.length > 5 && args[5].equals("followup");
        AlarmStatus status = followUp ? AlarmStatus.FOLLOW_UP : AlarmStatus.ACTIVE;
        Alarm alarm = app.getBean(AlarmRepository.class)
                .findAllByTransitTypeAndStatusInOrderByIdAsc(com.stopbell.alarm.entity.TransitType.BUS, List.of(status))
                .stream().filter(value -> value.getId().equals(alarmId)).findFirst().orElseThrow();
        Instant now = Instant.now();
        var observation = new TransitObservation(TransitProvider.SEOUL_BUS, "route", "vehicle-1", followUp ? "after" : "before", followUp ? 4 : 2,
                null, null, null, null, null, now, null, ArrivalEvidence.MOVING);
        var evaluated = app.getBean(BusAlarmEvaluator.class).evaluate(alarm, BusAlarmEvaluationState.initial(), List.of(observation), now);
        var result = app.getBean(BusAlarmLifecycleService.class).applyIfCurrent(
                new AlarmEvaluationKey(alarmId, alarm.getActivationGeneration()), status, evaluated, now);
        publishMarker(Path.of(args[4]), result.outcome() + ":" + evaluated.eventCandidates().size());
        Thread.sleep(Long.MAX_VALUE);
    }
    private static void publishMarker(Path marker, String value) throws java.io.IOException {
        Path temp = Files.createTempFile(marker.getParent(), ".marker-", ".tmp");
        try {
            Files.writeString(temp, value);
            Files.move(temp, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
