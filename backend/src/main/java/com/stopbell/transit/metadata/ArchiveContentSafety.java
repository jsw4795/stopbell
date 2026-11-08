package com.stopbell.transit.metadata;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/** Reject suspicious content; never redact or include the offending value in errors. */
final class ArchiveContentSafety {
    private final String serviceKey;
    private final String decodedKey;

    ArchiveContentSafety(String serviceKey) {
        this.serviceKey = serviceKey;
        String decoded;
        try { decoded = serviceKey == null ? null : URLDecoder.decode(serviceKey, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { decoded = serviceKey; }
        this.decodedKey = decoded;
    }

    void checkJson(JsonNode node) {
        if (node == null) throw new IllegalStateException("Archive content is missing");
        if (node.isObject()) {
            for (var field : node.properties()) {
                checkField(field.getKey());
                checkJson(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(this::checkJson);
        } else if (node.isString()) {
            checkText(node.asString());
        }
    }

    static void checkField(String field) {
        String name = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (name.contains("servicekey") || name.contains("apikey") || name.contains("authorization")
                || name.contains("password") || name.contains("token") || name.contains("signature")
                || name.contains("email") || name.contains("phone")) {
            throw new IllegalStateException("Archive content contains a sensitive field");
        }
    }

    void checkText(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("servicekey=")
                || lower.contains("authorization:") || lower.contains("bearer ")) {
            throw new IllegalStateException("Archive content contains a sensitive value");
        }
        if (serviceKey == null || serviceKey.isBlank()) return;
        if (containsSecret(value, serviceKey) || containsSecret(value, decodedKey)) {
            throw new IllegalStateException("Archive content contains a sensitive value");
        }
    }

    private static boolean containsSecret(String value, String secret) {
        return !secret.isBlank() && (secret.length() >= 8 ? value.contains(secret) : value.equals(secret));
    }
}
