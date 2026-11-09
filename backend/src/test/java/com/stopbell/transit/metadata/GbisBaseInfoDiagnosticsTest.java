package com.stopbell.transit.metadata;

import static org.assertj.core.api.Assertions.*;
import static com.stopbell.transit.metadata.GbisBaseInfoValidationException.Code.*;
import static com.stopbell.transit.metadata.GbisBaseInfoValidationException.Field.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class GbisBaseInfoDiagnosticsTest {
    private static final String KEY = "fake-service-secret";
    private static final String ROUTE_URL = "http://openapi.gbis.go.kr/download?file=route";
    private static final String BASE = """
            {"response":{"msgHeader":{"resultCode":0},"msgBody":{"baseInfoItem":{
            "routeDownloadUrl":"%s","routeVersion":"v1",
            "routeStationDownloadUrl":"http://openapi.gbis.go.kr/download?file=station","routeStationVersion":"v1"}}}}
            """.formatted(ROUTE_URL);
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void rejects(String input, GbisBaseInfoValidationException.Code code, GbisBaseInfoValidationException.Field field) {
        Throwable thrown = catchThrowable(() -> GbisMetadataClient.parseBaseInfo(bytes(input), KEY));
        assertThat(thrown).isInstanceOf(GbisBaseInfoValidationException.class).hasNoCause();
        var failure = (GbisBaseInfoValidationException) thrown;
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.field()).isEqualTo(field);
        assertThat(failure.getMessage()).doesNotContain(KEY, "signature=fake", "token=secret", "openapi.gbis.go.kr", input);
        var writer = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(writer));
        assertThat(writer.toString()).doesNotContain(KEY, "signature=fake", "token=secret", "openapi.gbis.go.kr");
    }
    @Test void accepts_existing_valid_and_coerced_scalar_inputs() {
        assertThat(GbisMetadataClient.parseBaseInfo(bytes(BASE), KEY).routeVersion()).isEqualTo("v1");
        String numeric = BASE.replace("\"routeVersion\":\"v1\"", "\"routeVersion\":1")
                .replace("\"routeStationVersion\":\"v1\"", "\"routeStationVersion\":1");
        assertThat(GbisMetadataClient.parseBaseInfo(bytes(numeric), KEY).routeVersion()).isEqualTo("1");
    }
    @Test void classifies_json_and_duplicate_without_exposing_parser_content() {
        rejects("{\"serviceKey\":\"" + KEY + "\",", JSON_PARSE_ERROR, NONE);
        rejects(BASE.replace("\"routeVersion\":\"v1\"", "\"routeVersion\":\"v1\",\"routeVersion\":\"" + KEY + "\""), JSON_DUPLICATE_FIELD, NONE);
    }
    @Test void classifies_envelope_and_provider_errors() {
        rejects("{}", ENVELOPE_STRUCTURE, RESPONSE);
        rejects("{\"response\":{}}", ENVELOPE_STRUCTURE, MSG_HEADER);
        rejects("{\"response\":{\"msgHeader\":{\"resultCode\":0}}}", ENVELOPE_STRUCTURE, MSG_BODY);
        rejects("{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{}}}", ENVELOPE_STRUCTURE, BASE_INFO_ITEM);
        rejects(BASE.replace("\"resultCode\":0", "\"resultCode\":1"), PROVIDER_RESULT_ERROR, RESULT_CODE);
        rejects("{\"response\":{\"msgHeader\":{\"resultCode\":4}}}", PROVIDER_RESULT_ERROR, RESULT_CODE);
        rejects(BASE.replace("\"resultCode\":0", "\"resultCode\":\"invalid\""), FIELD_TYPE_INVALID, RESULT_CODE);
        rejects(BASE.replace("\"resultCode\":0", "\"other\":0"), REQUIRED_FIELD_MISSING, RESULT_CODE);
        rejects(BASE.replace("\"resultCode\":0", "\"resultCode\":{}"), FIELD_TYPE_INVALID, RESULT_CODE);
    }
    @Test void classifies_required_version_and_url_fields() {
        rejects(BASE.replace("\"routeVersion\":\"v1\"", "\"other\":\"v1\""), REQUIRED_FIELD_MISSING, ROUTE_VERSION);
        rejects(BASE.replace("\"routeStationVersion\":\"v1\"", "\"other\":\"v1\""), REQUIRED_FIELD_MISSING, ROUTE_STATION_VERSION);
        rejects(BASE.replace("\"routeStationVersion\":\"v1\"", "\"routeStationVersion\":\"v2\""), VERSION_MISMATCH, NONE);
        rejects(BASE.replace("\"routeDownloadUrl\":\"" + ROUTE_URL + "\",", ""), URL_MISSING, ROUTE_DOWNLOAD_URL);
        rejects(BASE.replace("\"routeStationDownloadUrl\":\"http://openapi.gbis.go.kr/download?file=station\",", ""), URL_MISSING, ROUTE_STATION_DOWNLOAD_URL);
        rejects(BASE.replace("\"routeDownloadUrl\":\"" + ROUTE_URL + "\"", "\"routeDownloadUrl\":42"), FIELD_TYPE_INVALID, ROUTE_DOWNLOAD_URL);
        rejects(BASE.replace("\"routeDownloadUrl\":\"" + ROUTE_URL + "\"", "\"routeDownloadUrl\":null"), URL_MISSING, ROUTE_DOWNLOAD_URL);
        rejects(BASE.replace("\"routeVersion\":\"v1\"", "\"routeVersion\":{}"), FIELD_TYPE_INVALID, ROUTE_VERSION);
    }
    @Test void classifies_url_security_without_exposing_values() {
        for (String url : List.of("http://evil.test/a", "http://openapi.gbis.go.kr.evil.test/a"))
            rejects(BASE.replace(ROUTE_URL, url), URL_HOST_MISMATCH, ROUTE_DOWNLOAD_URL);
        rejects(BASE.replace(ROUTE_URL, "ftp://openapi.gbis.go.kr/a"), URL_SCHEME_UNSUPPORTED, ROUTE_DOWNLOAD_URL);
        rejects(BASE.replace(ROUTE_URL, "http://user:secret@openapi.gbis.go.kr/a"), URL_ENCODED_OR_USERINFO, ROUTE_DOWNLOAD_URL);
        rejects(BASE.replace(ROUTE_URL, "http://openapi.gbis.go.kr/a?file=%73ecret"), URL_ENCODED_OR_USERINFO, ROUTE_DOWNLOAD_URL);
        for (String query : List.of("signature=fake", "token=secret", "serviceKey=" + KEY, "apiKey=secret", "authorization=secret"))
            rejects(BASE.replace(ROUTE_URL, "http://openapi.gbis.go.kr/a?" + query), URL_FORBIDDEN_QUERY, ROUTE_DOWNLOAD_URL);
        rejects(BASE.replace(ROUTE_URL, "http://openapi.gbis.go.kr/" + KEY), URL_FORBIDDEN_CONTENT, ROUTE_DOWNLOAD_URL);
    }
    @Test void classifies_url_format_without_disclosing_input() {
        var cases = java.util.Map.ofEntries(
                java.util.Map.entry("http://openapi.gbis.go.kr/a b", URL_URI_SYNTAX),
                java.util.Map.entry("/relative", URL_SCHEME_MISSING),
                java.util.Map.entry("ftp://openapi.gbis.go.kr/a", URL_SCHEME_UNSUPPORTED),
                java.util.Map.entry("http:///a", URL_HOST_MISSING),
                java.util.Map.entry("http://openapi.gbis.go.kr:80/a", URL_PORT_PRESENT),
                java.util.Map.entry("http://openapi.gbis.go.kr/a#fragment", URL_FRAGMENT_PRESENT),
                java.util.Map.entry("http://openapi.gbis.go.kr", URL_PATH_MISSING),
                java.util.Map.entry("http:relative", URL_PATH_NOT_ABSOLUTE),
                java.util.Map.entry("http://openapi.gbis.go.kr/a\\\\b", URL_BACKSLASH));
        cases.forEach((url, code) -> rejects(BASE.replace(ROUTE_URL, url), code, ROUTE_DOWNLOAD_URL));
    }
    @Test void optional_fields_accept_metadata_but_reject_credentials() {
        for (String name : List.of("areaDownloadUrl", "routeLineDownloadUrl", "stationDownloadUrl", "vehicleDownloadUrl")) {
            String prefix = "\"" + name + "\":";
            String valid = BASE.replace("\"routeVersion\":", prefix + "\"" + ROUTE_URL + "\",\"routeVersion\":");
            assertThat(GbisMetadataClient.parseBaseInfo(bytes(valid), KEY).routeVersion()).isEqualTo("v1");
            for (String value : List.of("\"/relative\"", "\"\"", "null", "\"http://other.test/a\""))
                assertThat(GbisMetadataClient.parseBaseInfo(bytes(valid.replace(prefix + "\"" + ROUTE_URL + "\"", prefix + value)), KEY).routeVersion()).isEqualTo("v1");
            for (String value : List.of("http://other.test/a?token=secret", "http://user:secret@other.test/a",
                    "/a?%74oken=secret", "/a?%74oken=secret%", "/a?file=" + KEY))
                rejects(valid.replace(prefix + "\"" + ROUTE_URL + "\"", prefix + "\"" + value + "\""), CONTENT_UNSAFE, NONE);
        }
    }
    @Test void classifies_unsafe_other_fields() {
        rejects(BASE.replace("\"resultCode\":0", "\"resultCode\":0,\"other\":\"https://evil.test/a\""), URL_OUTSIDE_OFFICIAL_FIELD, NONE);
        rejects(BASE.replace("\"resultCode\":0", "\"resultCode\":0,\"personalPassword\":\"bad\""), CONTENT_UNSAFE, NONE);
        rejects(BASE.replace("\"resultCode\":0", "\"resultCode\":0,\"other\":\"" + KEY + "\""), CONTENT_UNSAFE, NONE);
    }
}
