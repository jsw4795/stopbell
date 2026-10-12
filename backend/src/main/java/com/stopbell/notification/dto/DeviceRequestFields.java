package com.stopbell.notification.dto;

import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import tools.jackson.databind.JsonNode;

final class DeviceRequestFields {

    private DeviceRequestFields() {
    }

    static String string(JsonNode value) {
        if (value == null || !value.isString()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return value.asString();
    }

    static long counter(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return value.longValue();
    }

    static boolean bool(JsonNode value) {
        if (value == null || !value.isBoolean()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        return value.booleanValue();
    }
}
