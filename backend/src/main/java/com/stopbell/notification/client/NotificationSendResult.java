package com.stopbell.notification.client;

import java.time.Duration;
import java.time.Instant;

import com.stopbell.notification.entity.NotificationProviderResult;

/** Only allowlisted codes and normalized timing hints may cross the provider boundary. */
public record NotificationSendResult(
        NotificationProviderResult result, String failureCode, Duration minimumRetryDelay,
        Instant receivedAt, long elapsedMs, boolean fcmRequestStarted
) {
}
