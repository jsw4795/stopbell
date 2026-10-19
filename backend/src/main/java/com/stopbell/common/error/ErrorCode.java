package com.stopbell.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    ALARM_NOT_FOUND(HttpStatus.NOT_FOUND, "Alarm was not found."),
    BUS_ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Bus route was not found."),
    TARGET_STOP_OCCURRENCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Target stop occurrence was not found."),
    INVALID_ALARM_REQUEST(HttpStatus.BAD_REQUEST, "Alarm request is invalid."),
    DEVICE_CREDENTIAL_INVALID(HttpStatus.FORBIDDEN, "Installation credential is invalid."),
    DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "Device was not found."),
    DEVICE_OWNERSHIP_CONFLICT(HttpStatus.CONFLICT, "Device ownership has changed."),
    DEVICE_PLATFORM_CONFLICT(HttpStatus.CONFLICT, "Device platform cannot change."),
    DEVICE_REGISTRATION_STALE(HttpStatus.CONFLICT, "Device registration revision is stale."),
    DEVICE_REVISION_CONFLICT(HttpStatus.CONFLICT, "Device registration revision conflicts."),
    DEVICE_PUSH_TARGET_CONFLICT(HttpStatus.CONFLICT, "Push target is already registered."),
    TRANSIT_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Transit is temporarily unavailable."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Request is invalid.");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
