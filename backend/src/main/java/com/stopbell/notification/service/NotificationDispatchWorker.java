package com.stopbell.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.stopbell.notification.client.FcmProviderClient;
import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.NotificationProviderResult;
import com.stopbell.notification.entity.NotificationSourceType;
import com.stopbell.boarding.service.BoardingTrackingRuntime;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Single synchronous worker; pending state lives only in MySQL. */
public class NotificationDispatchWorker {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchWorker.class);
    private final NotificationDispatchProperties properties;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationDispatchAttemptService attempts;
    private final FcmProviderClient provider;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean stopping;
    private boolean configurationBlocked;
    private final BoardingTrackingRuntime runtime;
    private long readyCursor;

    public NotificationDispatchWorker(NotificationDispatchProperties properties,
            NotificationDeliveryRepository deliveries, NotificationDispatchAttemptService attempts,
            FcmProviderClient provider, Clock clock) {
        this(properties, deliveries, attempts, provider, clock, null);
    }

    public NotificationDispatchWorker(NotificationDispatchProperties properties,
            NotificationDeliveryRepository deliveries, NotificationDispatchAttemptService attempts,
            FcmProviderClient provider, Clock clock, BoardingTrackingRuntime runtime) {
        this.runtime = runtime;
        this.properties = properties;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.provider = provider;
        this.clock = clock;
    }

    @Scheduled(scheduler = "notificationDispatchScheduler", fixedDelayString = "${notification.dispatch.fixed-delay:PT1S}")
    public void dispatchPending() {
        if (!properties.enabled() || stopping || !running.compareAndSet(false, true)) {
            return;
        }
        long started = System.nanoTime();
        int candidates = 0;
        int processed = 0;
        log.debug("Notification dispatch cycle started");
        try {
            requireNoTransaction();
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            PageRequest page = PageRequest.of(0, properties.batchSize());
            for (NotificationSourceType source : NotificationSourceType.values()) {
                List<Long> expired = deliveries.findExpiredPendingIdsBySource(source, now, page);
                candidates += expired.size();
                for (Long id : expired) {
                    if (stopping || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    attempts.expireDuePending(id);
                }
            }
            if (configurationBlocked) {
                return;
            }
            List<Long> alarms = deliveries.findDuePendingIds(now, page);
            candidates += alarms.size();
            processed += dispatchBatch(alarms);
            if (!configurationBlocked && !stopping && runtime != null) {
                // One bounded keyset window per cycle. Recovery/hold entries are absent from the runtime lookup index.
                var ready = runtime.readyWindow(readyCursor, properties.batchSize(),
                        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                readyCursor = ready.cursor();
                // Durable completion notifications remain eligible across process restarts.
                var sessions = deliveries.findBoardingDuePendingIds(ready.ids().isEmpty() ? List.of(-1L) : ready.ids(),
                        LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), page);
                candidates += sessions.size();
                processed += dispatchBatch(sessions);
            }
        } catch (RuntimeException exception) {
            log.warn("Notification dispatch cycle failed: exceptionType={}", exception.getClass().getSimpleName());
        } finally {
            running.set(false);
            log.debug("Notification dispatch cycle finished: candidates={}, processed={}, elapsedMs={}",
                    candidates, processed, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private int dispatchBatch(List<Long> ids) {
        int processed = 0;
        for (Long id : ids) {
            if (configurationBlocked || stopping || Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                dispatch(id);
                processed++;
            } catch (DataAccessException | TransactionException exception) {
                // Abort both sources on DB uncertainty; never resend a Provider request.
                throw exception;
            } catch (RuntimeException exception) {
                log.warn("Notification dispatch candidate failed: deliveryId={}, exceptionType={}",
                        id, exception.getClass().getSimpleName());
            }
        }
        return processed;
    }

    private void dispatch(long id) {
        NotificationAttemptSnapshot snapshot = attempts.beginAttempt(id).orElse(null);
        if (snapshot == null) {
            return;
        }
        requireNoTransaction();
        if (!clock.instant().isBefore(snapshot.target().expiresAt().toInstant(ZoneOffset.UTC))) {
            attempts.expireBeforeSend(snapshot);
            return;
        }
        if (snapshot.target().source().sourceType() == NotificationSourceType.BOARDING_SESSION
                && !attempts.allowsBeforeSend(snapshot)) {
            // Keep the durable count/recovery reservation. No synthetic Provider result.
            return;
        }
        NotificationSendResult result = provider.send(snapshot.target().request());
        if (result.result() == NotificationProviderResult.CONFIGURATION) {
            // Fail closed until configuration repair + Backend restart. Expiry sweeps remain enabled.
            configurationBlocked = true;
            log.warn("Notification dispatch blocked: reason=PROVIDER_CONFIGURATION, recovery=RESTART_AFTER_REPAIR");
        }
        log.debug("Notification dispatch provider result: deliveryId={}, result={}", id, result.result());
        attempts.completeAttempt(snapshot, result);
    }

    private void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Notification dispatch requires no enclosing transaction");
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        stopping = true;
    }
}
