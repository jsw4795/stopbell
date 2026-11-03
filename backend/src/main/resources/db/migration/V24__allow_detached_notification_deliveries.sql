-- Retain another user's delivery when its former recipient device is deleted.
ALTER TABLE notification_deliveries MODIFY COLUMN device_id BIGINT NULL;
