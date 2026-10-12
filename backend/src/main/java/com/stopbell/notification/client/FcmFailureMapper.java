package com.stopbell.notification.client;

import java.net.UnknownHostException;
import java.net.NoRouteToHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stopbell.notification.entity.NotificationProviderResult;

import static com.stopbell.notification.entity.NotificationProviderResult.*;

final class FcmFailureMapper {
    record Classification(NotificationProviderResult result, String code, Duration minimumDelay) { }

    static Classification transportFailure(Throwable failure) {
        if (failure instanceof UnknownHostException || failure instanceof NoRouteToHostException) {
            return classified(RETRYABLE, "CONNECTION_NOT_SENT");
        }
        return classified(AMBIGUOUS_TIMEOUT, "UNKNOWN_ACCEPTANCE");
    }

    static Classification response(int status, String body, String retryAfter, Instant receivedAt) {
        if (status >= 200 && status < 300) {
            try {
                String name = string(JsonParser.parseString(body).getAsJsonObject(), "name");
                if (name != null && name.matches("projects/[^/\\s]+/messages/[^/\\s]+")) {
                    return classified(ACCEPTED, null);
                }
            } catch (RuntimeException ignored) { }
            return classified(AMBIGUOUS_TIMEOUT, "SUCCESS_RESPONSE_INVALID");
        }

        String fcmCode = null;
        String genericCode = null;
        boolean inconsistent = false;
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            genericCode = string(error, "status");
            if (error.has("code") && (!error.get("code").isJsonPrimitive()
                    || !error.get("code").getAsJsonPrimitive().isNumber()
                    || !error.get("code").getAsBigDecimal().equals(java.math.BigDecimal.valueOf(status)))) {
                inconsistent = true;
            }
            if (error.has("details")) {
                for (JsonElement detail : error.getAsJsonArray("details")) {
                    JsonObject value = detail.getAsJsonObject();
                    if ("type.googleapis.com/google.firebase.fcm.v1.FcmError".equals(string(value, "@type"))) {
                        String code = string(value, "errorCode");
                        if (fcmCode != null && !fcmCode.equals(code)) {
                            inconsistent = true;
                        }
                        fcmCode = code;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Incomplete or malformed details cannot be used as cleanup evidence.
            fcmCode = null;
        }
        if ("UNREGISTERED".equals(fcmCode) && status == 404 && !inconsistent
                && "NOT_FOUND".equals(genericCode)) {
            return classified(INVALID_TARGET, "UNREGISTERED");
        }
        if (status == 401 || status == 403 || status == 404
                || "SENDER_ID_MISMATCH".equals(fcmCode)
                || "THIRD_PARTY_AUTH_ERROR".equals(fcmCode) || "APNS_AUTH_ERROR".equals(fcmCode)) {
            return classified(CONFIGURATION, status == 404 ? "NOT_FOUND_UNCLASSIFIED" : "AUTH_OR_PROJECT_ERROR");
        }
        if (status == 429 || status == 500 || status == 503) {
            Duration minimum = status == 429 ? Duration.ofMinutes(1) : Duration.ofSeconds(10);
            Duration header = retryAfter(retryAfter, receivedAt);
            return new Classification(RETRYABLE, status == 429 ? "QUOTA_EXCEEDED"
                    : status == 500 ? "INTERNAL" : "UNAVAILABLE", header.compareTo(minimum) > 0 ? header : minimum);
        }
        if (status == 400) {
            return classified(PERMANENT_REQUEST, "INVALID_ARGUMENT_UNCLASSIFIED");
        }
        if (status >= 300 && status < 500) {
            return classified(PERMANENT_REQUEST, "UNCLASSIFIED_REJECTION");
        }
        return classified(AMBIGUOUS_TIMEOUT, "UNKNOWN_ACCEPTANCE");
    }

    static Duration retryAfter(String value, Instant receivedAt) {
        if (value == null) {
            return Duration.ZERO;
        }
        value = value.trim();
        if (value.length() > 128) {
            return value.matches("[0-9]+") ? Duration.ofSeconds(Long.MAX_VALUE) : Duration.ZERO;
        }
        try {
            if (value.matches("[0-9]+")) {
                try {
                    return Duration.ofSeconds(Long.parseLong(value));
                } catch (NumberFormatException overflow) {
                    return Duration.ofSeconds(Long.MAX_VALUE);
                }
            }
            Instant date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(receivedAt, date);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (RuntimeException ignored) {
            return Duration.ZERO;
        }
    }

    static Classification classified(NotificationProviderResult result, String code) {
        return new Classification(result, code,
                result == RETRYABLE || result == AMBIGUOUS_TIMEOUT ? Duration.ofSeconds(10) : Duration.ZERO);
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isString()) {
            return null;
        }
        return object.get(key).getAsString();
    }
}
