package com.stopbell.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.stream.Stream;

import com.stopbell.alarm.service.AlarmService;
import com.stopbell.boarding.service.BoardingSessionService;
import com.stopbell.common.error.ApiException;
import com.stopbell.common.error.ErrorCode;
import com.stopbell.notification.service.DeviceService;
import com.stopbell.transit.service.BoardingCandidateService;
import com.stopbell.transit.service.BusRouteSearchService;
import com.stopbell.user.auth.exception.InvalidSocialCredentialException;
import com.stopbell.user.auth.exception.SocialIdentityVerificationException;
import com.stopbell.user.auth.identity.GoogleIdentityVerifier;
import com.stopbell.user.auth.service.JwtTokenService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ActiveProfiles("test")
@Import({SecurityConfigurationIntegrationTest.TestRepositoryConfiguration.class,
        MvcErrorHttpIntegrationTest.ErrorProbeConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class MvcErrorHttpIntegrationTest {

    private static final String PRIVATE = "private-JWT-refresh-google-firebase-FCM-SQL-constraint-/srv/internal";
    private static final String DEVICE = """
            {"installationId":"12345678-1234-4234-8234-123456789abc","platform":"IOS",
             "pushRegistrationId":"test-target","registrationRevision":0,
             "expectedOwnershipGeneration":0,"transferOwnership":false}
            """;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private JwtTokenService tokens;
    @MockitoBean private GoogleIdentityVerifier google;
    @MockitoBean private AlarmService alarms;
    @MockitoBean private DeviceService devices;
    @MockitoBean private BoardingSessionService sessions;
    @MockitoBean private BoardingCandidateService candidates;
    @MockitoBean private BusRouteSearchService routes;

    @ParameterizedTest
    @MethodSource("invalidRequests")
    @DisplayName("기존 API의 body·필드·타입·범위·Enum·UUID 검증 실패는 실제 HTTP에서 고정 JSON 400이다")
    void reject_invalid_request(String path, String body) throws Exception {
        assertError(request("POST", path, body, true, "application/json", "application/json"), ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(alarms, devices, sessions);
    }

    static Stream<Arguments> invalidRequests() {
        Stream<Arguments> alarms = Stream.of("", "{", "{}", "null", "{\"targetStopOccurrenceId\":null}",
                "{\"targetStopOccurrenceId\":\"\"}", "{\"targetStopOccurrenceId\":\"invalid\"}",
                "{\"targetStopOccurrenceId\":{}}", "{\"targetStopOccurrenceId\":0}",
                "{\"targetStopOccurrenceId\":-1}", "{\"targetStopOccurrenceId\":9223372036854775808}")
                .map(body -> Arguments.of("/api/v1/alarms", body));
        Stream<Arguments> devices = Stream.of(DEVICE.replace("\"IOS\"", "\"UNKNOWN\""),
                DEVICE.replace("12345678-1234-4234-8234-123456789abc", "not-a-uuid"),
                DEVICE.replace("test-target", ""), DEVICE.replace("\"registrationRevision\":0", "\"registrationRevision\":-1"))
                .map(body -> Arguments.of("/api/v1/devices", body));
        Stream<Arguments> sessions = Stream.of(
                "{\"clientRequestId\":\"not-a-uuid\",\"candidateRef\":\"candidate\"}",
                "{\"clientRequestId\":\"12345678-1234-4234-8234-123456789abc\",\"candidateRef\":\"\"}")
                .map(body -> Arguments.of("/api/v1/boarding-sessions", body));
        return Stream.of(alarms, devices, sessions).flatMap(stream -> stream);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/alarms/not-a-long", "/api/v1/bus-routes/not-a-long/stops",
            "/api/v1/boarding-sessions/not-a-long", "/api/v1/bus-routes/1/boarding-candidates",
            "/api/v1/bus-routes/1/boarding-candidates?targetStopOccurrenceId=not-a-long",
            "/api/v1/bus-routes/1/boarding-candidates?targetStopOccurrenceId=1&boardingStopOccurrenceId=invalid",
            "/api/v1/bus-routes"})
    @DisplayName("Path·Query 타입 오류와 필수 Query 누락은 500으로 가로채지 않고 JSON 400이다")
    void reject_path_and_query_errors(String path) throws Exception {
        assertError(get(path), ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(alarms, candidates, routes, sessions);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("Alarm 조회·활성화·비활성화·삭제의 비양수 Path ID는 Service 호출 전에 400이다")
    void reject_nonpositive_alarm_id(long id) throws Exception {
        String path = "/api/v1/alarms/" + id;
        assertError(get(path), ErrorCode.INVALID_REQUEST);
        assertError(request("POST", path + "/activate", "", true, "application/json", "application/json"), ErrorCode.INVALID_REQUEST);
        assertError(request("POST", path + "/deactivate", "", true, "application/json", "application/json"), ErrorCode.INVALID_REQUEST);
        assertError(request("DELETE", path, "", true, "application/json", "application/json"), ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(alarms);
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/v1/missing-path,404,NOT_FOUND", "PUT,/api/v1/alarms,405,METHOD_NOT_ALLOWED"})
    @DisplayName("기본 404·405는 실제 ERROR dispatch를 거쳐 원래 상태와 안전한 JSON을 반환한다")
    void preserve_default_mvc_status(String method, String path, int status, ErrorCode code) throws Exception {
        var response = request(method, path, "", true, "application/json", "application/json");
        assertError(response, code);
        assertErrorDispatch(response, status);
        if (status == 405) assertThat(response.headers().firstValue("Allow").orElseThrow()).contains("GET", "POST");
    }

    @Test
    @DisplayName("지원하지 않는 요청 Content-Type은 실제 ERROR dispatch에서 JSON 415를 유지한다")
    void preserve_unsupported_media_type() throws Exception {
        var response = request("POST", "/api/v1/alarms", "body", true, "text/plain", "application/json");
        assertError(response, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        assertErrorDispatch(response, 415);
    }

    @Test
    @DisplayName("Accept 협상 실패는 JSON 오류를 쓰더라도 원래 406과 지원 media type을 유지한다")
    void preserve_not_acceptable() throws Exception {
        var response = request("GET", "/api/v1/alarms", "", true, null, "application/xml");
        assertError(response, ErrorCode.NOT_ACCEPTABLE);
        assertErrorDispatch(response, 406);
        assertThat(response.headers().firstValue("Accept").orElseThrow()).contains("application/json");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 406, 409, 415, 418, 422, 429, 500, 502, 503, 504})
    @DisplayName("공개 Auth의 ResponseStatusException은 reason·cause 노출 없이 지정 상태를 ERROR dispatch로 보존한다")
    void preserve_response_status_exception(int status) throws Exception {
        when(google.verify("fixture")).thenThrow(new ResponseStatusException(HttpStatusCode.valueOf(status),
                PRIVATE, new IllegalStateException(PRIVATE)));
        var response = request("POST", "/auth/google", "{\"idToken\":\"fixture\"}", false,
                "application/json", "application/json");
        assertStatusError(response, status);
        assertErrorDispatch(response, status);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500, 503})
    @DisplayName("명시적 Servlet sendError도 인증 401로 바뀌지 않고 정확한 두 필드와 원래 상태를 유지한다")
    void preserve_servlet_error_dispatch(int status) throws Exception {
        var response = get("/test/mvc-errors/send-error/" + status);
        assertStatusError(response, status);
        assertErrorDispatch(response, status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"annotated", "wrapped-annotated"})
    @DisplayName("ResponseStatus annotation과 원인 예외의 annotation은 공통 fallback보다 우선한다")
    void preserve_annotated_exception(String kind) throws Exception {
        var response = get("/test/mvc-errors/" + kind);
        assertError(response, ErrorCode.NOT_FOUND);
        assertErrorDispatch(response, 404);
    }

    @Test
    @DisplayName("누락된 필수 Header는 기본 MVC 400이며 반환값 validation 실패는 내부 500이다")
    void distinguish_binding_from_return_validation() throws Exception {
        var binding = get("/test/mvc-errors/header");
        assertError(binding, ErrorCode.INVALID_REQUEST);
        assertErrorDispatch(binding, 400);
        assertError(get("/test/mvc-errors/return-validation"), ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "database"})
    @DisplayName("local Handler가 없는 Alarm의 Runtime·DB 실패는 안전한 공통 JSON 500이며 로그도 민감 원문을 제외한다")
    void normalize_unexpected_failure(String kind, CapturedOutput output) throws Exception {
        RuntimeException failure = kind.equals("database")
                ? new DataIntegrityViolationException(PRIVATE, new IllegalStateException(PRIVATE))
                : new IllegalStateException(PRIVATE);
        when(alarms.findAll(42L)).thenThrow(failure);
        assertError(get("/api/v1/alarms"), ErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(output).contains("Unhandled MVC failure: exceptionType=" + failure.getClass().getSimpleName())
                .doesNotContain(PRIVATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "database"})
    @DisplayName("공개 Auth에서 발생한 예상 밖 실패도 인증 오류로 바꾸지 않고 안전한 JSON 500이다")
    void normalize_public_unexpected_failure(String kind) throws Exception {
        when(google.verify("fixture")).thenThrow(kind.equals("database")
                ? new DataIntegrityViolationException(PRIVATE) : new IllegalStateException(PRIVATE));
        assertError(request("POST", "/auth/google", "{\"idToken\":\"fixture\"}", false,
                "application/json", "application/json"), ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("Device·Boarding의 local 영속성 Handler는 공통 fallback보다 우선하며 기존 JSON을 보존한다")
    void preserve_local_persistence_handlers(CapturedOutput output) throws Exception {
        when(devices.findState(42L, null, "fixture")).thenThrow(new DataIntegrityViolationException(PRIVATE));
        when(sessions.find(42L, 1L)).thenThrow(new TransactionSystemException(PRIVATE));
        assertError(get("/api/v1/devices/fixture"), ErrorCode.INTERNAL_SERVER_ERROR);
        assertError(get("/api/v1/boarding-sessions/1"), ErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(output).contains("Device persistence failed: DataIntegrityViolationException",
                "Boarding persistence failed: TransactionSystemException")
                .doesNotContain("Unhandled MVC failure", PRIVATE);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"DEVICE_CREDENTIAL_INVALID", "DEVICE_NOT_FOUND",
            "DEVICE_REGISTRATION_STALE", "DEVICE_REVISION_CONFLICT", "DEVICE_OWNERSHIP_CONFLICT"})
    @DisplayName("Device Domain의 403·404·409 code와 message는 공통 fallback 도입 후에도 유지한다")
    void preserve_device_domain_error(ErrorCode code) throws Exception {
        when(devices.findState(42L, null, "fixture")).thenThrow(new ApiException(code));
        assertError(get("/api/v1/devices/fixture"), code);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"ALARM_NOT_FOUND", "BUS_ROUTE_NOT_FOUND",
            "TARGET_STOP_OCCURRENCE_NOT_FOUND", "INVALID_ALARM_REQUEST"})
    @DisplayName("Alarm의 Domain 400·404는 resource별 code와 기존 공개 메시지를 보존한다")
    void preserve_alarm_domain_error(ErrorCode code) throws Exception {
        when(alarms.findById(42L, 1L)).thenThrow(new ApiException(code));
        assertError(get("/api/v1/alarms/1"), code);
        verify(alarms).findById(42L, 1L);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"BOARDING_SESSION_NOT_FOUND", "BOARDING_CANDIDATE_CHANGED",
            "BOARDING_REVISION_CONFLICT", "BOARDING_REQUEST_CONFLICT", "BOARDING_SESSION_CONFLICT"})
    @DisplayName("Boarding Domain의 404·409 의미와 code는 기존 local Handler 및 Advice에서 유지한다")
    void preserve_boarding_domain_error(ErrorCode code) throws Exception {
        when(sessions.find(42L, 1L)).thenThrow(new ApiException(code));
        assertError(get("/api/v1/boarding-sessions/1"), code);
    }

    @Test
    @DisplayName("Transit 503은 범용 SERVICE_UNAVAILABLE과 구분한 기존 code를 보존한다")
    void preserve_transit_unavailable() throws Exception {
        when(candidates.find(42L, 1L, 2L, null)).thenThrow(new ApiException(ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE));
        assertError(get("/api/v1/bus-routes/1/boarding-candidates?targetStopOccurrenceId=2"),
                ErrorCode.TRANSIT_TEMPORARILY_UNAVAILABLE);
    }

    @Test
    @DisplayName("실제 HTTP에서 Security 401과 Auth 401·503을 기존 code·message로 보존한다")
    void preserve_authentication_contracts() throws Exception {
        assertError(request("GET", "/api/v1/alarms", "", false, null, "application/json"), ErrorCode.UNAUTHENTICATED);
        when(google.verify("invalid")).thenThrow(new InvalidSocialCredentialException(new IllegalArgumentException(PRIVATE)));
        when(google.verify("unavailable")).thenThrow(new SocialIdentityVerificationException(new IOException(PRIVATE)));
        assertError(request("POST", "/auth/google", "{\"idToken\":\"invalid\"}", false,
                "application/json", "application/json"), ErrorCode.UNAUTHENTICATED);
        assertError(request("POST", "/auth/google", "{\"idToken\":\"unavailable\"}", false,
                "application/json", "application/json"), ErrorCode.SOCIAL_IDENTITY_UNAVAILABLE);
    }

    @Test
    @DisplayName("MVC의 Security 예외도 공통 500이 삼키지 않고 Filter의 401·403 및 challenge로 전달한다")
    void preserve_security_exception_boundary() throws Exception {
        var denied = get("/test/mvc-errors/denied");
        assertError(denied, ErrorCode.ACCESS_DENIED);
        assertThat(denied.headers().firstValue("WWW-Authenticate")).contains("Bearer error=\"insufficient_scope\"");
        assertError(get("/test/mvc-errors/unauthenticated"), ErrorCode.UNAUTHENTICATED);
    }

    private void assertErrorDispatch(HttpResponse<String> response, int status) {
        assertThat(response.headers().firstValue("X-Test-Error-Dispatch")).contains(Integer.toString(status));
    }

    private void assertStatusError(HttpResponse<String> response, int status) {
        ErrorCode code = switch (status) {
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
        if (code == null) assertError(response, status, "HTTP_" + status, "Request could not be completed.");
        else assertError(response, code);
    }

    private void assertError(HttpResponse<String> response, ErrorCode code) {
        assertError(response, code.getHttpStatus().value(), code.name(), code.getMessage());
    }

    private void assertError(HttpResponse<String> response, int status, String code, String message) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
        var json = JSON.readTree(response.body());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(json.get("code").asString()).isEqualTo(code);
        assertThat(json.get("message").asString()).isEqualTo(message);
        assertThat(response.body()).doesNotContain(PRIVATE, "stackTrace", "java.lang", "DataIntegrityViolationException");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request("GET", path, "", true, null, "application/json");
    }

    private HttpResponse<String> request(String method, String path, String body, boolean authenticated,
            String contentType, String accept) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", accept);
            if (authenticated) request.header("Authorization", "Bearer " + tokens.createAccessToken(42L));
            if (contentType != null) request.header("Content-Type", contentType);
            return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ErrorProbeConfiguration {
        @Bean
        ErrorProbeController errorProbeController() {
            return new ErrorProbeController();
        }

        @Bean
        FilterRegistrationBean<Filter> errorDispatchProbe() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                ((HttpServletResponse) response).setHeader("X-Test-Error-Dispatch",
                        request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE).toString());
                chain.doFilter(request, response);
            });
            registration.setDispatcherTypes(EnumSet.of(DispatcherType.ERROR));
            return registration;
        }
    }

    @RestController
    static class ErrorProbeController {
        @GetMapping("/test/mvc-errors/send-error/{status}")
        void sendError(@PathVariable int status, HttpServletResponse response) throws IOException {
            response.sendError(status, PRIVATE);
        }

        @GetMapping("/test/mvc-errors/annotated")
        void annotated() {
            throw new PrivateNotFoundException();
        }

        @GetMapping("/test/mvc-errors/wrapped-annotated")
        void wrappedAnnotated() {
            throw new IllegalStateException(PRIVATE, new PrivateNotFoundException());
        }

        @GetMapping("/test/mvc-errors/header")
        String header(@RequestHeader("X-Required") String value) {
            return value;
        }

        @Positive
        @GetMapping("/test/mvc-errors/return-validation")
        int invalidReturn() {
            return -1;
        }

        @GetMapping("/test/mvc-errors/denied")
        void denied() {
            throw new AccessDeniedException(PRIVATE);
        }

        @GetMapping("/test/mvc-errors/unauthenticated")
        void unauthenticated() {
            throw new AuthenticationCredentialsNotFoundException(PRIVATE);
        }
    }

    @ResponseStatus(code = HttpStatus.NOT_FOUND, reason = PRIVATE)
    static class PrivateNotFoundException extends RuntimeException {
    }
}
