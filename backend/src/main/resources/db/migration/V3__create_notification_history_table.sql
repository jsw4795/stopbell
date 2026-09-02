CREATE TABLE notification_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    alarm_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    failure_reason VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_history_alarm_id FOREIGN KEY (alarm_id) REFERENCES alarms (id)
);
