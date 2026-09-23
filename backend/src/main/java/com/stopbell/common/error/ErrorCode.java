package com.stopbell.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    ALARM_NOT_FOUND(HttpStatus.NOT_FOUND, "Alarm was not found."),
    BUS_ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Bus route was not found."),
    TARGET_STOP_OCCURRENCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Target stop occurrence was not found."),
    INVALID_ALARM_REQUEST(HttpStatus.BAD_REQUEST, "Alarm request is invalid."),
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
