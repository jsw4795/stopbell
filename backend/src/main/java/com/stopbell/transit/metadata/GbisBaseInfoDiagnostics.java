package com.stopbell.transit.metadata;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.stopbell.transit.metadata.GbisBaseInfoValidationException.Code;
import static com.stopbell.transit.metadata.GbisBaseInfoValidationException.Field;

/** Classifies an already rejected response; never decides whether input is accepted. */
final class GbisBaseInfoDiagnostics {
    private static final ObjectMapper STRICT = new ObjectMapper(tools.jackson.core.json.JsonFactory.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final ObjectMapper PERMISSIVE = new ObjectMapper();
    private static final Set<String> URL_FIELDS = Set.of("areaDownloadUrl", "routeDownloadUrl", "routeLineDownloadUrl",
            "routeStationDownloadUrl", "stationDownloadUrl", "vehicleDownloadUrl");
    private GbisBaseInfoDiagnostics() { }

    static GbisBaseInfoValidationException classify(byte[] bytes, String key) {
        try { return diagnose(bytes, key); }
        catch (RuntimeException e) { return failure(Code.UNKNOWN, Field.NONE); }
    }
    private static GbisBaseInfoValidationException diagnose(byte[] bytes, String key) {
        if (bytes == null || bytes.length == 0) return failure(Code.JSON_PARSE_ERROR, Field.NONE);
        if (key == null || key.isBlank()) return failure(Code.CONTENT_UNSAFE, Field.NONE);
        JsonNode tree;
        try { tree = STRICT.readTree(bytes); }
        catch (RuntimeException e) {
            return failure(hasDuplicateBeforeError(bytes) ? Code.JSON_DUPLICATE_FIELD : Code.JSON_PARSE_ERROR, Field.NONE);
        }
        if (tree == null) return failure(Code.JSON_PARSE_ERROR, Field.NONE);
        JsonNode item = tree.path("response").path("msgBody").path("baseInfoItem");
        var safetyFailure = inspectSafety(tree, item, new ArchiveContentSafety(key), key);
        if (safetyFailure != null) return safetyFailure;
        if (!tree.isObject() || !tree.path("response").isObject()) return failure(Code.ENVELOPE_STRUCTURE, Field.RESPONSE);
        JsonNode response = tree.path("response");
        if (!response.path("msgHeader").isObject()) return failure(Code.ENVELOPE_STRUCTURE, Field.MSG_HEADER);
        JsonNode resultCode = response.path("msgHeader").path("resultCode");
        if (resultCode.isMissingNode() || resultCode.isNull()) return failure(Code.REQUIRED_FIELD_MISSING, Field.RESULT_CODE);
        GbisMetadataClient.Header header;
        try { header = STRICT.treeToValue(response.path("msgHeader"), GbisMetadataClient.Header.class); }
        catch (RuntimeException e) { return failure(Code.FIELD_TYPE_INVALID, Field.RESULT_CODE); }
        if (header.resultCode() == null) return failure(Code.REQUIRED_FIELD_MISSING, Field.RESULT_CODE);
        if (header.resultCode() != 0) return failure(Code.PROVIDER_RESULT_ERROR, Field.RESULT_CODE);
        if (!response.path("msgBody").isObject()) return failure(Code.ENVELOPE_STRUCTURE, Field.MSG_BODY);
        if (!item.isObject()) return failure(Code.ENVELOPE_STRUCTURE, Field.BASE_INFO_ITEM);
        GbisMetadataClient.BaseInfo envelope;
        try { envelope = STRICT.readValue(bytes, GbisMetadataClient.BaseInfo.class); }
        catch (RuntimeException e) { return failure(Code.FIELD_TYPE_INVALID, fieldWithInvalidType(resultCode, item)); }
        if (envelope == null || envelope.response() == null || envelope.response().msgHeader() == null
                || envelope.response().msgHeader().resultCode() == null || envelope.response().msgBody() == null
                || envelope.response().msgBody().baseInfoItem() == null) return failure(Code.ENVELOPE_STRUCTURE, Field.NONE);
        if (envelope.response().msgHeader().resultCode() != 0) return failure(Code.PROVIDER_RESULT_ERROR, Field.RESULT_CODE);
        var info = envelope.response().msgBody().baseInfoItem();
        if (info.routeVersion() == null || info.routeVersion().isBlank()) return failure(Code.REQUIRED_FIELD_MISSING, Field.ROUTE_VERSION);
        if (info.routeStationVersion() == null || info.routeStationVersion().isBlank()) return failure(Code.REQUIRED_FIELD_MISSING, Field.ROUTE_STATION_VERSION);
        if (!info.routeVersion().equals(info.routeStationVersion())) return failure(Code.VERSION_MISMATCH, Field.NONE);
        if (info.routeDownloadUrl() == null || info.routeDownloadUrl().isBlank()) return failure(Code.URL_MISSING, Field.ROUTE_DOWNLOAD_URL);
        if (info.routeStationDownloadUrl() == null || info.routeStationDownloadUrl().isBlank()) return failure(Code.URL_MISSING, Field.ROUTE_STATION_DOWNLOAD_URL);
        var urlFailure = inspectUrl(info.routeDownloadUrl(), key, Field.ROUTE_DOWNLOAD_URL);
        if (urlFailure != null) return urlFailure;
        urlFailure = inspectUrl(info.routeStationDownloadUrl(), key, Field.ROUTE_STATION_DOWNLOAD_URL);
        return urlFailure == null ? failure(Code.UNKNOWN, Field.NONE) : urlFailure;
    }
    private static GbisBaseInfoValidationException inspectSafety(JsonNode node, JsonNode item, ArchiveContentSafety safety, String key) {
        if (node == null) return failure(Code.CONTENT_UNSAFE, Field.NONE);
        if (node.isObject()) {
            for (var entry : node.properties()) {
                try { ArchiveContentSafety.checkField(entry.getKey()); }
                catch (RuntimeException e) { return failure(Code.CONTENT_UNSAFE, Field.NONE); }
                if (node == item && URL_FIELDS.contains(entry.getKey())) {
                    Field field = field(entry.getKey());
                    if (entry.getValue().isNull()) return failure(Code.URL_MISSING, field);
                    if (!entry.getValue().isString()) return failure(Code.FIELD_TYPE_INVALID, field);
                    var error = inspectUrl(entry.getValue().asString(), key, field);
                    if (error != null) return error;
                } else {
                    var error = inspectSafety(entry.getValue(), item, safety, key);
                    if (error != null) return error;
                }
            }
        } else if (node.isArray()) {
            for (var child : node) {
                var error = inspectSafety(child, item, safety, key);
                if (error != null) return error;
            }
        } else if (node.isString()) {
            String value = node.asString();
            try { safety.checkText(value); }
            catch (RuntimeException e) {
                String lower = value.toLowerCase(Locale.ROOT);
                return failure(lower.contains("http://") || lower.contains("https://")
                        ? Code.URL_OUTSIDE_OFFICIAL_FIELD : Code.CONTENT_UNSAFE, Field.NONE);
            }
        }
        return null;
    }
    private static GbisBaseInfoValidationException inspectUrl(String value, String key, Field field) {
        try { GbisMetadataClient.validateDownloadUrl(value, key); return null; }
        catch (RuntimeException ignored) { /* The original validator decides acceptance. */ }
        if (value == null || value.isBlank()) return failure(Code.URL_MISSING, field);
        if (value.indexOf('%') >= 0) return failure(Code.URL_ENCODED_OR_USERINFO, field);
        if (value.indexOf('\\') >= 0) return failure(Code.URL_BACKSLASH, field);
        URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException e) { return failure(Code.URL_URI_SYNTAX, field); }
        if (uri.getScheme() == null) return failure(Code.URL_SCHEME_MISSING, field);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
            return failure(Code.URL_SCHEME_UNSUPPORTED, field);
        if (uri.isOpaque()) return failure(Code.URL_PATH_NOT_ABSOLUTE, field);
        if (uri.getHost() == null) return failure(Code.URL_HOST_MISSING, field);
        if (!"openapi.gbis.go.kr".equalsIgnoreCase(uri.getHost())) return failure(Code.URL_HOST_MISMATCH, field);
        if (uri.getRawUserInfo() != null) return failure(Code.URL_ENCODED_OR_USERINFO, field);
        if (uri.getPort() != -1) return failure(Code.URL_PORT_PRESENT, field);
        if (uri.getRawFragment() != null) return failure(Code.URL_FRAGMENT_PRESENT, field);
        if (uri.getRawPath() == null || uri.getRawPath().isEmpty()) return failure(Code.URL_PATH_MISSING, field);
        if (!uri.getRawPath().startsWith("/")) return failure(Code.URL_PATH_NOT_ABSOLUTE, field);
        var safety = new ArchiveContentSafety(key);
        try { safety.checkText(uri.getRawPath()); GbisMetadataClient.rejectCredentialWords(uri.getRawPath()); }
        catch (RuntimeException e) { return failure(Code.URL_FORBIDDEN_CONTENT, field); }
        String query = uri.getRawQuery();
        if (query != null) {
            if (query.isEmpty()) return failure(Code.URL_QUERY_STRUCTURE, field);
            for (String part : query.split("&", -1)) {
                String[] pair = part.split("=", -1);
                if (pair.length != 2) return failure(Code.URL_QUERY_STRUCTURE, field);
                if (!pair[0].matches("[A-Za-z0-9_-]+") || !pair[1].matches("[A-Za-z0-9._/-]+"))
                    return failure(Code.URL_QUERY_PARAMETER_FORMAT, field);
                try { ArchiveContentSafety.checkField(pair[0]); safety.checkText(pair[1]); GbisMetadataClient.rejectCredentialWords(pair[1]); }
                catch (RuntimeException e) { return failure(Code.URL_FORBIDDEN_QUERY, field); }
            }
        }
        return failure(Code.UNKNOWN, field);
    }
    private static Field fieldWithInvalidType(JsonNode result, JsonNode item) {
        if ((result.isObject() || result.isArray())) return Field.RESULT_CODE;
        for (String name : new String[]{"routeVersion", "routeStationVersion", "routeDownloadUrl", "routeStationDownloadUrl"})
            if ((item.path(name).isObject() || item.path(name).isArray())) return field(name);
        return Field.NONE;
    }
    private static Field field(String name) {
        return switch (name) {
            case "routeVersion" -> Field.ROUTE_VERSION;
            case "routeStationVersion" -> Field.ROUTE_STATION_VERSION;
            case "areaDownloadUrl" -> Field.AREA_DOWNLOAD_URL;
            case "routeDownloadUrl" -> Field.ROUTE_DOWNLOAD_URL;
            case "routeLineDownloadUrl" -> Field.ROUTE_LINE_DOWNLOAD_URL;
            case "routeStationDownloadUrl" -> Field.ROUTE_STATION_DOWNLOAD_URL;
            case "stationDownloadUrl" -> Field.STATION_DOWNLOAD_URL;
            case "vehicleDownloadUrl" -> Field.VEHICLE_DOWNLOAD_URL;
            default -> Field.NONE;
        };
    }
    private static boolean hasDuplicateBeforeError(byte[] bytes) {
        var objects = new ArrayDeque<Set<String>>();
        try (var parser = PERMISSIVE.createParser(bytes)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.START_OBJECT) objects.push(new HashSet<>());
                else if (token == JsonToken.END_OBJECT) objects.pop();
                else if (token == JsonToken.PROPERTY_NAME && !objects.peek().add(parser.currentName())) return true;
            }
        } catch (RuntimeException e) { return false; }
        return false;
    }
    private static GbisBaseInfoValidationException failure(Code code, Field field) {
        return new GbisBaseInfoValidationException(code, field);
    }
}
