package com.stopbell.common.error;

public record ApiErrorResponse(String code, String message) {

    static ApiErrorResponse forStatus(int status) {
        ErrorCode errorCode = switch (status) {
            case 400 -> ErrorCode.INVALID_REQUEST;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.ACCESS_DENIED;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 500 -> ErrorCode.INTERNAL_SERVER_ERROR;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> null;
        };
        return errorCode == null
                ? new ApiErrorResponse("HTTP_" + status, "Request could not be completed.")
                : new ApiErrorResponse(errorCode.name(), errorCode.getMessage());
    }
}
