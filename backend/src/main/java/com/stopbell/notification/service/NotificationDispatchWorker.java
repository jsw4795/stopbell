package com.stopbell.notification.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.stopbell.notification.client.FcmProviderClient;
import com.stopbell.notification.client.NotificationSendResult;
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

    public NotificationDispatchWorker(NotificationDispatchProperties properties,
            NotificationDeliveryRepository deliveries, NotificationDispatchAttemptService attempts,
            FcmProviderClient provider, Clock clock) {
        this.properties = properties;
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.provider = provider;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notification.dispatch.fixed-delay:PT1S}")
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
            List<Long> ids = deliveries.findDuePendingIds(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                    PageRequest.of(0, properties.batchSize()));
            candidates = ids.size();
            for (Long id : ids) {
                if (stopping || Thread.currentThread().isInterrupted()) {
                    break;
                }
                try {
                    dispatch(id);
                    processed++;
                } catch (DataAccessException | TransactionException exception) {
                    log.warn("Notification dispatch cycle stopped: reason=DATABASE_FAILURE, exceptionType={}",
                            exception.getClass().getSimpleName());
                    break;
                } catch (RuntimeException exception) {
                    // Never print raw exception messages/causes; do not resend after any failure.
                    log.warn("Notification dispatch candidate failed: deliveryId={}, exceptionType={}",
                            id, exception.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Notification dispatch cycle failed: exceptionType={}", exception.getClass().getSimpleName());
        } finally {
            running.set(false);
            log.debug("Notification dispatch cycle finished: candidates={}, processed={}, elapsedMs={}",
                    candidates, processed, (System.nanoTime() - started) / 1_000_000);
        }
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
        NotificationSendResult result = provider.send(snapshot.target().request());
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
