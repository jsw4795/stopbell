package com.stopbell.notification.service;

import java.time.LocalDateTime;

import com.stopbell.notification.client.NotificationSendResult;
import com.stopbell.notification.entity.NotificationDelivery;

/**
 * TASK-709 policy boundary. All methods run inside short caller-owned transactions.
 * No production implementation exists yet; enabling dispatch requires this bean.
 * Implementations must never invoke the provider or start independent transactions.
 */
public interface NotificationAttemptHandler {

    /** Expiry first, then total attempt budget; apply local termination and return false for caller commit. */
    boolean checkEligibility(NotificationDelivery delivery, LocalDateTime now);

    /** Local recipient termination only; never manufacture a provider result. */
    void rejectRecipient(NotificationDelivery delivery, NotificationRecipientPreflight.Rejection reason,
                         LocalDateTime now);

    /**
     * Increment count once, set start/revision, clear previous result/code and persist bounded recovery nextAt.
     * Return that exact attempt's snapshot. The caller returns it only after flush and confirmed commit.
     * Ambiguous start commit reconciliation must preserve this identity; never authorize an unconfirmed send.
     */
    NotificationAttemptSnapshot beginAttempt(NotificationDelivery delivery, NotificationDispatchTarget target,
                                             LocalDateTime now);

    /** Same-attempt result/retry/terminal decision and conditional Device cleanup, atomically. */
    void completeAttempt(NotificationDelivery delivery, NotificationAttemptSnapshot snapshot,
                         NotificationSendResult result);

    /** Local expiry after start commit; keep consumed count and never extend the original deadline. */
    void expireBeforeSend(NotificationDelivery delivery, NotificationAttemptSnapshot snapshot, LocalDateTime now);
}
