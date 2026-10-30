package com.stopbell.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required or invalid."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied."),
    SOCIAL_IDENTITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Social identity verification is temporarily unavailable."),
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
    BOARDING_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Boarding session was not found."),
    BOARDING_CANDIDATE_CHANGED(HttpStatus.CONFLICT, "Boarding candidate has changed. Refresh candidates."),
    BOARDING_SESSION_CONFLICT(HttpStatus.CONFLICT, "An open boarding session already exists."),
    BOARDING_REQUEST_CONFLICT(HttpStatus.CONFLICT, "Boarding request conflicts with the original request."),
    BOARDING_REVISION_CONFLICT(HttpStatus.CONFLICT, "Boarding session revision has changed."),
    BOARDING_TARGET_NOT_AHEAD(HttpStatus.CONFLICT, "Boarding target cannot be verified ahead."),
    BOARDING_RECONFIRM_REQUIRED(HttpStatus.CONFLICT, "A suspended session with the same vehicle and target is required."),
    BOARDING_TRACKING_UNSUPPORTED(HttpStatus.CONFLICT, "Boarding tracking is not supported by verified evidence."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Request is invalid."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Requested resource was not found."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Request method is not supported."),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Requested response media type is not supported."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Request media type is not supported."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service is temporarily unavailable."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred.");

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
