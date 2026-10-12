package com.stopbell.notification.client;

import java.time.Duration;
import java.time.Instant;

import com.stopbell.notification.entity.NotificationProviderResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static com.stopbell.notification.entity.NotificationProviderResult.*;

class FcmFailureMapperTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    @DisplayName("확인된 미전송 DNS와 경로 오류를 timeout 및 전송 불명 I/O와 구분한다")
    void classifies_transport_phase_conservatively() {
        assertThat(FcmFailureMapper.transportFailure(new java.net.UnknownHostException()).result()).isEqualTo(RETRYABLE);
        assertThat(FcmFailureMapper.transportFailure(new java.net.NoRouteToHostException()).result()).isEqualTo(RETRYABLE);
        assertThat(FcmFailureMapper.transportFailure(new java.net.SocketTimeoutException()).result()).isEqualTo(AMBIGUOUS_TIMEOUT);
        assertThat(FcmFailureMapper.transportFailure(new java.net.SocketException("connection reset")).result()).isEqualTo(AMBIGUOUS_TIMEOUT);
        assertThat(FcmFailureMapper.transportFailure(new java.io.IOException()).result()).isEqualTo(AMBIGUOUS_TIMEOUT);
    }

    @ParameterizedTest
    @CsvSource({
            "404,NOT_FOUND,UNREGISTERED,INVALID_TARGET",
            "400,INVALID_ARGUMENT,INVALID_ARGUMENT,PERMANENT_REQUEST",
            "429,RESOURCE_EXHAUSTED,QUOTA_EXCEEDED,RETRYABLE",
            "500,INTERNAL,INTERNAL,RETRYABLE",
            "503,UNAVAILABLE,UNAVAILABLE,RETRYABLE",
            "401,UNAUTHENTICATED,THIRD_PARTY_AUTH_ERROR,CONFIGURATION",
            "401,UNAUTHENTICATED,APNS_AUTH_ERROR,CONFIGURATION",
            "403,PERMISSION_DENIED,SENDER_ID_MISMATCH,CONFIGURATION",
            "400,INVALID_ARGUMENT,APNS_AUTH_ERROR,CONFIGURATION",
            "500,INTERNAL,UNREGISTERED,RETRYABLE",
            "404,INVALID_ARGUMENT,UNREGISTERED,CONFIGURATION"
    })
    @DisplayName("HTTP와 FcmError 근거를 보수적으로 여섯 Provider 결과로 분류한다")
    void classifies_structured_fcm_errors(int status, String generic, String code, NotificationProviderResult expected) {
        var result = FcmFailureMapper.response(status, error(status, generic, code), null, NOW);
        assertThat(result.result()).isEqualTo(expected);
        if (expected == RETRYABLE) {
            assertThat(result.minimumDelay()).isEqualTo(status == 429 ? Duration.ofSeconds(60) : Duration.ofSeconds(10));
        }
    }

    @Test
    @DisplayName("bare 404 및 불완전하거나 모순된 detail은 target cleanup 근거가 아니다")
    void bare_and_conflicting_404_are_configuration() {
        for (String body : new String[]{"{}", "not json", "{\"error\":{\"status\":\"NOT_FOUND\"}}",
                error(400, "NOT_FOUND", "UNREGISTERED"), error(404, "UNKNOWN", "UNREGISTERED"),
                "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"details\":["
                        + "{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"},"
                        + "{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"INTERNAL\"}]}}"}) {
            assertThat(FcmFailureMapper.response(404, body, null, NOW).result()).isEqualTo(CONFIGURATION);
        }
    }

    @Test
    @DisplayName("성공 응답 parsing 실패와 알 수 없는 서버 응답은 접수 불명이다")
    void success_parsing_failure_is_ambiguous() {
        assertThat(FcmFailureMapper.response(200, "broken", null, NOW).result()).isEqualTo(AMBIGUOUS_TIMEOUT);
        assertThat(FcmFailureMapper.response(200, "{}", null, NOW).result()).isEqualTo(AMBIGUOUS_TIMEOUT);
        assertThat(FcmFailureMapper.response(502, "broken", null, NOW).result()).isEqualTo(AMBIGUOUS_TIMEOUT);
        assertThat(FcmFailureMapper.response(422, "{}", null, NOW).result()).isEqualTo(PERMANENT_REQUEST);
    }

    @Test
    @DisplayName("Retry-After는 delta seconds와 HTTP-date를 정규화하고 큰 값을 줄이지 않는다")
    void normalizes_retry_after() {
        assertThat(FcmFailureMapper.retryAfter("120", NOW)).isEqualTo(Duration.ofSeconds(120));
        assertThat(FcmFailureMapper.retryAfter("Mon, 12 Oct 2026 00:02:00 GMT", NOW)).isEqualTo(Duration.ofSeconds(120));
        assertThat(FcmFailureMapper.retryAfter("Mon, 12 Oct 2026 00:00:00 GMT", NOW.plusSeconds(1))).isZero();
        assertThat(FcmFailureMapper.retryAfter("99999999999999999999", NOW)).isEqualTo(Duration.ofSeconds(Long.MAX_VALUE));
        assertThat(FcmFailureMapper.response(429, "{}", "0", NOW).minimumDelay()).isEqualTo(Duration.ofSeconds(60));
        for (String value : new String[]{"invalid", "-1", "1.5", "", "0"}) {
            assertThat(FcmFailureMapper.retryAfter(value, NOW)).isZero();
        }
    }

    private static String error(int status, String generic, String fcm) {
        return "{\"error\":{\"code\":" + status + ",\"status\":\"" + generic + "\",\"details\":["
                + "{\"@type\":\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"" + fcm + "\"}]}}";
    }
}
