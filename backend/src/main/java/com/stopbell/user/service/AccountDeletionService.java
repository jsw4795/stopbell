package com.stopbell.user.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;

import com.stopbell.user.auth.apple.AppleIdentityBoundary;

import com.stopbell.boarding.service.BoardingTrackingRuntime;
import com.stopbell.notification.service.NotificationRecipientDetachmentService;
import com.stopbell.notification.repository.NotificationDeliveryRepository;
import com.stopbell.user.entity.AuthProvider;
import com.stopbell.user.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Internal transaction only. Callers must supply the authenticated owner's ID. */
@Service
public class AccountDeletionService {
    private final UserRepository users;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final NotificationRecipientDetachmentService detachment;
    private final NotificationDeliveryRepository deliveriesRepository;
    private final BoardingTrackingRuntime boardingRuntime;
    private final AppleIdentityBoundary appleBoundary;
    private final AccountDeletionQuiescer quiescer;

    public AccountDeletionService(UserRepository users, JdbcTemplate jdbc,
            NotificationRecipientDetachmentService detachment, NotificationDeliveryRepository deliveriesRepository,
            BoardingTrackingRuntime boardingRuntime, Clock jwtClock, AppleIdentityBoundary appleBoundary, AccountDeletionQuiescer quiescer) {
        this.users = users;
        this.appleBoundary = appleBoundary;
        this.quiescer = quiescer;
        this.clock = jwtClock;
        this.jdbc = jdbc;
        this.detachment = detachment;
        this.deliveriesRepository = deliveriesRepository;
        this.boardingRuntime = boardingRuntime;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean delete(long userId) {
        appleBoundary.lockExistingAppleUser(userId);
        return deleteInternal(userId, false);
    }

    /** A verified event first commits a durable access fence and stops source decisions. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventResult prepareAppleEvent(byte[] jtiHash) {
        Target target = targetForAppleEvent(jtiHash);
        if (target.result() != null) return target.result() == EventResult.ALREADY_FINISHED
                ? target.result() : finishEvent(jtiHash, target.result());
        long userId = target.userId();
        var states = jdbc.queryForList("select state from account_deletions where user_id=? for update", String.class, userId);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (states.isEmpty()) {
            jdbc.update("insert into account_deletions (user_id,state,requested_at,next_attempt_at) values (?,'APPLE_NOTIFIED',?,?)",
                    userId, now, now.plusMinutes(1));
        } else if (states.getFirst().equals("PENDING")) {
            jdbc.update("update account_deletions set state='APPLE_NOTIFIED',last_error=null where user_id=?", userId);
        }
        quiescer.stop(userId);
        return EventResult.READY;
    }

    /** Event and deletion commit atomically after the durable access fence. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EventResult deleteForAppleEvent(byte[] jtiHash) {
        Target target = targetForAppleEvent(jtiHash);
        if (target.result() != null) return target.result() == EventResult.ALREADY_FINISHED
                ? target.result() : finishEvent(jtiHash, target.result());
        var states = jdbc.queryForList("select state from account_deletions where user_id=? for update", String.class, target.userId());
        if (states.isEmpty() || !(states.getFirst().equals("APPLE_NOTIFIED") || states.getFirst().equals("REVOKED")
                || states.getFirst().equals("MANUAL_REQUIRED"))) throw new IllegalStateException("Apple deletion is not prepared");
        if (!deleteInternal(target.userId(), true)) throw new IllegalStateException("Apple event target disappeared");
        return finishEvent(jtiHash, EventResult.DELETED);
    }

    private Target targetForAppleEvent(byte[] jtiHash) {
        var event = appleBoundary.lockPendingEvent(jtiHash);
        if (event == null) return new Target(null, EventResult.ALREADY_FINISHED);
        var user = users.findByAuthProviderAndProviderUserId(AuthProvider.APPLE, event.subject()).orElse(null);
        if (user != null) {
            user = users.findByIdForUpdate(user.getId()).orElse(null);
            if (user != null && (user.getAuthProvider() != AuthProvider.APPLE || !event.subject().equals(user.getProviderUserId()))) user = null;
        }
        if (user == null) return new Target(null, EventResult.SKIPPED);
        LocalDateTime occurred = jdbc.queryForObject("select event_time from apple_notification_events where jti_hash=? for update", LocalDateTime.class, jtiHash)
                .truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime generation = user.getAppleGenerationIssuedAt();
        if (generation == null) {
            var last = jdbc.query("select identity_issued_at from apple_credentials where user_id=? for update",
                    (rs, index) -> rs.getObject(1, LocalDateTime.class), user.getId());
            if (last.isEmpty() || last.getFirst() == null || !last.getFirst().truncatedTo(ChronoUnit.SECONDS).isBefore(occurred)) {
                return new Target(null, EventResult.REVIEW_REQUIRED);
            }
        } else {
            generation = generation.truncatedTo(ChronoUnit.SECONDS);
            if (generation.equals(occurred)) return new Target(null, EventResult.REVIEW_REQUIRED);
            if (generation.isAfter(occurred)) return new Target(null, EventResult.SKIPPED);
        }
        return new Target(user.getId(), null);
    }

    private record Target(Long userId, EventResult result) { }

    private EventResult finishEvent(byte[] jtiHash, EventResult result) {
        boolean review = result == EventResult.REVIEW_REQUIRED;
        int changed = jdbc.update("update apple_notification_events set state=?,apple_subject=case when ? then apple_subject else null end,completed_at=?,next_attempt_at=null,last_error=null where jti_hash=? and state='PENDING'",
                result.name(), review, review ? null : LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), jtiHash);
        if (changed != 1) throw new IllegalStateException("Apple event state changed unexpectedly");
        return result;
    }

    public enum EventResult { READY, DELETED, SKIPPED, REVIEW_REQUIRED, ALREADY_FINISHED }

    private boolean deleteInternal(long userId, boolean verifiedAppleEvent) {
        var user = users.findByIdForUpdate(userId).orElse(null);
        if (user == null) return false;
        if (user.getAuthProvider() == AuthProvider.APPLE || jdbc.queryForObject(
                "select count(*) from apple_credentials where user_id=?", Integer.class, userId) != 0) {
            var states = jdbc.queryForList("select state from account_deletions where user_id=? for update", String.class, userId);
            if (!verifiedAppleEvent && (states.isEmpty() || !(states.getFirst().equals("REVOKED") || states.getFirst().equals("MANUAL_REQUIRED")))) {
                throw new IllegalStateException("Apple revocation or explicit manual fallback is required");
            }
        }

        // Stop source decisions before collecting their durable Events and Deliveries.
        for (Long id : jdbc.queryForList("select id from alarms where user_id=? order by id", Long.class, userId)) {
            jdbc.queryForList("select id from alarms where id=? and user_id=? for update", Long.class, id, userId);
        }
        for (Long id : jdbc.queryForList("select id from boarding_sessions where user_id=? order by id", Long.class, userId)) {
            var revisions = jdbc.queryForList("select revision from boarding_sessions where id=? and user_id=? for update",
                    Long.class, id, userId);
            if (!revisions.isEmpty()) boardingRuntime.committed(id, revisions.getFirst(), false);
        }
        List<Long> deliveries = jdbc.queryForList("""
                select id from notification_deliveries
                where notification_event_id in (select id from notification_events where user_id=?)
                   or device_id in (select id from devices where user_id=?)
                order by id
                """, Long.class, userId, userId);
        // Lock all Deliveries first; repeated Delivery -> Device locking can deadlock a worker.
        for (Long id : deliveries) {
            jdbc.queryForList("select id from notification_deliveries where id=? for update", Long.class, id);
        }
        List<Long> deviceIds = jdbc.queryForList("select id from devices where user_id=? order by id", Long.class, userId);
        for (Long id : deviceIds) {
            jdbc.queryForList("select id from devices where id=? for update", Long.class, id);
        }
        var candidates = new HashSet<>(deviceIds);
        for (Long id : deliveries) {
            var device = jdbc.queryForList("select device_id from notification_deliveries where id=?", Long.class, id);
            if (!device.isEmpty() && candidates.contains(device.getFirst())) detachment.detach(id, userId);
        }
        deliveriesRepository.flush();

        jdbc.update("delete from notification_deliveries where notification_event_id in "
                + "(select id from notification_events where user_id=?)", userId);
        jdbc.update("delete from notification_events where user_id=?", userId);
        Integer historyExists = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema=database() and table_name='notification_history'
                """, Integer.class);
        if (historyExists != null && historyExists > 0) {
            jdbc.update("delete from notification_history where alarm_id in (select id from alarms where user_id=?)", userId);
        }
        jdbc.update("delete from alarm_creation_requests where user_id=?", userId);
        jdbc.update("delete from boarding_sessions where user_id=?", userId);
        jdbc.update("delete from alarms where user_id=?", userId);
        jdbc.update("delete from devices where user_id=?", userId);
        jdbc.update("delete from refresh_tokens where user_id=?", userId);
        jdbc.update("delete from apple_credentials where user_id=?", userId);
        jdbc.update("""
                update account_deletion_receipts set state=case when exists
                    (select 1 from account_deletions where user_id=? and state='MANUAL_REQUIRED')
                    then 'MANUAL_REQUIRED' else 'DELETED' end,
                    completed_at=?,pending_user_id=null where pending_user_id=?
                """, userId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), userId);
        jdbc.update("delete from users where id=?", userId);
        return true;
    }
}
