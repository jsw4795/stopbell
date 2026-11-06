CREATE TABLE apple_identity_boundaries (
    subject_hash BINARY(32) NOT NULL PRIMARY KEY,
    latest_identity_issued_at DATETIME(6) NULL,
    latest_revocation_at DATETIME(6) NULL
);

INSERT INTO apple_identity_boundaries (subject_hash, latest_identity_issued_at)
SELECT UNHEX(SHA2(u.provider_user_id, 256)), c.identity_issued_at
FROM users u JOIN apple_credentials c ON c.user_id = u.id
WHERE u.auth_provider = 'APPLE' AND c.identity_issued_at IS NOT NULL
ON DUPLICATE KEY UPDATE latest_identity_issued_at = VALUES(latest_identity_issued_at);

INSERT INTO apple_identity_boundaries (subject_hash, latest_revocation_at)
SELECT UNHEX(SHA2(apple_subject, 256)), MAX(event_time)
FROM apple_notification_events
WHERE state = 'PENDING' AND event_type IN ('consent-revoked', 'account-deleted')
GROUP BY UNHEX(SHA2(apple_subject, 256))
ON DUPLICATE KEY UPDATE latest_revocation_at = VALUES(latest_revocation_at);
