package com.stopbell.user.service;

import java.util.HashSet;
import java.util.List;

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
    private final JdbcTemplate jdbc;
    private final NotificationRecipientDetachmentService detachment;
    private final NotificationDeliveryRepository deliveriesRepository;
    private final BoardingTrackingRuntime boardingRuntime;

    public AccountDeletionService(UserRepository users, JdbcTemplate jdbc,
            NotificationRecipientDetachmentService detachment, NotificationDeliveryRepository deliveriesRepository,
            BoardingTrackingRuntime boardingRuntime) {
        this.users = users;
        this.jdbc = jdbc;
        this.detachment = detachment;
        this.deliveriesRepository = deliveriesRepository;
        this.boardingRuntime = boardingRuntime;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean delete(long userId) {
        var user = users.findByIdForUpdate(userId).orElse(null);
        if (user == null) return false;
        if (user.getAuthProvider() == AuthProvider.APPLE || jdbc.queryForObject(
                "select count(*) from apple_credentials where user_id=?", Integer.class, userId) != 0) {
            throw new IllegalStateException("Apple revocation is not available");
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
        jdbc.update("delete from users where id=?", userId);
        return true;
    }
}
