package com.stopbell.boarding.entity;

public enum BoardingSessionStatus {
    TRACKING, SUSPENDED, COMPLETED, CANCELLED, EXPIRED;

    public boolean isOpen() {
        return this == TRACKING || this == SUSPENDED;
    }
}
