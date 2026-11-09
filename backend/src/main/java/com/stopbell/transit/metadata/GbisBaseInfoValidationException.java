package com.stopbell.transit.metadata;

/** Safe GBIS validation diagnostics; never carries source values or parser exceptions. */
public final class GbisBaseInfoValidationException extends IllegalStateException {
    public enum Code {
        JSON_PARSE_ERROR, JSON_DUPLICATE_FIELD, ENVELOPE_STRUCTURE, PROVIDER_RESULT_ERROR,
        REQUIRED_FIELD_MISSING, FIELD_TYPE_INVALID, VERSION_MISMATCH, URL_MISSING,
        URL_INVALID_FORMAT, URL_HOST_MISMATCH, URL_FORBIDDEN_QUERY, URL_ENCODED_OR_USERINFO,
        URL_FORBIDDEN_CONTENT, URL_OUTSIDE_OFFICIAL_FIELD, CONTENT_UNSAFE, UNKNOWN
    }
    public enum Field {
        NONE, RESPONSE, MSG_HEADER, MSG_BODY, BASE_INFO_ITEM, RESULT_CODE,
        ROUTE_VERSION, ROUTE_STATION_VERSION, AREA_DOWNLOAD_URL, ROUTE_DOWNLOAD_URL,
        ROUTE_LINE_DOWNLOAD_URL, ROUTE_STATION_DOWNLOAD_URL, STATION_DOWNLOAD_URL, VEHICLE_DOWNLOAD_URL
    }
    private final Code code;
    private final Field field;

    GbisBaseInfoValidationException(Code code, Field field) {
        super("GBIS base-info validation failed: " + code + (field == Field.NONE ? "" : " field=" + field));
        this.code = code;
        this.field = field;
    }
    public Code code() { return code; }
    public Field field() { return field; }
}
