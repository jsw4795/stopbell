package com.stopbell.notification.entity;

public enum NotificationProviderResult {
    ACCEPTED,
    INVALID_TARGET,
    RETRYABLE,
    CONFIGURATION,
    PERMANENT_REQUEST,
    AMBIGUOUS_TIMEOUT
}
