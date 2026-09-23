package com.stopbell.transit.domain;

/**
 * A notification-worthy transit event candidate identified for one vehicle.
 */
public enum TransitEventType {
    ONE_STOP_BEFORE,
    ARRIVED,
    PASSED,
    ONE_STOP_AFTER
}
