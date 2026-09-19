ALTER TABLE notification_history
    DROP FOREIGN KEY fk_notification_history_alarm_id;

ALTER TABLE notification_history
    ADD CONSTRAINT fk_notification_history_alarm_id
        FOREIGN KEY (alarm_id) REFERENCES alarms (id) ON DELETE CASCADE;
